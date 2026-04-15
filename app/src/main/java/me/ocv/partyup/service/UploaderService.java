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

/*
 *
 * This service manages the notification section, it keeps tracks of all progress and update the notification accordingly
 * for instance, if this service is uploading the notification (unique id) will update relative to that id
 * if the upload is done, the relative notification with id is updated, same for if upload failed
 * so it makes sense to put last error in the UploadJob
 *
 *
 * */
public class UploaderService extends Service {
    // Jeezus look at those fields XD
    public static final String STATE_KEY = "state";

    private static final String TAG = "UploaderService";
    private static final String CHANNEL_ID = "Uploader";
    private static final String CHANNEL_NAME = "PartyUploader";
    private static final String GROUP_NAME = "party_upload_group";

    private static final int INITIAL_COUNT = 100;
    private static final int GLOBAL_NOTIFICATION_ID = 69;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final UploadProgress globalProgress = new UploadProgress();
    private final IBinder binder = new UploadBinder();

    private final LinkedBlockingQueue<UploadJob> jobs = new LinkedBlockingQueue<>();
    private final List<String> filePaths = new ArrayList<>();

    private final Map<Integer, List<Uploader.OnProgressListener>> progressListeners = new HashMap<>();
    private final Map<Integer, List<Uploader.OnCompleteListener>> successListeners = new HashMap<>();
    private final Map<Integer, List<Uploader.OnErrorListener>> errorListeners = new HashMap<>();
    private final Map<CustomFile, UploadJob> jobMap = new HashMap<>();

    private final AtomicInteger uploadCounter = new AtomicInteger(INITIAL_COUNT);

    private NotificationCompat.Builder globalNotification;
    private NotificationManager manager;
    private SharedPreferences preferences;
    private String serverPassword;
    private String serverUrl;
    private Future<?> watcher = null;

    public static final class STATE implements Serializable {
        private static final long serialVersionUID = 1L;

        public String baseUrl;
        public String serverPassword;
        public CustomFile[] filesToUpload;
    }

    public static final class UploadProgress {
        public long totalBytes = 0;
        public long doneBytes = 0;
        public int currentIndex = -1;
        public CustomFile currentFile = null;
    }

    public final class UploadBinder extends Binder {
        public UploaderService getService() {
            return UploaderService.this;
        }
    }

    private final class UploadJob {
        public final int id;
        public final UploadProgress progress;
        public final NotificationCompat.Builder notification;
        public final List<Throwable> errors;
        private boolean completed;

        public UploadJob(int jobId, @NonNull CustomFile file) {
            id = jobId;
            completed = false;
            errors = new ArrayList<>();
            progress = new UploadProgress();

            progress.currentFile = file;
            progress.currentIndex = 0;
            progress.doneBytes = 0;
            progress.totalBytes = 0;

            notification = createNotification();
        }

