package com.beatofthedrum.alacdecoder;

/**
 * Public entry point for raw ALAC frames (no MP4 container), as AirPlay sends them.
 * Added for the AirPlay receiver; the rest of this package is Peter McQuillan's decoder.
 */
public final class Alac {
    private final AlacFile file;

    /** AirPlay's fixed ALAC config: 352 frames, 16-bit, history 40, initial 10, k 14, stereo. */
    public Alac(int framesPerPacket, int sampleRate) {
        file = AlacDecodeUtils.create_alac(16, 2);
        file.setinfo_max_samples_per_frame = framesPerPacket;
        file.setinfo_7a = 0;
        file.setinfo_sample_size = 16;
        file.setinfo_rice_historymult = 40;
        file.setinfo_rice_initialhistory = 10;
        file.setinfo_rice_kmodifier = 14;
        file.setinfo_7f = 2;
        file.setinfo_80 = 255;
        file.setinfo_82 = 0;
        file.setinfo_86 = 0;
        file.setinfo_8a_rate = sampleRate;
    }

    /**
     * Decodes one frame into interleaved 16-bit samples. {@code in} must have a few bytes of
     * slack past the frame (the bit reader looks ahead). Returns the number of shorts written.
     */
    public int decode(byte[] in, int[] scratch, short[] out) {
        int bytes = AlacDecodeUtils.decode_frame(file, in, scratch, 0);
        int samples = Math.min(bytes / 2, out.length);
        for (int i = 0; i < samples; i++) out[i] = (short) scratch[i];
        return samples;
    }
}
