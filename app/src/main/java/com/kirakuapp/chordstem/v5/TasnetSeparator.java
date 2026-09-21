package com.kirakuapp.chordstem.v5;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Conv-TasNet 分轨引擎 — ONNX Runtime + overlap-add。
 *
 * <h3>特点</h3>
 * <ul>
 *   <li>纯时域 1D 卷积，无需 STFT/iSTFT</li>
 *   <li>模型 ~20MB (fp16 ONNX)，推理速度快</li>
 *   <li>25% overlap + 三角窗 fade，无缝拼接</li>
 *   <li>流式 flush，内存有界</li>
 * </ul>
 */
public final class TasnetSeparator {
    private static final String TAG = "TasnetSep";
    private static final String MODEL_FILE = "tasnet.onnx";
    private static final int SAMPLE_RATE = 44100;

    // Conv-TasNet demucs v2 输出顺序: [drums, bass, other, vocals]
    private static final String[] SOURCE_ORDER = {"drums", "bass", "other", "vocals"};
    // 文件命名和 UI 标签 (按用户期望: 人声/鼓/贝斯/其他)
    public static final String[] STEM_NAMES = {"vocals", "drums", "bass", "other"};
    public static final String[] STEM_LABELS = {"人声 (Vocals)", "鼓 (Drums)", "贝斯 (Bass)", "其他 (Other)"};
    // 模型输出索引 → 文件索引的映射
    // SOURCE_ORDER: [0]=drums, [1]=bass, [2]=other, [3]=vocals
    // STEM_NAMES: [0]=vocals, [1]=drums, [2]=bass, [3]=other
    private static final int[] SOURCE_TO_FILE = {1, 2, 3, 0}; // drums→1, bass→2, other→3, vocals→0
    // 每个 stem 的输出增益 (补偿模型训练的响度差异)
    private static final float[] STEM_GAIN = {1.1f, 1.4f, 1.0f, 1.0f}; // 鼓, 贝斯, 其他, 人声

    public interface Callback {
        void onProgress(String stage, float p);
        void onComplete(File[] files);
        void onError(String msg);
    }

    public static void separateAsync(Context ctx, String src, File out, Callback cb) {
        new Thread(() -> {
            try { cb.onComplete(separate(ctx, src, out, cb)); }
            catch (OutOfMemoryError e) { Log.e(TAG, "OOM", e); cb.onError("内存不足"); }
            catch (Exception e) { Log.e(TAG, "Err", e); cb.onError("分轨失败: " + e.getMessage()); }
        }, "tasnet").start();
    }

    static File[] separate(Context ctx, String src, File outDir, Callback cb) throws Exception {
        // 1. 加载 ONNX 模型
        long t0 = System.currentTimeMillis();
        cb.onProgress("加载模型...", 0f);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        opts.setIntraOpNumThreads(4);
        try { opts.setCPUArenaAllocator(false); } catch (Exception ignored) {}

        byte[] modelBytes = loadModelFromAssets(ctx);
        OrtSession session = env.createSession(modelBytes, opts);

        // 读取模型参数
        String inputName = session.getInputNames().iterator().next();
        ai.onnxruntime.TensorInfo ti = (ai.onnxruntime.TensorInfo) session.getInputInfo().get(inputName).getInfo();
        long[] inputShape = ti.getShape();
        int segSamples = inputShape.length >= 3 && inputShape[2] > 0 ? (int) inputShape[2] : 352800;
        int nCh = inputShape.length >= 2 && inputShape[1] > 0 ? (int) inputShape[1] : 2;
        final int N_STEMS = 4; // Conv-TasNet 输出 4 stems 合并在一个张量里

        cb.onProgress("模型就绪(" + (modelBytes.length/1024/1024) + "MB, " +
                segSamples + "样点)", 0.03f);

        // 2. 解码音频
        cb.onProgress("解码...", 0.05f);
        float[][] stereo = decodeStereo(ctx, src);
        int totalSamples = stereo[0].length;
        cb.onProgress("解码完成(" + (totalSamples/SAMPLE_RATE) + "s)", 0.12f);

        // 3. 初始化 Overlap-Add 引擎
        OverlapAddEngine ola = new OverlapAddEngine(segSamples, N_STEMS, nCh);
        int nChunks = ola.getNumChunks(totalSamples);

        // 4. 准备输出文件
        outDir.mkdirs();
        DataOutputStream[] outs = new DataOutputStream[N_STEMS];
        File[] tmpFiles = new File[N_STEMS];
        for (int s = 0; s < N_STEMS; s++) {
            tmpFiles[s] = new File(outDir, STEM_NAMES[s] + ".raw");
            outs[s] = new DataOutputStream(new FileOutputStream(tmpFiles[s]));
        }

        // 5. 分块推理 + overlap-add
        long totalWritten = 0;
        long chunkStartTime = System.currentTimeMillis();

        for (int chunk = 0; chunk < nChunks; chunk++) {
            long chunkT0 = System.currentTimeMillis();
            int offset = ola.getOffset(chunk);
            int chunkLen = Math.min(segSamples, totalSamples - offset);

            // 构建输入 [1, nCh, segSamples] — 零填充
            float[][][] input = new float[1][nCh][segSamples];
            for (int c = 0; c < nCh; c++) {
                for (int i = 0; i < chunkLen && offset + i < totalSamples; i++) {
                    input[0][c][i] = stereo[c][offset + i];
                }
            }

            // ONNX 推理
            OnnxTensor inTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(flatten(input)), new long[]{1, nCh, segSamples});
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inTensor);
            OrtSession.Result result = session.run(inputs);
            inTensor.close();