        @NonNull
        public NotificationCompat.Builder createNotification() {
            return new NotificationCompat.Builder(UploaderService.this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("File upload...").setContentText("Upload will begin shortly...").setSubText("Waiting").setGroup(GROUP_NAME).setProgress(0, 0, true).setOngoing(true);
        }

        public void error(@NonNull Throwable error) {
            NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
            for (Throwable t : errors) {
                inboxStyle.addLine(t.getLocalizedMessage());
            }

            notification.setSmallIcon(android.R.drawable.stat_notify_error).setStyle(inboxStyle).setSubText(errors.size() + " errors").setContentTitle(errors.size() > 1 ? "Many errors..." : "Errors").setContentText(errors.size() > 1 ? "Multiple error occurred" : error.getLocalizedMessage()).setProgress(0, 0, false);

            errors.add(error);
            UploaderService.this.manager.notify(id, notification.build());
        }

        public void update(long done, long total) {
            if (completed) return;

            progress.doneBytes = done;
            progress.totalBytes = total;
            int percent = (int) Math.floor(NumberUtils.calcPercentage(progress.doneBytes, progress.totalBytes));

            notification.setSmallIcon(android.R.drawable.stat_sys_upload).setSubText(NumberUtils.formatBytes(progress.totalBytes - progress.doneBytes) + " remaining").setContentTitle("File uploading...").setContentText(String.format("Progress: %s/%s", NumberUtils.formatBytes(progress.doneBytes), NumberUtils.formatBytes(progress.totalBytes))).setStyle(new NotificationCompat.BigTextStyle().setSummaryText(String.format("filename: %s\nfile size: %s", progress.currentFile.name, NumberUtils.formatBytes(progress.currentFile.size)))).setProgress(100, percent, false);
            UploaderService.this.manager.notify(id, notification.build());
        }

        public void complete(String resp) {
            if (completed) return;
            completed = true;
            progress.doneBytes = progress.totalBytes;

            Intent baseBeforeIntent = new Intent(UploaderService.this, XferAfterActivity.class);
            baseBeforeIntent.putExtra(XferAfterActivity.SHARE_URL_KEY, progress.currentFile.share_url);

            PendingIntent copyIntent = PendingIntent.getActivity(UploaderService.this, 1, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_COPY), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent shareIntent = PendingIntent.getActivity(UploaderService.this, 2, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHARE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent showIntent = PendingIntent.getActivity(UploaderService.this, 3, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHOW_QR), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            notification.setSmallIcon(android.R.drawable.stat_sys_upload_done).addAction(R.drawable.copy, getString(R.string.noti_action_copy), copyIntent).addAction(R.drawable.share, getString(R.string.noti_action_share), shareIntent).addAction(R.drawable.qr_code, getString(R.string.noti_action_qr), showIntent).setProgress(100, 100, false).setSubText("Noice work!").setContentTitle("File Uploaded!").setContentText("File successfully uploaded at: " + new Date()).setStyle(new NotificationCompat.BigTextStyle().setBigContentTitle("Server Response").setSummaryText(String.format("Server response: %s", resp != null && !resp.isEmpty() ? resp : "(empty)"))).setOngoing(false);
            UploaderService.this.manager.notify(id, notification.build());
        }
    }

    public void refreshPrefs() {
        String password = preferences.getString(PrefsKey.SERVER_PASSWORD, "");
        serverUrl = preferences.getString(PrefsKey.SERVER_URL, "");
        serverPassword = password.isEmpty() || password.equals(getString(R.string.server_default_password)) ? null : password;
    }

    public void removeProgressListener(int jobId, Uploader.OnProgressListener listener) {
        List<Uploader.OnProgressListener> list = progressListeners.get(jobId);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) progressListeners.remove(jobId);
        }
    }

