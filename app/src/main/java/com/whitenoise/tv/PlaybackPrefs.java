package com.whitenoise.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

final class PlaybackPrefs {
    private static final String PREFS = "white_noise_mix";
    private static final String MASTER = "master_enabled";
    private static final String ENABLED_PREFIX = "track_enabled_";
    private static final String VOLUME_PREFIX = "track_volume_";

    private PlaybackPrefs() {
    }

    static State read(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean[] defaultEnabled = NoiseTrack.defaultEnabled();
        int[] defaultVolumes = NoiseTrack.defaultVolumes();
        boolean[] enabled = Arrays.copyOf(defaultEnabled, defaultEnabled.length);
        int[] volumes = Arrays.copyOf(defaultVolumes, defaultVolumes.length);

        boolean masterEnabled = preferences.getBoolean(MASTER, true);
        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            enabled[i] = preferences.getBoolean(ENABLED_PREFIX + i, defaultEnabled[i]);
            volumes[i] = clamp(preferences.getInt(VOLUME_PREFIX + i, defaultVolumes[i]));
        }
        return new State(masterEnabled, enabled, volumes);
    }

    static void write(Context context, boolean masterEnabled, boolean[] enabled, int[] volumes) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putBoolean(MASTER, masterEnabled);
        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            editor.putBoolean(ENABLED_PREFIX + i, enabled[i]);
            editor.putInt(VOLUME_PREFIX + i, clamp(volumes[i]));
        }
        editor.apply();
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    static final class State {
        final boolean masterEnabled;
        final boolean[] enabled;
        final int[] volumes;

        State(boolean masterEnabled, boolean[] enabled, int[] volumes) {
            this.masterEnabled = masterEnabled;
            this.enabled = enabled;
            this.volumes = volumes;
        }
    }
}
