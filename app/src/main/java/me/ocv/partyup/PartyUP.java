package me.ocv.partyup;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import me.ocv.partyup.objects.PrefsKey;
import me.ocv.partyup.utils.SoundUtils;

public class PartyUP extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        AppCompatDelegate.setDefaultNightMode(preferences.getBoolean(PrefsKey.DARK_MODE, false)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        SoundUtils.init(this);
        SoundUtils.toggleShut(preferences.getBoolean(PrefsKey.SHUT_UP, true));
    }

}
