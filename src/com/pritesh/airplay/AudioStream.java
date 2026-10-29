package com.pritesh.airplay;

import android.util.Log;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Realtime audio (stream type 96): RTP over UDP, each payload AES-CBC encrypted, ALAC for music
 * and AAC-ELD while mirroring. Packets play as they arrive, in order; late ones are dropped.
 */
final class AudioStream {
    private final FairPlaySession fairPlay;
    private final AudioPlayer player;
    private final DatagramSocket data, control;
    private volatile boolean closed;

    AudioStream(FairPlaySession fairPlay, AudioStreamInfo info) throws IOException {
        this.fairPlay = fairPlay;
        this.player = new AudioPlayer(info);
        data = new DatagramSocket(0);
        data.setReceiveBufferSize(1 << 19);
        control = new DatagramSocket(0);
        start(this::receive, "airplay-audio");
        start(this::drainControl, "airplay-audio-ctl");
        Log.i(Receiver.TAG, "audio " + info);
    }

    int dataPort() {
        return data.getLocalPort();
    }

    int controlPort() {
        return control.getLocalPort();
    }

    void close() {
        closed = true;
        data.close();
        control.close();
    }

    private static void start(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private void receive() {
        byte[] buf = new byte[4096];
        // The ALAC bit reader looks a few bytes past the frame, so frames get slack at the end.
        byte[] payload = new byte[4096 + 16];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        int last = -1;
        try {
            while (!closed) {
                data.receive(p);
                int len = p.getLength();
                if (len <= 12) continue;
                int seq = (buf[2] & 0xFF) << 8 | (buf[3] & 0xFF);
                if (last >= 0) {
                    int ahead = (seq - last) & 0xFFFF;
                    if (ahead == 0 || ahead > 0x8000) continue; // duplicate or late
                }
                last = seq;
                int n = len - 12;
                System.arraycopy(buf, 12, payload, 0, n);
                fairPlay.decryptAudio(payload, n);
                player.play(payload, n);
            }
        } catch (Exception e) {
            if (!closed) Log.w(Receiver.TAG, "audio stream ended: " + e);
        } finally {
            player.release();
        }
    }

    /** Sync and retransmit packets; we play without them, but the port must be live. */
    private void drainControl() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            while (!closed) control.receive(p);
        } catch (IOException ignored) {
        }
    }
}
