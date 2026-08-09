package com.whitenoise.tv;

import java.util.Arrays;

/** The single source of truth for the seven bundled, loop-ready sound beds. */
final class NoiseTrack {
    static final int COUNT = 7;

    static final String[] IDS = {
            "soft_rain",
            "heavy_rain",
            "ocean_waves",
            "thunder",
            "stream",
            "wind",
            "campfire"
    };

    static final int[] RESOURCE_IDS = {
            R.raw.soft_rain,
            R.raw.heavy_rain,
            R.raw.ocean_waves,
            R.raw.thunder,
            R.raw.stream,
            R.raw.wind,
            R.raw.campfire
    };

    static final int[] DEFAULT_VOLUMES = {55, 45, 45, 24, 34, 36, 40};

    private NoiseTrack() {
    }

    static boolean[] defaultEnabled() {
        return new boolean[]{true, false, false, false, true, false, false};
    }

    static int[] defaultVolumes() {
        return Arrays.copyOf(DEFAULT_VOLUMES, DEFAULT_VOLUMES.length);
    }
}
