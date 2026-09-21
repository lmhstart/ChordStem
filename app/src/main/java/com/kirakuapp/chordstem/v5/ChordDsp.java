package com.kirakuapp.chordstem.v5;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 和弦分析核心 — 纯 Java，不依赖 Android，可在 PC 上单独编译测试。
 *
 * <h3>算法链</h3>
 * <ol>
 *   <li>FFT（8192 点 @22050Hz，Hann 窗，hop 2048 ≈ 93ms/帧）</li>
 *   <li>频谱峰值提取 + 抛物线插值（精确到分数 bin）</li>
 *   <li>峰值幅度开方压缩（削弱打击乐瞬态的支配）</li>
 *   <li>HPCP 色度图：峰值向自身音级投票，并按 3/5 次谐波关系回投给疑似基频，
 *       让根音获得来自和弦五度、三度泛音的交叉印证</li>
 *   <li>低音区（&lt;270Hz）单独累积低音色度，匹配时对模板根音加权
 *       ——低音几乎总在弹根音，是区分 C/Am 这类同音集和弦的关键</li>
 *   <li>色度前后各 3 帧平滑</li>
 *   <li>61 状态 Viterbi：60 和弦（大/小/7/m7/maj7 × 12 根音）
 *       + 1 个"无和弦"状态（静音/前奏间奏）；换和弦按五度圈距离加权转移</li>
 *   <li>短段吸收合并，输出带置信度的分段</li>
 * </ol>
 *
 * <p>与 V4（Goertzel + 二值模板 + 中值平滑）相比：频率分辨率由窗长决定且整谱一致、
 * 谐波回投方向正确、根音有低音佐证、时序平滑有乐理依据、静音不再被强行贴上和弦。</p>
 */
public final class ChordDsp {
    private ChordDsp() {}

    // ── 采样与分帧 ──
    public static final int SR = 22050;
    public static final int FFT = 8192;
    public static final int HOP = 2048;
    public static final double FRAME_MS = HOP * 1000.0 / SR;

    // ── 分析参数（由 dsp-test/SelfTest 调优）──
    private static final double C0 = 16.3515978313;   // C0
    private static final double PEAK_MIN_HZ = 55.0;   // A1 以下不采峰
    private static final double PEAK_MAX_HZ = 5000.0;
    private static final double BASS_MAX_HZ = 270.0;
    private static final double W_SUB3 = 0.30;        // 峰值按 3 次谐波回投基频权重
    private static final double W_SUB5 = 0.15;        // 峰值按 5 次谐波回投基频权重
    private static final double PEAK_FLOOR = 0.02;    // 帧内峰阈值（相对最强峰）
    private static final double SIEVE_CENT = 0.45;    // 谐波筛频率容差（半音）
    private static final double SIEVE_OCT = 0.60;     // 八度类谐波（h=2,4）削减系数
    private static final double SIEVE_ODD = 0.35;     // 非八度谐波（h=3,5,6）削减系数
    private static final int MAX_PEAKS = 80;
    private static final double BASS_BONUS = 0.48;    // 低音色度对根音的加权
    private static final double EMISSION_GAIN = 34.0; // 发射分数增益（决定"多确定才换和弦"）
    private static final double SEVENTH_PENALTY = 0.12;
    private static final double SEVENTH_MIN_EVIDENCE = 0.72;
    private static final double QUIET_RATIO = 0.10;   // RMS 低于全曲中值该比例 → 静音候选
    private static final double MIN_SEG_MS = 185;

