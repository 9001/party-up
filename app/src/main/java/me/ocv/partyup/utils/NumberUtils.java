package me.ocv.partyup.utils;

import androidx.annotation.NonNull;

import java.util.Locale;

import me.ocv.partyup.objects.CustomFile;

public final class NumberUtils {
    public static final String[] UNITS = { "KiB", "MiB", "GiB", "TiB", "PiB" };

    public static double calcPercentage(
            long x,
            long y
    ) {
        return y > 0 ? ((double) x / y) * 100.0f : 0.0f;
    }

    public static long sumSize(CustomFile[] files) {
        long result = 0;
        if (files != null) {
            for (CustomFile file : files) {
                if (file.size == null) {
                    continue;
                }
                result += file.size;
            }
        }
        return result;
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        int unit = -1;

        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < UNITS.length - 1);

        return String.format(Locale.getDefault(), "%.2f %s", value, UNITS[unit]);
    }

}
