package me.ocv.partyup;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.jetbrains.annotations.Contract;

import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.PrefsKey;
import me.ocv.partyup.utils.Analyzer;
import me.ocv.partyup.utils.NumberUtils;
import me.ocv.partyup.utils.PermissionUtils;
import me.ocv.partyup.utils.UploaderService;

public class XferActivity extends AppCompatActivity {
    private static final String TAG = "TransferActivity";

    private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault());
    private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.DEFAULT, Locale.getDefault());

    private final Analyzer analyzer = new Analyzer();

    private ActivityXferBinding binding;
    private SharedPreferences prefs;
    private UploaderService mService;

    private String serverUrl;
    private String shareUrl;
    private CustomFile[] filesToUpload;

    private boolean isUploaded;
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
                mService.addSuccessListener(s -> {
                    XferActivity.this.shareUrl = s.shareUrl;
                    postUploadSuccess();
                });
            }

            doUpload();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        }
    };

    public static String getExpiration(String expValue) {
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
    private static int[] parseExpiration(String value) {
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

    public static String generateRandomKey() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 12; i++)
            key.append(chars.charAt(random.nextInt(chars.length())));
        return key.toString();
    }

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
        autoSend = prefs.getBoolean(PrefsKey.AUTOSEND, false);
        serverUrl = prefs.getString(PrefsKey.SERVER_URL, "");
        beSilent = prefs.getBoolean(PrefsKey.BE_SILENT, false);

        if (filesToUpload == null || filesToUpload.length == 0) {
            showMessage(getString(R.string.xfer_loading));
        }
    }

    private void doUpload() {
        if (mService == null) {
            binding.getRoot().post(this::showShareSettings);
            return;
        }

        if (mService.isUploading()) {
            showMessage("Service is currently uploading...\nRetry again!");
            return;
        }

        if (isUploaded) {
//            showMessage("File Already uploaded!");
            return;
        }

        if (beSilent) {
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
        } else if (autoSend) {
            mService.startUploading();
            startedAt = System.currentTimeMillis();
            startedOn = new Date(startedAt);
        }

        binding.getRoot().post(this::showShareSettings);
    }

    private void doUI() {
        // Addition binding configuration
        binding.progressBar.setMax(100);
        binding.upperInfo.setGravity(Gravity.CENTER);

        binding.actionConfig.setOnClickListener(v -> XferActivity.this.startActivity(new Intent(this, SettingsActivity.class)));

        binding.actionSend.setOnClickListener(v -> {
            if (!mService.isUploading()) {
                binding.actionSend.setVisibility(View.GONE);
                binding.actionConfig.setVisibility(View.GONE);

                mService.startUploading();
            }
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
        if (shareUrl == null) return;
        // This will never happen
//        if (share_url == null || share_url.isEmpty()) {
//            Toast.makeText(getApplicationContext(), R.string.share_failed_text, Toast.LENGTH_SHORT).show();
//            return;
//        }

        Intent baseBeforeIntent = new Intent(this, XferAfterActivity.class);
        baseBeforeIntent.putExtra(XferAfterActivity.SHARE_URL_KEY, shareUrl);

        PendingIntent copyIntent = PendingIntent.getActivity(this, 1, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_COPY), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent shareIntent = PendingIntent.getActivity(this, 2, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHARE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent showIntent = PendingIntent.getActivity(this, 3, new Intent(baseBeforeIntent).putExtra(XferAfterActivity.ACTION_KEY, XferAfterActivity.ActionType.ACTION_SHOW_QR), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String act = prefs.getString(PrefsKey.ON_UP_OK, "menu");

        try {
            if (!act.equals("menu")) {
                if (act.equals("copy")) copyIntent.send();
                else if (act.equals("share")) shareIntent.send();
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

            binding.btnCopyLink.setOnClickListener(v -> {
                try {
                    copyIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Button Copy Intent Cancelled", e);
                }
            });
            binding.btnShareLink.setOnClickListener(v -> {
                try {
                    shareIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Button Share Intent Cancelled", e);
                }
            });
            binding.btnQrCode.setOnClickListener(view -> {
                try {
                    showIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Button QR Code Intent Cancelled", e);
                }
            });
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Main Intent Cancelled", e);
        }
    }

    private enum MESSAGE_KIND {
        SUCCESS, ERROR, NORMAL
    }
}
