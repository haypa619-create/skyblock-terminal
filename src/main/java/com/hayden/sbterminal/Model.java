package com.hayden.sbterminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The learning model: online ridge regression (SGD) from {@link Features} to the % move over the next 4 hours,
 * shared across items. Every sample is first used to TEST the model (predict before learning), so the
 * reported skill is genuinely out-of-sample. The model only influences ALGO once it has proven it beats guessing "no move". Pure logic.
 */
public final class Model {
    public double[] w = new double[Features.N], mean = new double[Features.N], m2 = new double[Features.N];
    public long n = 0;
    /** Running (exponential) out-of-sample numbers. */
    public double mseModel = 0, mseZero = 0, dirAcc = 0.5, maeModel = 0, maeZero = 0;
    public long dirN = 0;
    public String savedBy = "";

    public static final long MIN_SAMPLES = 1000;
    public static final double MIN_SKILL = 0.01, MIN_DIR = 0.52;
    private static final double LAMBDA = 0.0005;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private double[] standardize(double[] x) {
        double[] s = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            if (i == x.length - 1) { s[i] = 1; continue; } // bias
            double sd = n < 2 ? 1 : Math.max(0.05, Math.sqrt(m2[i] / n));
            s[i] = Math.max(-5, Math.min(5, (x[i] - mean[i]) / sd));
        }
        return s;
    }

    /** Predicted % move over the next 4 hours. */
    public synchronized double predict(double[] x) {
        double[] s = standardize(x);
        double p = 0;
        for (int i = 0; i < s.length; i++) p += w[i] * s[i];
        return Math.max(-15, Math.min(15, p));
    }

    /** Tests the model on this sample, then learns from it. y is the real % move. */
    public synchronized void train(double[] x, double y) {
        if (Double.isNaN(y)) return;
        y = Math.max(-15, Math.min(15, y));
        double[] s = standardize(x);
        double p = 0;
        for (int i = 0; i < s.length; i++) p += w[i] * s[i];
        p = Math.max(-15, Math.min(15, p));
        double e = y - p;
        double a = n < 500 ? 1.0 / (n + 1) : 0.002; // plain average at first, then a window of about 500 samples
        mseModel += a * (e * e - mseModel);
        mseZero += a * (y * y - mseZero);
        maeModel += a * (Math.abs(e) - maeModel);
        maeZero += a * (Math.abs(y) - maeZero);
        if (Math.abs(p) > 0.02 && y != 0) {
            dirN++;
            double ad = dirN < 500 ? 1.0 / dirN : 0.002;
            dirAcc += ad * ((Math.signum(p) == Math.signum(y) ? 1 : 0) - dirAcc);
        }
        double lr = 0.003 + 0.03 / (1 + n / 300.0);
        for (int i = 0; i < s.length; i++) w[i] += lr * (e * s[i] - LAMBDA * w[i]);
        n++;
        for (int i = 0; i < x.length; i++) { // running mean / variance (Welford)
            double d = x[i] - mean[i];
            mean[i] += d / n;
            m2[i] += d * (x[i] - mean[i]);
        }
    }

    /** 1 - mse(model) / mse(always zero). Above 0 means it beats guessing no move. */
    public synchronized double skill() {
        return n < 30 || mseZero <= 1e-12 ? 0 : 1 - mseModel / mseZero;
    }

    public synchronized boolean trusted() {
        return n >= MIN_SAMPLES && skill() >= MIN_SKILL && dirAcc >= MIN_DIR;
    }

    /** Why it is or isn't being used. */
    public synchronized String status() {
        if (n < MIN_SAMPLES) return "learning: " + n + " of " + MIN_SAMPLES + " samples before it can be trusted";
        if (skill() < MIN_SKILL) return "not trusted: no better than guessing no move (skill " + String.format("%.3f", skill()) + ")";
        if (dirAcc < MIN_DIR) return "not trusted: direction right only " + String.format("%.0f%%", dirAcc * 100) + " of the time";
        return "trusted: beating the no-move guess out of sample";
    }

    /** |weight| of each feature as a share of the total, for the ML screen. */
    public synchronized double[] importance() {
        double sum = 0;
        double[] out = new double[Features.N - 1];
        for (int i = 0; i < out.length; i++) { out[i] = Math.abs(w[i]); sum += out[i]; }
        if (sum > 0) for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    public synchronized double weight(int i) { return w[i]; }

    public synchronized void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            Terminal.LOG.warn("Could not save model: {}", e.toString());
        }
    }

    public static Model load(Path file) {
        try {
            if (Files.exists(file)) {
                Model m = GSON.fromJson(Files.readString(file), Model.class);
                if (m != null && m.w != null && m.w.length == Features.N && m.mean.length == Features.N && m.m2.length == Features.N) return m;
            }
        } catch (Exception e) {
            Terminal.LOG.warn("Could not read model, starting fresh: {}", e.toString());
        }
        return new Model();
    }
}