    public void removeErrorListener(int jobId, Uploader.OnErrorListener listener) {
        List<Uploader.OnErrorListener> list = errorListeners.get(jobId);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) errorListeners.remove(jobId);
        }
    }

    public void removeSuccessListener(int jobId, Uploader.OnCompleteListener listener) {
        List<Uploader.OnCompleteListener> list = successListeners.get(jobId);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) successListeners.remove(jobId);
        }
    }

    public void addProgressListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnProgressListener listener) {
        List<Uploader.OnProgressListener> listeners = progressListeners.get(jobId);
        if (listeners != null) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        } else {
            listeners = new ArrayList<>();
            listeners.add(listener);
            if (owner != null) {
                owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull LifecycleOwner owner) {
                        removeProgressListener(jobId, listener);
                    }
                });
            }
        }

        progressListeners.put(jobId, listeners);
    }

    public void addErrorListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnErrorListener listener) {
        List<Uploader.OnErrorListener> listeners = errorListeners.get(jobId);
        if (listeners != null) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        } else {
            listeners = new ArrayList<>();
            listeners.add(listener);
            if (owner != null) {
                owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull LifecycleOwner owner) {
                        removeErrorListener(jobId, listener);
                    }
                });
            }
        }

        errorListeners.put(jobId, listeners);
    }

    public void addSuccessListener(int jobId, @Nullable LifecycleOwner owner, Uploader.OnCompleteListener listener) {
        List<Uploader.OnCompleteListener> listeners = successListeners.get(jobId);
        if (listeners != null) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        } else {
            listeners = new ArrayList<>();
            listeners.add(listener);
            if (owner != null) {
                owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull LifecycleOwner owner) {
                        removeSuccessListener(jobId, listener);
                    }
                });
            }
        }

        successListeners.put(jobId, listeners);
    }

    public int enqueueFile(@NonNull CustomFile file) {
        UploadJob job;
        if ((job = jobMap.get(file)) != null) {
            return job.id;
        }

        job = new UploadJob(uploadCounter.getAndIncrement(), file);
        jobMap.put(file, job);

        if (!jobs.offer(job)) {
            throw new RuntimeException("Unable to upload file");
        }

        return job.id;
    }

    @NonNull
    @SuppressWarnings("CharsetObjectCanBeUsed")
    public String getShareUrl(CustomFile[] files) throws RuntimeException {
        if (files == null) return "";
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
            object.put("k", XferActivity.generateRandomKey());
            object.put("pw", preferences.getString(PrefsKey.SHARE_PASSWORD, ""));
            object.put("exp", XferActivity.getExpiration(preferences.getString(PrefsKey.LINK_EXPIRATION, "")));
            object.put("perms", new JSONArray().put("read"));

            JSONArray array = new JSONArray();
            for (String path : shareUrls) {
                array.put(URLDecoder.decode(new URL(path).getPath(), StandardCharsets.UTF_8.name()));
            }

            object.put("vp", array);

            Log.d(TAG, object.toString());
            HttpURLConnection connection = getShareApiConnection();

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(object.toString().getBytes());
                stream.flush();
            }

            String response = NetworkUtils.readConnection(connection, status -> status <= 300).toString();
            Log.d(TAG, connection.getResponseCode() + " " + response);

            if (response.startsWith("created share: ")) {
                return response;
            }

            throw new RuntimeException("Reached end without share url!");
        } catch (Exception e) {
            Log.e(TAG, "Error in share creation", e);
            throw new RuntimeException(e);
        }
    }

    public void enqueueFiles(@NonNull CustomFile[] files) {
        for (CustomFile file : files) {
            enqueueFile(file);
        }
    }

    private void watchJobs() {
        watcher = executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    UploadJob job = jobs.take();
                    uploadFile(job);
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    public Handler getHandler() {
        return this.handler;
    }

    public boolean isUploading() {
        return !jobs.isEmpty();
    }

    private void resetGlobalNotification() {
        globalNotification = new NotificationCompat.Builder(this, CHANNEL_ID).setGroup(GROUP_NAME).setGroupSummary(true).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.notification_title)).setSubText("Initializing...").setContentText("Upload will begin shortly...").setProgress(0, 0, true).setOngoing(true);
    }

    private void notificationSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }

    private void notifySuccess(int jobId, String serverResponse) {
        List<Uploader.OnCompleteListener> deadListeners = new ArrayList<>();
        List<Uploader.OnCompleteListener> listeners = successListeners.get(jobId);
        if (listeners == null) return;

        for (Uploader.OnCompleteListener listener : listeners) {
            handler.post(() -> {
                try {
                    listener.run(serverResponse);
                } catch (Throwable error) {
                    Log.e(TAG, "Error notifying success listener " + listener.hashCode(), error);
                    deadListeners.add(listener);
                }
            });
        }

        if (!deadListeners.isEmpty()) {
            for (Uploader.OnCompleteListener l : deadListeners) {
                listeners.remove(l);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                successListeners.replace(jobId, listeners);
            } else {
                successListeners.put(jobId, listeners);
            }
        }

        // Clean after success
        successListeners.remove(jobId);
    }

    private void notifyError(int jobId, Throwable err) {
        List<Uploader.OnErrorListener> deadListeners = new ArrayList<>();
        List<Uploader.OnErrorListener> listeners = errorListeners.get(jobId);
        if (listeners == null) return;

        for (Uploader.OnErrorListener listener : listeners) {
            handler.post(() -> {
                try {
                    listener.run(err);
                } catch (Throwable error) {
                    Log.e(TAG, "Error notifying error listener " + listener.hashCode(), error);
                    deadListeners.add(listener);
                }
            });
        }

        if (!deadListeners.isEmpty()) {
            for (Uploader.OnErrorListener l : deadListeners) {
                listeners.remove(l);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                errorListeners.replace(jobId, listeners);
            } else {
                errorListeners.put(jobId, listeners);
            }
        }
    }

    private void notifyProgress(int jobId, BaseUploadProgress progress) {
        List<Uploader.OnProgressListener> deadListeners = new ArrayList<>();
        List<Uploader.OnProgressListener> listeners = progressListeners.get(jobId);
        if (listeners == null) return;

        for (Uploader.OnProgressListener listener : listeners) {
            handler.post(() -> {
                try {
                    listener.run(progress);
                } catch (Throwable error) {
                    Log.e(TAG, "Error notifying progress listener " + listener.hashCode(), error);
                    deadListeners.add(listener);
                }
            });
        }

        if (!deadListeners.isEmpty()) {
            for (Uploader.OnProgressListener l : deadListeners) {
                listeners.remove(l);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                progressListeners.replace(jobId, listeners);
            } else {
                progressListeners.put(jobId, listeners);
            }
        }
    }

    private void uploadFile(@NonNull UploadJob job) {
        globalProgress.currentFile = job.progress.currentFile;
        updateGlobalNotification();
        AtomicReference<Throwable> prevErr = new AtomicReference<>();

        try {
            new Uploader.Builder(this).setServerPassword(serverPassword).setServerUrl(serverUrl).setOnProgress(progress -> {
                job.update(progress.done, progress.total);
                globalProgress.doneBytes += progress.delta;

                notifyProgress(job.id, progress);
                updateGlobalNotification();
            }).setOnComplete(resp -> {
                job.complete(resp);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    jobMap.remove(job.progress.currentFile, job);
                } else {
                    jobMap.remove(job.progress.currentFile);
                }

                filePaths.add(job.progress.currentFile.share_url);
                notifySuccess(job.id, resp);
                updateGlobalNotification();
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    jobMap.remove(job.progress.currentFile, job);
                } else {
                    jobMap.remove(job.progress.currentFile);
                }
                prevErr.set(err);

                notifyError(job.id, err);
                updateGlobalNotification();
            }).build().upload(job.progress.currentFile);
        } catch (Throwable e) {
            prevErr.set(new RuntimeException("Unexpected error while uploading", e));
        }
    }

    private void updateGlobalNotification() {
        if (manager == null || globalNotification == null) return;

        int jobCount = jobs.size();

        // Nothing to upload
        if (jobCount == 0 && globalProgress.currentFile == null) {
            globalNotification.setContentTitle(getString(R.string.notification_title)).setContentText("No active uploads").setSubText("Idle").setProgress(0, 0, false).setOngoing(false);
            manager.notify(GLOBAL_NOTIFICATION_ID, globalNotification.build());
            return;
        }

        long done = globalProgress.doneBytes;
        long total = globalProgress.totalBytes;

        boolean indeterminate = total <= 0;

        int percent = 0;
        if (!indeterminate) {
            percent = (int) Math.floor(NumberUtils.calcPercentage(done, total));
        }

        String currentFileName = globalProgress.currentFile != null ? globalProgress.currentFile.name : "Preparing...";
        String contentText;
        if (indeterminate) {
            contentText = "Calculating size...";
        } else {
            contentText = String.format("Overall: %s / %s", NumberUtils.formatBytes(done), NumberUtils.formatBytes(total));
        }

        String subText = String.format(Locale.getDefault(), "%d job%s remaining", jobCount, jobCount == 1 ? "" : "s");
        globalNotification
                .setContentTitle("Uploading files...")
                .setContentText(contentText)
                .setSubText(subText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText).setSummaryText("Current: " + currentFileName))
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setSmallIcon(android.R.drawable.stat_sys_upload);
        manager.notify(GLOBAL_NOTIFICATION_ID, globalNotification.build());
    }

    @NonNull
    private HttpURLConnection getShareApiConnection() throws IOException {
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

    @Override
    public void onCreate() {
        super.onCreate();
        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        resetGlobalNotification();
        notificationSetup();
        watchJobs();
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
        if (intent == null) return START_NOT_STICKY;
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        startForeground(GLOBAL_NOTIFICATION_ID, globalNotification.build());

        try {
            Serializable serializable = intent.getSerializableExtra(STATE_KEY);
            STATE state = (STATE) serializable;
            if (state == null) {
                refreshPrefs();
            } else {
                if (state.filesToUpload != null) {
                    enqueueFiles(state.filesToUpload);
                }
                serverPassword = state.serverPassword;
                serverUrl = state.baseUrl;
            }

            manager.notify(GLOBAL_NOTIFICATION_ID, globalNotification.setContentText("Waiting for upload to start...").setProgress(0, 0, false).build());
            return START_STICKY;
        } catch (ClassCastException /* This happens when I tried to retrieve the files */ ex) {
            Log.e(TAG, "Cannot retrieve state from intent", ex);
            return START_NOT_STICKY;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
