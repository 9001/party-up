package me.ocv.partyup.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.ocv.partyup.R;
import me.ocv.partyup.XferActivity;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.Progress;

public class UploaderService extends Service {
    private static final String TAG = "UploaderService";
    private static final String CHANNEL_ID = "uploader_channel";
    private static final int NOTIFICATION_ID = 1;

    private final Uploader uploader = new Uploader();
    private final Progress progress = new Progress();
    private boolean isUploading = false;

    private String password;
    private boolean useShareUrl;
    private String shareExpiration;
    private String sharePassword;
    private CustomFile[] files;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        uploader.setContext(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String baseUrl = intent.getStringExtra("base_url");
        password = intent.getStringExtra("password");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            files = intent.getParcelableArrayExtra("files", CustomFile.class);
        } else {
            Parcelable[] parcelables = intent.getParcelableArrayExtra("files");
            if (parcelables != null) {
                // Create a new array of the specific type
                files = new CustomFile[parcelables.length];
                // Copy the elements over
                System.arraycopy(parcelables, 0, files, 0, parcelables.length);
            } else {
                files = new CustomFile[0];
            }
        }
        useShareUrl = intent.getBooleanExtra("use_share_url", false);
        shareExpiration = intent.getStringExtra("share_expiration");
        sharePassword = intent.getStringExtra("share_password");

        if (baseUrl == null || files == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        uploader.setServerUrl(baseUrl);
        uploader.setPassword(password);

        for (CustomFile cf : files) {
            if (cf.size != null && cf.size > 0) {
                progress.total += cf.size;
            }
        }

        startForeground(NOTIFICATION_ID, createNotification("Initializing upload..."));

        if (!isUploading) {
            isUploading = true;
            new Thread(this::performUpload).start();
        }

        return START_NOT_STICKY;
    }

    private void performUpload() {
        try {
            progress.t0 = System.currentTimeMillis();
            for (int i = 0; i < files.length; i++) {
                final int fileIndex = i;
                CustomFile file = files[i];

                uploader.setOnProgress(up -> {
                    progress.done += up.delta;
                    updateNotification(String.format(Locale.getDefault(), "Uploading %d/%d: %s\n%s",
                            fileIndex + 1, files.length, file.name, progress.stats()));
                });

                if (!uploader.upload(file)) {
                    showFinalNotification("Upload failed: " + file.name, null);
                    return;
                }
            }

            String shareUrl = "";
            if (useShareUrl) {
                shareUrl = createShareUrl(files);
            }

            showFinalNotification("Upload completed successfully!", shareUrl);
        } catch (Exception e) {
            Log.e(TAG, "Upload error", e);
            showFinalNotification("Upload error: " + e.getMessage(), null);
        } finally {
            isUploading = false;
            stopForeground(false);
            stopSelf();
        }
    }

    private String createShareUrl(CustomFile[] files) {
        try {
            List<CustomFile> wantedFiles = new ArrayList<>();
            for (CustomFile file : files) {
                if (file.isSharable()) wantedFiles.add(file);
            }

            if (wantedFiles.isEmpty()) return files[0].share_url;

            String key = generateRandomKey();
            Uri shareApiUri = Uri.parse(uploader.getServerUrl());

            StringBuilder sharedFilesPaths = new StringBuilder();
            for (int i = 0; i < wantedFiles.size(); i++) {
                CustomFile cf = wantedFiles.get(i);
                String filePath = URLDecoder.decode(new URL(cf.full_url).getPath(), "UTF-8");
                sharedFilesPaths.append("\"").append(filePath).append("\"");
                if (i < wantedFiles.size() - 1) sharedFilesPaths.append(",");
            }

            String jsonBody = String.format("{\"k\":\"%s\",\"vp\":[%s],\"pw\":\"%s\",\"exp\":\"%s\",\"perms\":[\"read\"]}",
                    key, sharedFilesPaths, sharePassword != null ? sharePassword : "", shareExpiration != null ? shareExpiration : "");

            HttpURLConnection conn = getUrlConnection(shareApiUri, jsonBody);

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String response = br.readLine();
                if (response != null && response.startsWith("created share: "))
                    return response.substring(15);
            }
        } catch (Exception ex) {
            Log.w(TAG, "Share creation error: " + ex);
        }
        return "";
    }

    @NonNull
    private HttpURLConnection getUrlConnection(Uri shareApiUri, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) (new URL(shareApiUri.getScheme() + "://" + shareApiUri.getAuthority() + "/")).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        if (password != null) conn.setRequestProperty("PW", password);

        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        conn.connect();

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        if (conn.getResponseCode() >= 300) throw new RuntimeException("Unable to get share url!");
        return conn;
    }

    private String generateRandomKey() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 12; i++)
            key.append(chars.charAt(random.nextInt(chars.length())));
        return key.toString();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Uploader Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String content) {
        Intent notificationIntent = new Intent(this, XferActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Party UP! Uploading...")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, createNotification(content));
    }

    private void showFinalNotification(String content, @Nullable String shareUrl) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Party UP! Upload Status")
                    .setContentText(content)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setAutoCancel(true);

            if (shareUrl != null && !shareUrl.isEmpty()) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareUrl);
                PendingIntent pendingShare = PendingIntent.getActivity(this, 1, 
                    Intent.createChooser(shareIntent, "Share link"), 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(android.R.drawable.ic_menu_share, "Share Link", pendingShare);
            }

            manager.notify(NOTIFICATION_ID + 1, builder.build());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
