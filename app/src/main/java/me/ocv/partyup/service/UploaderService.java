package me.ocv.partyup.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.ocv.partyup.R;
import me.ocv.partyup.XferActivity;
import me.ocv.partyup.XferAfterActivity;
import me.ocv.partyup.objects.BaseUploadProgress;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.PrefsKey;
import me.ocv.partyup.utils.NetworkUtils;
import me.ocv.partyup.utils.NumberUtils;
import me.ocv.partyup.utils.SoundUtils;

public class UploaderService extends Service {
    // Jeezus look at those fields XD
    public static final String STATE_KEY = "state";

    private static final String TAG = "UploaderService";
    private static final String CHANNEL_ID = "Uploader";
    private static final String CHANNEL_NAME = "PartyUploader";
    private static final String GROUP_NAME = "party_upload_group";

    private static final int INITIAL_COUNT = 100;
    private static final int GLOBAL_NOTIFICATION_ID = 69;
    private static final int SHARE_API_RESPONSE_NOT_OKAY = 300;
    private static final int NOTIF_DELAY = 200;
    private static final int UPLOAD_DELAY = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final UploadProgress globalProgress = new UploadProgress();
    private final IBinder binder = new UploadBinder();

    private final LinkedBlockingQueue<UploadJob> jobs = new LinkedBlockingQueue<>();
    private final List<String> filePaths = new ArrayList<>();

    private final Map<Integer, Listeners<UploadProgress>> progressListeners = new HashMap<>();
    private final Map<Integer, Listeners<String>> successListeners = new HashMap<>();
    private final Map<Integer, Listeners<Throwable>> errorListeners = new HashMap<>();
    private final Map<CustomFile, UploadJob> jobMap = new HashMap<>();

    private final AtomicInteger uploadCounter = new AtomicInteger(INITIAL_COUNT);

    private NotificationCompat.Builder globalNotification;
    private NotificationManager manager;
    private SharedPreferences preferences;
    private String serverPassword;
    private String serverUrl;
    private Future<?> watcher = null;

    private <T> void _addListener(int jobId, @Nullable LifecycleOwner owner, Uploader.Listener<? super T> listener,
                                  Map<Integer, Listeners<T>> listenersMap) {
        if (listenersMap != null) {
            Listeners<T> listeners = listenersMap.get(jobId);

            if (listeners == null) {
                listeners = new Listeners<>();

                if (owner != null) {
                    owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                        @Override
                        public void onDestroy(@NonNull LifecycleOwner owner) {
                            _removeListener(jobId, listener, listenersMap);
                        }
                    });
                }

                listenersMap.put(jobId, listeners);
            }

