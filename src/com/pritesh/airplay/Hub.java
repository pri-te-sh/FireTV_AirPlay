package com.pritesh.airplay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the receiver is doing right now, shared between the network threads and the screens.
 * Listeners are called on the main thread.
 */
final class Hub {
    enum Mode { IDLE, MIRROR, CAST, AUDIO }

    interface Listener {
        void onHubChanged();
    }

    static final VideoPlayer video = new VideoPlayer();

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Set<AudioTrack> tracks = ConcurrentHashMap.newKeySet();

    private static int mirrors, audios;
    private static volatile Receiver receiver;
    private static volatile double volumeDb = 0;

    static volatile String sender, title, artist, album;
    static volatile Bitmap artwork;
    /** Track position in seconds at {@link #positionAt}, and its length; -1 when unknown. */
    static volatile double position = -1, duration = -1;
    static volatile long positionAt;

    private Hub() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onHubChanged();
        });
    }

    /** The receiver is listening and advertised (the home screen shows "Ready"). */
    static void notifyReady() {
        changed();
    }

    static synchronized Mode mode() {
        // Video from an app wins over mirroring: iOS keeps mirroring open while it hands over.
        if (Cast.active()) return Mode.CAST;
        return mirrors > 0 ? Mode.MIRROR : audios > 0 ? Mode.AUDIO : Mode.IDLE;
    }

    static void setReceiver(Receiver r) {
        receiver = r;
    }

    /** Stops whatever is playing; the phone shows AirPlay/mirroring ended. */
    static void disconnect() {
        Receiver r = receiver;
        if (r != null) new Thread(r::disconnectAll, "airplay-disconnect").start();
    }

    static void mirrorStarted(String from) {
        synchronized (Hub.class) {
            mirrors++;
            sender = from;
        }
        changed();
    }

    static void castSender(String from) {
        if (from != null) sender = from;
    }

    /** Video from an app started, stopped or began showing pictures. */
    static void castChanged() {
        changed();
    }

    static void mirrorStopped() {
        synchronized (Hub.class) {
            mirrors = Math.max(0, mirrors - 1);
        }
        changed();
    }

    static void audioStarted(String from, boolean withMirror) {
        synchronized (Hub.class) {
            audios++;
            sender = from;
            if (!withMirror) {
                title = artist = album = null;
                artwork = null;
                position = duration = -1;
            }
        }
        changed();
    }

    static void audioStopped() {
        synchronized (Hub.class) {
            audios = Math.max(0, audios - 1);
        }
        changed();
    }

    static void setMetadata(Dmap d) {
        title = d.title;
        artist = d.artist;
        album = d.album;
        changed();
    }

    static void setArtwork(byte[] image) {
        artwork = image.length == 0 ? null : BitmapFactory.decodeByteArray(image, 0, image.length);
        changed();
    }

    /** {@code progress: start/current/end} in RTP samples (44.1 kHz). */
    static void setProgress(String value) {
        String[] p = value.split("/");
        if (p.length != 3) return;
        try {
            long start = Long.parseLong(p[0].trim()), now = Long.parseLong(p[1].trim()), end = Long.parseLong(p[2].trim());
            position = (now - start) / 44100.0;
            duration = (end - start) / 44100.0;
            positionAt = SystemClock.elapsedRealtime();
            changed();
        } catch (NumberFormatException ignored) {
        }
    }

    /** AirPlay volume is dB from -30 (quietest) to 0; -144 means muted. */
    static void setVolumeDb(double db) {
        volumeDb = db;
        float v = volume();
        for (AudioTrack t : tracks) t.setVolume(v);
    }

    static double volumeDb() {
        return volumeDb;
    }

    static float volume() {
        double db = volumeDb;
        if (db <= -144) return 0;
        // A gentler curve than raw dB so the low end of the phone's slider stays audible.
        double x = (Math.max(-30, Math.min(0, db)) + 30) / 30;
        return (float) (x * x);
    }

    static void attachTrack(AudioTrack t) {
        tracks.add(t);
    }

    static void detachTrack(AudioTrack t) {
        tracks.remove(t);
    }
}
