package com.kirakuapp.chordstem.v5;

import java.util.List;

/** 单和弦色度调试：打印 12 维色度与模板得分，定位杂散能量。 */
public final class DebugChroma {

    static final String[] PC = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};

    public static void main(String[] args) {
        float[] pcm = SelfTest.synth(List.of("C"), 3.0, true, false, false);
        List<ChordDsp.Segment> segs = ChordDsp.analyzePcm(pcm, null);
        for (ChordDsp.Segment s : segs)
            System.out.printf("段: %-8s %.2f-%.2fs conf=%.2f%n", s.name(), s.startMs / 1000.0, s.endMs / 1000.0, s.confidence);

        // 中间帧的色度
        double[][] ch = ChordDsp.DEBUG_CHROMA, bs = ChordDsp.DEBUG_BASS;
        int mid = ch.length / 2;
        System.out.println("\n中间帧色度（C 大三和弦应为 C/E/G 强）：");
        for (int i = 0; i < 12; i++) System.out.printf("  %-3s %.3f   低音 %.3f%n", PC[i], ch[mid][i], bs[mid][i]);

        // 手工重算模板得分
        double[] d = new double[ChordDsp.N_CHORDS];
        String[] names = new String[ChordDsp.N_CHORDS];
        for (int s = 0; s < ChordDsp.N_CHORDS; s++) {
            double dot = 0;
            for (int i = 0; i < 12; i++) dot += ch[mid][i] * ChordDsp.templateAt(s)[i];
            d[s] = dot;
            names[s] = PC[s / ChordDsp.N_TYPES] + ChordDsp.TYPE_NAMES[s % ChordDsp.N_TYPES];
        }
        for (int top = 0; top < 8; top++) {
            int bi = 0;
            for (int s = 1; s < d.length; s++) if (d[s] > d[bi]) bi = s;
            System.out.printf("  #%d %-8s dot=%.4f%n", top + 1, names[bi], d[bi]);
            d[bi] = Double.NEGATIVE_INFINITY;
        }
    }
}
