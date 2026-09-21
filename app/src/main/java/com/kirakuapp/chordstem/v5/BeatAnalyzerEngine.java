package com.kirakuapp.chordstem.v5;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** 离线提取拍点，仅作为和弦时间线的对齐网格，不播放节拍声。 */
public final class BeatAnalyzerEngine {
    private BeatAnalyzerEngine() {}
    public interface Callback { void onComplete(List<Long> beats); void onError(String message); }

    public static void analyzeAsync(Context ctx, String source, Callback cb) {
        new Thread(() -> {
            try { cb.onComplete(analyze(ChordAnalyzerEngine.decodeForAnalysis(ctx, source))); }
            catch (Exception e) { cb.onError("拍点分析失败：" + e.getMessage()); }
        }, "beat-analysis").start();
    }

    private static List<Long> analyze(float[] pcm) {
        final int hop = 512, win = 1024;
        int n = Math.max(1, (pcm.length - win) / hop);
        double[] onset = new double[n];
        double prev = 0;
        for (int f = 0; f < n; f++) {
            double e = 0; int off = f * hop;
            for (int i = 0; i < win; i++) e += pcm[off + i] * pcm[off + i];
            double level = Math.log1p(Math.sqrt(e / win) * 80);
            onset[f] = Math.max(0, level - prev);
            prev = prev * 0.85 + level * 0.15;
        }
        int bestBpm = 100; double best = -1;
        for (int bpm = 50; bpm <= 180; bpm++) {
            int lag = Math.max(1, Math.round(60f * ChordDsp.SR / hop / bpm));
            double score = 0;
            for (int i = lag; i < n; i++) score += onset[i] * onset[i - lag];
            if (score > best) { best = score; bestBpm = bpm; }
        }
        long interval = Math.max(1, Math.round(60000.0 / bestBpm));
        int first = 0;
        for (int i = 1; i < Math.min(n, 200); i++) if (onset[i] > onset[first]) first = i;
        long start = Math.round(first * hop * 1000.0 / ChordDsp.SR);
        List<Long> out = new ArrayList<>();
        for (long t = start; t < pcm.length * 1000L / ChordDsp.SR; t += interval) out.add(t);
        return out;
    }
}
