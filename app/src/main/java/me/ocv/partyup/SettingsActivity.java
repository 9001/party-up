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

import me.ocv.partyup.objects.PrefsKey;

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
        if (prefs.getString(PrefsKey.ON_UP_OK, "menu").equals("menu")) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString(PrefsKey.ON_UP_OK, "menu");
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

            SwitchPreference beSilent = findPreference(PrefsKey.BE_SILENT);
            SwitchPreference autoSend = findPreference(PrefsKey.AUTOSEND);

            if (beSilent != null && autoSend != null) {
                beSilent.setOnPreferenceChangeListener((p, n) -> {
                    boolean enabled = (Boolean) n;
                    if (enabled) {
                        autoSend.setChecked(true);   // force ON
                        autoSend.setEnabled(false);  // lock it
                    } else {
                        autoSend.setEnabled(true);   // unlock
                    }
                    return true;
                });

                boolean enabled = beSilent.isChecked();
                autoSend.setEnabled(!enabled);
                if (enabled) {
                    autoSend.setChecked(true);
                }
            }

            SwitchPreference darkMode = findPreference(PrefsKey.DARK_MODE);
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

            EditTextPreference sUrl = findPreference(PrefsKey.SERVER_URL);
            if (sUrl != null) {
                sUrl.setDialogMessage(getString(R.string.server_url_help));
            }

            EditTextPreference passwd = findPreference(PrefsKey.SERVER_PASSWORD);
            if (passwd != null) {
                passwd.setSummaryProvider(preference -> {
                    if (passwd.getText() == null || passwd.getText().isEmpty()) {
                        return getString(R.string.setting_password_empty);
                    } else {
                        return getString(R.string.setting_password_success);
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

                passwd.setDialogMessage(getString(R.string.server_password_help));
            }

            EditTextPreference linkExp = findPreference(PrefsKey.LINK_EXPIRATION);
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

            return getString(R.string.invalid_expiration);
        }

        private String getExpSummaryText(String value) {
            if (value == null || value.trim().isEmpty())
                return getString(R.string.default_expiration);

            value = value.trim().toLowerCase();
            if (!value.matches("^\\d+[mhd]?$"))
                return getString(R.string.invalid_expiration);

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
                    return getString(R.string.default_expiration);
            }
        }
    }
}
