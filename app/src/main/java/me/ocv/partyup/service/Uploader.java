package me.ocv.partyup.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import me.ocv.partyup.objects.BaseUploadProgress;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.utils.NetworkUtils;

public class Uploader {
    private static final String             TAG            = "Uploader";
    private final        String             serverUrl;
    private final        String             password;
    private final        OnCompleteListener onComplete;
    private final        OnErrorListener    onError;
    private final        OnProgressListener onProgress;
    private final        Context            context;
    private final        int                chunkSize;
    private              StringBuilder      serverResponse = new StringBuilder();

    private Uploader(@NonNull Builder builder) {
        this.serverUrl  = builder.serverUrl;
        this.password   = builder.serverPassword;
        this.context    = builder.context;
        this.onError    = builder.onError;
        this.onProgress = builder.onProgress;
        this.onComplete = builder.onComplete;
        this.chunkSize  = builder.chunkSize;
    }

    public void upload(CustomFile cf) throws Exception {
        HttpURLConnection conn = makeConnection(cf);
        cf.full_url = conn.getURL().toString();

        Log.d(TAG, String.format("Uploading started of file: %s", cf));
        boolean uploadSuccess;

        if (cf.mime.equals("text/plain")) {
            // Text (aka links) Upload POST
            uploadSuccess = this.uploadText(cf, conn);
        } else {
            // Files Upload PUT
            uploadSuccess = this.uploadFile(cf, conn);
        }

        Log.d(TAG, "informing complete");
        this.onComplete.run(serverResponse.toString());
        Log.i(
                TAG,
                String.format(
                        "Uploader result: %s %s",
                        uploadSuccess,
                        cf.share_url != null ? cf.share_url : "(empty)"
                )
        );
        conn.disconnect();
    }

    private boolean uploadFile(
            @NonNull CustomFile cf,
            HttpURLConnection conn
    ) throws Exception {
        if (cf.size == null || cf.size == 0 || cf.handle == null) {
            Log.e(TAG, String.format("Bad media file: %s", cf));
            conn.disconnect();
            return false;
        }
        Log.d(
                TAG,
                String.format(
                        "Identified '%s' as a file (%s) with size: %d",
                        cf.name,
                        cf.mime,
                        cf.size
                )
        );

        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setChunkedStreamingMode(this.chunkSize);

        MessageDigest md = MessageDigest.getInstance("SHA-512");
        conn.connect();

        try (
                InputStream ins = this.context.getContentResolver().openInputStream(cf.handle);
                OutputStream os = conn.getOutputStream()
        ) {
            if (ins == null) {
                throw new RuntimeException("Input stream is null!");
            }
            byte[] buf = new byte[this.chunkSize];

            BaseUploadProgress up = new BaseUploadProgress();
            up.total = cf.size;
            up.delta = this.chunkSize;
            up.done  = 0;

            int n;
            while ((n = ins.read(buf, 0, buf.length)) != -1) {
                up.done += n;

                Log.d(TAG, String.format("read %d bytes now writing...", n));

                os.write(buf, 0, n);
                md.update(buf, 0, n);

                Log.d(
                        TAG,
                        String.format(
                                "[Progress] delta, total, done: %d, %d, %d",
                                up.delta,
                                up.total,
                                up.done
                        )
                );

                Log.d(TAG, "informing listener");
                this.onProgress.run(up);
            }

            if (up.done != up.total) {
                Log.e(TAG, "size mismatch", new IOException(
                        "Size mismatch: expected=" + cf.size +
                        " actual=" + up.done
                ));
            }
            
            up.total = up.done;
            this.onProgress.run(up);
        }

        Log.d(TAG, "reading connection...");
        int rc = conn.getResponseCode();
        serverResponse = NetworkUtils.readConnection(conn);
        Log.d(TAG, "connection read!");

        if (rc >= 300) {
            String message = String.format(
                    Locale.getDefault(),
                    "[%d] Server error:\n%s",
                    rc,
                    serverResponse.toString()
            );
            this.onError.run(new RuntimeException(message));
            conn.disconnect();
            return false;
        }

        return uploadSuccess(md, cf);
    }

    private boolean uploadSuccess(
            @NonNull MessageDigest md,
            CustomFile cf
    ) {
        Log.d(TAG, "determining success");
        StringBuilder sha = new StringBuilder();
        byte[] bSha = md.digest();
        for (int a = 0; a < 28; a++) {
            sha.append(String.format("%02x", bSha[a]));
        }

        String[] lines = serverResponse.toString()
                                       .split("\n");
        if (lines.length < 3) {
            this.onError.run(new RuntimeException("SERVER ERROR:\n" + lines[0]));
            return false;
        }

        if (lines[2].indexOf(sha.toString()) != 0) {
            this.onError.run(new RuntimeException(
                    "ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" +
                    sha + " from server"));
            return false;
        }

        if (lines.length > 3 && !lines[3].isEmpty()) {
            cf.share_url = lines[3];
        } else {
            cf.share_url = cf.full_url.split("\\?")[0];
        }

        return true;
    }

