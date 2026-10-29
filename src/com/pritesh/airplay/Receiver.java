package com.pritesh.airplay;

import android.util.Log;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AirPlay receiver: the RTSP control connection (pairing, FairPlay, SETUP/TEARDOWN, volume,
 * now-playing metadata) and the media streams each session opens. One phone at a time: a new
 * mirroring or audio session takes over from the previous one, like an Apple TV.
 */
final class Receiver {
    static final String TAG = "AirPlay";
    static final String SOURCE_VERSION = "220.68";
    static final String MODEL = "AppleTV3,2";
    static final int WIDTH = 1920, HEIGHT = 1080, FPS = 60;

    /** Video from apps, screen mirroring, realtime audio (FairPlay v3, legacy pairing), metadata. */
    static final long FEATURES = bits(
            0, 2, 3, 4,       // video: /play, FairPlay, volume, HLS
            33,               // video play queue
            7,                // screen mirroring
            9,                // audio
            12,               // FairPlay SAP v2.5
            14,               // MFi-less auth (FairPlay)
            15, 16, 17,       // metadata: artwork, progress, text
            19, 20, 21,       // audio formats (ALAC, AAC, AAC-ELD)
            27,               // legacy pairing (pair-setup / pair-verify)
            30);              // RAOP

    private final Identity identity;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private volatile boolean running;

    Receiver(Identity identity) {
        this.identity = identity;
    }

    static String featuresTxt() {
        return String.format(Locale.ROOT, "0x%X,0x%X", (int) FEATURES, (int) (FEATURES >>> 32));
    }

