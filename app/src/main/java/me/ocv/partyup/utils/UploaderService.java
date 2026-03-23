package me.ocv.partyup.utils;

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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import me.ocv.partyup.R;
import me.ocv.partyup.XferActivity;
import me.ocv.partyup.XferAfterActivity;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.PrefsKey;

public class UploaderService extends Service {
    public static final String STATE_KEY = "state";

    private static final String TAG = "UploaderService";
    private static final String CHANNEL_ID = "Uploader";
    private static final String CHANNEL_NAME = "PartyUploader";
    private static final int NOTIFICATION_ID = 69;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<CustomFile> fileQueue = new LinkedList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final UploadProgress uploadProgress = new UploadProgress();
    private final Uploader uploader = new Uploader();

    private final IBinder binder = new UploadBinder();

    private final List<ProgressListener> progressListeners = new ArrayList<>();
    private final List<SuccessListener> successListeners = new ArrayList<>();
    private final List<ErrorListener> errorListeners = new ArrayList<>();
    private final List<String> filePaths = new ArrayList<>();
    private final AtomicReference<Boolean> uploading = new AtomicReference<>(false);

    private NotificationCompat.Builder notification;
    private NotificationManager manager;

    private SharedPreferences preferences;
    private Throwable lastError;

    @Override
    public void onCreate() {
        super.onCreate();
        uploader.setContext(this);
        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        resetNotification();
        notificationSetup();
    }