    private boolean uploadText(
            @NonNull CustomFile cf,
            HttpURLConnection conn
    ) throws Exception {
        if (cf.content == null) {
            Log.e(TAG, String.format("Bad text file: %s", cf));
            conn.disconnect();
            return false;
        }

        Log.d(TAG, "Creating body...");

        @SuppressWarnings("CharsetObjectCanBeUsed")
        byte[] body = ("msg=" + URLEncoder.encode(
                cf.content,
                StandardCharsets.UTF_8.name()
        )).getBytes(StandardCharsets.UTF_8);

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        conn.connect();

        Log.d(TAG, "[POST] Body creation successful: " + body.length);

        BaseUploadProgress up = new BaseUploadProgress();
        up.delta = body.length;
        up.total = body.length;
        up.done  = body.length;

        Log.d(
                TAG,
                String.format(
                        "[Progress] delta, total, done: %d, %d, %d",
                        up.delta,
                        up.total,
                        up.done
                )
        );

        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        this.onProgress.run(up);

        serverResponse = NetworkUtils.readConnection(conn);

        int rc = conn.getResponseCode();
        if (rc >= 300) {
            String message = String.format(
                    Locale.getDefault(),
                    "[%d] Server error:\n%s",
                    rc,
                    serverResponse.toString()
            );
            this.onError.run(new RuntimeException(message));
            conn.disconnect();
            return false;
        }

        if (serverResponse.length() > 0) {
            cf.share_url = serverResponse.toString()
                                         .trim();
        } else {
            cf.share_url = "Server not happy!";
        }

        return true;
    }

    @NonNull
    private HttpURLConnection makeConnection(
            @NonNull CustomFile customFile
    ) throws Exception {
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        String encodedName = customFile.mime.equals("text/plain") ? "" : Uri.encode(
                customFile.name,
                "/"
        );
        String fullUrl = base + encodedName;

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        if (this.password != null) {
            conn.setRequestProperty("PW", this.password);
        }

        conn.setDoOutput(true);
        Log.d(TAG, "Sending to: " + fullUrl);
        return conn;
    }

    @FunctionalInterface
    public interface Listener<T> {
        void run(T listen);
    }

    @FunctionalInterface
    public interface OnCompleteListener extends Listener<String> {}

    @FunctionalInterface
    public interface OnErrorListener extends Listener<Throwable> {}

    @FunctionalInterface
    public interface OnProgressListener extends Listener<BaseUploadProgress> {}

    public static final class Builder {
        private static final int MIN_CHUNK_SIZE = 128 * 1024;
        private final Context            context;
        private       OnErrorListener    onError        = null;
        private       OnProgressListener onProgress     = null;
        private       OnCompleteListener onComplete     = null;
        private       String             serverUrl      = null;
        private       String             serverPassword = null;
        private       int                chunkSize      = MIN_CHUNK_SIZE;

        public Builder(
                @NonNull Context context
        ) {
            this.context = context.getApplicationContext();
        }

        public Builder setChunkSize(
                int chunkSize
        ) {
            this.chunkSize = Math.max(chunkSize, MIN_CHUNK_SIZE);
            return this;
        }

        public Builder setOnComplete(
                @Nullable OnCompleteListener onComplete
        ) {
            this.onComplete = onComplete;
            return this;
        }

        public Builder setOnError(
                @Nullable OnErrorListener onError
        ) {
            this.onError = onError;
            return this;
        }

        public Builder setOnProgress(
                @Nullable OnProgressListener onProgress
        ) {
            this.onProgress = onProgress;
            return this;
        }

        public Builder setServerPassword(
                @NonNull String serverPassword
        ) {
            this.serverPassword = serverPassword;
            return this;
        }

        public Builder setServerUrl(
                @NonNull String serverUrl
        ) {
            this.serverUrl = serverUrl;

            if (!this.serverUrl.startsWith("http")) {
                this.serverUrl = "http://" + this.serverUrl;
            }

            if (!this.serverUrl.endsWith("/")) {
                this.serverUrl += "/";
            }

            if (this.serverUrl.contains("%")) {
                String[] dtc = "%Y %q %m %d %j %H %M %S".split(" ");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy Q MM dd DDD HH mm ss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                String[] dtp = sdf.format(new Date())
                                  .split(" ");

                for (int a = 0; a < dtc.length; a++) {
                    this.serverUrl = this.serverUrl.replace(dtc[a], dtp[a]);
                }
            }

            return this;
        }

        @NonNull
        public Uploader build() {
            if (onProgress == null) {
                onProgress = (progress) -> Log.i(
                        TAG,
                        String.format(
                                Locale.getDefault(),
                                "Uploaded: %d/%d bytes (delta=%d)",
                                progress.done,
                                progress.total,
                                progress.delta
                        )
                );
            }
            if (onError == null) {
                onError = (err) -> Log.e(TAG, "Upload error: " + err.toString());
            }
            if (onComplete == null) {
                onComplete = response -> Log.i(TAG, "Uploaded Response: " + response);
            }

            if (serverUrl == null || serverUrl.isEmpty()) {
                throw new IllegalStateException("serverUrl is empty!");
            }
            return new Uploader(this);
        }

    }

}
