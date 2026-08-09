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
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
    private Button[] trackButtons;
    private SeekBar[] volumeBars;
    private TextView[] volumeLabels;

    private AudioPlaybackService audioService;
    private boolean bound;
    private boolean updatingUi;
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
        masterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioService != null) {
                    audioService.setMasterEnabled(!masterEnabled);
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

        updateUi(new AudioPlaybackService.Snapshot(masterEnabled,
                Arrays.copyOf(trackEnabled, trackEnabled.length),
                Arrays.copyOf(trackVolumes, trackVolumes.length), false));
        masterButton.post(new Runnable() {
            @Override
            public void run() {
                masterButton.requestFocus();
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

    private void buildTrackRows() {
        trackButtons = new Button[NoiseTrack.COUNT];
        volumeBars = new SeekBar[NoiseTrack.COUNT];
        volumeLabels = new TextView[NoiseTrack.COUNT];
        String[] names = getResources().getStringArray(R.array.track_names);
        String[] descriptions = getResources().getStringArray(R.array.track_descriptions);

        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.setBackgroundResource(R.drawable.bg_track_row);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(104));
            rowParams.bottomMargin = dp(12);

            Button toggleButton = new Button(this);
            toggleButton.setLayoutParams(new LinearLayout.LayoutParams(dp(142), dp(64)));
            toggleButton.setBackgroundResource(R.drawable.bg_control);
            toggleButton.setStateListAnimator(null);
            toggleButton.setTextSize(15);
            toggleButton.setTextColor(getColorStateListCompat(R.color.control_text));
            toggleButton.setAllCaps(false);
            toggleButton.setFocusable(true);
            toggleButton.setFocusableInTouchMode(true);
            toggleButton.setContentDescription(names[index] + " 开关");
            toggleButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (audioService != null) {
                        audioService.setTrackEnabled(index, !trackEnabled[index]);
                    }
                }
            });

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(android.view.Gravity.CENTER_VERTICAL);
            details.setPadding(dp(18), 0, dp(14), 0);
            details.setLayoutParams(new LinearLayout.LayoutParams(dp(218),
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
            descriptionView.setPadding(0, dp(5), 0, 0);

            details.addView(nameView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            details.addView(descriptionView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            SeekBar seekBar = new SeekBar(this);
            LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                    0, dp(64), 1f);
            seekBar.setLayoutParams(seekParams);
            seekBar.setMax(100);
            seekBar.setProgress(trackVolumes[index]);
            seekBar.setPadding(dp(6), 0, dp(6), 0);
            seekBar.setFocusable(true);
            seekBar.setFocusableInTouchMode(true);
            seekBar.setContentDescription(names[index] + " 音量");
            seekBar.setProgressTintList(getColorStateListCompat(R.color.seekbar_progress));
            seekBar.setThumbTintList(getColorStateListCompat(R.color.seekbar_thumb));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (updatingUi) {
                        return;
                    }
                    trackVolumes[index] = progress;
                    volumeLabels[index].setText(String.format(Locale.getDefault(),
                            getString(R.string.volume_format), progress));
                    if (audioService != null) {
                        audioService.setTrackVolume(index, progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    // D-pad and pointer interaction use the same listener.
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    // The value is persisted by the service on every change.
                }
            });
            seekBar.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        int delta = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5;
                        int next = Math.max(0, Math.min(100, volumeBars[index].getProgress() + delta));
                        volumeBars[index].setProgress(next);
                        return true;
                    }
                    return false;
                }
            });

            TextView volumeLabel = new TextView(this);
            volumeLabel.setLayoutParams(new LinearLayout.LayoutParams(dp(66),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            volumeLabel.setGravity(android.view.Gravity.CENTER);
            volumeLabel.setTextColor(getColorCompat(R.color.text_secondary));
            volumeLabel.setTextSize(15);
            volumeLabels[index] = volumeLabel;

            row.addView(toggleButton);
            row.addView(details);
            row.addView(seekBar);
            row.addView(volumeLabel);
            trackList.addView(row, rowParams);

            trackButtons[index] = toggleButton;
            volumeBars[index] = seekBar;
        }
    }

    private void updateUi(AudioPlaybackService.Snapshot snapshot) {
        masterEnabled = snapshot.masterEnabled;
        trackEnabled = Arrays.copyOf(snapshot.trackEnabled, snapshot.trackEnabled.length);
        trackVolumes = Arrays.copyOf(snapshot.trackVolumes, snapshot.trackVolumes.length);
        updatingUi = true;
        masterButton.setText(masterEnabled ? getString(R.string.master_on) : getString(R.string.master_off));
        playbackStatus.setText(!masterEnabled
                ? getString(R.string.paused)
                : (snapshot.hasPlayingTracks ? getString(R.string.now_playing) : getString(R.string.ready)));
        playbackStatus.setTextColor(getColorCompat(masterEnabled ? R.color.accent : R.color.text_muted));

        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            trackButtons[i].setText(trackEnabled[i] ? "已开启" : "开启");
            trackButtons[i].setSelected(trackEnabled[i]);
            volumeBars[i].setProgress(trackVolumes[i]);
            volumeLabels[i].setText(String.format(Locale.getDefault(),
                    getString(R.string.volume_format), trackVolumes[i]));
        }
        updateSummary();
        updatingUi = false;
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
            interactionHint.setText(getString(R.string.mixer_hint));
        } else {
            activeSummary.setText(String.format(Locale.getDefault(),
                    getString(R.string.track_count_format), count) + "\n" + activeNames);
            interactionHint.setText(getString(R.string.mixer_hint));
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