    private void resetNotification() {
        notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.notification_title))
                .setSubText("Initializing...")
                .setContentText("Upload will begin shortly...")
                .setProgress(0, 0, true)
                .setOngoing(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        progressListeners.clear();
        errorListeners.clear();
        executor.shutdownNow();
        stopForeground(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        startForeground(NOTIFICATION_ID, notification.build());

        try {
            Serializable serializable = intent.getSerializableExtra(STATE_KEY);
            UploaderService.STATE state = (STATE) serializable;
            if (state == null) {
                refreshPrefs();
            } else {
                if (state.filesToUpload != null) {
                    enqueueFiles(state.filesToUpload);
                }
                uploader.setPassword(state.serverPassword);
                uploader.setServerUrl(state.baseUrl);
            }

            manager.notify(NOTIFICATION_ID, notification.setContentText("Waiting for upload to start...").setProgress(0, 0, false).build());
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

    private void notificationSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }

    public boolean isDone() {
        return uploadProgress.doneBytes >= uploadProgress.totalBytes && uploadProgress.currentIndex == uploadProgress.totalFiles - 1;
    }

    public boolean isQueueEmpty() {
        return fileQueue.isEmpty();
    }

    private void notifyNotification(@NonNull UploadProgress progress) {
        String filename;
        String extra;
        if (progress.currentFile == null) {
            filename = getString(R.string.notification_filename_fallback);
            extra = "0MiB";
        } else {
            filename = progress.currentFile.name;
            extra =  NumberUtils.formatBytes(progress.currentFile.size);
        }

        notification
                .setContentTitle(getString(R.string.notification_title))
                .setSubText(getString(R.string.notification_upload_text))
                .setContentText(progress.longProgress())
                .setStyle(new NotificationCompat.BigTextStyle().setSummaryText(String.format(Locale.getDefault(), "%s (%s)", filename, extra)))
                .setProgress(100, (int) Math.floor(NumberUtils.calcPercentage(progress.doneBytes, progress.totalBytes)), progress.totalBytes == 0);
        manager.notify(NOTIFICATION_ID, notification.build());
    }

    private void notifyNotification(@NonNull SuccessResult result) {
        if (getLastError() != null) return;

        NotificationCompat.Builder notification = this.notification;
        notification.setContentText(getString(R.string.notification_upload_finished_text));

        Intent baseBeforeIntent = new Intent(this, XferAfterActivity.class);
        baseBeforeIntent.putExtra(XferAfterActivity.SHARE_URL_KEY, result.shareUrl);

        PendingIntent copyIntent = PendingIntent.getActivity(this, 1, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_COPY), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent shareIntent = PendingIntent.getActivity(this, 2, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHARE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent showIntent = PendingIntent.getActivity(this, 3, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHOW_QR), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        notification.addAction(R.drawable.copy, getString(R.string.noti_action_copy), copyIntent).addAction(R.drawable.share, getString(R.string.noti_action_share), shareIntent).addAction(R.drawable.qr_code, getString(R.string.noti_action_qr), showIntent);

        manager.notify(NOTIFICATION_ID, notification.build());
    }

    private void preUpload() {
        lastError = null;
        resetNotification();
        manager.notify(NOTIFICATION_ID, notification.setContentText(getString(R.string.notification_upload_info_text)).setSubText(uploadProgress.smallDescription()).build());
        filePaths.clear(); // Collect new file_paths
        uploading.set(true);
    }

    private void postUpload() {
        if (getLastError() == null) {
            manager.notify(NOTIFICATION_ID, notification.setSmallIcon(android.R.drawable.stat_sys_upload_done).setProgress(0, 0, false).setContentText(getString(R.string.notification_upload_finished_text)).setSubText(uploadProgress.longDescription()).setOngoing(false).build());
        } else {
            manager.notify(NOTIFICATION_ID, notification.setSmallIcon(android.R.drawable.stat_notify_error).setProgress(0, 0, false).setContentText(getString(R.string.notification_upload_failed_text)).setSubText(getLastError().getLocalizedMessage()).setOngoing(false).build());
        }

        uploading.set(false);
        executor.submit(this::notifyShareUrl);
    }

    @SuppressWarnings("CharsetObjectCanBeUsed")
    private void notifyShareUrl() {
        try {
            JSONObject object = new JSONObject();
            object.put("k", XferActivity.generateRandomKey());
            object.put("pw", preferences.getString(PrefsKey.SHARE_PASSWORD, ""));
            object.put("exp", XferActivity.getExpiration(preferences.getString(PrefsKey.LINK_EXPIRATION, "")));
            object.put("perms", new JSONArray().put("read"));

            JSONArray array = new JSONArray();
            for (String path : filePaths) {
                array.put(URLDecoder.decode(new URL(path).getPath(), StandardCharsets.UTF_8.name()));
            }

            object.put("vp", array);

            Log.d(TAG, object.toString());
            HttpURLConnection connection = getShareApiConnection();

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(object.toString().getBytes());
                stream.flush();
            }

            String response;
            Log.d(TAG, String.valueOf(connection.getResponseCode()));

            if (connection.getResponseCode() >= 300) {
                response = String.join("\n", Uploader.readConnectionStream(connection.getErrorStream()));
                throw new RuntimeException("Error Server response:\n".concat(response));
            } else {
                response = String.join("\n", Uploader.readConnectionStream(connection.getInputStream())).trim();
                Log.d(TAG, response);
            }

            if (response.startsWith("created share: ")) {
                notifySuccess(new SuccessResult(response.substring(15)));
                return;
            }

            throw new RuntimeException("Reached end without share url!");
        } catch (Exception e) {
            Log.e(TAG, "Error in share creation", e);
        }
    }

    @NonNull
    private HttpURLConnection getShareApiConnection() throws IOException {
        // URL shareApiUrl = new URL(uploader.getServerUrl());
        // shareApiUrl = new URL(shareApiUrl.getProtocol() + "://" + shareApiUrl.getHost() + (shareApiUrl.getPort() != -1 ? ":" + shareApiUrl.getPort() : "") + "/?share");
        URL shareApiUrl = new URL(uploader.getServerUrl() + ( uploader.getServerUrl().endsWith("/") ? "" : "/" ) + "?share");

        HttpURLConnection connection = (HttpURLConnection) shareApiUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/plain");

        connection.setDoOutput(true);
        connection.setDoInput(true);

        String serverPass;
        if (( serverPass = uploader.getServerPassword() ) != null && !serverPass.isBlank()) {
            connection.setRequestProperty("PW", serverPass);
        }
        return connection;
    }

    private void notifySuccess(SuccessResult result) {
        List<SuccessListener> deadListeners = new ArrayList<>();

        for (int i = 0; i < successListeners.size(); i++) {
            SuccessListener l = successListeners.get(i);
            this.handler.post(() -> {
                try {
                    l.onSuccess(result);
                } catch (Throwable error) {
                    Log.e(TAG, "Error notifying success listener " + l.hashCode(), error);
                    deadListeners.add(l);
                }
            });
        }

        notifyNotification(result);

        if (!deadListeners.isEmpty()) {
            for (SuccessListener l : deadListeners) {
                successListeners.remove(l);
            }
        }
    }

    private void notifyProgress(UploadProgress progress) {
        List<ProgressListener> deadListeners = new ArrayList<>();

        for (int i = 0; i < progressListeners.size(); i++) {
            ProgressListener l = progressListeners.get(i);
            this.handler.post(() -> {
                try {
                    l.onProgress(progress);
                } catch (Throwable error) {
                    Log.e(TAG, "Error notifying progress listener " + l.hashCode(), error);
                    deadListeners.add(l);
                }
            });
        }

        notifyNotification(progress);
        if (!deadListeners.isEmpty()) {
            for (ProgressListener l : deadListeners) {
                progressListeners.remove(l);
            }
        }
    }

    private void notifyError(Throwable error) {
        List<ErrorListener> deadListeners = new ArrayList<>();

        for (int i = 0; i < errorListeners.size(); ++i) {
            ErrorListener l = errorListeners.get(i);
            this.handler.post(() -> {
                try {
                    l.onError(error);
                } catch (Throwable err) {
                    // Irony lmao
                    Log.e(TAG, "Error notifying error listener " + l.hashCode(), err);
                    deadListeners.add(l);
                }
            });
        }

        if (!deadListeners.isEmpty()) {
            for (ErrorListener i : deadListeners) {
                errorListeners.remove(i);
            }
        }
        lastError = error;
    }

    private void uploadFile() {
        if (( uploadProgress.currentFile = getFile() ) == null) return;

        uploader.setOnProgress(progress -> {
            uploadProgress.doneBytes += progress.delta;
            notifyProgress(uploadProgress);
        });

        uploader.setOnError(this::notifyError);
        uploader.setOnComplete(() -> filePaths.add(uploadProgress.currentFile.full_url));

        try {
            uploader.upload(uploadProgress.currentFile);
        } catch (Throwable e) {
            Log.e(TAG, "UploaderError", e);
            notifyError(e);
        }
    }

    @Nullable
    private CustomFile getFile() {
        if (!fileQueue.isEmpty()) {
            CustomFile file = fileQueue.poll();
            uploadProgress.currentIndex++;
            return file;
        }

        return null;
    }

    public void refreshPrefs() {
        String password = preferences.getString(PrefsKey.SERVER_PASSWORD, "");
        String baseUrl = preferences.getString(PrefsKey.SERVER_URL, "");
        uploader.setPassword(password.isEmpty() || password.equals(getString(R.string.server_default_password)) ? null : password);
        uploader.setServerUrl(baseUrl);
    }

    public void addProgressListener(ProgressListener progressListener) {
        if (uploading.get()) return;
        this.progressListeners.add(progressListener);
    }

    public void addErrorListener(ErrorListener errorListener) {
        this.errorListeners.add(errorListener);
    }

    public void addSuccessListener(SuccessListener successListener) {
        if (uploading.get()) return;
        this.successListeners.add(successListener);
    }

    public void enqueueFile(@NonNull CustomFile file) {
        if (uploading.get()) return;

        fileQueue.add(file);
        uploadProgress.totalFiles++;

        if (file.size != null) {
            uploadProgress.totalBytes += file.size;
        }

        Log.d(TAG, String.format("File enqueued: %s", file));
    }

    public void enqueueFiles(@NonNull CustomFile[] files) {
        if (uploading.get()) return;

        for (CustomFile file : files) {
            enqueueFile(file);
        }
    }

    public void startUploading() {
        if (uploading.get()) return;

        executor.submit(() -> {
            preUpload();
            while (fileQueue.peek() != null) {
                uploadFile();
            }
            postUpload();
        });
    }

    public Handler getHandler() {
        return this.handler;
    }

    public Throwable getLastError() {
        return lastError;
    }

    public boolean isUploading() {
        return uploading.get();
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(UploaderService.UploadProgress progress);
    }

    @FunctionalInterface
    public interface ErrorListener {
        void onError(Throwable error);
    }

    @FunctionalInterface
    public interface SuccessListener {
        void onSuccess(SuccessResult result);
    }

    public static final class STATE implements Serializable {
        private static final long serialVersionUID = 1L;

        public String baseUrl;
        public String serverPassword;
        public CustomFile[] filesToUpload;
    }

    public static final class SuccessResult {
        public String shareUrl;
        /*
         * public long completedAt;
         * public long uploadedSize;
         * ...etc
         * */

        public SuccessResult(String shareUrl) {
            this.shareUrl = shareUrl;
        }
    }

    public static final class UploadProgress {
        public long totalBytes = 0;
        public long doneBytes = 0;

        public int totalFiles = 0;
        public int currentIndex = -1;
        public CustomFile currentFile = null;

        @NonNull
        public String smallDescription() {
            return String.format(Locale.getDefault(), "Files: %d (%s)", totalFiles, NumberUtils.formatBytes(totalBytes));
        }

        @NonNull
        public String longDescription() {
            return String.format(Locale.getDefault(), "(%.1f%%) File: %d/%d [%s]", NumberUtils.calcPercentage(doneBytes, totalBytes), currentIndex + 1, totalFiles, NumberUtils.formatBytes(currentFile != null ? currentFile.size : 0));
        }

        @NonNull
        public String smallProgress() {
            return String.format(Locale.getDefault(), "Progress: %.1f%%", NumberUtils.calcPercentage(doneBytes, totalBytes));
        }

        @NonNull
        public String longProgress() {
            return String.format(Locale.getDefault(), "Progress: %.1f%% %d/%d", NumberUtils.calcPercentage(doneBytes, totalBytes), currentIndex + 1, totalFiles);
        }
    }

    public final class UploadBinder extends Binder {
        public UploaderService getService() {
            return UploaderService.this;
        }
    }
}