    // ── 和弦状态 ──
    public static final String[] TYPE_NAMES = {"", "m", "7", "m7", "maj7"};
    public static final int N_TYPES = TYPE_NAMES.length;   // 5
    public static final int N_CHORDS = 12 * N_TYPES;       // 60
    public static final int STATE_N = N_CHORDS;            // 无和弦状态
    public static final int N_STATES = N_CHORDS + 1;       // 109
    private static final String[] NOTE = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};

    /** 一段和弦。type 为 -1 表示无和弦（静音/间奏），root 无效。 */
    public static final class Segment {
        public final long startMs, endMs;
        public final int root, type;
        public final float confidence;
        public Segment(long s, long e, int r, int t, float c) {
            startMs = s; endMs = e; root = r; type = t; confidence = c;
        }
        public String name() { return type < 0 ? "—" : NOTE[root] + TYPE_NAMES[type]; }
    }

    public interface Progress { void onProgress(float p); }

    /** 仅供 dsp-test 调试：最近一次分析的平滑后色度帧。发布版可移除。 */
    public static double[][] DEBUG_CHROMA;
    public static double[][] DEBUG_BASS;

    // ── 模板 ──
    private static final float[][] TEMPLATES = buildTemplates();

    private static float[] tpl(int root, double[] iv, double[] w) {
        float[] v = new float[12];
        for (int i = 0; i < iv.length; i++) v[(root + (int) iv[i]) % 12] = (float) w[i];
        double n = 0; for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < 12; i++) v[i] /= (float) n;
        return v;
    }

    private static float[][] buildTemplates() {
        float[][] t = new float[N_CHORDS][12];
        for (int root = 0; root < 12; root++) {
            int b = root * N_TYPES;
            t[b + 0] = tpl(root, new double[]{0, 4, 7},        new double[]{1.15, 0.90, 1.00});        // 大
            t[b + 1] = tpl(root, new double[]{0, 3, 7},        new double[]{1.15, 0.90, 1.00});        // 小
            t[b + 2] = tpl(root, new double[]{0, 4, 7, 10},    new double[]{1.10, 0.85, 0.95, 0.45});  // 属7
            t[b + 3] = tpl(root, new double[]{0, 3, 7, 10},    new double[]{1.10, 0.85, 0.95, 0.45});  // 小7
            t[b + 4] = tpl(root, new double[]{0, 4, 7, 11},    new double[]{1.10, 0.85, 0.95, 0.45});  // 大7
        }
        return t;
    }

    private static int rootOf(int state) { return state / N_TYPES; }

    /** 仅供同包测试读取模板。 */
    static float[] templateAt(int s) { return TEMPLATES[s]; }

    // ── 转移表：五度圈距离加权 ──
    private static final double[][] TRANS = buildTrans();

    /** 两根音在五度圈上的最短距离 0..6（近似：I-IV-V 邻近、远关系调转移更少见）。 */
    private static int fifthsDist(int r1, int r2) {
        int p1 = (7 * r1) % 12, p2 = (7 * r2) % 12;
        int d = Math.abs(p1 - p2);
        return Math.min(d, 12 - d);
    }

    private static double[][] buildTrans() {
        double[][] t = new double[N_STATES][N_STATES];
        final double stayChord = Math.log(0.88);
        final double stayN = Math.log(0.90);
        final double change = Math.log(0.055);
        final double toN = Math.log(0.04);
        final double fromN = Math.log(0.06);
        for (int s = 0; s < N_CHORDS; s++) {
            for (int d = 0; d < N_CHORDS; d++) {
                if (d == s) { t[s][s] = stayChord; continue; }
                int r1 = s / N_TYPES, r2 = d / N_TYPES;
                // 同根音换类型（C→C7）不加惩罚，跨根音按五度圈距离衰减
                double pen = (r1 == r2) ? 0 : 0.16 * fifthsDist(r1, r2);
                t[s][d] = change - pen;
            }
            t[s][STATE_N] = toN;
        }
        for (int s = 0; s < N_CHORDS; s++) t[STATE_N][s] = fromN;
        t[STATE_N][STATE_N] = stayN;
        return t;
    }

    // ═══════════════════════ 入口 ═══════════════════════

    /**
     * 分析 22050Hz 单声道 PCM。
     * @throws IllegalArgumentException 音频太短
     */
    public static List<Segment> analyzePcm(float[] pcm, Progress cb) {
        if (pcm.length < FFT) throw new IllegalArgumentException("音频太短，无法分析和弦");
        int nFrames = (pcm.length - FFT) / HOP + 1;

        // ── Pass 1: 逐帧 FFT → 峰值 → 色度 ──
        double[][] chroma = new double[nFrames][12];
        double[][] bass = new double[nFrames][12];
        double[] rms = new double[nFrames];

        float[] hann = new float[FFT];
        for (int i = 0; i < FFT; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1)));

        double[] re = new double[FFT], im = new double[FFT];
        double[] mag = new double[FFT / 2 + 1];
        double[] pkHz = new double[MAX_PEAKS], pkAmp = new double[MAX_PEAKS];
        int minBin = Math.max(2, (int) Math.ceil(PEAK_MIN_HZ * FFT / SR));
        int maxBin = Math.min(FFT / 2 - 2, (int) (PEAK_MAX_HZ * FFT / SR));
        int bassBin = (int) (BASS_MAX_HZ * FFT / SR);

        for (int f = 0; f < nFrames; f++) {
            int off = f * HOP;
            double e = 0;
            for (int i = 0; i < FFT; i++) {
                float w = pcm[off + i] * hann[i];
                re[i] = w; im[i] = 0;
                e += w * w;
            }
            rms[f] = Math.sqrt(e / FFT);
            fft(re, im);

            for (int i = 0; i <= FFT / 2; i++) mag[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);

            // 峰值：局部极大 + 抛物线插值
            int nPk = 0;
            double maxA = 0;
            for (int i = minBin; i <= maxBin && nPk < MAX_PEAKS; i++) {
                double a = mag[i];
                if (a < mag[i - 1] || a < mag[i + 1]) continue;
                double denom = mag[i - 1] - 2 * a + mag[i + 1];
                double delta = denom == 0 ? 0 : 0.5 * (mag[i - 1] - mag[i + 1]) / denom;
                if (delta > 0.5 || delta < -0.5) delta = 0;
                double amp = a - 0.25 * (mag[i - 1] - mag[i + 1]) * delta;
                if (amp <= 0) amp = a;
                pkHz[nPk] = (i + delta) * SR / FFT;
                pkAmp[nPk] = amp;
                if (amp > maxA) maxA = amp;
                nPk++;
            }
            double thr = maxA * PEAK_FLOOR;
            double[] ch = chroma[f], bs = bass[f];
            double[] sieve = sieveHarmonics(pkHz, pkAmp, nPk);
            for (int p = 0; p < nPk; p++) {
                if (pkAmp[p] < thr) continue;
                double w = Math.sqrt(pkAmp[p]) * sieve[p];   // 幅度压缩 × 谐波筛
                vote(ch, pkHz[p], w);
                if (pkHz[p] <= BASS_MAX_HZ) vote(bs, pkHz[p], w);
            }
            l2(ch);
            l2(bs);

            if (cb != null && (f & 31) == 0) cb.onProgress(0.25f + 0.75f * f / nFrames);
        }

        // ── 色度时域平滑（3 帧核 ×2 ≈ 280ms）──
        // 原来连续平滑两次会带来约 280ms 的换和弦滞后；保留一次平滑，
        // 再由 Viterbi 和短段吸收负责抑制瞬态噪声。
        chroma = smooth3(chroma);
        bass = smooth3(bass);
        DEBUG_CHROMA = chroma;
        DEBUG_BASS = bass;

        // ── 静音门限：全曲 RMS 中值 ──
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double quietThr = sorted[sorted.length / 2] * QUIET_RATIO;

        // ── Viterbi ──
        double[][] dots = new double[nFrames][N_CHORDS];  // 模板原始匹配度，留作置信度
        int[] path = viterbi(chroma, bass, rms, quietThr, dots);

        // ── 分段合并 ──
        return mergeRuns(path, dots, nFrames);
    }

    // ═══════════════════════ 色度投票 ═══════════════════════

    /**
     * 谐波筛：峰若能由"更强的低频峰 × h（h=2..6）"解释，则它大概率是泛音而非独立音，
     * 削弱其直接投票（八度类 h=2,4 影响同一音级，削减更温和）。
     * 例如 C 和弦里 E 音的 3 次泛音恰好是 B —— 不筛掉它就会被误判成 maj7。
     */
    private static double[] sieveHarmonics(double[] hz, double[] amp, int n) {
        double[] factor = new double[n];
        double lo = Math.pow(2, -SIEVE_CENT / 12), hi = Math.pow(2, SIEVE_CENT / 12);
        for (int i = 0; i < n; i++) {
            double f = hz[i], a = amp[i], fac = 1.0;
            for (int h = 2; h <= 6; h++) {
                double parent = f / h;
                if (parent < PEAK_MIN_HZ * 0.5) break;
                double pLo = parent * lo, pHi = parent * hi;
                for (int j = 0; j < n; j++) {
                    if (j == i || amp[j] < a * 1.1) continue;
                    if (hz[j] >= pLo && hz[j] <= pHi) {
                        double f2 = (h == 2 || h == 4) ? SIEVE_OCT : SIEVE_ODD;
                        if (f2 < fac) fac = f2;
                        break;
                    }
                }
            }
            factor[i] = fac;
        }
        return factor;
    }

    /**
     * 频率 f 处的峰为自身音级投票；同时它可能是更低音的 3/5 次谐波，
     * 因此向基频候选（f/3、f/5 的音级）回投一部分票 —— 方向是"由泛音找根音"。
     */
    private static void vote(double[] out, double hz, double w) {
        double semi = 12.0 * Math.log(hz / C0) / Math.log(2);
        spread(out, semi, w);
        spread(out, semi - 19.02, w * W_SUB3);   // f/3：低一个八度 + 纯五度
        spread(out, semi - 27.86, w * W_SUB5);   // f/5：低两个八度 + 大三度
    }

    /** 半音位置 → 两个最近音级的线性分配（消除量化偏差）。 */
    private static void spread(double[] out, double semi, double w) {
        double x = ((semi % 12) + 12) % 12;
        int i0 = (int) x;
        double frac = x - i0;
        out[i0] += w * (1 - frac);
        out[(i0 + 1) % 12] += w * frac;
    }

    private static void l2(double[] v) {
        double n = 0;
        for (double x : v) n += x * x;
        n = Math.sqrt(n);
        if (n < 1e-12) return;
        for (int i = 0; i < v.length; i++) v[i] /= n;
    }

    private static double[][] smooth3(double[][] in) {
        int n = in.length;
        double[][] out = new double[n][12];
        for (int f = 0; f < n; f++) {
            double[] o = out[f];
            int i0 = Math.max(0, f - 1), i2 = Math.min(n - 1, f + 1);
            for (int p = i0; p <= i2; p++)
                for (int i = 0; i < 12; i++) o[i] += in[p][i] * (p == f ? 0.5 : 0.25);
            l2(o);
        }
        return out;
    }

    // ═══════════════════════ Viterbi ═══════════════════════

    private static int[] viterbi(double[][] chroma, double[][] bass, double[] rms, double quietThr, double[][] dots) {
        int n = chroma.length;
        int[] path = new int[n];
        double[] delta = new double[N_STATES], deltaNext = new double[N_STATES];
        double[] emis = new double[N_STATES];
        int[][] psi = new int[n][N_STATES];

        for (int f = 0; f < n; f++) {
            double[] ch = chroma[f], bs = bass[f];
            for (int s = 0; s < N_CHORDS; s++) {
                float[] t = TEMPLATES[s];
                double dot = 0;
                for (int i = 0; i < 12; i++) dot += ch[i] * t[i];
                dot += BASS_BONUS * bs[rootOf(s)];
                // 七音必须相对和弦主体有独立能量。这样可以挡住“第三音的
                // 三次泛音落在大七度/小七度”的常见误报，同时保留真实七和弦。
                int type = s % N_TYPES;
                if (type >= 2) {
                    int root = rootOf(s);
                    int third = (root + (type == 3 ? 3 : 4)) % 12;
                    int seventh = (root + (type == 4 ? 11 : 10)) % 12;
                    double body = (ch[root] + ch[third] + ch[(root + 7) % 12]) / 3.0;
                    double evidence = body < 1e-9 ? 0 : ch[seventh] / body;
                    if (evidence < SEVENTH_MIN_EVIDENCE) dot -= SEVENTH_PENALTY;
                }
                dots[f][s] = dot;
            }
            // 先以三和弦为基准，再决定是否升级为七和弦。仅凭模板多一个音级
            // 的微小余量不够，必须形成清晰的分数优势，避免泛音导致跳型。
            for (int root = 0; root < 12; root++) {
                int b = root * N_TYPES;
                int major = b, minor = b + 1;
                int dominant7 = b + 2, minor7 = b + 3, major7 = b + 4;
                if (dots[f][dominant7] < dots[f][major] + 0.025)
                    dots[f][dominant7] = dots[f][major] - 0.015;
                if (dots[f][minor7] < dots[f][minor] + 0.025)
                    dots[f][minor7] = dots[f][minor] - 0.015;
                if (dots[f][major7] < dots[f][major] + 0.025)
                    dots[f][major7] = dots[f][major] - 0.015;
            }
            for (int s = 0; s < N_CHORDS; s++) emis[s] = dots[f][s] * EMISSION_GAIN;
            // 无和弦状态：按低于门限的程度渐进吸引，有声则轻微排斥
            if (rms[f] < quietThr) {
                double q = (quietThr - rms[f]) / quietThr;
                emis[STATE_N] = 6.0 * Math.min(1, q * 2);
            } else {
                emis[STATE_N] = -2.0;
            }

            if (f == 0) {
                System.arraycopy(emis, 0, delta, 0, N_STATES);
                continue;
            }
            for (int cur = 0; cur < N_STATES; cur++) {
                double best = Double.NEGATIVE_INFINITY;
                int bestPrev = 0;
                for (int prev = 0; prev < N_STATES; prev++) {
                    double v = delta[prev] + TRANS[prev][cur];
                    if (v > best) { best = v; bestPrev = prev; }
                }
                deltaNext[cur] = best + emis[cur];
                psi[f][cur] = bestPrev;
            }
            double[] tmp = delta; delta = deltaNext; deltaNext = tmp;
        }

        int bestState = 0;
        double bestV = Double.NEGATIVE_INFINITY;
        for (int s = 0; s < N_STATES; s++)
            if (delta[s] > bestV) { bestV = delta[s]; bestState = s; }
        path[n - 1] = bestState;
        for (int f = n - 2; f >= 0; f--) path[f] = psi[f + 1][path[f + 1]];
        return path;
    }

    // ═══════════════════════ 分段 ═══════════════════════

    private static List<Segment> mergeRuns(int[] path, double[][] dots, int nFrames) {
        // 连续同状态 → 原始段
        List<int[]> runs = new ArrayList<>();  // {state, startFrame, endFrameExclusive}
        int s0 = path[0], start = 0;
        for (int f = 1; f < nFrames; f++) {
            if (path[f] != s0) {
                runs.add(new int[]{s0, start, f});
                s0 = path[f]; start = f;
            }
        }
        runs.add(new int[]{s0, start, nFrames});

        // 过短段（< MIN_SEG_MS）反复并入相邻段：优先并入前段，开头段并入后段
        boolean changed = true;
        while (changed && runs.size() > 1) {
            changed = false;
            for (int i = 0; i < runs.size(); i++) {
                int[] r = runs.get(i);
                long ms = Math.round((r[2] - r[1]) * FRAME_MS);
                if (ms >= MIN_SEG_MS) continue;
                if (i > 0) {
                    int[] prev = runs.get(i - 1);
                    prev[2] = r[2];
                } else {
                    int[] next = runs.get(1);
                    next[1] = r[1];
                }
                runs.remove(i);
                changed = true;
                break;
            }
        }

        // 输出：和弦段正常；无和弦段只保留较长者（≥700ms，真静音/间奏），短的并入前段已处理
        List<Segment> out = new ArrayList<>();
        for (int[] r : runs) {
            int state = r[0];
            long msA = Math.round(r[1] * FRAME_MS), msB = Math.round(r[2] * FRAME_MS);
            if (state == STATE_N) {
                boolean isEdge = out.isEmpty() && r[2] == nFrames;   // 整曲无和弦
                if (msB - msA < 700 && !isEdge) {
                    if (!out.isEmpty()) {
                        Segment last = out.get(out.size() - 1);
                        out.set(out.size() - 1, new Segment(last.startMs, msB, last.root, last.type, last.confidence));
                    }
                    continue;
                }
                out.add(new Segment(msA, msB, 0, -1, 0f));
            } else {
                double sum = 0;
                for (int f = r[1]; f < r[2]; f++) sum += dots[f][state];
                double dot = sum / Math.max(1, r[2] - r[1]);
                float conf = (float) Math.max(0, Math.min(1, (dot - 0.55) / 0.32));
                out.add(new Segment(msA, msB, rootOf(state), state % N_TYPES, conf));
            }
        }
        return out;
    }

    // ═══════════════════════ FFT（迭代 radix-2）═══════════════════════

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        // 位反转
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr;        im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }
}
