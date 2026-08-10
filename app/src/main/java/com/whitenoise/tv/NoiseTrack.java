package com.whitenoise.tv;

import java.util.Arrays;

/** The single source of truth for the three bundled, loop-ready sound beds. */
final class NoiseTrack {
    static final int COUNT = 3;

    static final String[] IDS = {
            "soft_rain",
            "heavy_rain",
            "ocean_waves"
    };

    static final int[] RESOURCE_IDS = {
            R.raw.soft_rain,
            R.raw.heavy_rain,
            R.raw.ocean_waves
    };

    // The source files are normalized to the same perceived loudness.
    static final int[] DEFAULT_VOLUMES = {100, 100, 100};

    private NoiseTrack() {
    }

    static boolean[] defaultEnabled() {
        return new boolean[]{true, false, false};
    }

    static int[] defaultVolumes() {
        return Arrays.copyOf(DEFAULT_VOLUMES, DEFAULT_VOLUMES.length);
    }
}
