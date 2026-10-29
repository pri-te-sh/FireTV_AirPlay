package com.pritesh.airplay;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AirPlay video from apps ({@code POST /play}): the TV plays the URL itself with MediaPlayer
 * while the phone stays the remote (rate, scrub, playback-info), and we report state back over
 * the reverse {@code /event} channel.
 * <p>
 * YouTube-style {@code mlhls://localhost/…} playlists live on the phone: we ask for each one
 * over the event channel (FCUP), it answers with {@code POST /action}, and the player reads
 * them from our own server at {@code /fcup?u=…}.
 */
final class Cast {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Pattern MLHLS = Pattern.compile("mlhls://localhost[^\\s\"']+");
    /** Position refresh for playback-info; every 4th tick also re-announces "playing". */
    private static final long TICK_MS = 500;
    private static int ticks;
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            update();
            if (playing && ++ticks % 4 == 0) sendState("playing");
            main.postDelayed(this, TICK_MS);
        }
    };

    // What the phone asked for (written on network threads).
    private static volatile String url, sessionId, uuid;
    private static volatile double startSeconds, startFraction;
    private static volatile int localPort = 7000;

    // Player state (owned by the main thread; snapshots read by network threads).
    private static MediaPlayer player;
    private static SurfaceHolder surface;
    private static volatile boolean prepared, playing, rendering;
    private static volatile double duration = -1, position;
    private static int videoW, videoH;
    private static VideoPlayer.SizeListener sizeListener;
    private static int eventSessionId = 1;

    // Reverse channel and FCUP requests.
    private static final Object eventLock = new Object();
    private static OutputStream events;
    private static int fcupRequestId;
    private static final Map<String, CompletableFuture<String>> fcup = new ConcurrentHashMap<>();

    private Cast() {}

    static boolean active() {
        return url != null;
    }

    static boolean rendering() {
        return rendering;
    }

    // ---- Phone → TV (network threads)

    /** {@code POST /play}. {@code startSec} wins over {@code fraction} when both are given. */
    static void play(String contentUrl, double startSec, double fraction, String session, String itemUuid, int port) {
        localPort = port;
        sessionId = session;
        uuid = itemUuid;
        startSeconds = startSec;
        startFraction = fraction;
        url = isPhonePlaylist(contentUrl) ? local(contentUrl) : contentUrl;
        Log.i(Receiver.TAG, "cast /play " + contentUrl + (url.equals(contentUrl) ? "" : " -> " + url));
        main.post(() -> {
            eventSessionId += 2;
            release();
            sendState("loading");
            start();
        });
        Hub.castChanged();
    }

    static void rate(double r) {
        main.post(() -> {
            if (player == null || !prepared) return;
            if (r > 0 && !player.isPlaying()) player.start();
            if (r <= 0 && player.isPlaying()) player.pause();
            update();
            sendState(r > 0 ? "playing" : "paused");
        });
    }

    static void scrub(double seconds) {
        main.post(() -> {
            if (player == null || !prepared) {
                startSeconds = seconds;
                return;
            }
            player.seekTo((int) (seconds * 1000));
            position = seconds;
        });
    }

    /** The phone stopped (or removed the item). */
    static void stop() {
        if (url == null) return;
        url = null;
        main.post(() -> {
            release();
            failPendingFcup();
        });
        Hub.castChanged();
    }

    /** Back on the remote or the screen went away: tell the phone, then stop. */
    static void stopFromTv() {
        if (url == null) return;
        main.post(() -> sendState("stopped"));
        stop();
    }

    /** {@code GET /playback-info}. */
    static byte[] playbackInfo() {
        NSDictionary r = new NSDictionary();
        double dur = Math.max(0, duration), pos = Math.max(0, Math.min(position, dur > 0 ? dur : position));
        boolean ready = prepared && dur > 0;
        r.put("duration", dur);
        r.put("position", pos);
        r.put("rate", playing ? 1.0 : 0.0);
        r.put("readyToPlay", ready);
        r.put("playbackBufferEmpty", !ready);
        r.put("playbackBufferFull", ready);
        r.put("playbackLikelyToKeepUp", ready);
        r.put("loadedTimeRanges", new NSArray(range(dur)));
        r.put("seekableTimeRanges", new NSArray(range(dur)));
        return r.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code GET /scrub}. */
    static String scrubInfo() {
        return String.format(Locale.ROOT, "duration: %.6f\r\nposition: %.6f\r\n", Math.max(0, duration), position);
    }

    // ---- Reverse event channel

    static void setEventChannel(OutputStream out, String session) {
        synchronized (eventLock) {
            events = out;
        }
        if (session != null && sessionId == null) sessionId = session;
    }

    static void eventChannelClosed(OutputStream out) {
        synchronized (eventLock) {
            if (events != out) return;
            events = null;
        }
        // The phone is gone (it closes the event channel when AirPlay ends).
        stop();
    }

    private static void sendEvent(NSDictionary event) {
        byte[] body = event.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
        new Thread(() -> {
            synchronized (eventLock) {
                if (events == null) return;
                try {
                    String head = "POST /event HTTP/1.1\r\n"
                            + "Content-Type: text/x-apple-plist+xml\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + (sessionId != null ? "X-Apple-Session-ID: " + sessionId + "\r\n" : "")
                            + "\r\n";
                    events.write(head.getBytes(StandardCharsets.US_ASCII));
                    events.write(body);
                    events.flush();
                } catch (IOException e) {
                    Log.w(Receiver.TAG, "event channel: " + e);
                }
            }
        }, "airplay-event").start();
    }

    private static void sendState(String state) {
        NSDictionary e = new NSDictionary();
        e.put("category", "video");
        e.put("sessionID", eventSessionId);
        e.put("state", state);
        NSDictionary params = new NSDictionary();
        if (uuid != null) params.put("uuid", uuid);
        if ("playing".equals(state) && duration > 0) {
            params.put("duration", duration);
            params.put("position", Math.min(position, duration));
            params.put("rate", 1.0);
            params.put("readyToPlay", true);
            params.put("playbackBufferEmpty", false);
            params.put("playbackBufferFull", true);
            params.put("playbackLikelyToKeepUp", true);
            params.put("loadedTimeRanges", new NSArray(range(duration)));
            params.put("seekableTimeRanges", new NSArray(range(duration)));
        }
        if (params.count() > 0) e.put("params", params);
        sendEvent(e);
    }

    /** End of a VOD item: the sequence the phone expects before it moves on. */
    private static void sendEnded() {
        sendState("loading");
        sendTyped("itemPlayedToEnd", eventSessionId);
        NSDictionary stopped = new NSDictionary();
        stopped.put("category", "video");
        stopped.put("sessionID", eventSessionId);
        stopped.put("state", "stopped");
        stopped.put("reason", "ended");
        sendEvent(stopped);
        NSDictionary removed = new NSDictionary();
        removed.put("category", "video");
        removed.put("sessionID", sessionId != null ? sessionId : "");
        removed.put("type", "itemRemoved");
        if (uuid != null) removed.put("uuid", uuid);
        sendEvent(removed);
        sendTyped("currentItemChanged", eventSessionId);
        sendState("stopped");
    }

    private static void sendTyped(String type, int sid) {
        NSDictionary e = new NSDictionary();
        e.put("category", "video");
        e.put("sessionID", sid);
        e.put("type", type);
        if (uuid != null) e.put("uuid", uuid);
        sendEvent(e);
    }

    // ---- FCUP: playlists that live on the phone

    private static boolean isPhonePlaylist(String u) {
        return u.startsWith("mlhls://");
    }

    private static String local(String remote) {
        try {
            return "http://127.0.0.1:" + localPort + "/fcup?u=" + URLEncoder.encode(remote, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String u) {
        int q = u.indexOf('?');
        return q < 0 ? u : u.substring(0, q);
    }

    /** {@code GET /fcup?u=…} from our own player: fetch that playlist from the phone (blocks). */
    static String fetchFromPhone(String remote) throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        fcup.put(key(remote), f);
        int id;
        synchronized (Cast.class) {
            id = ++fcupRequestId;
        }
        NSDictionary headers = new NSDictionary();
        headers.put("X-Playback-Session-Id", sessionId != null ? sessionId : "");
        headers.put("User-Agent", "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us");
        NSDictionary request = new NSDictionary();
        request.put("FCUP_Response_ClientInfo", 1);
        request.put("FCUP_Response_ClientRef", 40030004);
        request.put("FCUP_Response_Headers", headers);
        request.put("FCUP_Response_RequestID", id);
        request.put("FCUP_Response_URL", remote);
        request.put("sessionID", 1);
        NSDictionary wrapper = new NSDictionary();
        wrapper.put("request", request);
        wrapper.put("sessionID", 1);
        wrapper.put("type", "unhandledURLRequest");
        sendEvent(wrapper);
        try {
            return rewrite(f.get(15, TimeUnit.SECONDS));
        } finally {
            fcup.remove(key(remote));
        }
    }

    /** {@code POST /action} type {@code unhandledURLResponse}. */
    static void fcupResponse(String responseUrl, byte[] data) {
        String k = key(responseUrl);
        int at = k.indexOf("/playlist/");
        if (k.startsWith("http") && at >= 0) k = "mlhls://localhost" + k.substring(at + "/playlist".length());
        CompletableFuture<String> f = fcup.get(k);
        if (f == null) {
            Log.w(Receiver.TAG, "FCUP response nobody asked for: " + responseUrl);
            return;
        }
        if (data == null) f.completeExceptionally(new IOException("phone returned no playlist"));
        else f.complete(new String(data, StandardCharsets.UTF_8));
    }

    private static void failPendingFcup() {
        for (CompletableFuture<String> f : fcup.values()) f.completeExceptionally(new IOException("stopped"));
        fcup.clear();
    }

    /** Point phone URIs at our server, keep only H.264 variants, expand YouTube's condensed URLs. */
    static String rewrite(String playlist) {
        if (playlist.contains("#EXT-X-STREAM-INF")) playlist = avcOnly(playlist);
        playlist = expandCondensed(playlist);
        Matcher m = MLHLS.matcher(playlist);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(local(m.group())));
        m.appendTail(out);
        return out.toString();
    }

    /** Drops VP9/AV1 variants: the TV's HLS player wants H.264. */
    private static String avcOnly(String master) {
        String[] lines = master.split("\r?\n");
        StringBuilder out = new StringBuilder();
        boolean anyAvc = master.toLowerCase(Locale.ROOT).contains("avc1");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#EXT-X-STREAM-INF:") && anyAvc) {
                String codecs = attr(line, "CODECS").toLowerCase(Locale.ROOT);
                boolean keep = codecs.isEmpty() || codecs.contains("avc1") || codecs.contains("avc3");
                if (!keep) {
                    i++; // skip its URI line too
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** {@code #YT-EXT-CONDENSED-URL:BASE-URI=…,PARAMS=…,PREFIX=…}: segments are PREFIX + values. */
    private static String expandCondensed(String media) {
        int at = media.indexOf("#YT-EXT-CONDENSED-URL:");
        if (at < 0) return media;
        String line = media.substring(at, media.indexOf('\n', at) < 0 ? media.length() : media.indexOf('\n', at));
        String base = attr(line, "BASE-URI"), params = attr(line, "PARAMS"), prefix = attr(line, "PREFIX");
        if (base.isEmpty() || params.isEmpty() || prefix.isEmpty()) return media;
        String[] names = params.split(",");
        StringBuilder out = new StringBuilder();
        for (String l : media.split("\r?\n")) {
            if (!l.startsWith("#") && l.startsWith(prefix)) {
                String[] values = l.substring(prefix.length()).split("/");
                if (values.length == names.length) {
                    StringBuilder u = new StringBuilder(base);
                    for (int i = 0; i < names.length; i++) u.append('/').append(names[i]).append('/').append(values[i]);
                    l = u.toString();
                }
            }
            out.append(l).append('\n');
        }
        return out.toString();
    }

    private static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[,:])" + Pattern.quote(name) + "=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
        if (!m.find()) return "";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    // ---- The player (main thread)

    static void attach(SurfaceHolder holder, VideoPlayer.SizeListener sizes) {
        surface = holder;
        sizeListener = sizes;
        if (player != null) player.setDisplay(holder);
        else start();
        if (videoW > 0 && sizes != null) sizes.onVideoSize(videoW, videoH);
    }

    static void detach() {
        surface = null;
        sizeListener = null;
        if (player != null) player.setDisplay(null);
    }

    /** TV remote: play/pause. */
    static void togglePause() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            sendState("paused");
        } else {
            player.start();
            sendState("playing");
        }
        update();
    }

    /** TV remote: skip by {@code seconds} (negative = back). */
    static void skip(double seconds) {
        if (player == null || !prepared) return;
        double to = Math.max(0, Math.min(duration > 0 ? duration - 1 : Double.MAX_VALUE, position + seconds));
        player.seekTo((int) (to * 1000));
        position = to;
    }

    static double position() {
        return position;
    }

    static double duration() {
        return duration;
    }

    static boolean playing() {
        return playing;
    }

    private static void start() {
        String u = url;
        if (u == null || surface == null || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = rendering = false;
        duration = -1;
        position = 0;
        videoW = videoH = 0;
        try {
            mp.setDisplay(surface);
            mp.setScreenOnWhilePlaying(true);
            mp.setDataSource(u);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                duration = p.getDuration() > 0 ? p.getDuration() / 1000.0 : -1;
                double seek = startSeconds > 0 ? startSeconds
                        : startFraction > 0 && duration > 0 ? startFraction * duration : 0;
                if (seek > 0) p.seekTo((int) (seek * 1000));
                p.start();
                update();
                sendState("playing");
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                videoW = w;
                videoH = h;
                if (sizeListener != null && w > 0) sizeListener.onVideoSize(w, h);
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendering = true;
                    Hub.castChanged();
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                sendEnded();
                url = null;
                release();
                Hub.castChanged();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(Receiver.TAG, "cast player error " + what + "/" + extra + " for " + u);
                if (p == player) stopFromTv();
                return true;
            });
            mp.prepareAsync();
            main.postDelayed(TICK, TICK_MS);
        } catch (IOException | RuntimeException e) {
            Log.e(Receiver.TAG, "cast start failed for " + u, e);
            stopFromTv();
        }
    }

    private static void update() {
        if (player == null || !prepared) return;
        try {
            playing = player.isPlaying();
            position = player.getCurrentPosition() / 1000.0;
            if (player.getDuration() > 0) duration = player.getDuration() / 1000.0;
        } catch (IllegalStateException ignored) {
        }
    }

    private static void release() {
        main.removeCallbacks(TICK);
        MediaPlayer mp = player;
        player = null;
        prepared = playing = rendering = false;
        if (mp != null) {
            try {
                mp.stop();
            } catch (IllegalStateException ignored) {
            }
            mp.release();
        }
    }

    private static NSDictionary range(double dur) {
        NSDictionary d = new NSDictionary();
        d.put("duration", Math.max(0, dur));
        d.put("start", 0.0);
        return d;
    }
}
