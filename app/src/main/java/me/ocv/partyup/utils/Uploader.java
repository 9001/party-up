package me.ocv.partyup.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Stream;

import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.BaseUploadProgress;

public class Uploader {
    private static final String TAG = "Uploader";
    private final Runnable onInit = () -> Log.d(TAG, "Uploading started!");

    private String serverUrl;
    private String password;
    private Consumer<Throwable> onError = (err) -> Log.e(TAG, "Upload error: " + err.toString());
    private Consumer<BaseUploadProgress> onProgress = (progress) -> Log.i(TAG, String.format(Locale.getDefault(), "Uploaded: %d/%d bytes (delta=%d)", progress.done, progress.total, progress.delta));
    private Runnable onComplete = () -> Log.d(TAG, "Uploading completed!");
    private Context context;

    private boolean uploadFile(@NonNull CustomFile cf, HttpURLConnection conn) throws Exception {
        if (cf.size == null || cf.size == 0 || cf.handle == null) {
            Log.e(TAG, String.format("Bad media file: %s", cf));
            conn.disconnect();
            return false;
        }
        Log.d(TAG, String.format("Identified '%s' as a file (%s) with size: %d", cf.name, cf.mime, cf.size));

        conn.setRequestMethod("PUT");
        conn.setFixedLengthStreamingMode(cf.size);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.connect();
        this.onInit.run();

        OutputStream os = conn.getOutputStream();
        try (InputStream ins = this.context.getContentResolver().openInputStream(cf.handle)) {
            if (ins == null) throw new RuntimeException("Input stream is null!");
            MessageDigest md = MessageDigest.getInstance("SHA-512");

            byte[] buf = new byte[128 * 1024];

            BaseUploadProgress up = new BaseUploadProgress();
            up.total = cf.size;
            up.done = 0;
            up.delta = 0;

            while (true) {
                int n = ins.read(buf);
                if (n == -1) break;

                os.write(buf, 0, n);
                md.update(buf, 0, n);

                up.delta = n;
                up.done += n;
                this.onProgress.accept(up);
                Log.d(TAG, String.format("[Progress] delta, total, done: %d, %d, %d", up.delta, up.total, up.done));
            }

            os.flush();
            int rc = conn.getResponseCode();
            if (rc >= 300) {
                String message = String.format(Locale.getDefault(), "[%d] Server error:\n%s", rc, Arrays.toString(readServerResponse(conn)));
                this.onError.accept(new RuntimeException(message));
                conn.disconnect();
                return false;
            }
            return uploadSuccess(md, conn, cf);
        }
    }

    private boolean uploadText(@NonNull CustomFile cf, HttpURLConnection conn) throws Exception {
        if (cf.content == null) {
            Log.e(TAG, String.format("Bad text file: %s", cf));
            conn.disconnect();
            return false;
        }

        Log.d(TAG, "Creating body...");

        @SuppressWarnings("CharsetObjectCanBeUsed")
        byte[] body = ("msg=" + URLEncoder.encode(cf.content, StandardCharsets.UTF_8.name())).getBytes(StandardCharsets.UTF_8);

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        conn.connect();

        this.onInit.run();
        Log.d(TAG, "[POST] Body creation successful: " + body.length);

        BaseUploadProgress up = new BaseUploadProgress();
        up.delta = body.length;
        up.total = body.length;
        up.done = body.length;

        Log.d(TAG, String.format("[Progress] delta, total, done: %d, %d, %d", up.delta, up.total, up.done));

        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        this.onProgress.accept(up);

        int rc = conn.getResponseCode();
        if (rc >= 300) {
            String message = String.format(Locale.getDefault(), "[%d] Server error:\n%s", rc, Arrays.toString(readServerResponse(conn)));
            this.onError.accept(new RuntimeException(message));
            conn.disconnect();
            return false;
        }

        String[] serverResponse = readServerResponse(conn);
        if (serverResponse.length > 0) {
            cf.share_url = String.join("\n", serverResponse).trim();
        } else {
            cf.share_url = "Server not happy!";
        }

        return true;
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

        if (uploadSuccess) this.onComplete.run();

        Log.i(TAG, String.format("Uploader result: %s", uploadSuccess));
        conn.disconnect();
    }