            if (!listeners.listeners.contains(listener)) {
                listeners.listeners.add(listener);
            }
        }
    }

    public void addProgressListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnProgressListener listener) {
        _addListener(jobId, owner, listener, progressListeners);
    }

    private <T> void _removeListener(int jobId, Uploader.Listener<? super T> listener,
                                     Map<Integer, Listeners<T>> listenersMap) {
        if (listenersMap != null) {
            Listeners<T> listeners = listenersMap.get(jobId);
            if (listeners != null) {
                listeners.listeners.remove(listener);
                if (listeners.listeners.isEmpty()) {
                    listenersMap.remove(jobId);
                }
            }
        }
    }

    public void removeProgressListener(int jobId, Uploader.OnProgressListener listener) {
        _removeListener(jobId, listener, progressListeners);
    }

    public void addErrorListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnErrorListener listener) {
        _addListener(jobId, owner, listener, errorListeners);
    }

    public void removeErrorListener(int jobId, Uploader.OnErrorListener listener) {
        _removeListener(jobId, listener, errorListeners);
    }

    public void addSuccessListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnCompleteListener listener) {
        _addListener(jobId, owner, listener, successListeners);
    }

    public void removeSuccessListener(int jobId, Uploader.OnCompleteListener listener) {
        _removeListener(jobId, listener, successListeners);
    }

    @NonNull
    @SuppressWarnings("CharsetObjectCanBeUsed")
    public String getShareUrl(CustomFile[] files) throws RuntimeException {
        if (files == null) {
            return "";
        }
        List<String> shareUrls = new ArrayList<>();

        // Check for if they exists
        for (CustomFile file : files) {
            if (filePaths.contains(file.share_url)) {
                shareUrls.add(file.share_url);
            }
        }

        if (shareUrls.isEmpty()) {
            return "";
        }

        try {
            JSONObject object = new JSONObject();
            JSONArray array = new JSONArray();

            for (String path : shareUrls) {
                array.put(URLDecoder.decode(new URL(path).getPath(), StandardCharsets.UTF_8.name()));
            }

            object.put("k", XferActivity.generateRandomKey());
            object.put("pw", preferences.getString(PrefsKey.SHARE_PASSWORD, ""));
            object.put("perms", new JSONArray().put("read"));
            object.put("vp", array);
            object.put("exp", XferActivity.getExpiration(preferences.getString(PrefsKey.LINK_EXPIRATION, "")));

            String req = object.toString();
            Log.d(TAG, req);
            HttpURLConnection connection = getShareApiConnection();

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(req.getBytes());
                stream.flush();
            }

            String response = NetworkUtils.readConnection(connection, i -> i <= SHARE_API_RESPONSE_NOT_OKAY).toString();
            Log.d(TAG, connection.getResponseCode() + " " + response);

            if (response.startsWith("created share: ")) {
                return response;
            } else {
                Log.e(TAG, "Not okay share response: " + response);
            }

            throw new RuntimeException("Reached end without share url!");
        } catch (Exception e) {
            Log.e(TAG, "Error in share creation", e);
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private HttpURLConnection getShareApiConnection() throws IOException {
        // serverUrl: https://copyparty.server.me/upload/media
        // shareApiUrl: https://copyparty.server.me/upload/media/?share
        URL shareApiUrl = new URL(serverUrl + (serverUrl.endsWith("/") ? "" : "/") + "?share");

        HttpURLConnection connection = (HttpURLConnection) shareApiUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");

        connection.setDoOutput(true);
        connection.setDoInput(true);

        if (serverPassword != null && !serverPassword.isEmpty()) {
            connection.setRequestProperty("PW", serverPassword);
        }

        return connection;
    }

    public Handler getHandler() {
        return this.handler;
    }

    public boolean isUploading() {
        return !jobs.isEmpty();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        resetGlobalNotification();
        notificationSetup();
        watchJobs();
    }

    private void watchJobs() {
        watcher = executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    uploadFile(jobs.take());

                    Thread.sleep(UPLOAD_DELAY);
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    private void uploadFile(@NonNull UploadJob job) {
        Log.d(TAG, String.format("processing job: %s", job));

        globalProgress.currentFile = job.progress.currentFile;
        globalProgress.total += globalProgress.currentFile.size != null ? globalProgress.currentFile.size : 0;

        updateGlobalNotification();
        AtomicReference<Throwable> prevErr = new AtomicReference<>();
        AtomicReference<String> prevResp = new AtomicReference<>();

        try {
            new Uploader.Builder(this).setServerPassword(serverPassword).setServerUrl(serverUrl)
                    .setOnProgress(progress -> {
                        job.update(progress.done, progress.total);
                        globalProgress.done += progress.delta;

                        notifyProgress(job.id, new UploadProgress(progress));
                        updateGlobalNotification();
                    }).setOnComplete(resp -> {
                        prevResp.set(resp);
                        job.complete(resp);

                        filePaths.add(job.progress.currentFile.share_url);
                        notifySuccess(job.id, resp);
                    }).setOnError(err -> {
                        // typical race condition here, this error is more likely due to reading the input streams
                        Log.e(TAG, "Error with connection", err);
                        job.error(err);

                        if (prevErr.get() != null) {
                            // wooah guess what had happened xD
                            Log.wtf(TAG, "Some messy shit happened", prevErr.get());
                            job.error(prevErr.get());
                            notifyError(job.id, prevErr.get());
                        }

                        prevErr.set(err);

                        notifyError(job.id, err);
                    }).build().upload(job.progress.currentFile);

            if (prevErr.get() != null) {
                job.error(prevErr.get());
                notifyError(job.id, prevErr.get());
            } else {
                job.complete(prevResp.get());
                notifySuccess(job.id, prevResp.get());
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                jobMap.remove(job.progress.currentFile, job);
            } else {
                jobMap.remove(job.progress.currentFile);
            }

            updateGlobalNotification();
        } catch (Throwable e) {
            prevErr.set(new RuntimeException("Unexpected error while uploading", e));
        }
    }

    private <T, L extends Uploader.Listener<? super T>> void _notify(T message, List<L> listeners) {
        if (listeners == null || message == null || listeners.isEmpty()) return;

        handler.post(() -> {
            for (L listener : listeners) {
                try {
                    listener.run(message);
                } catch (Throwable error) {
                    Log.e(TAG, String.format("error notifying '%s' listener of '%s' message at %d",
                            listener.getClass().getName(), message.getClass().getName(),
                            listeners.indexOf(listener) + 1));
                    listeners.remove(listener);
                }
            }
        });
    }

    private <T, L extends Listeners<? super T>> void _notifyJob(int jobId, T message, Map<Integer, L> listenersMap,
                                                                boolean force) {
        if (listenersMap != null) {
            L listeners = listenersMap.get(jobId);
            if (listeners != null) {
                long now = System.currentTimeMillis();
                if (force || now - listeners.last > NOTIF_DELAY) {
                    listeners.last = now;
                    _notify(message, listeners.listeners);
                }
            }
        }
    }

    private void notifySuccess(int jobId, String serverResponse) {
        _notifyJob(jobId, serverResponse, successListeners, true);
        // Clean after success
        successListeners.remove(jobId);
        SoundUtils.playSuccess();
    }

    private void notifyError(int jobId, Throwable err) {
        _notifyJob(jobId, err, errorListeners, true);
        SoundUtils.playError();
    }

    private void notifyProgress(int jobId, UploadProgress progress) {
        _notifyJob(jobId, progress, progressListeners, false);
        SoundUtils.playBackGround();
    }

    private void updateGlobalNotification() {
        if (manager == null || globalNotification == null) {
            return;
        }

        CustomFile file = globalProgress.currentFile;
        int jobCount = jobs.size();

        // Nothing to upload
        if (jobCount == 0 || (file != null && filePaths.contains(file.share_url))) {
            globalNotification.setContentTitle(getString(R.string.notification_title))
                    .setSmallIcon(R.drawable.ic_launcher).setContentText("No active uploads").setSubText("Idle")
                    .setProgress(0, 0, false).setOngoing(false);
            manager.notify(GLOBAL_NOTIFICATION_ID, globalNotification.build());
            return;
        }

        long done = globalProgress.done;
        long total = globalProgress.total;

        boolean indeterminate = total <= 0;

        int percent = 0;
        if (!indeterminate) {
            percent = (int) Math.floor(NumberUtils.calcPercentage(done, total));
        }

        String currentFileName = file != null ? file.name : "Preparing...";
        String contentText;
        if (indeterminate) {
            contentText = "Calculating size...";
        } else {
            contentText = String.format("Overall: %s / %s", NumberUtils.formatBytes(done),
                    NumberUtils.formatBytes(total));
        }

        String subText = String.format(Locale.getDefault(), "%d job%s remaining", jobCount, jobCount == 1 ? "" : "s");
        globalNotification.setContentTitle("Uploading files...").setContentText(contentText).setSubText(subText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText)
                        .setSummaryText("Current: " + currentFileName)).setProgress(100, percent, indeterminate)
                .setOngoing(true).setSmallIcon(android.R.drawable.stat_sys_upload);
        manager.notify(GLOBAL_NOTIFICATION_ID, globalNotification.build());
    }

    private void resetGlobalNotification() {
        globalNotification = new NotificationCompat.Builder(this, CHANNEL_ID).setGroup(GROUP_NAME).setGroupSummary(true)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle(getString(R.string.notification_title))
                .setSubText("Initializing...").setContentText("Upload will begin shortly...").setProgress(0, 0, true)
                .setOngoing(true);
    }

    private void notificationSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        progressListeners.clear();
        errorListeners.clear();
        successListeners.clear();
        jobs.clear();
        jobMap.clear();

        if (watcher != null) {
            watcher.cancel(true);
        }

        executor.shutdownNow();
        stopForeground(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        startForeground(GLOBAL_NOTIFICATION_ID, globalNotification.build());

        try {
            Serializable serializable = intent.getSerializableExtra(STATE_KEY);
            STATE state = (STATE) serializable;
            if (state == null) {
                refreshPrefs();
            } else {
                if (state.filesToUpload != null) {
                    for (CustomFile file : state.filesToUpload) {
                        enqueueFile(file);
                    }
                }
                serverPassword = state.serverPassword;
                serverUrl = state.baseUrl;
            }
            return START_STICKY;
        } catch (ClassCastException /* This happens when I tried to retrieve the files */ ex) {
            Log.e(TAG, "Cannot retrieve state from intent", ex);
            return START_NOT_STICKY;
        }
    }

    public void refreshPrefs() {
        String password = preferences.getString(PrefsKey.SERVER_PASSWORD, "");
        serverUrl = preferences.getString(PrefsKey.SERVER_URL, "");
        serverPassword =
                password.isEmpty() || password.equals(getString(R.string.server_default_password)) ? null : password;
    }

    public int enqueueFile(@NonNull CustomFile file) {
        UploadJob job;
        if ((job = jobMap.get(file)) != null) {
            return job.id;
        }

        job = new UploadJob(uploadCounter.getAndIncrement(), file);
        jobMap.put(file, job);

        if (!jobs.offer(job)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                jobMap.remove(file, job);
            } else {
                jobMap.remove(file);
            }

            throw new RuntimeException("Unable to upload file");
        }

        return job.id;
    }

    public void stop() {
        stopForeground(true);
        stopSelf();
    }

    public static final class STATE implements Serializable {
        private static final long serialVersionUID = 1L;

        public String baseUrl;
        public String serverPassword;
        public CustomFile[] filesToUpload;

    }

    private static final class UploadProgress extends BaseUploadProgress {
        public int currentIndex = -1;
        public CustomFile currentFile = null;

        public UploadProgress() {}

        public UploadProgress(BaseUploadProgress ignoredProgress) {}
    }

    private static final class Listeners<T> {
        public final List<Uploader.Listener<? super T>> listeners = new ArrayList<>();
        public long last = System.currentTimeMillis();
    }

    public final class UploadBinder extends Binder {
        public UploaderService getService() {
            return UploaderService.this;
        }
    }

    private final class UploadJob {
        private static final int NOTIFICATION_THROTTLE = UPLOAD_DELAY/*ms*/;
        public final int id;
        public final UploadProgress progress;
        public final NotificationCompat.Builder notification;
        public final List<Throwable> errors;
        private boolean completed;
        private long last = System.currentTimeMillis();

        public UploadJob(int jobId, @NonNull CustomFile file) {
            id = jobId;
            completed = false;
            errors = new ArrayList<>();
            progress = new UploadProgress();

            progress.currentFile = file;
            progress.currentIndex = 0;
            progress.done = 0;
            progress.total = 0;

            notification = createNotification();
        }

        @NonNull
        public NotificationCompat.Builder createNotification() {
            return new NotificationCompat.Builder(UploaderService.this, CHANNEL_ID).setSmallIcon(
                            android.R.drawable.stat_sys_upload).setContentTitle("File upload...")
                    .setContentText("Upload will begin shortly...").setSubText("Waiting").setGroup(GROUP_NAME)
                    .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true);
        }

        public void error(@NonNull Throwable error) {
            errors.add(error);

            NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
            for (Throwable t : errors) {
                inboxStyle.addLine(t.getLocalizedMessage());
            }

            notification.setSmallIcon(android.R.drawable.stat_notify_error).setStyle(inboxStyle)
                    .setContentTitle(errors.size() > 1 ? "Many errors..." : "Errors")
                    .setContentText(errors.size() > 1 ? "Multiple error occurred" : error.getLocalizedMessage())
                    .setSubText(errors.size() + " error(s)").setProgress(0, 0, false).setOngoing(false);

            updateNotification(notification, true);
        }

        private void updateNotification(NotificationCompat.Builder builder, boolean force) {
            Log.d(TAG, "checking throttle logic");
            long now = System.currentTimeMillis();
            if (force || now - last > NOTIFICATION_THROTTLE) {
                last = now;
                UploaderService.this.manager.notify(id, builder.build());
                Log.d(TAG, "throttle passed");
            }
        }

        public void update(long done, long total) {
            if (completed) {
                return;
            }

            progress.done = done;
            progress.total = total;
            int percent = (int) Math.floor(NumberUtils.calcPercentage(progress.done, progress.total));

            notification.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("File uploading...")
                    .setContentText(String.format("Progress: %s/%s", NumberUtils.formatBytes(progress.done),
                            NumberUtils.formatBytes(progress.total)))
                    .setSubText(NumberUtils.formatBytes(progress.total - progress.done) + " remaining").setStyle(
                            new NotificationCompat.BigTextStyle().setSummaryText(
                                    String.format("filename: %s\nfile size: %s", progress.currentFile.name,
                                            NumberUtils.formatBytes(progress.currentFile.size))))
                    .setProgress(100, percent, false);
            updateNotification(notification, false);
        }

        public void complete(String resp) {
            if (completed) {
                return;
            }
            completed = true;
            progress.done = progress.total;

            PendingIntent copyIntent = createActionIntent(XferAfterActivity.ActionType.ACTION_COPY);
            PendingIntent shareIntent = createActionIntent(XferAfterActivity.ActionType.ACTION_SHARE);
            PendingIntent qrIntent = createActionIntent(XferAfterActivity.ActionType.ACTION_SHOW_QR);

            notification.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("File Uploaded!")
                    .setContentText("File successfully uploaded at: " + new Date()).setSubText("Noice work!")
                    .setProgress(0, 0, false).setStyle(
                            new NotificationCompat.BigTextStyle().setBigContentTitle("Server Response")
                                    .bigText(resp != null && !resp.isEmpty() ? resp : "(empty)"))
                    .addAction(R.drawable.copy, getString(R.string.noti_action_copy), copyIntent)
                    .addAction(R.drawable.share, getString(R.string.noti_action_share), shareIntent)
                    .addAction(R.drawable.qr_code, getString(R.string.noti_action_qr), qrIntent).setOngoing(false);

            updateNotification(notification, true);
        }

        private PendingIntent createActionIntent(XferAfterActivity.ActionType action) {
            Intent intent = new Intent(UploaderService.this, XferAfterActivity.class);
            intent.putExtra(XferAfterActivity.SHARE_URL_KEY, progress.currentFile.share_url);
            intent.putExtra(XferAfterActivity.ACTION_KEY, action);

            return PendingIntent.getActivity(UploaderService.this, action.ordinal(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

    }

}
