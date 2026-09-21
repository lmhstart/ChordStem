package com.kirakuapp.chordstem.v5;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ChordDsp 合成音频自测 — 在 PC 上直接运行（无 Android 依赖）。
 * 合成带泛音的吉他和弦、贝斯根音、鼓组与旋律，验证识别准确率与边界定位。
 *
 * 运行：javac -encoding UTF-8 -d out app/src/main/java/com/kirakuapp/chordstem/v5/ChordDsp.java dsp-test/SelfTest.java
 *      java -cp out com.kirakuapp.chordstem.v5.SelfTest
 */
public final class SelfTest {

    private static final int SR = ChordDsp.SR;
    private static final String[] ROOTS = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
    private static final int[][] TONES = {
            {0,4,7}, {0,3,7}, {0,4,7,10}, {0,3,7,10}, {0,4,7,11},
            {0,3,6}, {0,2,7}, {0,5,7}, {0,7}
    };

    public static void main(String[] args) {
        System.out.println("ChordDsp 自测  SR=" + SR + "  FFT=" + ChordDsp.FFT);
        int fail = 0;
        fail += test("三和弦流行进行", List.of("C","G","Am","F","C","G","F","G"), 1.8, true, false, false, 0.90);
        fail += test("七和弦进行",   List.of("Cmaj7","Am7","Dm7","G7","Cmaj7","Fmaj7","G7","C7"), 1.8, true, false, false, 0.80);
        fail += test("带旋律",       List.of("C","Am","F","G","Em","Am","Dm","G"), 1.8, true, true, false, 0.85);
        fail += test("慢速民谣",     List.of("C","G","Am","Em","F","C","Dm","G"), 3.2, true, false, false, 0.92);
        fail += test("小调暗色",     List.of("Am","F","C","G","Am","F","Dm","E"), 1.8, true, false, false, 0.88);
        fail += test("静音首尾",     List.of("C","G","Am","F"), 1.8, true, false, true, 0.85);
        System.out.println(fail == 0 ? "\n全部通过" : "\n失败用例数: " + fail);
        if (fail > 0) System.exit(1);
    }

