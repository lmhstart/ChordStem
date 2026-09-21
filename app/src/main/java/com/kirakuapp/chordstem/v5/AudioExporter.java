package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 离线音频导出引擎 — 流式 PCM 混合 + 变速/变调。 */
public final class AudioExporter {
    private static final String TAG = "AudioExport";
    private static final int OUT_RATE = 44100;

    public interface Callback {
        void onProgress(float p);
        void onComplete(File file);
        void onError(String msg);
    }

    public static void exportMixAsync(Context ctx, AudioProject proj, Callback cb) {
        new Thread(() -> {
            try { cb.onComplete(export(ctx, proj, cb)); }
            catch (Exception e) { Log.e(TAG, "Export err", e); cb.onError("导出失败: " + e.getMessage()); }
        }, "audio-exporter").start();
    }

    private static File export(Context ctx, AudioProject proj, Callback cb) throws Exception {
        // 筛选活跃轨道
        boolean hasSolo = false;
        for (AudioProject.TrackSpec t : proj.tracks) if (t.solo) { hasSolo = true; break; }
        List<AudioProject.TrackSpec> active = new ArrayList<>();
        for (AudioProject.TrackSpec t : proj.tracks)
            if (!t.mute && (!hasSolo || t.solo) && t.volume > 0) active.add(t);
        if (active.isEmpty()) throw new IllegalArgumentException("无活跃音轨");

        float speed = Math.max(0.25f, Math.min(proj.speed, 2.0f));
        float pitchR = (float) Math.pow(2.0, proj.pitchSemitones / 12.0);
        float ratio = speed * pitchR;
        float mg = proj.masterVolume / 100f;

        // 解码各轨道到临时文件
        cb.onProgress(0.05f);
        File tmpDir = new File(ctx.getCacheDir(), "exp_" + System.currentTimeMillis());
        tmpDir.mkdirs();
        List<Decoded> dec = new ArrayList<>();
        long maxFrames = 0;

        for (int i = 0; i < active.size(); i++) {
            File f = new File(tmpDir, "t" + i + ".f32");
            long frames = decodeFloat(ctx, active.get(i), f);
            dec.add(new Decoded(active.get(i), f, frames));
            if (frames > maxFrames) maxFrames = frames;
            cb.onProgress(0.05f + 0.20f * (i + 1) / active.size());
        }

        // 创建输出
        File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ChordStem");
        outDir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File outFile = new File(outDir, proj.title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-_]", "_") + "_" + ts + ".wav");

        FileOutputStream fos = new FileOutputStream(outFile);
        fos.write(new byte[44]);

        // 打开输入流
        List<FileInputStream> ins = new ArrayList<>();
        for (Decoded d : dec) ins.add(new FileInputStream(d.file));

        long outFrames = Math.round(maxFrames / ratio);
        long outPos = 0, inPos = 0;
        int CHUNK = 8192;
        byte[] ob = new byte[CHUNK * 4];
        int oo = 0;
        long written = 0;

        try {
            while (outPos < outFrames && inPos < maxFrames) {
                int batch = (int) Math.min(CHUNK, maxFrames - inPos);
                float[][] data = new float[dec.size()][];
                for (int t = 0; t < dec.size(); t++) {
                    long rem = Math.max(0, dec.get(t).totalFrames - inPos);
                    int rd = (int) Math.min(batch, rem);
                    data[t] = new float[batch * 2];
                    if (rd > 0) {
                        byte[] rb = new byte[rd * 8];
                        int r = ins.get(t).read(rb);
                        if (r > 0) ByteBuffer.wrap(rb, 0, r).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data[t], 0, r / 4);
                    }
                }

                double phase = 0;
                while (phase < batch && outPos < outFrames) {
                    int i0 = (int) phase;
                    double frac = phase - i0;
                    float sumL = 0, sumR = 0;
                    for (int t = 0; t < dec.size(); t++) {
                        int base = i0 * 2;
                        if (base + 3 >= data[t].length) continue;
                        float sL = (float) (data[t][base] + frac * (data[t][base + 2] - data[t][base]));
                        float sR = (float) (data[t][base + 1] + frac * (data[t][base + 3] - data[t][base + 1]));
                        AudioProject.TrackSpec sp = dec.get(t).spec;
                        float vg = sp.volume / 100f;
                        double pa = sp.pan * Math.PI / 2;
                        float pL = (float) Math.cos(Math.max(0, pa));
                        float pR = (float) Math.cos(Math.max(0, -pa));
                        sumL += sL * vg * pL;
                        sumR += sR * vg * pR;
                    }
                    short oL = clamp(softLimit(sumL * mg));
                    short oR = clamp(softLimit(sumR * mg));
                    ob[oo++] = (byte) (oL & 0xFF);
                    ob[oo++] = (byte) ((oL >> 8) & 0xFF);
                    ob[oo++] = (byte) (oR & 0xFF);
                    ob[oo++] = (byte) ((oR >> 8) & 0xFF);
                    if (oo >= ob.length) { fos.write(ob, 0, oo); written += oo; oo = 0; }
                    outPos++;
                    phase += ratio;
                }
                inPos += batch;
                if (outPos % 32768 == 0) cb.onProgress(0.25f + 0.70f * outPos / (float) outFrames);
            }
            if (oo > 0) { fos.write(ob, 0, oo); written += oo; }
        } finally {
            for (FileInputStream fi : ins) try { fi.close(); } catch (Exception ignored) {}
            fos.close();
        }

