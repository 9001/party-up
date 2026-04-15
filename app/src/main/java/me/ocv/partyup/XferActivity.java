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

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.jetbrains.annotations.Contract;

import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.objects.BaseUploadProgress;
import me.ocv.partyup.objects.CustomFile;
import me.ocv.partyup.objects.PrefsKey;
import me.ocv.partyup.service.UploaderService;
import me.ocv.partyup.utils.Analyzer;
import me.ocv.partyup.utils.NumberUtils;
import me.ocv.partyup.utils.PermissionUtils;
import me.ocv.partyup.utils.ToastUtils;

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
    private Date startedOn = null;

    private final StringBuilder serverResponse = new StringBuilder();
    private final Map<CustomFile, Section> fileSections = new LinkedHashMap<>();
    private final AtomicInteger uploadCount = new AtomicInteger(0);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UploaderService.UploadBinder binder = (UploaderService.UploadBinder) iBinder;
            mService = binder.getService();
            doUpload();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        }
    };

    private final class Section {
        public CustomFile file;
        public String details;

        public Section(@NonNull CustomFile file) {
            this.file = file;
            this.details = file.name + ": Pending...";
        }

        public Section progress(BaseUploadProgress progress) {
            this.details = buildDetails(progress);
            return this;
        }

        public Section error(Throwable error) {
            this.details = buildDetails(error);
            return this;
        }

        public Section complete(String resp) {
            this.details = buildDetails(resp);
            return this;
        }

        @NonNull
        public String buildDetails(BaseUploadProgress progress) {
            String header = String.format("Sending to: %s...", serverUrl);
            int currentIndex = getFileIndex(file);

            List<String> fileInfo = new ArrayList<>();
            fileInfo.add(String.format(Locale.getDefault(), "File: %d of %d", currentIndex + 1, filesToUpload.length));
            fileInfo.add(String.format(Locale.getDefault(), "Title: %s", file.name));
            fileInfo.add(String.format(Locale.getDefault(), "Size: %s (%d)", NumberUtils.formatBytes(file.size != null ? file.size : 0), file.size != null ? file.size : 0));
            fileInfo.add(String.format(Locale.getDefault(), "ContentType: %s", file.mime));

            List<String> progressInfo = new ArrayList<>();
            if (progress != null) {
                double speed;
                if (startedAt == 0 || progress.done == 0) {
                    speed = 0.0f;
                } else {
                    double seconds = (System.currentTimeMillis() - startedAt) / 1000.0f;
                    speed = seconds > 0 ? progress.done / seconds : 0.0f;
                }

                double eta;
                if (speed <= 0) {
                    eta = -1;
                } else {
                    eta = (double) Math.max(0, progress.total - progress.done) / speed;
                }

                progressInfo.add(String.format(Locale.getDefault(), "Progress: %d/%d (%.1f%%)", progress.done, progress.total, NumberUtils.calcPercentage(progress.done, progress.total)));
                progressInfo.add(String.format(Locale.getDefault(), "Speed: %.2f KiB/s", speed / 1024.0f));
                progressInfo.add(String.format(Locale.getDefault(), "ETA: %.2fs", eta));
                progressInfo.add(String.format(Locale.getDefault(), "Started At: %s on %s", dateFormat.format(startedOn), timeFormat.format(startedOn)));
            } else {
                progressInfo.add("Something doesn't looks right!");
            }

            return String.join("\n\n", header, "---------------", String.join("\n", fileInfo), "---------------", String.join("\n", progressInfo));
        }

        @NonNull
        public String buildDetails(Throwable error) {
            return error != null ? String.format(Locale.getDefault(), "Error happened: %s", error.getLocalizedMessage()) : "Man fuck off!";
        }

        @NonNull
        public String buildDetails(String resp) {
            return String.format(Locale.getDefault(), "Upload done!\nResponse: %s", resp == null || resp.isEmpty() ? "(empty)" : resp);
        }

        @NonNull
        @Override
        public String toString() {
            return this.details;
        }
    }

    private void updateSectionProgress(@NonNull CustomFile file, @NonNull BaseUploadProgress progress) {
        Section section = fileSections.get(file);
        if (section == null) return;

        fileSections.put(file, section.progress(progress));
    }

    private void updateSectionComplete(@NonNull CustomFile file, @NonNull String resp) {
        Section section = fileSections.get(file);
        if (section == null) return;

        fileSections.put(file, section.complete(resp));
    }

    private void updateSectionError(@NonNull CustomFile file, @NonNull Throwable error) {
        Section section = fileSections.get(file);
        if (section == null) return;

        fileSections.put(file, section.error(error));
    }

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
            finishAndRemoveTask();
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

        setContentView(binding.getRoot());
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        doUI();
    }

    private void doInit() {
        isUploaded = false;
        filesToUpload = analyzer.analyze(this, getIntent(), (e) -> Log.e(TAG, e));

        // Grant URI permissions for all files
        for (CustomFile cf : filesToUpload) {
            fileSections.put(cf, new Section(cf));
            if (cf.handle != null) {
                grantUriPermission(getPackageName(), cf.handle, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private void loadPrefs() {
        autoSend = prefs.getBoolean(PrefsKey.AUTOSEND, false);
        serverUrl = prefs.getString(PrefsKey.SERVER_URL, "");
        beSilent = prefs.getBoolean(PrefsKey.BE_SILENT, false);

        if (serverUrl.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            ToastUtils.show(this, "Please configure server url!");
        }
    }

    private void attachListenersAndEnqueue() {
        if (startedOn != null) {
            ToastUtils.show(this, "Nuh-uh!");
            return;
        }

        for (CustomFile file : filesToUpload) {
            int jobId = mService.enqueueFile(file);
            mService.addProgressListener(jobId, this, prog -> handleProgress(prog, file));
            mService.addErrorListener(jobId, this, err -> handleError(err, file));
            mService.addSuccessListener(jobId, this, resp -> {
                handleSuccess(resp, file);
                int done = uploadCount.incrementAndGet();
                isUploaded = done == filesToUpload.length;
            });
        }

        startedAt = System.currentTimeMillis();
        startedOn = new Date(startedAt);
        isUploaded = false;
    }

    private void doUpload() {
        if (mService == null) {
            return;
        }

        if (beSilent) {
            boolean annoying = prefs.getBoolean(PrefsKey.ANNOY_ME, false);
            Map<Integer, Long[]> lastSeen = new HashMap<>();
            for (CustomFile file : filesToUpload) {
                int jobId = mService.enqueueFile(file);

                if (annoying) {
                    lastSeen.put(jobId, new Long[]{0L});
                    mService.addProgressListener(jobId, null /* temp */, progress -> mService.getHandler().post(() -> {
                        long now = System.currentTimeMillis();

                        if (now - Objects.requireNonNull(lastSeen.get(jobId))[0] > 1000) {
                            lastSeen.put(jobId, new Long[]{now});
                            ToastUtils.show(mService, String.format("Progress: %s/%s", NumberUtils.formatBytes(progress.done), NumberUtils.formatBytes(progress.total)));
                        }
                    }));
                    mService.addErrorListener(jobId, null /* temp */, e -> mService.getHandler().post(() -> ToastUtils.show(mService, String.format("Job %s failed!", jobId))));
                }
            }
            finishAndRemoveTask();
            return;
        }

        if (isUploaded) {
            showMessage("File already uploaded!");
            return;
        }

        if (autoSend) {
            attachListenersAndEnqueue();
        } else {
            showShareSettings();
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

                attachListenersAndEnqueue();
            }
        });

        binding.btnQrCode.setEnabled(false);
        binding.btnShareLink.setEnabled(false);
        binding.btnCopyLink.setEnabled(false);
        binding.btnExit.setOnClickListener(v -> finishAndRemoveTask());

        binding.successButtons.setVisibility(View.GONE);
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

    private void showSuccess(String message) {
        displayText(message, MESSAGE_KIND.SUCCESS);
    }

    private void showMessage(String message) {
        displayText(message, MESSAGE_KIND.NORMAL);
    }

    private void refreshSections(MESSAGE_KIND kind) {
        List<String> sections = new ArrayList<>();
        for (Section section : fileSections.values()) {
            sections.add(section.details);
        }

        String fullBody = String.join("\n" + "=".repeat(20) + "\n", sections);
        displayText(fullBody, kind);
    }

    private void handleProgress(BaseUploadProgress progress, CustomFile file) {
        updateSectionProgress(file, progress);
        binding.progressBar.setProgress((int) Math.floor(NumberUtils.calcPercentage(progress.done, progress.total)));
        refreshSections(MESSAGE_KIND.NORMAL);
    }

    private void handleError(Throwable error, CustomFile file) {
        updateSectionError(file, error);

        binding.getRoot().post(() -> {
            binding.actionSend.setEnabled(true);
            binding.actionConfig.setEnabled(true);
            binding.actionSend.setVisibility(View.VISIBLE);
            binding.actionConfig.setVisibility(View.VISIBLE);

            binding.successButtons.setVisibility(View.GONE);
        });

        isUploaded = false;
        Log.e(TAG, "Upload failed due to service error", error);
        refreshSections(MESSAGE_KIND.ERROR);
    }

    private void handleSuccess(String serverResponse, CustomFile file) {
        this.serverResponse.append(serverResponse);
        updateSectionComplete(file, serverResponse);
        binding.getRoot().post(() -> {
            binding.actionConfig.setVisibility(View.VISIBLE);
            binding.actionSend.setVisibility(View.GONE);

            binding.actionConfig.setEnabled(true);
            binding.successButtons.setVisibility(View.VISIBLE);
        });

        refreshSections(MESSAGE_KIND.SUCCESS);
        showSuccessScreen();
    }

    private int getFileIndex(CustomFile file) {
        for (int i = 0; i < filesToUpload.length; ++i) {
            if (Objects.equals(filesToUpload[i], file)) return i;
        }

        return -1;
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
        if (!isUploaded) return;

        String msg = getString(R.string.complete_text);
        String footer = String.format(Locale.getDefault(), "Total file uploaded: %d (%s)", filesToUpload.length, NumberUtils.formatBytes(NumberUtils.sumSize(filesToUpload)));
        String resp = String.format(Locale.getDefault(), "Last server response: %s", serverResponse.toString());
        showSuccess(String.join("\n", msg, footer, resp));

        postUploadSuccess();
    }

    private void postUploadSuccess() {
        try {
            if ((shareUrl = mService.getShareUrl(filesToUpload)).isEmpty()) {
                return;
            }
        } catch (RuntimeException ex) {
            ToastUtils.show(this, "Unable to get share url!");
        }

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
                else ToastUtils.show(this, getString(R.string.upload_ok));

                finishAndRemoveTask();
                return;
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
