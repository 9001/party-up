package me.ocv.partyup;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

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
            ed.commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

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
                case 'm': return num + " minute" + (num != 1 ? "s" : "");
                case 'h': return num + " hour" + (num != 1 ? "s" : "");
                case 'd': return num + " day" + (num != 1 ? "s" : "");
                default: return "Never expires";
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
