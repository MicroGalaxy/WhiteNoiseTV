package com.whitenoise.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity implements AudioPlaybackService.StateListener {
    private LinearLayout trackList;
    private TextView playbackStatus;
    private TextView activeSummary;
    private TextView interactionHint;
    private Button masterButton;
    private Button resetButton;
    private LinearLayout[] trackRows;
    private TextView[] trackStateLabels;
    private ProgressBar[] volumeBars;
    private TextView[] volumeLabels;

    private AudioPlaybackService audioService;
    private boolean bound;
    private boolean masterEnabled = true;
    private boolean[] trackEnabled = NoiseTrack.defaultEnabled();
    private int[] trackVolumes = NoiseTrack.defaultVolumes();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlaybackService.ServiceBinder serviceBinder =
                    (AudioPlaybackService.ServiceBinder) service;
            audioService = serviceBinder.getService();
            bound = true;
            audioService.addListener(MainActivity.this);
            onStateChanged(audioService.getSnapshot());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            audioService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeatures();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        trackList = findViewById(R.id.track_list);
        playbackStatus = findViewById(R.id.playback_status);
        activeSummary = findViewById(R.id.active_summary);
        interactionHint = findViewById(R.id.interaction_hint);
        masterButton = findViewById(R.id.master_button);
        resetButton = findViewById(R.id.reset_button);

        buildTrackRows();
        configureGlobalControls();
        updateUi(new AudioPlaybackService.Snapshot(masterEnabled,
                Arrays.copyOf(trackEnabled, trackEnabled.length),
                Arrays.copyOf(trackVolumes, trackVolumes.length), false));

        // The first sound is the most useful starting point for a five-key
        // remote. Pressing Up from it reaches the master control.
        trackRows[0].post(new Runnable() {
            @Override
            public void run() {
                trackRows[0].requestFocus();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bound = bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (audioService != null) {
            audioService.removeListener(this);
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        audioService = null;
        super.onStop();
    }

    @Override
    public void onStateChanged(final AudioPlaybackService.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateUi(snapshot);
            }
        });
    }

    private void requestWindowFeatures() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(11, 20, 34));
        getWindow().setNavigationBarColor(Color.rgb(11, 20, 34));
    }

    private void configureGlobalControls() {
        masterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioService != null) {
                    audioService.setMasterEnabled(!masterEnabled);
                }
            }
        });
        masterButton.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return moveFocus(trackRows[0], event);
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return moveFocus(resetButton, event);
                }
                return false;
            }
        });
        masterButton.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    interactionHint.setText(R.string.master_focus_hint);
                }
            }
        });

        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioService != null) {
                    audioService.resetMix();
                }
            }
        });
        resetButton.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return moveFocus(trackRows[NoiseTrack.COUNT - 1], event);
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return moveFocus(masterButton, event);
                }
                return false;
            }
        });
        resetButton.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    interactionHint.setText(R.string.reset_focus_hint);
                }
            }
        });
    }

    private void buildTrackRows() {
        trackRows = new LinearLayout[NoiseTrack.COUNT];
        trackStateLabels = new TextView[NoiseTrack.COUNT];
        volumeBars = new ProgressBar[NoiseTrack.COUNT];
        volumeLabels = new TextView[NoiseTrack.COUNT];
        final String[] names = getResources().getStringArray(R.array.track_names);
        String[] descriptions = getResources().getStringArray(R.array.track_descriptions);

        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setId(View.generateViewId());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackgroundResource(R.drawable.bg_track_row);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    getResources().getDimensionPixelSize(R.dimen.track_row_height));
            rowParams.bottomMargin = dp(10);

            TextView stateLabel = new TextView(this);
            stateLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(94), dp(38)));
            stateLabel.setGravity(android.view.Gravity.CENTER);
            stateLabel.setTextSize(14);

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(android.view.Gravity.CENTER_VERTICAL);
            details.setPadding(dp(18), 0, dp(16), 0);
            details.setLayoutParams(new LinearLayout.LayoutParams(dp(238),
                    LinearLayout.LayoutParams.MATCH_PARENT));

            TextView nameView = new TextView(this);
            nameView.setText(names[index]);
            nameView.setTextColor(getColorCompat(R.color.text_primary));
            nameView.setTextSize(19);
            nameView.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));

            TextView descriptionView = new TextView(this);
            descriptionView.setText(descriptions[index]);
            descriptionView.setTextColor(getColorCompat(R.color.text_secondary));
            descriptionView.setTextSize(13);
            descriptionView.setMaxLines(1);
            descriptionView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            descriptionView.setPadding(0, dp(4), 0, 0);

            details.addView(nameView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            details.addView(descriptionView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView volumeTitle = new TextView(this);
            volumeTitle.setLayoutParams(new LinearLayout.LayoutParams(dp(52),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            volumeTitle.setText(R.string.volume_title);
            volumeTitle.setTextColor(getColorCompat(R.color.text_muted));
            volumeTitle.setTextSize(13);
            volumeTitle.setGravity(android.view.Gravity.CENTER);

            ProgressBar volumeBar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    0, dp(14), 1f);
            volumeBar.setLayoutParams(progressParams);
            volumeBar.setMax(100);
            volumeBar.setProgress(trackVolumes[index]);
            volumeBar.setFocusable(false);
            volumeBar.setProgressTintList(getColorStateListCompat(R.color.seekbar_progress));
            volumeBar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(getColorCompat(R.color.divider)));

            TextView volumeLabel = new TextView(this);
            volumeLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(72),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            volumeLabel.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
            volumeLabel.setTextColor(getColorCompat(R.color.text_primary));
            volumeLabel.setTextSize(16);

            row.addView(stateLabel);
            row.addView(details);
            row.addView(volumeTitle);
            row.addView(volumeBar);
            row.addView(volumeLabel);
            trackList.addView(row, rowParams);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleTrack(index);
                }
            });
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    return handleTrackKey(index, v, keyCode, event);
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        interactionHint.setText(getString(R.string.track_focus_hint, names[index]));
                    }
                }
            });

            trackRows[index] = row;
            trackStateLabels[index] = stateLabel;
            volumeBars[index] = volumeBar;
            volumeLabels[index] = volumeLabel;
        }
    }

    private boolean handleTrackKey(int index, View row, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            View target = index == 0 ? masterButton : trackRows[index - 1];
            return moveFocus(target, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View target = index == NoiseTrack.COUNT - 1 ? resetButton : trackRows[index + 1];
            return moveFocus(target, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int delta = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5;
                adjustTrackVolume(index, delta);
            }
            return true;
        }
        if (isConfirmKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                row.performClick();
            }
            return true;
        }
        return false;
    }

    private boolean moveFocus(View target, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            target.requestFocus();
        }
        return true;
    }

    private boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private void toggleTrack(int index) {
        if (audioService != null) {
            audioService.setTrackEnabled(index, !trackEnabled[index]);
        }
    }

    private void adjustTrackVolume(int index, int delta) {
        int next = Math.max(0, Math.min(100, trackVolumes[index] + delta));
        if (next == trackVolumes[index]) {
            return;
        }
        trackVolumes[index] = next;
        updateVolumeUi(index);
        if (audioService != null) {
            audioService.setTrackVolume(index, next);
        }
    }

    private void updateUi(AudioPlaybackService.Snapshot snapshot) {
        masterEnabled = snapshot.masterEnabled;
        trackEnabled = Arrays.copyOf(snapshot.trackEnabled, snapshot.trackEnabled.length);
        trackVolumes = Arrays.copyOf(snapshot.trackVolumes, snapshot.trackVolumes.length);

        masterButton.setText(masterEnabled ? getString(R.string.master_on) : getString(R.string.master_off));
        playbackStatus.setText(!masterEnabled
                ? getString(R.string.paused)
                : (snapshot.hasPlayingTracks ? getString(R.string.now_playing) : getString(R.string.ready)));
        playbackStatus.setTextColor(getColorCompat(masterEnabled ? R.color.accent : R.color.text_muted));

        String[] names = getResources().getStringArray(R.array.track_names);
        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            boolean enabled = trackEnabled[i];
            trackRows[i].setActivated(enabled);
            trackStateLabels[i].setText(enabled
                    ? (masterEnabled ? R.string.track_state_playing : R.string.track_state_enabled)
                    : R.string.track_state_off);
            trackStateLabels[i].setTextColor(getColorCompat(enabled
                    ? R.color.accent_bright : R.color.text_muted));
            trackStateLabels[i].setBackgroundResource(enabled
                    ? R.drawable.bg_state_on : R.drawable.bg_state_off);
            updateVolumeUi(i);
            trackRows[i].setContentDescription(getString(R.string.track_accessibility,
                    names[i], trackStateLabels[i].getText(), trackVolumes[i]));
        }
        updateSummary();
    }

    private void updateVolumeUi(int index) {
        volumeBars[index].setProgress(trackVolumes[index]);
        volumeLabels[index].setText(String.format(Locale.getDefault(),
                getString(R.string.volume_format), trackVolumes[index]));
    }

    private void updateSummary() {
        String[] names = getResources().getStringArray(R.array.track_names);
        StringBuilder activeNames = new StringBuilder();
        int count = 0;
        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            if (trackEnabled[i]) {
                if (count > 0) {
                    activeNames.append("、");
                }
                activeNames.append(names[i]);
                count++;
            }
        }
        if (count == 0) {
            activeSummary.setText(getString(R.string.no_tracks));
        } else {
            activeSummary.setText(String.format(Locale.getDefault(),
                    getString(R.string.track_count_format), count) + "\n" + activeNames);
        }
    }

    private ColorStateList getColorStateListCompat(int colorRes) {
        return getResources().getColorStateList(colorRes);
    }

    private int getColorCompat(int colorRes) {
        return getResources().getColor(colorRes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