        writeHeader(outFile, (int) written, OUT_RATE, 2);

        for (Decoded d : dec) d.file.delete();
        tmpDir.delete();
        cb.onProgress(1f);
        return outFile;
    }

    private static long decodeFloat(Context ctx, AudioProject.TrackSpec t, File out) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        if (t.rawResId != 0) {
            android.content.res.AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(t.rawResId);
            if (afd != null) { ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength()); afd.close(); }
        } else if (t.uri != null && !t.uri.isEmpty()) {
            File f = new File(t.uri);
            if (f.exists()) ex.setDataSource(f.getAbsolutePath());
            else ex.setDataSource(ctx, Uri.parse(t.uri), null);
        } else { return 0; }

        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat mf = ex.getTrackFormat(i);
            if (mf.getString(MediaFormat.KEY_MIME).startsWith("audio/")) { ex.selectTrack(i); fmt = mf; break; }
        }
        if (fmt == null) { ex.release(); return 0; }

        int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = Math.min(fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), 2);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        DataOutputStream dos = new DataOutputStream(new FileOutputStream(out));
        long frames = 0;
        double ratio = (double) sr / OUT_RATE, phase = 0;
        float pL = 0, pR = 0;
        boolean hp = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean ie = false, oe = false;
        byte[] pair = new byte[8];

        while (!oe) {
            if (!ie) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(idx);
                    int sz = ex.readSampleData(buf, 0);
                    if (sz < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ie = true; }
                    else { codec.queueInputBuffer(idx, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int idx = codec.dequeueOutputBuffer(info, 10000);
            if (idx >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size); buf.order(ByteOrder.LITTLE_ENDIAN);
                    while (buf.remaining() >= ch * 2) {
                        float sL = buf.getShort() / 32768f;
                        float sR = ch > 1 ? buf.getShort() / 32768f : sL;
                        if (!hp) { pL = sL; pR = sR; hp = true; }
                        while (phase < 1.0) {
                            float oL = (float) (pL + phase * (sL - pL));
                            float oR = (float) (pR + phase * (sR - pR));
                            ByteBuffer.wrap(pair).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, oL).putFloat(4, oR);
                            dos.write(pair); frames++;
                            phase += ratio;
                        }
                        phase -= 1.0; pL = sL; pR = sR;
                    }
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) oe = true;
            }
        }
        dos.close(); codec.stop(); codec.release(); ex.release();
        return frames;
    }

    private static float softLimit(float x) { float a = Math.abs(x); if (a <= 0.85f) return x; float s = Math.signum(x); float e = a - 0.85f; return s * (0.85f + e / (1f + e * 2.5f)); }
    private static short clamp(float v) { int iv = Math.round(v * 32767f); return (short) Math.max(-32768, Math.min(32767, iv)); }

    private static void writeHeader(File f, int dataBytes, int rate, int ch) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
        raf.seek(0);
        raf.writeBytes("RIFF"); raf.writeInt(Integer.reverseBytes(dataBytes + 36));
        raf.writeBytes("WAVEfmt ");
        raf.writeInt(Integer.reverseBytes(16)); raf.writeShort(Short.reverseBytes((short) 1));
        raf.writeShort(Short.reverseBytes((short) ch)); raf.writeInt(Integer.reverseBytes(rate));
        raf.writeInt(Integer.reverseBytes(rate * ch * 2)); raf.writeShort(Short.reverseBytes((short) (ch * 2)));
        raf.writeShort(Short.reverseBytes((short) 16)); raf.writeBytes("data"); raf.writeInt(Integer.reverseBytes(dataBytes));
        raf.close();
    }

    private static class Decoded {
        final AudioProject.TrackSpec spec;
        final File file;
        final long totalFrames;
        Decoded(AudioProject.TrackSpec s, File f, long tf) { spec = s; file = f; totalFrames = tf; }
    }
}
