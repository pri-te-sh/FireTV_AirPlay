package com.pritesh.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Hardware H.264 decode straight to the mirror screen's Surface, rendering each frame as soon as
 * it's out (lowest latency; mirroring has no audio/video sync to honour).
 * <p>
 * iOS sends a keyframe only when a stream starts or the picture changes shape, so frames that
 * arrive before the screen has a Surface are kept and replayed, not dropped.
 */
final class VideoPlayer {
    interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final int MAX_PENDING = 240;

    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private byte[] sps, pps;
    private Surface surface;
    private volatile MediaCodec codec;
    private Thread drainer;
    private boolean waitForKeyframe;
    private long frames;
    private volatile SizeListener sizeListener;

    void setSizeListener(SizeListener l) {
        sizeListener = l;
    }

    synchronized void setSurface(Surface s) {
        if (s == surface) return;
        stopCodec();
        surface = s;
        startCodec();
    }

    /** New SPS/PPS: a new stream or the phone rotated. */
    synchronized void config(byte[] sps, byte[] pps) {
        if (Arrays.equals(sps, this.sps) && Arrays.equals(pps, this.pps) && codec != null) return;
        this.sps = sps;
        this.pps = pps;
        pending.clear();
        waitForKeyframe = false;
        stopCodec();
        startCodec();
    }

    synchronized void frame(byte[] annexB, boolean keyframe) {
        if (waitForKeyframe) {
            if (!keyframe) return;
            waitForKeyframe = false;
        }
        if (codec == null) {
            if (pending.size() >= MAX_PENDING) {
                pending.clear();
                waitForKeyframe = !keyframe;
                if (!keyframe) return;
            }
            pending.add(annexB);
            return;
        }
        queue(annexB);
    }

    /** The sender stopped mirroring. */
    synchronized void endStream() {
        stopCodec();
        pending.clear();
        sps = pps = null;
    }

    private void startCodec() {
        if (surface == null || sps == null || !surface.isValid()) return;
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, Receiver.WIDTH, Receiver.HEIGHT);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 << 20);
            f.setInteger("low-latency", 1);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            c.configure(f, surface, null, 0);
            c.start();
            codec = c;
            frames = 0;
            drainer = new Thread(() -> drain(c), "airplay-video-out");
            drainer.setPriority(Thread.MAX_PRIORITY);
            drainer.start();
            while (!pending.isEmpty() && codec != null) queue(pending.poll());
        } catch (Exception e) {
            Log.e(Receiver.TAG, "video decoder start failed", e);
            codec = null;
        }
    }

    private void stopCodec() {
        MediaCodec c = codec;
        Thread t = drainer;
        codec = null;
        drainer = null;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
        }
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        c.release();
        // After a restart the decoder needs a keyframe; until iOS sends one, show nothing.
        if (frames > 0) waitForKeyframe = true;
    }

    private void queue(byte[] data) {
        MediaCodec c = codec;
        try {
            int i = c.dequeueInputBuffer(500_000);
            if (i < 0) {
                Log.w(Receiver.TAG, "video decoder busy, frame dropped");
                return;
            }
            ByteBuffer b = c.getInputBuffer(i);
            b.clear();
            b.put(data);
            c.queueInputBuffer(i, 0, data.length, System.nanoTime() / 1000, 0);
            frames++;
        } catch (RuntimeException e) {
            Log.w(Receiver.TAG, "video queue: " + e);
        }
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (codec == c) {
                int i = c.dequeueOutputBuffer(info, 20_000);
                if (i >= 0) {
                    c.releaseOutputBuffer(i, true);
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportSize(c.getOutputFormat());
                }
            }
        } catch (RuntimeException e) {
            if (codec == c) Log.w(Receiver.TAG, "video output stopped", e);
        }
    }

    private void reportSize(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        }
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        SizeListener l = sizeListener;
        if (l != null) l.onVideoSize(w, h);
    }
}
