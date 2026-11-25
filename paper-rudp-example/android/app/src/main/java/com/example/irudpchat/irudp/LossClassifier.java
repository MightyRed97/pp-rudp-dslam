package com.example.irudpchat.irudp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GBDT loss classifier of the paper (Sec. 4.7, Eqs. 9-11): 60 trees of depth 3, theta = 0.4. The model
 * is the text file app/src/main/assets/gbdt.txt (the same as server/model/gbdt.txt):
 * "gbdt nFeatures nTrees f0 learningRate", then per tree "tree nNodes" and its nodes in preorder, each
 * "split feature threshold left right" (x[feature] <= threshold goes left) or "leaf value".
 */
public final class LossClassifier {
    private static final class Tree {
        final int[] feature;                       // -1 for a leaf
        final double[] threshold, value;
        final int[] left, right;

        Tree(int size) {
            feature = new int[size];
            threshold = new double[size];
            value = new double[size];
            left = new int[size];
            right = new int[size];
        }
    }

    private final List<Tree> trees;
    private final double f0, lr;

    private LossClassifier(List<Tree> trees, double f0, double lr) {
        this.trees = trees;
        this.f0 = f0;
        this.lr = lr;
    }

    public static LossClassifier load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<Tree> trees = new ArrayList<>();
        double f0 = 0, lr = 0;
        Tree cur = null;
        int node = 0;
        String raw;
        while ((raw = r.readLine()) != null) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "gbdt":
                    f0 = Double.parseDouble(t[3]);
                    lr = Double.parseDouble(t[4]);
                    break;
                case "tree":
                    cur = new Tree(Integer.parseInt(t[1]));
                    trees.add(cur);
                    node = 0;
                    break;
                case "split":
                    if (cur == null) throw new IOException("split before tree");
                    cur.feature[node] = Integer.parseInt(t[1]);
                    cur.threshold[node] = Double.parseDouble(t[2]);
                    cur.left[node] = Integer.parseInt(t[3]);
                    cur.right[node] = Integer.parseInt(t[4]);
                    node++;
                    break;
                case "leaf":
                    if (cur == null) throw new IOException("leaf before tree");
                    cur.feature[node] = -1;
                    cur.value[node] = Double.parseDouble(t[1]);
                    node++;
                    break;
                default:
                    throw new IOException("unknown line in the model: " + line);
            }
        }
        if (trees.isEmpty()) throw new IOException("the model has no trees");
        return new LossClassifier(trees, f0, lr);
    }

    /** Eq. (10): the probability that a loss event was caused by congestion. */
    public double prob(double[] x) {
        double s = 0;
        for (Tree t : trees) {
            int i = 0;
            while (t.feature[i] >= 0) i = x[t.feature[i]] <= t.threshold[i] ? t.left[i] : t.right[i];
            s += t.value[i];
        }
        return 1 / (1 + Math.exp(-(f0 + lr * s)));
    }

    /** Eq. (9): features from the sender's structures, all dimensionless. */
    public static double[] features(double latestRtt, double minRtt, List<Double> rtts, int lostRun, double lossEwma,
                                    int inflight, double btlRate, double gapUs, double srtt) {
        double q = (latestRtt - minRtt) / minRtt;
        double trend = 0;
        int m = rtts.size();
        if (m >= 8) {
            double last = 0, prev = 0;
            for (int i = m - 4; i < m; i++) last += rtts.get(i);
            for (int i = 0; i < m - 4; i++) prev += rtts.get(i);
            trend = (last / 4 - prev / (m - 4)) / minRtt;
        }
        double bdp = btlRate * minRtt;                // segments per microsecond x microseconds
        double sat = bdp > 0 ? inflight / bdp : 1;
        return new double[] {q, trend, lostRun, 100 * lossEwma, sat, Math.min(gapUs / srtt, 50)};
    }
}
