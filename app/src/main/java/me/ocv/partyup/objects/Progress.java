package me.ocv.partyup.objects;

import java.util.Locale;

public class Progress {
    // bytes
    public long done;
    public long total;

    public long t0 = System.currentTimeMillis();

    public Progress() {
        total = 0;
        done = 0;
    }

    public Progress(long done, long total) {
        this.done = done;
        this.total = total;
    }

    public static String formatBytes(Long bytes) {
        if (bytes == null)
            return "0 B";

        if (bytes < 1024) {
            return bytes + " B";
        }

        final String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;

        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);

        return String.format(Locale.getDefault(), "%.2f %s", value, units[unit]);
    }

    public long left() {
        return Math.max(0, total - done);
    }

    public double ratio() {
        return total > 0 ? (double) done / total : 0.0;
    }

    public int perc() {
        return (int) Math.round(ratio() * 100.0);
    }

    public double speed() {
        // in bytes
        if (t0 == 0 || done == 0)
            return 0.0;
        double seconds = (System.currentTimeMillis() - t0) / 1000.0;
        return seconds <= 0 ? 0.0 : done / seconds;
    }

    public long eta() {
        // seconds
        double s = speed();
        return s <= 0 ? -1 : (long) (left() / speed());
    }

    public String stats() {
        String format = String.join("\n", "Bytes: %d/%d (%d)", "Percentage: %.1f%%", "Speed: %s", "ETA: %d sec");
        return String.format(Locale.getDefault(), format, done, total, left(), ratio() * 100.0, formatBytes((long) speed()), eta());
    }
}

