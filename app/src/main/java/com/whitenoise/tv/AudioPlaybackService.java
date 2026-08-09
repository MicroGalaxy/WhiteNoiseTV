package com.whitenoise.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps the audio mix alive independently of the TV activity. Each sound bed
 * is a local, loop-ready PCM asset, so MediaPlayer can mix them without any
 * network dependency or architecture-specific native code.
 */
public class AudioPlaybackService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "white_noise_playback";

    private final IBinder binder = new ServiceBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new ArrayList<>();
    private final MediaPlayer[] players = new MediaPlayer[NoiseTrack.COUNT];

    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private boolean masterEnabled;
    private boolean[] trackEnabled;
    private int[] trackVolumes;
    private boolean audioFocusLost;

    public interface StateListener {
        void onStateChanged(Snapshot snapshot);
    }

    public final class ServiceBinder extends android.os.Binder {
        public AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }

    public static final class Snapshot {
        public final boolean masterEnabled;
        public final boolean[] trackEnabled;
        public final int[] trackVolumes;
        public final boolean hasPlayingTracks;

        Snapshot(boolean masterEnabled, boolean[] trackEnabled, int[] trackVolumes,
                 boolean hasPlayingTracks) {
            this.masterEnabled = masterEnabled;
            this.trackEnabled = trackEnabled;
            this.trackVolumes = trackVolumes;
            this.hasPlayingTracks = hasPlayingTracks;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackPrefs.State state = PlaybackPrefs.read(this);
        masterEnabled = state.masterEnabled;
        trackEnabled = Arrays.copyOf(state.enabled, state.enabled.length);
        trackVolumes = Arrays.copyOf(state.volumes, state.volumes.length);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        requestAudioFocus();

        if (masterEnabled) {
            startEnabledTracks();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY lets Android TV restore the mixer after reclaiming the
        // process. The persisted mix is loaded again in onCreate().
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public Snapshot getSnapshot() {
        return createSnapshot();
    }

    public void addListener(final StateListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
        final Snapshot snapshot = createSnapshot();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (listeners) {
                    if (listeners.contains(listener)) {
                        listener.onStateChanged(snapshot);
                    }
                }
            }
        });
    }

    public void removeListener(StateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public void setMasterEnabled(boolean enabled) {
        masterEnabled = enabled;
        if (masterEnabled) {
            audioFocusLost = false;
            requestAudioFocus();
            startEnabledTracks();
        } else {
            pauseAllTracks();
        }
        saveState();
        publishState();
    }

    public void setTrackEnabled(int index, boolean enabled) {
        if (!isValidIndex(index)) {
            return;
        }
        trackEnabled[index] = enabled;
        if (enabled && masterEnabled && !audioFocusLost) {
            startTrack(index);
        } else if (!enabled) {
            releaseTrack(index);
        }
        saveState();
        publishState();
    }

    public void setTrackVolume(int index, int volume) {
        if (!isValidIndex(index)) {
            return;
        }
        trackVolumes[index] = PlaybackPrefs.clamp(volume);
        if (players[index] != null) {
            float gain = trackVolumes[index] / 100f;
            players[index].setVolume(gain, gain);
        }
        saveState();
        publishState();
    }

    public void resetMix() {
        masterEnabled = true;
        trackEnabled = NoiseTrack.defaultEnabled();
        trackVolumes = NoiseTrack.defaultVolumes();
        releaseAllTracks();
        audioFocusLost = false;
        requestAudioFocus();
        startEnabledTracks();
        saveState();
        publishState();
    }

    @Override
    public void onDestroy() {
        releaseAllTracks();
        if (audioManager != null) {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
        super.onDestroy();
    }

    private void startEnabledTracks() {
        if (!masterEnabled || audioFocusLost) {
            return;
        }
        for (int i = 0; i < NoiseTrack.COUNT; i++) {
            if (trackEnabled[i]) {
                startTrack(i);
            }
        }
    }

    private void startTrack(int index) {
        if (!isValidIndex(index) || !masterEnabled || audioFocusLost) {
            return;
        }

        MediaPlayer existing = players[index];
        if (existing != null) {
            try {
                if (!existing.isPlaying()) {
                    existing.start();
                }
            } catch (IllegalStateException ignored) {
                releaseTrack(index);
            }
            return;
        }

        MediaPlayer player = new MediaPlayer();
        AssetFileDescriptor descriptor = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setLooping(true);
            descriptor = getResources().openRawResourceFd(NoiseTrack.RESOURCE_IDS[index]);
            if (descriptor == null) {
                throw new IOException("Audio asset is compressed; expected an uncompressed WAV resource");
            }
            player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
            player.prepare();
            float gain = trackVolumes[index] / 100f;
            player.setVolume(gain, gain);
            players[index] = player;
            player.start();
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            players[index] = null;
            try {
                player.release();
            } catch (Exception ignored) {
                // Nothing else to release after a failed prepare.
            }
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // The descriptor is no longer needed after prepare().
                }
            }
        }
    }

    private void pauseAllTracks() {
        for (MediaPlayer player : players) {
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        player.pause();
                    }
                } catch (IllegalStateException ignored) {
                    // A player that is already tearing down can be ignored.
                }
            }
        }
    }

    private void releaseTrack(int index) {
        if (!isValidIndex(index)) {
            return;
        }
        MediaPlayer player = players[index];
        players[index] = null;
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // The player may have failed during preparation.
            }
            player.release();
        }
    }

    private void releaseAllTracks() {
        for (int i = 0; i < players.length; i++) {
            releaseTrack(i);
        }
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        audioFocusLost = true;
                        pauseAllTracks();
                        publishState();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        audioFocusLost = false;
                        if (masterEnabled) {
                            startEnabledTracks();
                        }
                        publishState();
                    }
                }
            };

    private Snapshot createSnapshot() {
        return new Snapshot(masterEnabled,
                Arrays.copyOf(trackEnabled, trackEnabled.length),
                Arrays.copyOf(trackVolumes, trackVolumes.length),
                hasPlayingTracks());
    }

    private boolean hasPlayingTracks() {
        for (MediaPlayer player : players) {
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        return true;
                    }
                } catch (IllegalStateException ignored) {
                    // Continue checking the remaining players.
                }
            }
        }
        return false;
    }

    private void publishState() {
        final Snapshot snapshot = createSnapshot();
        updateNotification(snapshot);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                List<StateListener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<>(listeners);
                }
                for (StateListener listener : copy) {
                    listener.onStateChanged(snapshot);
                }
            }
        });
    }

    private void saveState() {
        PlaybackPrefs.write(this, masterEnabled, trackEnabled, trackVolumes);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.app_subtitle));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags);
        Snapshot snapshot = createSnapshot();
        String text = snapshot.masterEnabled
                ? getString(R.string.now_playing) + " · " + activeCount(snapshot) + " 个音源"
                : getString(R.string.paused);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(Snapshot snapshot) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private int activeCount(Snapshot snapshot) {
        int count = 0;
        for (boolean enabled : snapshot.trackEnabled) {
            if (enabled) {
                count++;
            }
        }
        return count;
    }

    private boolean isValidIndex(int index) {
        return index >= 0 && index < NoiseTrack.COUNT;
    }
}