    /** Starts listening (port 7000 like an Apple TV, any free port if taken) and returns the port. */
    int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(7000));
        } catch (IOException taken) {
            server.bind(new InetSocketAddress(0));
        }
        running = true;
        Thread t = new Thread(this::acceptLoop, "airplay-rtsp");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "Listening on " + server.getLocalPort());
        return server.getLocalPort();
    }

    int port() {
        return server == null ? 0 : server.getLocalPort();
    }

    void stop() {
        running = false;
        closeQuietly(server);
        disconnectAll();
    }

    /** Ends every session; the sender sees mirroring/playback stop. */
    void disconnectAll() {
        for (Connection c : connections) c.close();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                Connection c = new Connection(s);
                connections.add(c);
                Thread t = new Thread(c, "airplay-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e);
            }
        }
    }

    /** Another sender is taking over: drop sessions that are playing something. */
    private void preempt(Connection keep) {
        for (Connection c : connections) {
            if (c != keep && c.streaming()) c.close();
        }
    }

    /** One control connection = one sender session. */
    private final class Connection implements Runnable {
        private final Socket socket;
        private final FairPlaySession fairPlay;
        private MirrorStream mirror;
        private AudioStream audio;
        private String sender;
        private volatile boolean closed;
        /** After {@code POST /reverse} this connection carries our events to the phone. */
        private volatile OutputStream reverse;

        Connection(Socket socket) {
            this.socket = socket;
            this.fairPlay = new FairPlaySession(identity.pairing());
        }

        boolean streaming() {
            return mirror != null || audio != null;
        }

        @Override
        public void run() {
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                Rtsp.Request req;
                while (!closed && (req = Rtsp.read(in)) != null) {
                    if (reverse != null) continue; // the phone answering one of our events
                    Rtsp.Response res = new Rtsp.Response(req);
                    try {
                        handle(req, res);
                    } catch (Exception | LinkageError e) {
                        Log.e(TAG, req + " failed", e);
                        res.status(500, "Internal Server Error");
                    }
                    Rtsp.write(out, res);
                    if (res.status == 101) {
                        reverse = out;
                        Cast.setEventChannel(out, req.header("x-apple-session-id"));
                    }
                }
            } catch (IOException e) {
                if (!closed) Log.i(TAG, "connection ended: " + e.getMessage());
            } finally {
                close();
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            connections.remove(this);
            if (reverse != null) Cast.eventChannelClosed(reverse);
            stopMirror();
            stopAudio();
            closeQuietly(socket);
        }

        private void handle(Rtsp.Request req, Rtsp.Response res) throws Exception {
            String m = req.method, path = req.path();
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "<- " + req);
            if (req.protocol.startsWith("HTTP")) {
                video(req, res);
            } else if ("GET".equals(m) && "/info".equals(path)) {
                res.body(info(), "application/x-apple-binary-plist");
            } else if ("POST".equals(m) && "/pair-setup".equals(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                fairPlay.pairSetup(out);
                res.body(out.toByteArray(), "application/octet-stream");
            } else if ("POST".equals(m) && "/pair-verify".equals(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                fairPlay.pairVerify(new ByteArrayInputStream(req.body), out);
                res.body(out.toByteArray(), "application/octet-stream");
            } else if ("POST".equals(m) && "/fp-setup".equals(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                fairPlay.fairPlaySetup(new ByteArrayInputStream(req.body), out);
                res.body(out.toByteArray(), "application/octet-stream");
            } else if ("SETUP".equals(m)) {
                setup(req, res);
            } else if ("RECORD".equals(m)) {
                res.header("Audio-Latency", "11025").header("Audio-Jack-Status", "connected; type=analog");
            } else if ("GET_PARAMETER".equals(m)) {
                String text = String.format(Locale.ROOT, "volume: %.6f\r\n", Hub.volumeDb());
                res.body(text.getBytes(StandardCharsets.US_ASCII), "text/parameters");
            } else if ("SET_PARAMETER".equals(m)) {
                setParameter(req);
                res.header("Audio-Jack-Status", "connected; type=analog");
            } else if ("TEARDOWN".equals(m)) {
                teardown(req);
            } else if ("OPTIONS".equals(m)) {
                res.header("Public", "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
            } else if ("FLUSH".equals(m) || "ANNOUNCE".equals(m) || "POST".equals(m)) {
                // /feedback (keep-alive), /audioMode, /command, FLUSH: acknowledge.
            } else {
                Log.w(TAG, "Unhandled " + req);
                res.status(404, "Not Found");
            }
        }

        /** The HTTP side of port 7000: video from apps, and playlists for our own player. */
        private void video(Rtsp.Request req, Rtsp.Response res) throws Exception {
            String m = req.method, path = req.path();
            if (!"/playback-info".equals(path)) Log.i(TAG, "video " + m + " " + req.uri);
            if ("GET".equals(m) && "/server-info".equals(path)) {
                NSDictionary r = new NSDictionary();
                // A compact, older profile here keeps apps on the plain /play path.
                r.put("features", (long) 0x77);
                r.put("protovers", 1.0);
                r.put("srcvers", 101.28);
                r.put("deviceid", identity.deviceId());
                r.put("model", MODEL);
                res.body(r.toXMLPropertyList().getBytes(StandardCharsets.UTF_8), "text/x-apple-plist+xml");
            } else if ("POST".equals(m) && "/reverse".equals(path)) {
                res.status(101, "Switching Protocols")
                        .header("Upgrade", req.header("upgrade") != null ? req.header("upgrade") : "PTTH/1.0")
                        .header("Connection", "Upgrade");
            } else if ("POST".equals(m) && "/play".equals(path)) {
                play(req);
            } else if ("GET".equals(m) && "/playback-info".equals(path)) {
                res.body(Cast.playbackInfo(), "text/x-apple-plist+xml");
            } else if ("POST".equals(m) && "/rate".equals(path)) {
                Cast.rate(number(query(req.uri, "value"), 1));
            } else if ("POST".equals(m) && "/scrub".equals(path)) {
                Cast.scrub(number(query(req.uri, "position"), 0));
            } else if ("GET".equals(m) && "/scrub".equals(path)) {
                res.body(Cast.scrubInfo().getBytes(StandardCharsets.US_ASCII), "text/parameters");
            } else if ("POST".equals(m) && path.startsWith("/stop")) {
                Cast.stop();
            } else if ("POST".equals(m) && "/action".equals(path)) {
                action(req);
            } else if ("POST".equals(m) && "/getProperty".equals(path)) {
                res.body(new NSDictionary().toXMLPropertyList().getBytes(StandardCharsets.UTF_8), "text/x-apple-plist+xml");
            } else if ("GET".equals(m) && "/fcup".equals(path)) {
                String body = Cast.fetchFromPhone(java.net.URLDecoder.decode(query(req.uri, "u"), "UTF-8"));
                res.body(body.getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
            } else if ("POST".equals(m) && path.startsWith("/fp-setup")) {
                // FairPlay v1 over HTTP (some video apps): acknowledge without running v3 on it.
            } else if ("PUT".equals(m) || "POST".equals(m)) {
                // /setProperty, /photo …: acknowledge.
            } else {
                res.status(404, "Not Found");
            }
        }

        private void play(Rtsp.Request req) throws Exception {
            String url = null, uuid = null;
            double startSec = 0, fraction = 0;
            if (req.body.length >= 8 && new String(req.body, 0, 6, StandardCharsets.US_ASCII).equals("bplist")) {
                NSDictionary p = (NSDictionary) BinaryPropertyListParser.parse(req.body);
                url = string(p.get("Content-Location"));
                uuid = string(p.get("uuid"));
                startSec = number(string(p.get("Start-Position-Seconds")), 0);
                fraction = number(string(p.get("Start-Position")), 0);
                if (sender == null) sender = string(p.get("clientProcName"));
            } else {
                // Older senders: "Content-Location: …\nStart-Position: 0.25".
                for (String line : new String(req.body, StandardCharsets.UTF_8).split("\r?\n")) {
                    int c = line.indexOf(':');
                    if (c < 0) continue;
                    String k = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
                    if ("Content-Location".equalsIgnoreCase(k)) url = v;
                    else if ("Start-Position".equalsIgnoreCase(k)) fraction = number(v, 0);
                }
            }
            if (url == null || url.isEmpty()) throw new IllegalArgumentException("/play without Content-Location");
            Hub.castSender(sender);
            Cast.play(url, startSec, fraction, req.header("x-apple-session-id"), uuid, port());
        }

        private void action(Rtsp.Request req) throws Exception {
            NSDictionary a;
            try {
                a = (NSDictionary) BinaryPropertyListParser.parse(req.body);
            } catch (Exception notBinary) {
                a = (NSDictionary) com.dd.plist.PropertyListParser.parse(req.body);
            }
            String type = string(a.get("type"));
            NSObject paramsObj = a.get("params");
            NSDictionary params = paramsObj instanceof NSDictionary ? (NSDictionary) paramsObj : null;
            if ("unhandledURLResponse".equals(type) && params != null) {
                NSObject data = params.get("FCUP_Response_Data");
                Cast.fcupResponse(string(params.get("FCUP_Response_URL")),
                        data instanceof NSData ? ((NSData) data).bytes() : null);
            } else if ("playlistRemove".equals(type)) {
                Cast.stop();
            } else if ("playlistInsert".equals(type) && params != null && params.get("item") instanceof NSDictionary) {
                NSDictionary item = (NSDictionary) params.get("item");
                String url = string(item.get("Content-Location"));
                if (url != null) {
                    Cast.play(url, number(string(item.get("Start-Position-Seconds")), 0), 0,
                            req.header("x-apple-session-id"), string(item.get("uuid")), port());
                }
            }
        }

        private void setup(Rtsp.Request req, Rtsp.Response res) throws Exception {
            NSDictionary plist = (NSDictionary) BinaryPropertyListParser.parse(req.body);
            NSObject name = plist.get("name");
            if (name != null) sender = name.toJavaObject().toString();
            MediaStreamInfo info = fairPlay.rtspSetup(new ByteArrayInputStream(req.body)).orElse(null);
            if (info == null) {
                // First SETUP: keys and the sender's identity. Nothing to open yet.
                return;
            }
            NSDictionary reply = new NSDictionary();
            NSDictionary stream = new NSDictionary();
            if (info.getStreamType() == MediaStreamInfo.StreamType.VIDEO) {
                preempt(this);
                stopMirror();
                mirror = new MirrorStream(fairPlay, Hub.video);
                stream.put("dataPort", mirror.port());
                stream.put("type", 110);
                reply.put("eventPort", port());
                reply.put("timingPort", 0);
                Hub.mirrorStarted(sender);
            } else {
                AudioStreamInfo a = (AudioStreamInfo) info;
                preempt(this);
                stopAudio();
                audio = new AudioStream(fairPlay, a);
                stream.put("dataPort", audio.dataPort());
                stream.put("controlPort", audio.controlPort());
                stream.put("type", 96);
                Hub.audioStarted(sender, mirror != null);
            }
            reply.put("streams", new NSArray(stream));
            res.body(BinaryPropertyListWriter.writeToArray(reply), "application/x-apple-binary-plist");
        }

        private void teardown(Rtsp.Request req) throws Exception {
            MediaStreamInfo info = req.body.length == 0 ? null
                    : fairPlay.rtspTeardown(new ByteArrayInputStream(req.body)).orElse(null);
            if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.VIDEO) stopMirror();
            if (info == null || info.getStreamType() == MediaStreamInfo.StreamType.AUDIO) stopAudio();
        }

        private void setParameter(Rtsp.Request req) {
            String type = req.contentType();
            if (type.startsWith("text/parameters")) {
                for (String line : new String(req.body, StandardCharsets.US_ASCII).split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                    if ("volume".equalsIgnoreCase(key)) {
                        try {
                            Hub.setVolumeDb(Double.parseDouble(value));
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("progress".equalsIgnoreCase(key)) {
                        Hub.setProgress(value);
                    }
                }
            } else if (type.startsWith("image/")) {
                Hub.setArtwork(req.body);
            } else if (type.startsWith("application/x-dmap-tagged")) {
                Hub.setMetadata(Dmap.parse(req.body));
            }
        }

        private void stopMirror() {
            if (mirror == null) return;
            mirror.close();
            mirror = null;
            Hub.mirrorStopped();
        }

        private void stopAudio() {
            if (audio == null) return;
            audio.close();
            audio = null;
            Hub.audioStopped();
        }
    }

    /** {@code GET /info}: what this receiver is and the display it offers. */
    private byte[] info() throws IOException {
        NSDictionary display = new NSDictionary();
        display.put("features", 14);
        display.put("height", HEIGHT);
        display.put("heightPixels", HEIGHT);
        display.put("heightPhysical", false);
        display.put("width", WIDTH);
        display.put("widthPixels", WIDTH);
        display.put("widthPhysical", false);
        display.put("maxFPS", FPS);
        display.put("refreshRate", 60);
        display.put("overscanned", false);
        display.put("rotation", false);
        display.put("uuid", identity.displayUuid());

        NSArray formats = new NSArray(audioDescriptor(100), audioDescriptor(101));
        NSArray latencies = new NSArray(latency(100), latency(101));

        NSDictionary r = new NSDictionary();
        r.put("audioFormats", formats);
        r.put("audioLatencies", latencies);
        r.put("displays", new NSArray(display));
        r.put("features", FEATURES);
        r.put("keepAliveSendStatsAsBody", 1);
        r.put("model", MODEL);
        r.put("name", identity.name());
        r.put("deviceID", identity.deviceId());
        r.put("macAddress", identity.deviceId());
        r.put("pi", identity.pairingUuid());
        r.put("sourceVersion", SOURCE_VERSION);
        r.put("statusFlags", 0x44);
        r.put("vv", 2);
        r.put("pk", new NSData(identity.pairing().publicKey()));
        return BinaryPropertyListWriter.writeToArray(r);
    }

    private static NSDictionary audioDescriptor(int type) {
        NSDictionary d = new NSDictionary();
        d.put("audioInputFormats", 0x3FFFFFC);
        d.put("audioOutputFormats", 0x3FFFFFC);
        d.put("type", type);
        return d;
    }

    private static NSDictionary latency(int type) {
        NSDictionary d = new NSDictionary();
        d.put("audioType", "default");
        d.put("inputLatencyMicros", false);
        d.put("type", type);
        return d;
    }

    private static String string(NSObject o) {
        return o == null ? null : o.toJavaObject().toString();
    }

    private static double number(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String query(String uri, String name) {
        int q = uri.indexOf('?');
        if (q < 0) return null;
        for (String kv : uri.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    private static long bits(int... positions) {
        long mask = 0;
        for (int p : positions) mask |= 1L << p;
        return mask;
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