            // 提取输出 — 模型顺序:[drums,bass,other,vocals]→映射到文件
            float[][][][] allStems = (float[][][][]) result.get(0).getValue();
            float[][][] stemOut = new float[N_STEMS][nCh][segSamples];
            for (int srcIdx = 0; srcIdx < N_STEMS; srcIdx++) {
                int dstIdx = SOURCE_TO_FILE[srcIdx];
                for (int c = 0; c < nCh; c++)
                    System.arraycopy(allStems[0][srcIdx][c], 0, stemOut[dstIdx][c], 0, segSamples);
            }
            result.close();

            // 施加 stem 增益后 overlap-add 累加
            for (int s = 0; s < N_STEMS; s++) {
                float gain = STEM_GAIN[s];
                if (gain != 1f) {
                    for (int c = 0; c < nCh; c++)
                        for (int i = 0; i < segSamples; i++)
                            stemOut[s][c][i] *= gain;
                }
            }
            ola.accumulate(stemOut, chunkLen);

            // 确定本次可以安全写出的样本范围
            int safeEnd;
            if (chunk < nChunks - 1) {
                safeEnd = ola.strideSamples; // 下一个 chunk 的 overlap 区域会覆盖这里之后
            } else {
                safeEnd = segSamples; // 最后一个 chunk，下面再按原始长度截断
            }
            // 模型输入最后一块是零填充，不能把填充部分写入输出，否则歌曲会变长、
            // 播放听起来像被放慢。分轨必须与解码后的原始样本严格等长。
            safeEnd = Math.min(safeEnd, totalSamples - (int) totalWritten);

            // 归一化并写出 [0, safeEnd) 范围的样本
            int written = 0;
            for (int i = 0; i < safeEnd; i++) {
                float denom = ola.weightSum[i];
                if (denom < 1e-8f) denom = 1e-8f;
                for (int s = 0; s < N_STEMS; s++) {
                    for (int c = 0; c < nCh; c++) {
                        float val = ola.accumulators[s][c][i] / denom;
                        short pcm = clamp(val);
                        outs[s].write((byte)(pcm & 0xFF));
                        outs[s].write((byte)((pcm >> 8) & 0xFF));
                    }
                }
                written++;
            }

            // 平移未写出的尾部到数组开头
            ola.shiftTail(safeEnd);
            totalWritten += written;

