package me.ocv.partyup.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.ocv.partyup.R;
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
    private final List<ErrorListener> errorListeners = new ArrayList<>();

    private NotificationCompat.Builder notification;
    private NotificationManager manager;

    private SharedPreferences preferences;
    private Throwable lastError;

    @Override
    public void onCreate() {
        super.onCreate();
        uploader.setContext(this);
        notification = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.notification_title)).setContentText("Initializing...").setProgress(0, 0, true).setOngoing(true);

        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationSetup();
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
        String name = progress.currentFile == null ? getString(R.string.notification_filename_fallback) : (String.format("%s (%s)", progress.currentFile.name, NumberUtils.formatBytes(progress.currentFile.size)));
        notification.setContentText(getString(R.string.notification_upload_text)).setSubText(name).setProgress(100, (int) Math.floor(NumberUtils.calcPercentage(progress.doneBytes, progress.totalBytes)), progress.totalBytes == 0);
        manager.notify(NOTIFICATION_ID, notification.build());
    }

    private void preUpload() {
        manager.notify(NOTIFICATION_ID, notification.setContentText(getString(R.string.notification_upload_info_text)).setSubText(uploadProgress.smallDescription()).build());
    }

    private void postUpload() {
        if (getLastError() == null) {
            manager.notify(NOTIFICATION_ID, notification.setSmallIcon(android.R.drawable.stat_sys_upload_done).setProgress(0, 0, false).setContentText(getString(R.string.notification_upload_finished_text)).setSubText(uploadProgress.longDescription()).setOngoing(false).build());
        } else {
            manager.notify(NOTIFICATION_ID, notification.setSmallIcon(android.R.drawable.stat_notify_error).setProgress(0, 0, false).setContentText(getString(R.string.notification_upload_failed_text)).setSubText(getLastError().getLocalizedMessage()).setOngoing(false).build());
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
        if ((uploadProgress.currentFile = getFile()) == null) return;

        uploader.setOnProgress(progress -> {
            uploadProgress.doneBytes += progress.delta;
            notifyProgress(uploadProgress);
        });

        uploader.setOnError(this::notifyError);

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
        this.progressListeners.add(progressListener);
    }

    public void addErrorListener(ErrorListener errorListener) {
        this.errorListeners.add(errorListener);
    }

    public void enqueueFile(@NonNull CustomFile file) {
        fileQueue.add(file);
        uploadProgress.totalFiles++;

        if (file.size != null) {
            uploadProgress.totalBytes += file.size;
        }

        Log.d(TAG, String.format("File enqueued: %s", file));
    }

    public void enqueueFiles(@NonNull CustomFile[] files) {
        for (CustomFile file : files) {
            enqueueFile(file);
        }
    }

    public void startUploading() {
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

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(UploaderService.UploadProgress progress);
    }

    @FunctionalInterface
    public interface ErrorListener {
        void onError(Throwable error);
    }

    public static final class STATE implements Serializable {
        private static final long serialVersionUID = 1L;

        public String baseUrl;
        public String serverPassword;
        public CustomFile[] filesToUpload;
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
    }

    public final class UploadBinder extends Binder {
        public UploaderService getService() {
            return UploaderService.this;
        }
    }
}
