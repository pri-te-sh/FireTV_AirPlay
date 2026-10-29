package com.pritesh.airplay;

import android.util.Log;

import com.github.serezhka.airplay.protocol.FairPlaySession;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The screen-mirroring TCP stream: 128-byte headers, then either an AES-CTR encrypted H.264
 * access unit (length-prefixed NALs) or the plaintext avcC codec config. Feeds {@link VideoPlayer}.
 */
final class MirrorStream {
    private static final int HEADER = 128;
    private static final int TYPE_VIDEO = 0, TYPE_CODEC = 1;

    private final FairPlaySession fairPlay;
    private final VideoPlayer video;
    private final ServerSocket server;
    private volatile Socket socket;
    private volatile boolean closed;

    MirrorStream(FairPlaySession fairPlay, VideoPlayer video) throws IOException {
        this.fairPlay = fairPlay;
        this.video = video;
        server = new ServerSocket(0);
        Thread t = new Thread(this::run, "airplay-mirror");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void close() {
        closed = true;
        Receiver.closeQuietly(server);
        Receiver.closeQuietly(socket);
        video.endStream();
    }

    private void run() {
        try {
            socket = server.accept();
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(1 << 20);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
            byte[] header = new byte[HEADER];
            while (!closed) {
                in.readFully(header);
                int size = (header[0] & 0xFF) | (header[1] & 0xFF) << 8 | (header[2] & 0xFF) << 16 | (header[3] & 0xFF) << 24;
                int type = header[4] & 0xFF;
                if (size < 0 || size > (16 << 20)) throw new IOException("Bad mirror packet size " + size);
                byte[] payload = new byte[size];
                in.readFully(payload);
                if (type == TYPE_VIDEO) {
                    // Only type 0 is encrypted; decrypting anything else would advance the CTR counter.
                    fairPlay.decryptVideo(payload);
                    int key = toAnnexB(payload);
                    if (key >= 0) video.frame(payload, key == 1);
                } else if (type == TYPE_CODEC) {
                    codecConfig(payload);
                }
                // Type 2 (heartbeat) and 5 (streaming report) carry nothing we need.
            }
        } catch (Exception e) {
            if (!closed) Log.i(Receiver.TAG, "mirror stream ended: " + e);
        } finally {
            Receiver.closeQuietly(socket);
        }
    }

    /** avcC: version, profile, compat, level, 0xFF, 0xE1, sps len, sps, pps count, pps len, pps. */
    private void codecConfig(byte[] p) {
        int spsLen = (p[6] & 0xFF) << 8 | (p[7] & 0xFF);
        int ppsAt = 8 + spsLen + 1;
        int ppsLen = (p[ppsAt] & 0xFF) << 8 | (p[ppsAt + 1] & 0xFF);
        byte[] sps = new byte[4 + spsLen], pps = new byte[4 + ppsLen];
        sps[3] = 1;
        pps[3] = 1;
        System.arraycopy(p, 8, sps, 4, spsLen);
        System.arraycopy(p, ppsAt + 2, pps, 4, ppsLen);
        video.config(sps, pps);
    }

    /**
     * Rewrites 4-byte NAL lengths as Annex-B start codes in place.
     * Returns 1 for a keyframe, 0 for other frames, -1 if the lengths don't add up (bad decrypt).
     */
    static int toAnnexB(byte[] p) {
        int i = 0, key = 0;
        while (i + 4 <= p.length) {
            int n = (p[i] & 0xFF) << 24 | (p[i + 1] & 0xFF) << 16 | (p[i + 2] & 0xFF) << 8 | (p[i + 3] & 0xFF);
            if (n <= 0 || i + 4 + n > p.length) return -1;
            p[i] = 0;
            p[i + 1] = 0;
            p[i + 2] = 0;
            p[i + 3] = 1;
            if ((p[i + 4] & 0x1F) == 5) key = 1;
            i += 4 + n;
        }
        return i == p.length ? key : -1;
    }
}
