package dev.sts.broadcast;

/** Standard mono IMA ADPCM: at most 24,003 bytes for the existing three-second utterance. */
public final class AdpcmCodec {
    private static final int[] INDEX = {-1,-1,-1,-1,2,4,6,8};
    private static final int[] STEP = {7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
    private AdpcmCodec() {}
    public static VoiceClip encode(short[] pcm) {
        if (pcm.length < 1 || pcm.length > VoiceClip.MAX_SAMPLES) throw new IllegalArgumentException("Clip exceeds three seconds");
        byte[] data = new byte[3 + pcm.length / 2];
        int predictor = pcm[0], index = 0;
        data[0] = (byte)predictor; data[1] = (byte)(predictor >> 8);
        for (int i = 1; i < pcm.length; i++) {
            int difference = pcm[i] - predictor, nibble = difference < 0 ? 8 : 0;
            int magnitude = Math.abs(difference), step = STEP[index], delta = step >> 3;
            if (magnitude >= step) { nibble |= 4; magnitude -= step; delta += step; }
            if (magnitude >= (step >> 1)) { nibble |= 2; magnitude -= step >> 1; delta += step >> 1; }
            if (magnitude >= (step >> 2)) { nibble |= 1; delta += step >> 2; }
            predictor = Math.clamp(predictor + ((nibble & 8) != 0 ? -delta : delta), -32768, 32767);
            index = Math.clamp(index + INDEX[nibble & 7], 0, 88);
            int offset = 3 + (i - 1) / 2;
            data[offset] |= (byte)(nibble << (((i - 1) & 1) * 4));
        }
        return new VoiceClip(pcm.length, data);
    }
    public static short[] decode(VoiceClip clip) {
        byte[] data = clip.encoded();
        short[] pcm = new short[clip.samples()];
        int predictor = (short)((data[0] & 255) | data[1] << 8), index = data[2] & 255;
        pcm[0] = (short)predictor;
        for (int i = 1; i < pcm.length; i++) {
            int nibble = (data[3 + (i - 1) / 2] >> (((i - 1) & 1) * 4)) & 15;
            int step = STEP[index], delta = step >> 3;
            if ((nibble & 4) != 0) delta += step;
            if ((nibble & 2) != 0) delta += step >> 1;
            if ((nibble & 1) != 0) delta += step >> 2;
            predictor = Math.clamp(predictor + ((nibble & 8) != 0 ? -delta : delta), -32768, 32767);
            index = Math.clamp(index + INDEX[nibble & 7], 0, 88);
            pcm[i] = (short)predictor;
        }
        return pcm;
    }
}
