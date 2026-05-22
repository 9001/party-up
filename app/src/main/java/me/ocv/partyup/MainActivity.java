package me.ocv.partyup;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Html;
import android.text.method.LinkMovementMethod;

import androidx.appcompat.app.AppCompatActivity;

import me.ocv.partyup.databinding.ActivityMainBinding;
import me.ocv.partyup.service.UploaderService;
import me.ocv.partyup.utils.PermissionUtils;
import me.ocv.partyup.utils.SoundUtils;
import me.ocv.partyup.utils.ToastUtils;

public class MainActivity extends AppCompatActivity {
    private UploaderService mService;
    private ActivityMainBinding binding;
    private boolean serviceKilled;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(
                ComponentName componentName,
                IBinder iBinder
        ) {
            UploaderService.UploadBinder binder = (UploaderService.UploadBinder) iBinder;
            mService = binder.getService();
            serviceConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        }
    };

    private void serviceConnected() {
        binding.stopServBtn.post(() -> {
            binding.stopServBtn.setEnabled(true);
            binding.stopServBtn.setOnClickListener(v -> {
                if (mService == null) {
                    return;
                }
                if (serviceKilled) {
                    ToastUtils.show(MainActivity.this, "Service killed!");
                } else {
                    mService.stop();
                    serviceKilled = true;
                    binding.stopServBtn.setEnabled(false);
                    SoundUtils.playServiceKilled();
                }
            });
        });
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
        if (!PermissionUtils.hasAllPermissions(this)) {
            PermissionUtils.requestAllPermissions(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mService != null) {
            unbindService(connection);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());

        setContentView(binding.getRoot());
        String txt = String.format(getString(R.string.welcome_text), BuildConfig.VERSION_NAME);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.textView4.setText(Html.fromHtml(txt, Html.FROM_HTML_MODE_LEGACY));
        } else {
            binding.textView4.setText(Html.fromHtml(txt));
        }
        binding.textView4.setMovementMethod(LinkMovementMethod.getInstance());

        binding.settingsBtn.setOnClickListener(v -> startActivity(new Intent(
                this,
                SettingsActivity.class
        )));

        binding.errBtn.setOnClickListener(v -> SoundUtils.playError());
        binding.successBtn.setOnClickListener(v -> SoundUtils.playSuccess());
        binding.bgBtn.setOnClickListener(v -> SoundUtils.playBackGround());
    }

}