    @NonNull
    private HttpURLConnection makeConnection(@NonNull CustomFile customFile) throws Exception {
        String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        String encodedName = customFile.mime.equals("text/plain") ? "" : Uri.encode(customFile.name, "/");
        String fullUrl = base + encodedName;

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        if (this.password != null) conn.setRequestProperty("PW", this.password);

        conn.setDoOutput(true);
        Log.d(TAG, "Sending to: " + fullUrl);
        return conn;
    }

    @NonNull
    public static String[] readConnectionStream(InputStream inputStream) throws IOException {
        if (inputStream == null) return new String[]{""};
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
            List<String> lines = new ArrayList<>();
            String line;

            do {
                line = bufferedReader.readLine();
                lines.add(line);
            } while (line != null && !line.isBlank());

            return lines.toArray(new String[0]);
        }
    }

    @NonNull
    private String[] readServerResponse(@NonNull HttpURLConnection conn) throws Exception {
        InputStream is;
        if (conn.getResponseCode() >= 400) {
            is = conn.getErrorStream();
            if (is == null) {
                return new String[]{""};
            }
        } else {
            is = conn.getInputStream();
        }
        return readConnectionStream(is);
    }

    private boolean uploadSuccess(@NonNull MessageDigest md, HttpURLConnection conn, CustomFile cf) throws Exception {
        StringBuilder sha = new StringBuilder();
        byte[] bSha = md.digest();
        for (int a = 0; a < 28; a++)
            sha.append(String.format("%02x", bSha[a]));

        String[] lines = readServerResponse(conn);

        if (lines.length < 3) {
            this.onError.accept(new RuntimeException("SERVER ERROR:\n" + lines[0]));
            return false;
        }

        if (lines[2].indexOf(sha.toString()) != 0) {
            this.onError.accept(new RuntimeException("ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" + sha + " from server"));
            return false;
        }

        if (lines.length > 3 && !lines[3].isEmpty()) cf.share_url = lines[3];
        else cf.share_url = cf.full_url.split("\\?")[0];

        return true;
    }

    public void setContext(Context con) {
        this.context = con;
    }

    public void setOnProgress(Consumer<BaseUploadProgress> onProc) {
        this.onProgress = onProc;
    }

    public String post(String body) throws Exception {
        String base = serverUrl + (serverUrl.endsWith("/") ? "" : "/") + "?share";
        HttpURLConnection conn = (HttpURLConnection) new URL(base).openConnection();

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);

        if (this.password != null) {
            conn.setRequestProperty("PW", this.password);
        }

        conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }

        String[] resp = readServerResponse(conn); // <-- better to pass stream, not conn
        return String.join("\n", resp);
    }

    public void setOnError(Consumer<Throwable> onErr) {
        this.onError = onErr;
    }

    public String getServerUrl() {
        return this.serverUrl;
    }

    public void setServerUrl(String url) {
        this.serverUrl = url;

        if (!this.serverUrl.startsWith("http")) this.serverUrl = "http://" + this.serverUrl;

        if (!this.serverUrl.endsWith("/")) this.serverUrl += "/";

        if (this.serverUrl.contains("%")) {
            String[] dtc = "%Y %q %m %d %j %H %M %S".split(" ");

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy Q MM dd DDD HH mm ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String[] dtp = sdf.format(new Date()).split(" ");

            for (int a = 0; a < dtc.length; a++)
                this.serverUrl = this.serverUrl.replace(dtc[a], dtp[a]);
        }

        Log.d(TAG, "Server Url: " + this.serverUrl);
    }

    public String getServerPassword() { return this.password; }
    public void setPassword(String pass) {
        this.password = pass;
    }

    public void setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
    }
}
