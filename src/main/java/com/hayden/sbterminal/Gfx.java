package com.hayden.sbterminal;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Drawing helpers for the terminal: text in the bundled monospace font (IBM Plex Mono) or
 * Minecraft's font, boxes, lines. Monospace keeps every column on a character grid.
 */
final class Gfx {
    static final FontDescription MONO = new FontDescription.Resource(Identifier.fromNamespaceAndPath("sbterminal", "mono"));

    final GuiGraphicsExtractor g;
    final Font font;
    final boolean mono;

    Gfx(GuiGraphicsExtractor g, Font font, boolean mono) {
        this.g = g;
        this.font = font;
        this.mono = mono;
    }

    Component c(String s) {
        MutableComponent m = Component.literal(s);
        return mono ? m.withStyle(st -> st.withFont(MONO)) : m;
    }

    int w(String s) {
        return font.width(c(s));
    }

    /** Width of one character cell. */
    int cw() {
        return Math.max(4, w("0"));
    }

    /** Text at x,y. Returns the x after it. */
    int t(String s, int x, int y, int color) {
        g.text(font, c(s), x, y, color, false);
        return x + w(s);
    }

    void tr(String s, int right, int y, int color) {
        t(s, right - w(s), y, color);
    }

    void tc(String s, int center, int y, int color) {
        t(s, center - w(s) / 2, y, color);
    }

    /** Text cut to fit maxWidth. */
    String fit(String s, int maxWidth) {
        if (w(s) <= maxWidth) return s;
        String out = s;
        while (!out.isEmpty() && w(out + "…") > maxWidth) out = out.substring(0, out.length() - 1);
        return out + "…";
    }

    /** Scaled text (2 = double size). */
    void big(String s, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, c(s), 0, 0, color, false);
        g.pose().popMatrix();
    }

    int bigW(String s, float scale) {
        return (int) Math.ceil(w(s) * scale);
    }

    void fill(int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }

    void box(int x, int y, int w, int h, int color) {
        g.outline(x, y, w, h, color);
    }

    void hline(int x1, int x2, int y, int color) {
        g.fill(x1, y, x2, y + 1, color);
    }

    void dotted(int x1, int x2, int y, int color) {
        for (int x = x1; x < x2; x += 3) g.fill(x, y, x + 1, y + 1, color);
    }

    void line(int x0, int y0, int x1, int y1, int color) {
        if (x1 < x0) { int t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }
        int dx = x1 - x0;
        if (dx == 0) { g.fill(x0, Math.min(y0, y1), x0 + 1, Math.max(y0, y1) + 1, color); return; }
        int prev = y0;
        for (int x = x0; x <= x1; x++) {
            int y = y0 + (int) Math.round((y1 - y0) * (double) (x - x0) / dx);
            g.fill(x, Math.min(prev, y), x + 1, Math.max(prev, y) + 1, color);
            prev = y;
        }
    }
}
