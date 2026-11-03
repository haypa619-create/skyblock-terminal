package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Gradient-boosted regression trees on histogram bins (squared loss). Small and dependency free:
 * enough to learn non-linear and seasonal patterns from hundreds of thousands of samples. Pure logic.
 * Serializable with Gson (plain arrays).
 */
public final class Gbm {
    public static final int BINS = 64;
    public double base;
    public float[][] edges;           // per feature: upper bin edges (BINS-1 of them at most)
    public List<Tree> trees = new ArrayList<>();
    public double lr;

    public static final class Tree {
        public int[] feat, thr;       // node i: split on feat[i] at bin <= thr[i]; feat -1 = leaf
        public double[] val;
        double eval(byte[] bins) {
            int i = 0;
            while (feat[i] >= 0) i = 2 * i + ((bins[feat[i]] & 0xFF) <= thr[i] ? 1 : 2);
            return val[i];
        }
    }

    public byte[] bin(float[] x) {
        byte[] b = new byte[x.length];
        for (int f = 0; f < x.length; f++) b[f] = (byte) binOf(edges[f], x[f]);
        return b;
    }

    private static int binOf(float[] e, float v) {
        int lo = 0, hi = e.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (e[m] < v) lo = m + 1; else hi = m; }
        return lo;
    }

    public double predict(float[] x) {
        byte[] b = bin(x);
        double p = base;
        for (Tree t : trees) p += lr * t.eval(b);
        return p;
    }

    /** @param X rows of features, @param y targets */
    public static Gbm fit(float[][] X, float[] y, int nTrees, int depth, double lr, double lambda, int minLeaf, long seed) {
        int n = X.length, F = X[0].length;
        Gbm g = new Gbm();
        g.lr = lr;
        double sum = 0;
        for (float v : y) sum += v;
        g.base = sum / n;
        // bin edges from a sample of up to 20000 rows
        Random rnd = new Random(seed);
        int ns = Math.min(n, 20000);
        g.edges = new float[F][];
        for (int f = 0; f < F; f++) {
            float[] s = new float[ns];
            for (int i = 0; i < ns; i++) s[i] = X[ns == n ? i : rnd.nextInt(n)][f];
            Arrays.sort(s);
            List<Float> e = new ArrayList<>();
            for (int k = 1; k < BINS; k++) {
                float v = s[(int) ((long) k * (ns - 1) / BINS)];
                if (e.isEmpty() || v > e.get(e.size() - 1)) e.add(v);
            }
            g.edges[f] = new float[e.size()];
            for (int i = 0; i < e.size(); i++) g.edges[f][i] = e.get(i);
        }
        byte[][] Xb = new byte[F][n];
        for (int i = 0; i < n; i++) for (int f = 0; f < F; f++) Xb[f][i] = (byte) binOf(g.edges[f], X[i][f]);
        double[] pred = new double[n];
        Arrays.fill(pred, g.base);
        int nodesMax = (1 << (depth + 1)) - 1;
        for (int t = 0; t < nTrees; t++) {
            double[] r = new double[n];
            for (int i = 0; i < n; i++) r[i] = y[i] - pred[i];
            int[] rows = new int[n];
            int m = 0;
            for (int i = 0; i < n; i++) if (rnd.nextDouble() < 0.5) rows[m++] = i;
            Tree tree = new Tree();
            tree.feat = new int[nodesMax];
            tree.thr = new int[nodesMax];
            tree.val = new double[nodesMax];
            Arrays.fill(tree.feat, -1);
            int[] node = new int[n]; // node id per row (0 = root)
            boolean[] splittable = new boolean[nodesMax];
            splittable[0] = true;
            for (int level = 0; level < depth; level++) {
                int first = (1 << level) - 1, count = 1 << level;
                double[] hg = new double[count * F * BINS];
                int[] hc = new int[count * F * BINS];
                double[] ng = new double[count];
                int[] nc = new int[count];
                for (int k = 0; k < m; k++) {
                    int i = rows[k], nd = node[i] - first;
                    if (nd < 0 || nd >= count || !splittable[node[i]]) continue;
                    double ri = r[i];
                    ng[nd] += ri;
                    nc[nd]++;
                    int base = nd * F * BINS;
                    for (int f = 0; f < F; f++) { int idx = base + f * BINS + (Xb[f][i] & 0xFF); hg[idx] += ri; hc[idx]++; }
                }
                for (int nd = 0; nd < count; nd++) {
                    int id = first + nd;
                    if (!splittable[id] || nc[nd] < 2 * minLeaf) { splittable[id] = false; continue; }
                    double best = 1e-9;
                    int bf = -1, bt = -1;
                    double parent = ng[nd] * ng[nd] / (nc[nd] + lambda);
                    for (int f = 0; f < F; f++) {
                        double gl = 0;
                        int cl = 0;
                        int base = nd * F * BINS + f * BINS;
                        for (int b = 0; b < BINS - 1; b++) {
                            gl += hg[base + b];
                            cl += hc[base + b];
                            int cr = nc[nd] - cl;
                            if (cl < minLeaf || cr < minLeaf) continue;
                            double gr = ng[nd] - gl;
                            double gain = gl * gl / (cl + lambda) + gr * gr / (cr + lambda) - parent;
                            if (gain > best) { best = gain; bf = f; bt = b; }
                        }
                    }
                    if (bf < 0) { splittable[id] = false; continue; }
                    tree.feat[id] = bf;
                    tree.thr[id] = bt;
                    splittable[2 * id + 1] = true;
                    splittable[2 * id + 2] = true;
                }
                for (int k = 0; k < n; k++) { // route every row one level down
                    int id = node[k];
                    if (tree.feat[id] >= 0 && id >= first && id < first + count) node[k] = 2 * id + 1 + ((Xb[tree.feat[id]][k] & 0xFF) <= tree.thr[id] ? 0 : 1);
                }
            }
            // leaf values from the sampled rows
            double[] lg = new double[nodesMax];
            int[] lc = new int[nodesMax];
            for (int k = 0; k < m; k++) { int i = rows[k]; lg[node[i]] += r[i]; lc[node[i]]++; }
            for (int id = 0; id < nodesMax; id++) tree.val[id] = lc[id] == 0 ? 0 : lg[id] / (lc[id] + lambda);
            for (int i = 0; i < n; i++) pred[i] += lr * tree.val[node[i]];
            g.trees.add(tree);
        }
        return g;
    }
}
