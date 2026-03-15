package me.ocv.partyup;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getString("on_up_ok", "menu").equals("menu")) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString("on_up_ok", "menu");
            ed.apply();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            super.getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            SwitchPreference darkMode = findPreference("dark_mode");

            if (darkMode != null) {
                darkMode.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (Boolean) newValue;

                    if (enabled) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    } else {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    }

                    return true;
                });
            }

            EditTextPreference sUrl = findPreference("server_url");
            if (sUrl != null) {
                sUrl.setDialogMessage("To upload into a folder, add the folder at the end of the URL.\nFor example: \"https://ask.com/foo/bar/\"\nExample: \"https://ask.com/%Y/%m/\" will create\n\"/Year/month/\" (%Y/%m/%d, %H:%M:%S)");
            }

            EditTextPreference passwd = findPreference("server_password");
            if (passwd != null) {
                passwd.setSummaryProvider(preference -> {
                    if (passwd.getText() == null || passwd.getText().isEmpty()) {
                        return "Password is not set";
                    } else {
                        return "Click to change password";
                    }
                });
                passwd.setOnBindEditTextListener(editText -> {
                    editText.setInputType(
                            InputType.TYPE_CLASS_TEXT |
                                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                    );
                    editText.setOnLongClickListener(view -> {
                        editText.setInputType(
                                InputType.TYPE_CLASS_TEXT
                        );
                        editText.setOnLongClickListener(null);
                        return true;
                    });
                });

                passwd.setDialogMessage("If server has enabled login using usernames, input \"<username>:<password>\",\nFor example: azure:hunter2\n\nLong-press to reveal the password");
            }

            EditTextPreference linkExp = findPreference("link_expiration");
            if (linkExp != null) {
                // Use SummaryProvider for dynamic summary
                linkExp.setSummaryProvider(preference -> {
                    String value = ((EditTextPreference) preference).getText();
                    return getExpSummaryText(value);
                });

                // Validate on change
                linkExp.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = (String) newValue;
                    String error = validateExpiration(value);
                    if (error != null) {
                        Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    return true;
                });
            }
        }

        private String validateExpiration(String value) {
            if (value == null || value.trim().isEmpty())
                return null; // Empty is valid (never expires)

            value = value.trim().toLowerCase();
            if (value.matches("^\\d+[mhd]?$"))
                return null; // Valid format

            return "Invalid format. Use: 30m, 2h, 7d, or empty for never";
        }

        private String getExpSummaryText(String value) {
            if (value == null || value.trim().isEmpty())
                return "Never expires";

            value = value.trim().toLowerCase();
            if (!value.matches("^\\d+[mhd]?$"))
                return "Invalid format";

            char unit = value.charAt(value.length() - 1);
            int num;
            if (Character.isDigit(unit)) {
                num = Integer.parseInt(value);
                unit = 'm';
            } else {
                num = Integer.parseInt(value.substring(0, value.length() - 1));
            }

            switch (unit) {
                case 'm':
                    return num + " minute" + (num != 1 ? "s" : "");
                case 'h':
                    return num + " hour" + (num != 1 ? "s" : "");
                case 'd':
                    return num + " day" + (num != 1 ? "s" : "");
                default:
                    return "Never expires";
            }
        }
    }
}
