package com.pritesh.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import com.beatofthedrum.alacdecoder.Alac;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.nio.ByteBuffer;

/**
 * Decodes AirPlay audio frames to 44.1 kHz stereo PCM and plays them. ALAC (music) is decoded in
 * Java because Fire OS has no ALAC codec; AAC and AAC-ELD (mirroring) go through MediaCodec.
 */
final class AudioPlayer {
    private static final int RATE = 44100;
    /** AudioSpecificConfig for AAC-ELD, 44.1 kHz, stereo, 480 samples per frame. */
    private static final byte[] ELD_CONFIG = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
    /** AudioSpecificConfig for AAC-LC, 44.1 kHz, stereo. */
    private static final byte[] LC_CONFIG = {0x12, 0x10};

    private final AudioTrack track;
    private final Alac alac;
    private final MediaCodec aac;
    private final int[] alacScratch;
    private final short[] pcm;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] pcmBytes = new byte[0];

    AudioPlayer(AudioStreamInfo stream) {
        AudioStreamInfo.CompressionType type = stream.getCompressionType();
        int spf = stream.getSamplesPerFrame() > 0 ? stream.getSamplesPerFrame() : 352;
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(type == AudioStreamInfo.CompressionType.ALAC
                                ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, spf * 4 * 16))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setVolume(Hub.volume());
        track.play();
        Hub.attachTrack(track);

        if (type == AudioStreamInfo.CompressionType.ALAC) {
            alac = new Alac(spf, RATE);
            alacScratch = new int[spf * 2 * 2];
            pcm = new short[spf * 2];
            aac = null;
        } else {
            alac = null;
            alacScratch = null;
            pcm = null;
            aac = aacDecoder(type == AudioStreamInfo.CompressionType.AAC_ELD);
        }
    }

    private static MediaCodec aacDecoder(boolean eld) {
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, eld
                    ? MediaCodecInfo.CodecProfileLevel.AACObjectELD
                    : MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(eld ? ELD_CONFIG : LC_CONFIG));
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.configure(f, null, null, 0);
            c.start();
            return c;
        } catch (Exception e) {
            Log.e(Receiver.TAG, "AAC decoder failed", e);
            return null;
        }
    }

    /** One decrypted frame. Called on the audio receive thread. */
    void play(byte[] frame, int length) {
        if (alac != null) {
            int samples = alac.decode(frame, alacScratch, pcm);
            if (samples > 0) track.write(pcm, 0, samples);
        } else if (aac != null) {
            try {
                int in = aac.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = aac.getInputBuffer(in);
                    b.clear();
                    b.put(frame, 0, length);
                    aac.queueInputBuffer(in, 0, length, 0, 0);
                }
                int out;
                while ((out = aac.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer b = aac.getOutputBuffer(out);
                    if (pcmBytes.length < info.size) pcmBytes = new byte[info.size];
                    b.position(info.offset);
                    b.get(pcmBytes, 0, info.size);
                    aac.releaseOutputBuffer(out, false);
                    track.write(pcmBytes, 0, info.size);
                }
            } catch (RuntimeException e) {
                Log.w(Receiver.TAG, "AAC decode: " + e);
            }
        }
    }

    void release() {
        Hub.detachTrack(track);
        if (aac != null) {
            try {
                aac.stop();
            } catch (RuntimeException ignored) {
            }
            aac.release();
        }
        track.pause();
        track.flush();
        track.release();
    }
}
