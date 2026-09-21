package com.kirakuapp.chordstem.v5;

/**
 * Overlap-Add 拼接引擎 — 解决神经网络分块推理的边界伪影。
 *
 * <h3>算法 (demucs/demixr 同款)</h3>
 * <pre>
 * segment = 模型固定输入长度
 * overlap = segment / 4 (25%)
 * stride  = segment - overlap
 * window  = triangular fade [0→1 ramp, 1 flat center, 1→0 ramp]
 *
 * for each chunk at offset:
 *   y = model(chunk)                // [stems, ch, segment]
 *   acc += y * window               // 加窗累加
 *   weight_sum += window
 *   flush 已确定样本: acc / weight_sum → 写文件
 *   shift 尾部到开头
 * </pre>
 */
public final class OverlapAddEngine {
    public final int segmentSamples;
    public final int overlapSamples;
    public final int strideSamples;
    private final float[] window;

    // 每 stem 每声道一个累积缓冲 (只留一个 segment 大小，流式 flush)
    final float[][][] accumulators; // [nStems][nCh][segment]
    final float[] weightSum;
    private final int nStems;
    private final int nChannels;

    public OverlapAddEngine(int segmentSamples, int nStems, int nChannels) {
        this.segmentSamples = segmentSamples;
        this.overlapSamples = segmentSamples / 4;
        this.strideSamples = segmentSamples - overlapSamples;
        this.nStems = nStems;
        this.nChannels = nChannels;

        this.window = buildTriangularWindow(segmentSamples, overlapSamples);
        this.accumulators = new float[nStems][nChannels][segmentSamples];
        this.weightSum = new float[segmentSamples];
    }

    /** 获取下一个 chunk 在整首歌中的偏移量。 */
    public int getOffset(int chunkIndex) {
        return chunkIndex * strideSamples;
    }

    /** 计算需要处理的 chunk 总数。 */
    public int getNumChunks(int totalSamples) {
        if (totalSamples <= segmentSamples) return 1;
        return (totalSamples - overlapSamples + strideSamples - 1) / strideSamples;
    }

    /**
     * 将一个 chunk 的模型输出累加到累积缓冲中。
     * @param stemOutput [nStems][nCh][segment] 模型原始输出
     * @param chunkLen   本 chunk 实际有效样本数（最后一个 chunk 可能不足 segment）
     */
    public void accumulate(float[][][] stemOutput, int chunkLen) {
        int effectiveLen = Math.min(chunkLen, segmentSamples);
        for (int s = 0; s < nStems; s++) {
            for (int c = 0; c < nChannels; c++) {
                for (int i = 0; i < effectiveLen; i++) {
                    float w = window[i];
                    accumulators[s][c][i] += stemOutput[s][c][i] * w;
                }
            }
        }
        // 权重是每个时间点的一份公共归一化因子，不能按 stem/声道重复累加。
        for (int i = 0; i < effectiveLen; i++) {
            weightSum[i] += window[i];
                }
    }

    /**
     * 刷新从 bufferStart 到 flushEnd 之间的已完成样本。
     * @return 刷新的样本数
     */
    public int flush(float[][] outputBuffer, int stemIdx, int chIdx,
                     int bufferStart, int flushEnd) {
        int count = 0;
        for (int i = bufferStart; i < flushEnd && i < segmentSamples; i++) {
            float denom = weightSum[i];
            if (denom < 1e-8f) denom = 1e-8f;
            outputBuffer[stemIdx][count] = accumulators[stemIdx][chIdx][i] / denom;
            count++;
        }
        return count;
    }

    /**
     * 将 segment 尾部未完成的样本平移到数组开头，
     * 为下一个 chunk 腾出空间。
     */
    public void shiftTail(int keptSamples) {
        int tail = segmentSamples - keptSamples;
        if (tail <= 0 || keptSamples <= 0) {
            // 全清
            for (int s = 0; s < nStems; s++)
                for (int c = 0; c < nChannels; c++)
                    java.util.Arrays.fill(accumulators[s][c], 0f);
            java.util.Arrays.fill(weightSum, 0f);
            return;
        }
        for (int s = 0; s < nStems; s++) {
            for (int c = 0; c < nChannels; c++) {
                System.arraycopy(accumulators[s][c], keptSamples,
                        accumulators[s][c], 0, tail);
                java.util.Arrays.fill(accumulators[s][c], tail, segmentSamples, 0f);
            }
        }
        System.arraycopy(weightSum, keptSamples, weightSum, 0, tail);
        java.util.Arrays.fill(weightSum, tail, segmentSamples, 0f);
    }

    /** 重置所有状态。 */
    public void reset() {
        for (int s = 0; s < nStems; s++)
            for (int c = 0; c < nChannels; c++)
                java.util.Arrays.fill(accumulators[s][c], 0f);
        java.util.Arrays.fill(weightSum, 0f);
    }

    // ── 三角过渡窗 ──

    private static float[] buildTriangularWindow(int segment, int overlap) {
        float[] w = new float[segment];
        for (int i = 0; i < segment; i++) {
            if (i < overlap) {
                w[i] = (float) i / overlap;           // fade in: 0 → 1
            } else if (i >= segment - overlap) {
                w[i] = (float) (segment - i) / overlap; // fade out: 1 → 0
            } else {
                w[i] = 1f;                             // flat center
            }
        }
        return w;
    }
}
