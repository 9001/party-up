package me.ocv.partyup.utils;

import static me.ocv.partyup.utils.SoundIDs.BG_SOUND_IDS;
import static me.ocv.partyup.utils.SoundIDs.ERROR_SOUND_IDS;
import static me.ocv.partyup.utils.SoundIDs.SERVICE_KILLED_SOUND_IDS;
import static me.ocv.partyup.utils.SoundIDs.SUCCESS_SOUND_IDS;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

public final class SoundUtils {

    private static final Random random = new Random();

    private static final float RATE_HIGH = 0.9f;
    private static final float RATE_FACTOR = 0.2f;

    private static final long BG_COOLDOWN = 150;
    private static final long MAIN_COOLDOWN = 300;
    private static final long BG_ACTIVE_WINDOW = 8000/*ms*/;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static SoundPool soundPool;

    private static int[] errorSounds;
    private static int[] successSounds;
    private static int[] bgSounds;
    private static int[] serviceKilledSounds;

    private static boolean initialized = false;

    private static int currentStreamId = 0;
    private static SoundType currentType = null;
    private static long lastPlayTime = 0;

    private static int bgStreamId = 0;
    private static long bgActiveUntil = 0;
    private static Runnable bgStopRunnable;
    private static boolean be_silent = false;

    private SoundUtils() {
    }

    @SuppressWarnings("All")
    public static synchronized void release() {
        if (!initialized) {
            return;
        }

        if (bgStopRunnable != null) {
            handler.removeCallbacks(bgStopRunnable);
        }

        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }

        currentStreamId = 0;
        bgStreamId = 0;
        currentType = null;

        initialized = false;
    }

    public static void toggleShut(boolean shouldShut) {
        be_silent = shouldShut;
        if (be_silent) {
            stopBg();
        }
    }

    private static void stopBg() {
        if (bgStreamId != 0) {
            soundPool.stop(bgStreamId);
            bgStreamId = 0;
        }

        if (bgStopRunnable != null) {
            handler.removeCallbacks(bgStopRunnable);
        }

        if (currentType == SoundType.BG) {
            currentType = null;
        }
    }

    public static void playError() {
        if (be_silent) {
            return;
        }
        playMain(SoundType.ERROR, errorSounds);
    }

    private static synchronized void playMain(
            SoundType type,
            int[] sounds
    ) {
        if (!initialized || sounds.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastPlayTime < MAIN_COOLDOWN && currentType == type) {
            return;
        }

        if (currentType == SoundType.BG) {
            stopBg();
        }

        if (currentType != null && currentType.priority <= type.priority) {
            soundPool.stop(currentStreamId);
        } else if (currentType != null) {
            return; // higher priority already playing
        }

        int soundId = sounds[random.nextInt(sounds.length)];
        float rate = RATE_HIGH + random.nextFloat() * RATE_FACTOR;

        currentStreamId = soundPool.play(soundId, 1f, 1f, type.priority, 0, rate);

        currentType = type;
        lastPlayTime = now;
    }

    public static void playSuccess() {
        if (be_silent) {
            return;
        }
        playMain(SoundType.SUCCESS, successSounds);
    }

    public static void playServiceKilled() {
        if (be_silent) {
            return;
        }
        playMain(SoundType.SERVICE_KILLED, serviceKilledSounds);
    }

    public static synchronized void playBackGround() {
        if (be_silent) {
            return;
        }
        if (!initialized || bgSounds.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastPlayTime < BG_COOLDOWN && currentType == SoundType.BG) {
            extendBgWindow(now);
            return;
        }

        extendBgWindow(now);


        if (currentType == SoundType.BG && bgStreamId != 0) {
            scheduleBgStop();
            return;
        }


        int soundId = bgSounds[random.nextInt(bgSounds.length)];
        float rate = RATE_HIGH + random.nextFloat() * RATE_FACTOR;

        bgStreamId = soundPool.play(soundId, 1f, 1f, 0, -1, rate);

        currentStreamId = bgStreamId;
        currentType = SoundType.BG;
        lastPlayTime = now;

        scheduleBgStop();
    }

    private static void extendBgWindow(long now) {
        bgActiveUntil = now + BG_ACTIVE_WINDOW;
    }

    private static void scheduleBgStop() {
        if (bgStopRunnable != null) {
            handler.removeCallbacks(bgStopRunnable);
        }

        bgStopRunnable = () -> {
            long now = System.currentTimeMillis();

            if (now >= bgActiveUntil) {
                stopBg();
            } else {
                scheduleBgStop();
            }
        };

        handler.postDelayed(bgStopRunnable, BG_ACTIVE_WINDOW);
    }


    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder().setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build();

        errorSounds = load(context, ERROR_SOUND_IDS);
        successSounds = load(context, SUCCESS_SOUND_IDS);
        bgSounds = load(context, BG_SOUND_IDS);
        serviceKilledSounds = load(context, SERVICE_KILLED_SOUND_IDS);

        initialized = true;
    }

    private static int[] load(
            Context context,
            int[] rawIds
    ) {
        int[] loaded = new int[rawIds.length];
        for (int i = 0; i < rawIds.length; i++) {
            loaded[i] = soundPool.load(context, rawIds[i], 1);
        }
        return loaded;
    }

    private enum SoundType {
        BG(0), SUCCESS(1), ERROR(1), SERVICE_KILLED(2);

        final int priority;

        SoundType(int priority) {
            this.priority = priority;
        }
    }

}
