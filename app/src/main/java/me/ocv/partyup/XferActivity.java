package me.ocv.partyup;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.jetbrains.annotations.Contract;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.Consumer;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.Progress;
import me.ocv.partyup.objects.UploadProgress;
import me.ocv.partyup.utils.Discovery;
import me.ocv.partyup.utils.PermissionUtils;
import me.ocv.partyup.utils.Uploader;

public class XferActivity extends AppCompatActivity {
    private static final String TAG = "TransferActivity";
    private final Progress progress = new Progress();
    private final Discovery discovery = new Discovery();
    private final Uploader uploader = new Uploader();

    private ActivityXferBinding binding;
    private SharedPreferences prefs;

    private String base_url;
    private String password;

    private Boolean upping;
    private Boolean autosend;
    private CustomFile[] files;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!PermissionUtils.hasAllPermissions(this)) PermissionUtils.requestAllPermissions(this);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        upping = false;

        autosend = prefs.getBoolean("autosend", false);
        base_url = prefs.getString("server_url", "");
        password = prefs.getString("server_password", "");
        if (password.isEmpty() || password.equals("Default value")) password = null;

        files = discovery.parseIntent(this, getIntent(), (e) -> Log.e(TAG, e));

        uploader.setContext(this);
        uploader.setPassword(password);
        uploader.setServerUrl(base_url);

        for (CustomFile cf : files) {
            if (cf.size != null && cf.size > 0) {
                progress.total += cf.size;
            }
        }

        binding = ActivityXferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        binding.fab.setOnClickListener(v -> {
            binding.fab.setVisibility(View.GONE);
            doUp();
        });

        if (autosend) doUp();
        else showShareSettings();
    }

    private void doUp() {
        if (upping) return;
        upping = true;
        new Thread(this::doUp2).start();
    }

    private void doUp2() {
        try {
            progress.t0 = System.currentTimeMillis();

            final TextView tv = binding.upperInfo;
            final ProgressBar pb = binding.progbar;

            class ProgressStats {
                // Set default so it doesn't freak out
                public long done = 0;
                public int file = 0;
                public int total_file = files.length;
            }

            final Consumer<ProgressStats> onProgress = (ps) -> {
                try {
                    tv.post(() -> {
                        progress.done = ps.done;
                        int file = ps.file;
                        int total_file = ps.total_file;

                        tv.setText(String.join("\n\n", String.format("Sending to: %s...", base_url), String.format(Locale.getDefault(), "File: %d of %d\nDesc: %s", file + 1, total_file, files[file].desc), progress.stats()));
                        pb.setProgress(progress.perc());
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Issue in ui updating: " + e);
                }
            };
            Consumer<Error> onError = (err) -> tShowMsg(err.toString());

            uploader.setOnError(onError);

            final ProgressStats ps = new ProgressStats();
            ps.total_file = files.length;

            for (int i = 0; i < files.length; i++) {
                CustomFile file = files[i];
                ps.file = i;

                Consumer<UploadProgress> onPro = (up) -> {
                    ps.done += up.delta;
                    onProgress.accept(ps);
                };

                uploader.setOnProgress(onPro);
                boolean uploaded = uploader.upload(file);
                if (!uploaded) {
                    return;
                }
            }

            // Handle creation of share url in `onSuccess`
            findViewById(R.id.upper_info).post(this::onSuccess);
        } catch (Exception ex) {
            Log.e(TAG, ex.toString());
            tShowMsg("Error2: " + ex + "\n\nmaybe wrong password?");
        }
    }

    public void showMsg(String txt) {
        binding.upperInfo.setText(txt);
    }

    public void tShowMsg(String txt) {
        binding.upperInfo.post(() -> binding.upperInfo.setText(txt));
    }

    @NonNull
    private String getTextBody() {
        StringJoiner messages = new StringJoiner("\n");

        int counter = 1;
        for (CustomFile cf : files) {
            if (counter > 10) break;

            if (!cf.mime.equals("text/plain") || cf.content == null) continue;

            messages.add(String.format(Locale.getDefault(), "%d. %s", counter, cf.content.trim()));
            counter++;
        }

        String header = String.format("Post%s the following link%s%s", autosend ? "ing" : "", counter > 1 ? "s" : "", autosend ? ":" : "?");
        return counter == 1 ? "" : String.join("\n\n", header, messages.toString(), counter > 10 ? "[...]" : "");
    }

    @NonNull
    private String getFileBody() {
        StringJoiner filenames = new StringJoiner("\n");
        int counter = 1;
        for (CustomFile file : files) {
            if (counter > 10) break;

            if (file.mime.equals("text/plain") || file.size == null) continue;

            filenames.add(String.format(Locale.getDefault(), "%d. %s [%s]", counter, file.name, Progress.formatBytes(file.size)));
            counter++;
        }

        String header = String.format("Upload%s the following file%s%s", autosend ? "ing" : "", counter > 1 ? "s" : "", autosend ? ":" : "?");
        return counter == 1 ? "" : String.join("\n\n", header, filenames.toString(), counter > 10 ? "[...]" : "");
    }

    public void showShareSettings() {
        if (prefs.getBoolean("use_share_url", false)) {
            binding.shareSettings.setVisibility(View.VISIBLE);

            EditText expField = findViewById(R.id.share_expiration);
            EditText pwField = findViewById(R.id.share_password);

            String defaultExp = prefs.getString("link_expiration", "");
            String defaultPw = prefs.getString("share_password", "");

            expField.setText(defaultExp);
            pwField.setText(defaultPw);
        }

        String header = "Hi, Welcome to PartyUP!";
        String body = String.join("\n", "You are uploading to:", base_url, getTextBody(), getFileBody(), (!autosend ? "Press the button to upload!" : "Starting..."));
        String footer = String.join("\n", String.format(Locale.getDefault(), "Total files: %d", files.length), String.format("Total size: %s", Progress.formatBytes(progress.total)));

        String fullBody = String.join("\n\n", header, body, footer);
        showMsg(fullBody);
    }

    public void onSuccess() {
        String msg = "✅👍\n\nCompleted successfully";
        final String share_url = createShareUrl();
        String footer = String.format(Locale.getDefault(), "Total file uploaded: %d (%s)", files.length, Progress.formatBytes(progress.total));

        showMsg(String.join("\n", msg, share_url, footer));
        binding.upperInfo.setGravity(Gravity.CENTER);

        if (share_url.isEmpty()) {
            Toast.makeText(getApplicationContext(), "Share Failed!", Toast.LENGTH_SHORT).show();
            return;
        }

        String act = prefs.getString("on_up_ok", "menu");
        if (!act.equals("menu")) {
            if (act.equals("copy")) copyLink(share_url);
            else if (act.equals("share")) shareLink(share_url);
            else Toast.makeText(getApplicationContext(), "Upload OK", Toast.LENGTH_SHORT).show();

            finishAndRemoveTask();
            return;
        }

        binding.progbar.setVisibility(View.GONE);
        binding.shareSettings.setVisibility(View.GONE);
        binding.successbuttons.setVisibility(View.VISIBLE);

        binding.btnExit.setOnClickListener(v -> finishAndRemoveTask());

        binding.btnCopyLink.setOnClickListener(v -> copyLink(share_url));
        binding.btnQrCode.setOnClickListener(v -> shareLink(share_url));
        binding.btnShareLink.setOnClickListener(view -> showQr(share_url));
    }

    private void copyLink(@NonNull String share_url) {
        StringBuilder sb = new StringBuilder();

        if (!share_url.isEmpty()) sb.append(share_url);

        for (CustomFile file : files) {
            String bestUrl = file.getBestUrl();
            if (file.getBestUrl().isEmpty()) continue;
            sb.append(bestUrl);
        }

        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText("copyparty upload", sb);
        cb.setPrimaryClip(cd);
        Toast.makeText(getApplicationContext(), "Upload OK -- Link copied", Toast.LENGTH_SHORT).show();
    }

    private void shareLink(String share_url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        send.putExtra(Intent.EXTRA_SUBJECT, "Uploaded file");
        send.putExtra(Intent.EXTRA_TEXT, share_url);

        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setData(Uri.parse(share_url));

        Intent i = Intent.createChooser(send, "Share file link");
        i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{view});
        startActivity(i);
    }

    private void showQr(String share_url) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            int size = 256;
            BitMatrix bitMatrix = qrCodeWriter.encode(share_url, BarcodeFormat.QR_CODE, size, size);

            Bitmap shareQr = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    shareQr.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            AlertDialog.Builder ImageDialog = new AlertDialog.Builder(this);
            ImageView shownImage = new ImageView(this);
            shownImage.setImageBitmap(shareQr);
            shownImage.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shownImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            shownImage.setAdjustViewBounds(true);

            ImageDialog.setView(shownImage);

            ImageDialog.show();
        } catch (WriterException e) {
            throw new RuntimeException(e);
        }
    }

    public void needStorage(String msg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && msg.contains("EACCES")) {
            showMsg(msg + "\n\nThe app you shared from uses deprecated file APIs.");
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            request_storage();
            return;
        }

        String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)
            return; // already have it, so that\'s not why it failed

        if (!shouldShowRequestPermissionRationale(perm)) {
            request_storage();
            return;
        }

        AlertDialog.Builder ab = new AlertDialog.Builder(findViewById(R.id.upper_info).getContext());
        ab.setMessage("PartyUP! needs additional permissions to read that file, because the app you" + "shared it from is using old APIs.").setPositiveButton("OK", (dialog, which) -> request_storage()).setNegativeButton("Cancel", (dialog, which) -> {

        }).show();
    }

    @NonNull
    private CustomFile[] getWantedFiles() {
        ArrayList<CustomFile> needed = new ArrayList<>();

        for (CustomFile file : files) {
            if (file.isSharable()) needed.add(file);
        }

        return needed.toArray(new CustomFile[0]);
    }

    public String createShareUrl() {
        try {
            CustomFile[] wantedFiles = getWantedFiles();

            if (wantedFiles.length == 0) {
                return files[0].share_url;
            } else if ((files.length - wantedFiles.length) < 2) {
                for (CustomFile file : wantedFiles) {
                    String bestUrl = file.getBestUrl();
                    if (bestUrl.isEmpty()) continue;
                    return bestUrl;
                }
                throw new Exception("Man don't know!");
            }

            String key = generateRandomKey();
            Uri shareApiUri = Uri.parse(uploader.getServerUrl());

            String expiration = getExpiration();
            EditText pwField = findViewById(R.id.share_password);
            String sharePw = pwField.getText().toString();

            StringBuilder sharedFilesPaths = new StringBuilder();

            for (int i = 0; i < wantedFiles.length; i++) {
                CustomFile cf = wantedFiles[i];
                String filePath = java.net.URLDecoder.decode(new URL(cf.full_url).getPath(), "UTF-8");

                sharedFilesPaths.append("\"").append(filePath).append("\"");
                if (i < files.length - 1) {
                    sharedFilesPaths.append(",");
                }
            }

            if (sharedFilesPaths.length() == 0) {
                throw new Exception("No media/file type found in shared items");
            }

            return getSharableUrl(key, sharedFilesPaths, sharePw, expiration, shareApiUri);
        } catch (Exception ex) {
            Log.w(TAG, "Share creation error: " + ex);
        }

        return "";
    }

    @NonNull
    private String getSharableUrl(String key, StringBuilder sharedFilesPaths, String sharePw, String expiration, @NonNull Uri shareApiUri) throws Exception {

        String jsonBody = String.format("{\"k\":\"%s\",\"vp\":[%s],\"pw\":\"%s\",\"exp\":\"%s\",\"perms\":[\"read\"]}", key, sharedFilesPaths, sharePw, expiration);

        HttpURLConnection conn = (HttpURLConnection) (new URL(shareApiUri.getScheme() + "://" + shareApiUri.getAuthority() + "/")).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        if (password != null) conn.setRequestProperty("PW", password);

        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        conn.connect();

        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();

        int rc = conn.getResponseCode();
        if (rc >= 300) {
            Log.w(TAG, "Share creation failed: " + rc);
            conn.disconnect();
            throw new RuntimeException("Unable to get share url!");
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String response = br.readLine();
        conn.disconnect();

        if (response != null && response.startsWith("created share: "))
            return response.substring(15);

        return "";
    }

    public void request_storage() {
        String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{perm}, 573);
    }

    @Override
    public void onRequestPermissionsResult(int permRequestCode, @NonNull String[] perms, @NonNull int[] grantRes) {
        super.onRequestPermissionsResult(permRequestCode, perms, grantRes);
        String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        if (permRequestCode != 573) return;

        for (int a = 0; a < grantRes.length; a++) {
            if (!perms[a].equals(perm)) continue;

            if (grantRes[a] != PackageManager.PERMISSION_GRANTED) return;
        }
    }

    private String getExpiration() {
        EditText expField = findViewById(R.id.share_expiration);
        String expValue = expField.getText().toString();
        int[] parsed = parseExpiration(expValue);
        String expiration = "";
        if (parsed[1] >= 0) {
            int minutes = parsed[0];
            if (parsed[1] == 1) minutes *= 60;
            else if (parsed[1] == 2) minutes *= 1440;
            expiration = String.valueOf(minutes);
        }

        return expiration;
    }

    @NonNull
    @Contract("null -> new")
    private int[] parseExpiration(String value) {
        if (value == null || value.trim().isEmpty()) return new int[]{0, -1};

        value = value.trim().toLowerCase();
        if (!value.matches("^\\d+[mhd]?$")) return new int[]{0, -1};

        char unit = value.charAt(value.length() - 1);
        int num;
        int unitType;

        if (Character.isDigit(unit)) {
            num = Integer.parseInt(value);
            unitType = 0; // minutes
        } else {
            num = Integer.parseInt(value.substring(0, value.length() - 1));
            switch (unit) {
                case 'h':
                    unitType = 1;
                    break;
                case 'd':
                    unitType = 2;
                    break;
                default:
                    unitType = 0;
                    break;
            }
        }
        return new int[]{num, unitType};
    }

    @NonNull
    private String generateRandomKey() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 12; i++)
            key.append(chars.charAt(random.nextInt(chars.length())));
        return key.toString();
    }

}
