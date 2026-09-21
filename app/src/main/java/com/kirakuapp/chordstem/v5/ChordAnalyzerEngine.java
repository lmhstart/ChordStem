package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线和弦分析引擎 — Android 侧：解码/降采样 + 线程调度。
 * 算法核心在 {@link ChordDsp}（FFT → HPCP 色度 → 低音根音加权 → Viterbi）。
 *
 * <p>与 V4 的行为差异：</p>
 * <ul>
 *   <li>分析失败或音频过短时如实报错，不再返回编造的示例进行（V4 会静默给出 C-G-Am-F）。</li>
 *   <li>降采样前经 4 阶低通，抑制高频混叠对色度的污染。</li>
 *   <li>静音段输出"无和弦"（—）而不是硬贴一个和弦。</li>
 * </ul>
 */
public final class ChordAnalyzerEngine {
    private static final String TAG = "ChordEngine";

    public static final class ChordSegment {
        public final long timeMs;
        public final String chordName;
        public final String defaultChordName;
        public final float confidence;
        public ChordSegment(long t, String n, float c) { this(t, n, n, c); }
        public ChordSegment(long t, String n, String defaultName, float c) {
            timeMs = t; chordName = n; defaultChordName = defaultName == null ? n : defaultName; confidence = c;
        }
    }

    public interface Callback {
        void onProgress(float p);
        void onComplete(List<ChordSegment> chords);
        void onError(String msg);
    }

    public static void analyzeAsync(Context ctx, String source, Callback cb) {
        new Thread(() -> {
            try {
                List<ChordSegment> result = analyze(ctx, source, cb);
                if (result.isEmpty()) throw new IllegalArgumentException("未识别到和弦内容");
                cb.onComplete(result);
            } catch (Exception e) {
                Log.e(TAG, "Chord analysis failed", e);
                cb.onError("和弦分析失败：" + e.getMessage());
            }
        }, "chord-v5").start();
    }

    private static List<ChordSegment> analyze(Context ctx, String source, Callback cb) throws Exception {
        float[] pcm = decodeMono(ctx, source, cb);
        List<ChordDsp.Segment> segs = ChordDsp.analyzePcm(pcm,
                p -> { if (cb != null) cb.onProgress(0.45f + 0.55f * p); });

        List<ChordSegment> out = new ArrayList<>(segs.size());
        for (ChordDsp.Segment s : segs) out.add(new ChordSegment(s.startMs, s.name(), s.confidence));
        return out;
    }

    static float[] decodeForAnalysis(Context ctx, String source) throws Exception {
        return decodeMono(ctx, source, null);
    }


    // ── 解码：任意源 → 22050Hz 单声道 float ──

    private static float[] decodeMono(Context ctx, String source, Callback cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        setSource(ctx, ex, source);
        MediaFormat fmt = null; int ai = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat mf = ex.getTrackFormat(i);
            if (mf.getString(MediaFormat.KEY_MIME).startsWith("audio/")) { ai = i; fmt = mf; break; }
        }
        if (ai < 0 || fmt == null) { ex.release(); throw new IllegalArgumentException("文件中没有音频轨"); }
        ex.selectTrack(ai);

        int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = Math.min(fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), 2);
        long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 240_000_000L;
        int est = (int) (durUs / 1_000_000.0 * sr) + sr;
        float[] buf = new float[est];
        int count = 0;

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        // 降采样前低通（4 阶 Butterworth，截止 0.40×目标率），抑制混叠
        double lpFc = 0.40 * ChordDsp.SR;
        if (lpFc > 0.45 * sr) lpFc = 0.45 * sr;
        Biquad lp1 = new Biquad(sr, lpFc, 0.54119610);   // Butterworth 4 阶两个双二阶节
        Biquad lp2 = new Biquad(sr, lpFc, 1.30656296);

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean ie = false, oe = false;

        while (!oe) {
            if (!ie) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer bb = codec.getInputBuffer(idx);
                    int sz = ex.readSampleData(bb, 0);
                    if (sz < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ie = true; }
                    else { codec.queueInputBuffer(idx, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int idx = codec.dequeueOutputBuffer(info, 10000);
            if (idx >= 0) {
                ByteBuffer bb = codec.getOutputBuffer(idx);
                if (bb != null && info.size > 0) {
                    bb.position(info.offset); bb.limit(info.offset + info.size); bb.order(ByteOrder.LITTLE_ENDIAN);
                    while (bb.remaining() >= ch * 2) {
                        float s = bb.getShort() / 32768f;
                        if (ch > 1) { float r = bb.getShort() / 32768f; s = (s + r) * 0.5f; }
                        s = lp2.process(lp1.process(s));
                        if (count < est) buf[count++] = s;
                    }
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) oe = true;
                if (cb != null && durUs > 0) cb.onProgress(0.4f * Math.min(1f, (float) info.presentationTimeUs / durUs));
            }
        }
        codec.stop(); codec.release(); ex.release();

        if (sr == ChordDsp.SR) {
            float[] out = new float[count];
            System.arraycopy(buf, 0, out, 0, count);
            return out;
        }
        double step = (double) sr / ChordDsp.SR;
        int outN = Math.max(1, (int) Math.ceil((count - 1) / step) + 1);
        float[] out = new float[outN];
        for (int i = 0; i < outN; i++) {
            double pos = i * step;
            int i0 = Math.min(count - 1, (int) pos), i1 = Math.min(count - 1, i0 + 1);
            float frac = (float) (pos - i0);
            out[i] = buf[i0] + frac * (buf[i1] - buf[i0]);
        }
        return out;
    }

    /** RBJ 双二阶低通。 */
    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        Biquad(double fs, double fc, double q) {
            double w0 = 2 * Math.PI * fc / fs;
            double cs = Math.cos(w0), sn = Math.sin(w0);
            double al = sn / (2 * q);
            double a0 = 1 + al;
            b0 = (1 - cs) / 2 / a0; b1 = (1 - cs) / a0; b2 = b0;
            a1 = -2 * cs / a0; a2 = (1 - al) / a0;
        }

        float process(float x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return (float) y;
        }
    }

    private static void setSource(Context ctx, MediaExtractor ex, String src) throws Exception {
        if (src.startsWith("content://") || src.startsWith("file://") || src.startsWith("android.resource://"))
            ex.setDataSource(ctx, Uri.parse(src), null);
        else {
            File f = new File(src);
            if (f.exists()) ex.setDataSource(f.getAbsolutePath());
            else ex.setDataSource(ctx, Uri.parse(src), null);
        }
    }
}