            // 进度 + 耗时预估
            float prog = 0.12f + 0.82f * (chunk + 1f) / nChunks;
            long elapsed = (System.currentTimeMillis() - chunkStartTime) / 1000;
            long eta = elapsed > 0 ? elapsed * (nChunks - chunk - 1) / (chunk + 1) : 0;
            long chunkMs = System.currentTimeMillis() - chunkT0;
            cb.onProgress(String.format("分轨 %d/%d块 | 已处理%ds | 耗时%ds | 剩余%ds | 每块%.1fs",
                    chunk+1, nChunks, totalWritten/SAMPLE_RATE, elapsed, eta, chunkMs/1000f), prog);
        }

        session.close();
        env.close();
        for (DataOutputStream o : outs) o.close();

        // 6. 写 WAV 头
        cb.onProgress("保存WAV...", 0.95f);
        File[] files = new File[N_STEMS];
        for (int s = 0; s < N_STEMS; s++) {
            files[s] = new File(outDir, STEM_NAMES[s] + ".wav");
            rawToWav(tmpFiles[s], files[s], (int)totalWritten);
            tmpFiles[s].delete();
        }

        cb.onProgress("完成!", 1f);
        return files;
    }

    // ── 模型加载 ──

    private static byte[] loadModelFromAssets(Context ctx) throws Exception {
        java.io.InputStream is = ctx.getAssets().open(MODEL_FILE);
        byte[] data = new byte[is.available()];
        int total = 0;
        while (total < data.length) {
            int n = is.read(data, total, data.length - total);
            if (n <= 0) break;
            total += n;
        }
        is.close();
        return data;
    }

    // ── 解码 ──

    private static float[][] decodeStereo(Context ctx, String src) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        setSource(ctx, ex, src);
        MediaFormat fmt = findAudio(ex);
        if (fmt == null) { ex.release(); throw new IllegalArgumentException("无音频轨"); }
        int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = Math.min(fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), 2);
        long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 240_000_000L;
        float durSec = durUs / 1_000_000f;
        int estSamples = (int)(durSec * sr) + sr;
        float[] lBuf = new float[estSamples], rBuf = new float[estSamples];
        int count = 0;

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean ie = false, oe = false;

        while (!oe) {
            if (!ie) {
                int idx = codec.dequeueInputBuffer(10000);
                if (idx >= 0) {
                    ByteBuffer b = codec.getInputBuffer(idx);
                    int sz = ex.readSampleData(b, 0);
                    if (sz < 0) { codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); ie = true; }
                    else { codec.queueInputBuffer(idx,0,sz,ex.getSampleTime(),0); ex.advance(); }
                }
            }
            int idx = codec.dequeueOutputBuffer(info, 10000);
            if (idx >= 0) {
                ByteBuffer b = codec.getOutputBuffer(idx);
                if (b != null && info.size > 0) {
                    b.position(info.offset); b.limit(info.offset+info.size); b.order(ByteOrder.LITTLE_ENDIAN);
                    while (b.remaining() >= ch*2) {
                        float sl = b.getShort()/32768f, sr2 = ch>1 ? b.getShort()/32768f : sl;
                        if (count < estSamples) {
                            lBuf[count] = sl;
                            rBuf[count] = sr2;
                            count++;
                        }
                    }
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) oe = true;
            }
        }
        codec.stop(); codec.release(); ex.release();

        return resampleStereo(lBuf, rBuf, count, sr);
    }

    /** 线性重采样：step 是“一个目标采样对应多少个源采样”，避免音调/时长漂移。 */
    private static float[][] resampleStereo(float[] left, float[] right, int n, int sourceRate) {
        if (sourceRate == SAMPLE_RATE) {
            float[][] same = new float[2][n];
            System.arraycopy(left, 0, same[0], 0, n);
            System.arraycopy(right, 0, same[1], 0, n);
            return same;
        }
        double step = (double) sourceRate / SAMPLE_RATE;
        int outN = Math.max(1, (int) Math.ceil((n - 1) / step) + 1);
        float[][] out = new float[2][outN];
        double pos = 0;
        for (int i = 0; i < outN; i++, pos += step) {
            int i0 = Math.min(n - 1, (int) pos);
            int i1 = Math.min(n - 1, i0 + 1);
            float frac = (float) (pos - i0);
            out[0][i] = left[i0] + frac * (left[i1] - left[i0]);
            out[1][i] = right[i0] + frac * (right[i1] - right[i0]);
        }
        return out;
    }

    // ── WAV ──

    private static void rawToWav(File raw, File wav, int samples) throws Exception {
        int ds = samples * 4; // stereo 16-bit
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(wav))) {
            dos.writeBytes("RIFF"); dos.writeInt(Integer.reverseBytes(ds+36));
            dos.writeBytes("WAVEfmt ");
            dos.writeInt(Integer.reverseBytes(16)); dos.writeShort(Short.reverseBytes((short)1));
            dos.writeShort(Short.reverseBytes((short)2)); dos.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            dos.writeInt(Integer.reverseBytes(SAMPLE_RATE*4));
            dos.writeShort(Short.reverseBytes((short)4)); dos.writeShort(Short.reverseBytes((short)16));
            dos.writeBytes("data"); dos.writeInt(Integer.reverseBytes(ds));
        }
        try (FileInputStream fis=new FileInputStream(raw); FileOutputStream fos=new FileOutputStream(wav,true)) {
            byte[] b=new byte[65536]; int n; while((n=fis.read(b))>0) fos.write(b,0,n);
        }
    }

    // ── 工具 ──

    private static float[] flatten(float[][][] data) {
        int d1=data.length, d2=data[0].length, d3=data[0][0].length;
        float[] f=new float[d1*d2*d3]; int idx=0;
        for (float[][] a : data) for (float[] b : a) for (float v : b) f[idx++]=v;
        return f;
    }

    private static short clamp(float v){int i=Math.round(v*32767f);return(short)Math.max(-32768,Math.min(32767,i));}
    private static MediaFormat findAudio(MediaExtractor ex){for(int i=0;i<ex.getTrackCount();i++){MediaFormat mf=ex.getTrackFormat(i);if(mf.getString(MediaFormat.KEY_MIME).startsWith("audio/")){ex.selectTrack(i);return mf;}}return null;}
    private static void setSource(Context ctx,MediaExtractor ex,String src)throws Exception{if(src.startsWith("content://")||src.startsWith("file://")||src.startsWith("android.resource://"))ex.setDataSource(ctx,Uri.parse(src),null);else{File f=new File(src);if(f.exists())ex.setDataSource(f.getAbsolutePath());else ex.setDataSource(ctx,Uri.parse(src),null);}}
}
