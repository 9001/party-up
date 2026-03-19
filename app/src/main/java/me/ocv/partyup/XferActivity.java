package me.ocv.partyup;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.PrefsKey;
import me.ocv.partyup.utils.Analyzer;
import me.ocv.partyup.utils.NumberUtils;
import me.ocv.partyup.utils.PermissionUtils;
import me.ocv.partyup.utils.Uploader;
import me.ocv.partyup.utils.UploaderService;

public class XferActivity extends AppCompatActivity {
    private static final String TAG = "TransferActivity";

    private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault());
    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.DEFAULT, Locale.getDefault());

    private final Analyzer analyzer = new Analyzer();
    private final Uploader uploader = new Uploader();

    private ActivityXferBinding binding;
    private SharedPreferences prefs;
    private UploaderService mService;

    private String serverUrl;
    private String serverPas;
    private CustomFile[] filesToUpload;

    private boolean isUploaded;
    private boolean isUploading;
    private boolean autoSend;
    private boolean beSilent;

    private long startedAt = System.currentTimeMillis();
    private Date startedOn = new Date(startedAt);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UploaderService.UploadBinder binder = (UploaderService.UploadBinder) iBinder;
            mService = binder.getService();
            if (filesToUpload != null && mService.isQueueEmpty()) {
                mService.enqueueFiles(filesToUpload);
            }

            if (!beSilent) {
                mService.addProgressListener(p -> runOnUiThread(() -> handleProgress(p)));
                mService.addErrorListener(e -> runOnUiThread(() -> handleError(e)));
            }

            doUpload();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        }
    };

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            doLeave();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mService != null) {
            mService.refreshPrefs();
        }
        loadPrefs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UploaderService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(intent);
        } else {
            getApplicationContext().startService(intent);
        }

        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!PermissionUtils.hasAllPermissions(this)) PermissionUtils.requestAllPermissions(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (connection != null) unbindService(connection);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityXferBinding.inflate(getLayoutInflater());
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        doInit();
        loadPrefs();
        doUpload();

        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        doUI();
    }

    private void doInit() {
        isUploaded = false;
        filesToUpload = analyzer.analyze(this, getIntent(), (e) -> Log.e(TAG, e));

        // Grant URI permissions for all files
        for (CustomFile cf : filesToUpload) {
            if (cf.handle != null) {
                grantUriPermission(getPackageName(), cf.handle, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private void loadPrefs() {
        boolean darkMode = prefs.getBoolean(PrefsKey.DARK_MODE, false);
        AppCompatDelegate.setDefaultNightMode(darkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        autoSend = prefs.getBoolean(PrefsKey.AUTOSEND, false);
        serverUrl = prefs.getString(PrefsKey.SERVER_URL, "");
        serverPas = prefs.getString(PrefsKey.SERVER_PASSWORD, "");
        beSilent = prefs.getBoolean(PrefsKey.BE_SILENT, false);
        showMessage(getString(R.string.xfer_loading));
    }

    private void doUpload() {
        if (!isUploaded && !isUploading) {
            if (beSilent && mService != null) {
                if (prefs.getBoolean(PrefsKey.ANNOY_ME, false)) {
                    long[] lastSeen = {0};
                    mService.addProgressListener(p -> mService.getHandler().post(() -> {
                        long now = System.currentTimeMillis();

                        if (now - lastSeen[0] > 1000) {
                            lastSeen[0] = now;
                            Toast.makeText(mService, p.smallProgress(), Toast.LENGTH_SHORT).show();
                        }
                    }));
                    mService.addErrorListener(e -> mService.getHandler().post(() -> Toast.makeText(getApplicationContext(), String.format("Error: %s", e.getLocalizedMessage()), Toast.LENGTH_LONG).show()));
                }

                mService.startUploading();
                doLeave();
            } else if (autoSend && mService != null) {
                mService.startUploading();
                startedAt = System.currentTimeMillis();
                startedOn = new Date(startedAt);
                isUploading = true;
            } else {
                binding.getRoot().post(this::showShareSettings);
            }
        }
    }

    private void doUI() {
        // Addition binding configuration
        binding.progressBar.setMax(100);
        binding.upperInfo.setGravity(Gravity.CENTER);

        binding.actionConfig.setOnClickListener(v -> XferActivity.this.startActivity(new Intent(this, SettingsActivity.class)));

        binding.actionSend.setOnClickListener(v -> {
            binding.actionSend.setVisibility(View.GONE);
            binding.actionConfig.setVisibility(View.GONE);

            mService.startUploading();
        });

        binding.btnQrCode.setEnabled(false);
        binding.btnShareLink.setEnabled(false);
        binding.btnCopyLink.setEnabled(false);
        binding.btnExit.setOnClickListener(v -> finishAndRemoveTask());

        binding.successButtons.setVisibility(View.GONE);
    }

    private void doLeave() {
        this.finishAndRemoveTask();
    }

    private void displayText(String message, @NonNull MESSAGE_KIND kind) {
        @StyleRes int resId;

        switch (kind) {
            case NORMAL:
                resId = R.style.TextAppearance_PartyUP_Normal;
                break;
            case SUCCESS:
                resId = R.style.TextAppearance_PartyUP_Success;
                break;
            case ERROR:
                resId = R.style.TextAppearance_PartyUP_Error;
                break;
            default:
                return;
        }

        binding.getRoot().post(() -> {
            binding.upperInfo.setTextAppearance(XferActivity.this, resId);
            binding.upperInfo.setText(message);
        });
    }

    private void showError(Throwable error) {
        displayText(error != null ? error.getLocalizedMessage() : "Unexpected Error!", MESSAGE_KIND.ERROR);
    }

    private void showSuccess(String message) {
        displayText(message, MESSAGE_KIND.SUCCESS);
    }

    private void showMessage(String message) {
        displayText(message, MESSAGE_KIND.NORMAL);
    }

    private void handleProgress(UploaderService.UploadProgress progress) {
        if (mService.isDone() || progress.doneBytes >= progress.totalBytes) {
            handleSuccess();
        } else {
            showMessage(consumeProgress(progress));
            binding.progressBar.setProgress((int) Math.floor(NumberUtils.calcPercentage(progress.doneBytes, progress.totalBytes)));
        }
    }

    private void handleError(Throwable error) {
        showError(error);

        binding.getRoot().post(() -> {
            binding.actionSend.setEnabled(true);
            binding.actionConfig.setEnabled(true);
            binding.actionSend.setVisibility(View.VISIBLE);
            binding.actionConfig.setVisibility(View.VISIBLE);

            binding.successButtons.setVisibility(View.GONE);
        });

        isUploading = false;
        isUploaded = false;
        Log.e(TAG, "Upload failed due to service error", mService.getLastError());
    }

    private void handleSuccess() {
        showSuccessScreen();

        binding.getRoot().post(() -> {
            binding.actionConfig.setVisibility(View.VISIBLE);
            binding.actionSend.setVisibility(View.GONE);

            binding.actionConfig.setEnabled(true);
            binding.successButtons.setVisibility(View.VISIBLE);
        });

        isUploading = false;
        isUploaded = true;
        postUploadSuccess();
    }

    @NonNull
    private String consumeProgress(@NonNull UploaderService.UploadProgress ps) {
        String header = String.format("Sending to: %s...", serverUrl);

        List<String> fileInfo = new ArrayList<>();
        fileInfo.add(String.format(Locale.getDefault(), "File: %d of %d", ps.currentIndex + 1, ps.totalFiles));
        fileInfo.add(String.format(Locale.getDefault(), "Title: %s", ps.currentFile.name));
        fileInfo.add(String.format(Locale.getDefault(), "Size: %s (%d)", NumberUtils.formatBytes(ps.currentFile.size != null ? ps.currentFile.size : 0), ps.currentFile.size != null ? ps.currentFile.size : 0));
        fileInfo.add(String.format(Locale.getDefault(), "ContentType: %s", ps.currentFile.mime));

        double speed;
        if (startedAt == 0 || ps.doneBytes == 0) {
            speed = 0.0f;
        } else {
            double seconds = (System.currentTimeMillis() - startedAt) / 1000.0f;
            speed = seconds > 0 ? ps.doneBytes / seconds : 0.0f;
        }

        double eta;
        if (speed <= 0) {
            eta = -1;
        } else {
            eta = (double) Math.max(0, ps.totalBytes - ps.doneBytes) / speed;
        }

        List<String> progressInfo = new ArrayList<>();
        progressInfo.add(String.format(Locale.getDefault(), "Progress: %d/%d (%.1f%%)", ps.doneBytes, ps.totalBytes, NumberUtils.calcPercentage(ps.doneBytes, ps.totalBytes)));
        progressInfo.add(String.format(Locale.getDefault(), "Speed: %.2f KiB/s", speed / 1024.0f));
        progressInfo.add(String.format(Locale.getDefault(), "ETA: %.2fs", eta));
        progressInfo.add(String.format(Locale.getDefault(), "Started At: %s on %s", dateFormat.format(startedOn), timeFormat.format(startedOn)));

        return String.join("\n\n", header, "---------------", String.join("\n", fileInfo), "---------------", String.join("\n", progressInfo));
    }

    @NonNull
    private String getTextBody() {
        List<String> messages = new ArrayList<>();

        int counter = 1;
        for (CustomFile cf : filesToUpload) {
            if (counter > 10) break;

            if (!cf.mime.equals("text/plain") || cf.content == null) continue;

            messages.add(String.format(Locale.getDefault(), "%d. %s", counter, cf.content.trim()));
            counter++;
        }

        String header = String.format("Post%s the following link%s%s", autoSend ? "ing" : "", counter > 1 ? "s" : "", autoSend ? ":" : "?");
        return counter == 1 ? "" : String.join("\n\n", header, String.join("\n", messages), counter > 10 ? "[...]" : "");
    }

    @NonNull
    private String getFileBody() {
        List<String> filenames = new ArrayList<>();
        int counter = 1;
        for (CustomFile file : filesToUpload) {
            if (counter > 10) break;

            if (file.mime.equals("text/plain") || file.size == null) continue;

            filenames.add(String.format(Locale.getDefault(), "%d. %s [%s]", counter, file.name, NumberUtils.formatBytes(file.size != null ? file.size : 0)));
            counter++;
        }

        String header = String.format("Upload%s the following file%s%s", autoSend ? "ing" : "", counter > 1 ? "s" : "", autoSend ? ":" : "?");
        return counter == 1 ? "" : String.join("\n\n", header, String.join("\n", filenames), counter > 10 ? "[...]" : "");
    }

    public void showShareSettings() {
        if (prefs.getBoolean(PrefsKey.USE_SHARE_URL, false)) {
            binding.shareSettings.setVisibility(View.VISIBLE);

            EditText expField = binding.shareExpiration;
            EditText pwField = binding.sharePassword;

            String defaultExp = prefs.getString(PrefsKey.LINK_EXPIRATION, "");
            String defaultPw = prefs.getString(PrefsKey.SHARE_PASSWORD, "");

            expField.setText(defaultExp);
            pwField.setText(defaultPw);
        }

        String header = getString(R.string.greetings);
        String body = String.join("\n", "You are uploading to:", serverUrl, getTextBody(), getFileBody(), (!autoSend ? "Press the button to upload!" : "Starting..."));
        String footer = String.join("\n", String.format(Locale.getDefault(), "Total files: %d", filesToUpload.length), String.format("Total size: %s", NumberUtils.formatBytes(NumberUtils.sumSize(filesToUpload))));

        String fullBody = String.join("\n\n", header, body, footer);
        showMessage(fullBody);
    }

    private void showSuccessScreen() {
        String msg = getString(R.string.complete_text);
        String footer = String.format(Locale.getDefault(), "Total file uploaded: %d (%s)", filesToUpload.length, NumberUtils.formatBytes(NumberUtils.sumSize(filesToUpload)));
        showSuccess(String.join("\n", msg, footer));
        binding.upperInfo.setGravity(Gravity.CENTER);
    }

    private void postUploadSuccess() {
        String share_url = createShareUrl();
        if (share_url == null || share_url.isEmpty()) {
            Toast.makeText(getApplicationContext(), R.string.share_failed_text, Toast.LENGTH_SHORT).show();
            return;
        }

        String act = prefs.getString(PrefsKey.ON_UP_OK, "menu");

        if (!act.equals("menu")) {
            if (act.equals("copy")) copyLink(share_url);
            else if (act.equals("share")) shareLink(share_url);
            else Toast.makeText(this, R.string.upload_ok, Toast.LENGTH_SHORT).show();

            doLeave();
        }

        binding.progressBar.setVisibility(View.GONE);
        binding.shareSettings.setVisibility(View.GONE);
        binding.successButtons.setVisibility(View.VISIBLE);

        binding.actionSend.setVisibility(View.GONE);
        binding.actionConfig.setVisibility(View.VISIBLE);

        binding.actionConfig.setEnabled(true);

        binding.btnCopyLink.setEnabled(true);
        binding.btnShareLink.setEnabled(true);
        binding.btnQrCode.setEnabled(true);

        binding.btnCopyLink.setOnClickListener(v -> copyLink(share_url));
        binding.btnShareLink.setOnClickListener(v -> shareLink(share_url));
        binding.btnQrCode.setOnClickListener(view -> showQr(share_url));
    }

    private void copyLink(@NonNull String share_url) {
        StringBuilder sb = new StringBuilder();

        if (!share_url.isEmpty()) sb.append(share_url);

        for (CustomFile file : filesToUpload) {
            String bestUrl = file.getBestUrl();
            if (file.getBestUrl().isEmpty()) continue;
            sb.append(bestUrl);
        }

        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText(getString(R.string.clipboard_label), sb);
        cb.setPrimaryClip(cd);
        Toast.makeText(getApplicationContext(), R.string.copy_success, Toast.LENGTH_SHORT).show();
    }

    private void shareLink(String share_url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_extra_text));
        send.putExtra(Intent.EXTRA_TEXT, share_url);

        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setData(Uri.parse(share_url));

        Intent i = Intent.createChooser(send, getString(R.string.share_link_title));
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
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Unable to show QR", e);
        }
    }

    @NonNull
    private CustomFile[] getWantedFiles() {
        ArrayList<CustomFile> needed = new ArrayList<>();

        for (CustomFile file : filesToUpload) {
            if (file.isSharable()) needed.add(file);
        }

        return needed.toArray(new CustomFile[0]);
    }

    @SuppressWarnings("CharsetObjectCanBeUsed")
    public String createShareUrl() {
        try {
            CustomFile[] wantedFiles = getWantedFiles();

            if (wantedFiles.length == 0) {
                return filesToUpload[0].share_url;
            } else if ((filesToUpload.length - wantedFiles.length) < 2) {
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
            EditText pwField = binding.sharePassword;
            String sharePw = pwField.getText().toString();

            StringBuilder sharedFilesPaths = new StringBuilder();

            for (int i = 0; i < wantedFiles.length; i++) {
                CustomFile cf = wantedFiles[i];
                String filePath;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    filePath = URLDecoder.decode(new URL(cf.full_url).getPath(), StandardCharsets.UTF_8);
                } else {
                    filePath = URLDecoder.decode(new URL(cf.full_url).getPath(), "UTF-8");
                }

                sharedFilesPaths.append("\"").append(filePath).append("\"");
                if (i < filesToUpload.length - 1) {
                    sharedFilesPaths.append(",");
                }
            }

            if (sharedFilesPaths.length() == 0) {
                throw new Exception("No media/file type found in shared items");
            }

            AtomicReference<String> shareUrl = new AtomicReference<>();

            new Thread(() -> shareUrl.set(getSharableUrl(key, sharedFilesPaths, sharePw, expiration, shareApiUri))).join();
            return shareUrl.get();
        } catch (Exception ex) {
            Toast.makeText(this, String.format("X Share: %s", ex.getLocalizedMessage()), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Share creation error: " + ex);
        }

        return "";
    }

    @NonNull
    private String getSharableUrl(String key, StringBuilder sharedFilesPaths, String sharePw, String expiration, @NonNull Uri shareApiUri) {
        try {
            String jsonBody = String.format("{\"k\":\"%s\",\"vp\":[%s],\"pw\":\"%s\",\"exp\":\"%s\",\"perms\":[\"read\"]}", key, sharedFilesPaths, sharePw, expiration);

            HttpURLConnection conn = (HttpURLConnection) (new URL(shareApiUri.getScheme() + "://" + shareApiUri.getAuthority() + "/")).openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain");
            if (serverPas != null) conn.setRequestProperty("PW", serverPas);

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
        } catch (Exception e) {
            Log.e(TAG, "Unable to get sharable url from server", e);
            throw new RuntimeException(e);
        }
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
        EditText expField = binding.shareExpiration;
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

    private enum MESSAGE_KIND {
        SUCCESS, ERROR, NORMAL
    }
}