    /** @return 0=通过 1=失败 */
    static int test(String title, List<String> prog, double chordSec, boolean drums,
                    boolean melody, boolean quietEdges, double need) {
        float[] pcm = synth(prog, chordSec, drums, melody, quietEdges);
        long t0 = System.nanoTime();
        List<ChordDsp.Segment> segs;
        try {
            segs = ChordDsp.analyzePcm(pcm, null);
        } catch (Exception e) {
            System.out.println("  [失败] " + title + " 异常: " + e);
            return 1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        // 识别序列
        StringBuilder seq = new StringBuilder();
        for (ChordDsp.Segment s : segs)
            seq.append(String.format("%s(%.1fs,%.0f%%) ", s.name(), s.startMs / 1000.0, s.confidence * 100));

        // 帧级准确率：帧中心时刻的 GT 与识别段比对；跳过每段两端 250ms 过渡带
        int nFrames = (pcm.length - ChordDsp.FFT) / ChordDsp.HOP + 1;
        double edgeMs = quietEdges ? 2200 : 0;   // 静音边缘不算
        int hit = 0, cnt = 0;
        Map<String, Integer> confusion = new HashMap<>();
        for (int f = 0; f < nFrames; f++) {
            double t = (f * ChordDsp.HOP + ChordDsp.FFT / 2.0) / SR * 1000;   // 帧中心 ms
            if (t < edgeMs + 250 || t > (prog.size() * chordSec * 1000 + edgeMs) - 250) continue;
            double pos = (t - edgeMs) / 1000;
            int idx = (int) Math.min(prog.size() - 1, pos / chordSec);
            double inSeg = pos - idx * chordSec;
            if (inSeg < 0.25 || inSeg > chordSec - 0.25) continue;            // 段内过渡带
            String gt = prog.get(idx), got = nameAt(segs, t);
            if (got == null) got = "?";
            if (gt.equals(got)) hit++;
            else {
                String key = gt + "→" + got;
                confusion.merge(key, 1, Integer::sum);
            }
            cnt++;
        }
        double acc = cnt == 0 ? 0 : (double) hit / cnt;
        boolean ok = acc >= need;
        System.out.printf("%s  %s  准确率 %.1f%%（需 %.0f%%，%d 帧，%dms）%n",
                ok ? "[通过]" : "[失败]", title, acc * 100, need * 100, cnt, ms);
        System.out.println("    期望: " + String.join(" ", prog));
        System.out.println("    识别: " + seq);
        if (!confusion.isEmpty()) {
            List<Map.Entry<String, Integer>> top = new ArrayList<>(confusion.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder cf = new StringBuilder("    混淆: ");
            for (int i = 0; i < Math.min(5, top.size()); i++)
                cf.append(top.get(i).getKey()).append("×").append(top.get(i).getValue()).append(" ");
            System.out.println(cf);
        }
        return ok ? 0 : 1;
    }

    static String nameAt(List<ChordDsp.Segment> segs, double ms) {
        for (ChordDsp.Segment s : segs)
            if (ms >= s.startMs && ms < s.endMs) return s.name();
        return null;
    }

    // ═══════════════════════ 合成器 ═══════════════════════

    static float[] synth(List<String> prog, double chordSec, boolean drums, boolean melody, boolean quietEdges) {
        double lead = quietEdges ? 2.2 : 0.2, tail = quietEdges ? 2.2 : 0.4;
        int total = (int) ((lead + prog.size() * chordSec + tail) * SR);
        float[] out = new float[total];
        Random rnd = new Random(42);

        for (int i = 0; i < prog.size(); i++) {
            double t0 = lead + i * chordSec;
            int[] chord = tones(prog.get(i));
            int rootPc = rootPc(prog.get(i));
            // 吉他和弦：每 0.6s 重新拨一次，带泛音衰减
            for (double trig = t0; trig < t0 + chordSec; trig += 0.6) {
                for (int iv : chord)
                    pluck(out, midiHz(48 + rootPc + iv), trig, 0.6, 6, 0.22, rnd);
            }
            // 贝斯根音：每 0.9s
            for (double trig = t0; trig < t0 + chordSec; trig += 0.9)
                pluck(out, midiHz(36 + rootPc), trig, 0.9, 4, 0.30, rnd);
            // 旋律（和弦音上方两个八度，随节拍）
            if (melody) {
                for (double trig = t0 + 0.3; trig < t0 + chordSec; trig += 0.45) {
                    int tone = chord[rnd.nextInt(chord.length)];
                    pluck(out, midiHz(72 + rootPc + tone), trig, 0.35, 3, 0.10, rnd);
                }
            }
        }

        if (drums) {
            double end = lead + prog.size() * chordSec;
            for (double t = lead; t < end; t += 0.45) kick(out, t);
            for (double t = lead + 0.225; t < end; t += 0.45) hat(out, t, rnd);
            for (double t = lead + 0.45; t < end; t += 0.9) snare(out, t, rnd);
        }

        // 归一化
        float peak = 0;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        if (peak > 0) for (int i = 0; i < out.length; i++) out[i] = out[i] / peak * 0.9f;
        return out;
    }

    /** 弹拨音：harm 阶泛音，幅度 1/h^1.3，随机相位，指数衰减。 */
    static void pluck(float[] out, double hz, double tSec, double tau, int harm, double amp, Random rnd) {
        int start = (int) (tSec * SR);
        int len = Math.min(out.length - start, (int) (tau * 4 * SR));
        for (int h = 1; h <= harm; h++) {
            double f = hz * h;
            if (f > SR / 2.0 - 100) break;
            double a = amp / Math.pow(h, 1.3);
            double ph = rnd.nextDouble() * 2 * Math.PI;
            for (int i = 0; i < len; i++) {
                double env = Math.min(1, i / (0.01 * SR)) * Math.exp(-i / (tau * SR));
                out[start + i] += (float) (a * env * Math.sin(2 * Math.PI * f * i / SR + ph));
            }
        }
    }

    static void kick(float[] out, double tSec) {
        int start = (int) (tSec * SR);
        int len = (int) (0.16 * SR);
        for (int i = 0; i < len && start + i < out.length; i++) {
            double f = 110 * Math.exp(-i / (0.02 * SR)) + 45;
            double env = Math.exp(-i / (0.06 * SR));
            out[start + i] += (float) (0.5 * env * Math.sin(2 * Math.PI * f * i / SR));
        }
    }

    static void snare(float[] out, double tSec, Random rnd) {
        int start = (int) (tSec * SR);
        int len = (int) (0.12 * SR);
        double lp = 0;
        for (int i = 0; i < len && start + i < out.length; i++) {
            double n = rnd.nextDouble() * 2 - 1;
            lp += (n - lp) * 0.35;
            double env = Math.exp(-i / (0.045 * SR));
            out[start + i] += (float) (0.22 * env * lp);
        }
    }

    static void hat(float[] out, double tSec, Random rnd) {
        int start = (int) (tSec * SR);
        int len = (int) (0.04 * SR);
        double hp = 0, prev = 0;
        for (int i = 0; i < len && start + i < out.length; i++) {
            double n = rnd.nextDouble() * 2 - 1;
            hp = n - prev; prev = n;
            double env = Math.exp(-i / (0.012 * SR));
            out[start + i] += (float) (0.12 * env * hp);
        }
    }

    static double midiHz(int midi) { return 440.0 * Math.pow(2, (midi - 69) / 12.0); }

    static int rootPc(String chord) {
        int letter = List.of("C","D","E","F","G","A","B").indexOf(chord.substring(0, 1).toUpperCase());
        int pc = new int[]{0, 2, 4, 5, 7, 9, 11}[letter];
        if (chord.length() > 1 && chord.charAt(1) == '#') pc++;
        if (chord.length() > 1 && chord.charAt(1) == 'b') pc = (pc + 11) % 12;
        return pc;
    }

    static int[] tones(String chord) {
        int rootLen = (chord.length() > 1 && chord.charAt(1) == '#') ? 2 : 1;
        String suffix = chord.substring(rootLen);
        for (int t = 0; t < ChordDsp.TYPE_NAMES.length; t++)
            if (ChordDsp.TYPE_NAMES[t].equals(suffix)) return TONES[t];
        throw new IllegalArgumentException("未知和弦 " + chord);
    }
}
