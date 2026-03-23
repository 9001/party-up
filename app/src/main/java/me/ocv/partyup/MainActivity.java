package me.ocv.partyup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import me.ocv.partyup.databinding.ActivityMainBinding;
import me.ocv.partyup.objects.PrefsKey;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String txt = String.format(getString(R.string.welcome_text), BuildConfig.VERSION_NAME);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.textView4.setText(Html.fromHtml(txt, Html.FROM_HTML_MODE_LEGACY));
        } else {
            binding.textView4.setText(Html.fromHtml(txt));
        }
        binding.textView4.setMovementMethod(LinkMovementMethod.getInstance());

        binding.settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }
}
