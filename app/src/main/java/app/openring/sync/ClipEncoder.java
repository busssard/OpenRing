package app.openring.sync;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import app.openring.core.analysis.SoundDetector;
import app.openring.data.SoundNight;

/**
 * Writes a sleep sound clip as AAC at 24 kbps in an .m4a file (issue #380, phase 1b): about 47 KB for 15 s, measured in the
 * Experiment view. Android's own {@code MediaCodec} and {@code MediaMuxer}, no library. Encoders differ between phones and
 * cannot be tested on the JVM, so any failure throws and {@code data/SoundNight} writes that clip and the rest of the night as
 * WAV instead (Design review of #380).
 */
final class ClipEncoder implements SoundNight.Writer {
    static final String MIME = "audio/mp4a-latm";
    static final int BIT_RATE = 24000;
    private static final long WAIT_US = 10000;

    @Override
    public String ext() {
        return ".m4a";
    }

    @Override
    public void write(File f, short[] pcm, int off, int len) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(MIME, SoundDetector.RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaMuxer muxer = null;
        boolean muxing = false;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(f.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            int at = off, end = off + len;
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long started = System.currentTimeMillis();
            while (!outputDone) {
                // a clip is at most 20 s; an encoder that hangs is a failure (the clip goes to WAV)
                if (System.currentTimeMillis() - started > 20000) throw new IllegalStateException("the encoder did not finish");
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(WAIT_US);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        b.clear();
                        b.order(ByteOrder.LITTLE_ENDIAN);
                        int n = Math.min(end - at, b.remaining() / 2);
                        long us = (at - off) * 1000000L / SoundDetector.RATE;
                        for (int i = 0; i < n; i++) b.putShort(pcm[at + i]);
                        at += n;
                        if (at >= end) {
                            codec.queueInputBuffer(in, 0, n * 2, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n * 2, us, 0);
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, WAIT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (out >= 0) {
                    ByteBuffer o = codec.getOutputBuffer(out);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0 && muxing && o != null) {
                        o.position(info.offset);
                        o.limit(info.offset + info.size);
                        muxer.writeSampleData(track, o, info);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (!muxing) throw new IllegalStateException("the encoder gave no audio");
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // not started
            }
            codec.release();
            if (muxer != null) {
                try {
                    if (muxing) muxer.stop();
                } finally {
                    muxer.release();
                }
            }
        }
    }
}
