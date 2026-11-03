package com.hayden.sbterminal;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The terminal. Everything is drawn by hand (no Minecraft buttons): black background, amber
 * function bar, a command line, dense tables, charts and an order book.
 *
 * Keys: type a command and Enter. Up/Down pick a row, Enter opens it. Left/Right change the
 * chart range, movers window or screener sort. F1 HELP, F2 MON, F3 MOV, F4 SCR,
 * F5 add/remove the open item on your Monitor, Delete removes a Monitor row, Esc goes back.
 */
public final class TerminalScreen extends Screen {
    // ---- colors ----
    static final int BG = 0xFF000000, PANEL = 0xFF0B0B0B, LINE = 0xFF262626, SEL = 0xFF16233C,
            AMBER = 0xFFFFA028, WHITE = 0xFFE6E6E6, GRAY = 0xFF8C8C8C, DIM = 0xFF5A5A5A,
            GREEN = 0xFF3CDC50, RED = 0xFFFF4848, BLUE = 0xFF58A6FF, YELLOW = 0xFFFFE15A, BLACK = 0xFF000000;
    static final int LH = 11;

    enum View { MON, MOV, SCR, ITEM, HELP, PICK }

    private record Place(View view, String item) {}

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

    // ---- state (static so it survives closing and reopening) ----
    private static View view = View.MON;
    private static String item = null;
    private static final Deque<Place> back = new ArrayDeque<>();
    private static Command.Range range = Command.Range.D1;
    private static boolean moversDay = true;
    private static List<Screener.Filter> filters = new ArrayList<>();
    private static Screener.Field sortField = Screener.Field.FLOW;
    private static int sel = 0, scroll = 0;
    private static List<String> pick = List.of();

    private String cmd = "";
    private String status = "";
    private int statusColor = GRAY;
    private final List<Hit> hits = new ArrayList<>();
    /** Item ids in the order they're drawn on the current list view (for Up/Down/Enter). */
    private List<String> rows = new ArrayList<>();

    public TerminalScreen() {
        super(Component.literal("SkyBlock Terminal"));
    }

    @Override
    protected void init() {
        Feed.terminalOpen = true;
        if (Terminal.CONFIG.watchlist.isEmpty() && view == View.MON) status("Type an item name and press Enter, or F3 for movers. ADD <item> builds your Monitor.", GRAY);
    }

    @Override
    public void removed() {
        Feed.terminalOpen = false;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =====================================================================================
    // drawing
    // =====================================================================================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float d) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        hits.clear();
        g.fill(0, 0, width, height, BG);
        drawTopBar(g);
        drawCommandLine(g);
        int top = 28, bottom = height - 13;
        switch (view) {
            case MON -> drawMonitor(g, top, bottom, mx, my);
            case MOV -> drawMovers(g, top, bottom, mx, my);
            case SCR -> drawScreener(g, top, bottom, mx, my);
            case ITEM -> drawItem(g, top, bottom, mx, my);
            case HELP -> drawHelp(g, top, bottom);
            case PICK -> drawPick(g, top, bottom, mx, my);
        }
        drawFooter(g);
    }

    private void drawTopBar(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, 12, AMBER);
        String title = "SKYBLOCK TERMINAL  <" + viewTitle() + ">";
        text(g, title, 4, 2, BLACK);
        Market.Snapshot s = Feed.snapshot;
        StringBuilder right = new StringBuilder();
        if (Terminal.purse >= 0) right.append("PURSE ").append(Fmt.px(Terminal.purse)).append("   ");
        if (s.hypixelUpdated() > 0) right.append("DATA ").append(Fmt.ago(System.currentTimeMillis() - s.hypixelUpdated())).append(" OLD   ");
        right.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        textRight(g, right.toString(), width - 4, 2, BLACK);
    }

    private String viewTitle() {
        return switch (view) {
            case MON -> "MON  Monitor";
            case MOV -> "MOV  Movers";
            case SCR -> "SCR  Screener";
            case ITEM -> Feed.name(item).toUpperCase(Locale.US);
            case HELP -> "HELP";
            case PICK -> "Which item?";
        };
    }

    private void drawCommandLine(GuiGraphicsExtractor g) {
        g.fill(0, 13, width, 26, PANEL);
        text(g, ">", 4, 16, AMBER);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        text(g, cmd + (blink ? "_" : ""), 12, 16, WHITE);
        String st = Feed.error != null ? Feed.error : status;
        int sc = Feed.error != null ? RED : statusColor;
        if (!st.isEmpty()) textRight(g, font.plainSubstrByWidth(st, width / 2), width - 4, 16, sc);
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        int y = height - 12;
        g.fill(0, y, width, height, PANEL);
        String keys = switch (view) {
            case ITEM -> "F1 HELP  F2 MON  F3 MOV  F4 SCR  F5 " + (Terminal.CONFIG.watchlist.contains(item) ? "REMOVE FROM" : "ADD TO") + " MON  ←→ RANGE  ESC BACK";
            case MOV -> "F1 HELP  F2 MON  F3 MOV  F4 SCR  ↑↓ PICK  ENTER OPEN  ←→ 1H/24H  ESC BACK";
            case SCR -> "F1 HELP  F2 MON  F3 MOV  F4 SCR  ↑↓ PICK  ENTER OPEN  ←→ SORT  ESC BACK";
            case MON -> "F1 HELP  F2 MON  F3 MOV  F4 SCR  ↑↓ PICK  ENTER OPEN  DEL REMOVE  ESC CLOSE";
            default -> "F1 HELP  F2 MON  F3 MOV  F4 SCR  ESC BACK";
        };
        text(g, keys, 4, y + 2, YELLOW);
        String bf = Feed.backfillStatus();
        if (!bf.isEmpty()) textRight(g, bf, width - 4, y + 2, DIM);
    }

    // ---------- MON ----------

    private void drawMonitor(GuiGraphicsExtractor g, int top, int bottom, int mx, int my) {
        List<String> ids = new ArrayList<>(Terminal.CONFIG.watchlist);
        rows = ids;
        if (ids.isEmpty()) {
            text(g, "Your Monitor is empty.", 6, top + 4, WHITE);
            text(g, "ADD enchanted coal    adds an item       MOV    see what's moving", 6, top + 4 + LH, GRAY);
            text(g, "radar                 opens an item       SCR    screen every item", 6, top + 4 + 2 * LH, GRAY);
            return;
        }
        Table t = new Table(width, new String[]{"ITEM", "BID", "ASK", "SPR%", "1H", "24H", "VOL 7D", "FLOW/D"},
                new double[]{0.30, 0.10, 0.10, 0.08, 0.09, 0.09, 0.12, 0.12});
        t.header(g, top);
        listRows(g, t, ids, top + LH + 2, bottom, mx, my);
    }

    /** Draws item rows in the standard columns, with selection and scrolling. */
    private void listRows(GuiGraphicsExtractor g, Table t, List<String> ids, int y0, int bottom, int mx, int my) {
        int fit = Math.max(1, (bottom - y0) / LH);
        clampSel(ids.size(), fit);
        Market.Snapshot s = Feed.snapshot;
        for (int i = scroll; i < Math.min(ids.size(), scroll + fit); i++) {
            String id = ids.get(i);
            int y = y0 + (i - scroll) * LH;
            Market.Product p = s.get(id);
            int idx = i;
            rowBackground(g, y, i == sel, mx, my, () -> { sel = idx; open(id); });
            t.cell(g, 0, y, Feed.name(id), WHITE);
            if (p == null) { t.cell(g, 1, y, "no data", DIM); continue; }
            t.cell(g, 1, y, Fmt.px(p.bid()), GREEN);
            t.cell(g, 2, y, Fmt.px(p.ask()), BLUE);
            t.cell(g, 3, y, String.format(Locale.US, "%.2f", p.spreadPct()), WHITE);
            double c1 = Feed.change(id, 3_600_000L), c24 = Feed.change(id, 86_400_000L);
            t.cell(g, 4, y, Fmt.pct(c1), changeColor(c1));
            t.cell(g, 5, y, Fmt.pct(c24), changeColor(c24));
            t.cell(g, 6, y, Fmt.qty(p.weekVolume()), WHITE);
            t.cell(g, 7, y, Fmt.px(p.coinFlowDay()), GRAY);
        }
        scrollHint(g, ids.size(), fit, bottom);
    }

    // ---------- MOV ----------

    private void drawMovers(GuiGraphicsExtractor g, int top, int bottom, int mx, int my) {
        long window = moversDay ? 86_400_000L : 3_600_000L;
        List<List<Movers.Move>> mv = Movers.top(Feed.snapshot.products().values(), id -> Feed.change(id, window),
                Terminal.CONFIG.minWeekVolume, 40);
        text(g, (moversDay ? "24 HOUR" : "1 HOUR") + " MOVERS", 6, top, AMBER);
        text(g, "items trading at least " + Fmt.qty(Terminal.CONFIG.minWeekVolume) + " a week", 110, top, DIM);
        if (!moversDay && Feed.RECORDER.recordingMs(System.currentTimeMillis()) < 3_600_000L)
            textRight(g, "1H fills in after an hour of recording (" + Fmt.ago(Feed.RECORDER.recordingMs(System.currentTimeMillis())) + " so far)", width - 6, top, DIM);
        int half = width / 2;
        int y0 = top + LH + 2;
        text(g, "▲ GAINERS", 6, y0, GREEN);
        text(g, "▼ LOSERS", half + 6, y0, RED);
        y0 += LH;
        // Selection walks down gainers, then losers
        rows = new ArrayList<>();
        for (Movers.Move m : mv.get(0)) rows.add(m.p().id());
        int gainers = rows.size();
        for (Movers.Move m : mv.get(1)) rows.add(m.p().id());
        int fit = Math.max(1, (bottom - y0) / LH);
        if (sel >= rows.size()) sel = Math.max(0, rows.size() - 1);
        drawMoveColumn(g, mv.get(0), 0, half, y0, fit, 0, mx, my);
        drawMoveColumn(g, mv.get(1), half, half, y0, fit, gainers, mx, my);
        if (rows.isEmpty()) text(g, moversDay ? "Loading 24h history from Coflnet..." : "Not enough recording yet.", 6, y0 + LH, GRAY);
    }

    private void drawMoveColumn(GuiGraphicsExtractor g, List<Movers.Move> list, int x, int w, int y0, int fit, int selOffset,
                                int mx, int my) {
        for (int i = 0; i < Math.min(list.size(), fit); i++) {
            Movers.Move m = list.get(i);
            int y = y0 + i * LH;
            boolean selected = sel == selOffset + i;
            int idx = selOffset + i;
            String id = m.p().id();
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + LH;
            if (selected || hover) g.fill(x, y - 1, x + w, y + LH - 1, selected ? SEL : 0xFF101010);
            hits.add(new Hit(x, y - 1, x + w, y + LH - 1, () -> { sel = idx; open(id); }));
            text(g, font.plainSubstrByWidth(Feed.name(id), (int) (w * 0.5)), x + 6, y, WHITE);
            textRight(g, Fmt.pct(m.change()), x + (int) (w * 0.70), y, changeColor(m.change()));
            textRight(g, Fmt.px(m.p().mid()), x + (int) (w * 0.85), y, WHITE);
            textRight(g, Fmt.qty(m.p().weekVolume()), x + w - 6, y, GRAY);
        }
    }

    // ---------- SCR ----------

    private void drawScreener(GuiGraphicsExtractor g, int top, int bottom, int mx, int my) {
        List<Screener.Filter> fs = new ArrayList<>(filters);
        if (fs.stream().noneMatch(f -> f.field() == Screener.Field.VOL)) fs.add(new Screener.Filter(Screener.Field.VOL, ">=", Terminal.CONFIG.minWeekVolume));
        List<Screener.Row> res = Screener.run(Feed.snapshot.products().values(), fs,
                id -> Feed.change(id, 86_400_000L), id -> Feed.change(id, 3_600_000L), sortField, true);
        StringBuilder fl = new StringBuilder();
        for (Screener.Filter f : fs) fl.append(f).append("  ");
        text(g, res.size() + " ITEMS", 6, top, AMBER);
        text(g, fl.toString(), 70, top, GRAY);
        textRight(g, "SORT: " + sortField + " ↓", width - 6, top, YELLOW);
        Table t = new Table(width, new String[]{"ITEM", "BID", "ASK", "SPR%", "1H", "24H", "VOL 7D", "FLOW/D"},
                new double[]{0.30, 0.10, 0.10, 0.08, 0.09, 0.09, 0.12, 0.12});
        int hy = top + LH + 2;
        t.header(g, hy);
        // underline the sorted column
        int sortCol = switch (sortField) { case PX -> 1; case SPR -> 3; case CHG1H -> 4; case CHG -> 5; case VOL -> 6; case FLOW -> 7; case ORD -> -1; };
        if (sortCol >= 0) t.underline(g, sortCol, hy + LH - 1, YELLOW);
        List<String> ids = new ArrayList<>();
        for (Screener.Row r : res) ids.add(r.p().id());
        rows = ids;
        listRows(g, t, ids, hy + LH + 2, bottom, mx, my);
    }

    // ---------- ITEM ----------

    private void drawItem(GuiGraphicsExtractor g, int top, int bottom, int mx, int my) {
        Market.Product p = Feed.snapshot.get(item);
        if (p == null) { text(g, "No live data for " + item + " yet.", 6, top + 4, GRAY); return; }
        long now = System.currentTimeMillis();
        // title line
        int x = 6;
        x = textW(g, Feed.name(item).toUpperCase(Locale.US), x, top, AMBER) + 8;
        x = textW(g, item, x, top, DIM) + 12;
        x = pair(g, "BID ", Fmt.px(p.bid()), x, top, GREEN);
        x = pair(g, "ASK ", Fmt.px(p.ask()), x, top, BLUE);
        x = pair(g, "SPR ", String.format(Locale.US, "%s (%.2f%%)", Fmt.px(p.spread()), p.spreadPct()), x, top, WHITE);
        double c1 = Feed.change(item, 3_600_000L), c24 = Feed.change(item, 86_400_000L);
        List<Bars.Point> wk = Feed.week(item);
        double c7 = wk == null || wk.isEmpty() ? Double.NaN : Bars.change(wk, p.mid(), now, 7 * 86_400_000L);
        x = pair(g, "1H ", Fmt.pct(c1), x, top, changeColor(c1));
        x = pair(g, "24H ", Fmt.pct(c24), x, top, changeColor(c24));
        pair(g, "7D ", Fmt.pct(c7), x, top, changeColor(c7));
        if (Terminal.CONFIG.watchlist.contains(item)) textRight(g, "★ ON MONITOR", width - 6, top, YELLOW);

        int bookW = Math.max(150, (int) (width * 0.30));
        int chartX = 6, chartW = width - bookW - 18, chartY = top + LH + 4;
        int statsH = 5 * LH + 6;
        int chartH = Math.max(60, bottom - chartY - statsH - 4);
        drawChart(g, p, chartX, chartY, chartW, chartH, now, mx, my);
        drawStats(g, p, chartX, chartY + chartH + 4, chartW, now);
        drawBook(g, p, width - bookW - 6, chartY, bookW, bottom - chartY);
    }

    private void drawChart(GuiGraphicsExtractor g, Market.Product p, int x, int y, int w, int h, long now, int mx, int my) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, LINE);
        // range tabs
        int tx = x + 4;
        for (Command.Range r : Command.Range.values()) {
            String lab = switch (r) { case H1 -> "1H"; case D1 -> "1D"; case W1 -> "1W"; };
            int tw = font.width(lab) + 8;
            boolean on = r == range;
            g.fill(tx, y + 3, tx + tw, y + 3 + LH, on ? AMBER : 0xFF1A1A1A);
            text(g, lab, tx + 4, y + 4, on ? BLACK : GRAY);
            Command.Range rr = r;
            hits.add(new Hit(tx, y + 3, tx + tw, y + 3 + LH, () -> range = rr));
            tx += tw + 3;
        }
        text(g, "■ bid", tx + 8, y + 4, GREEN);
        text(g, "■ ask", tx + 40, y + 4, BLUE);

        long span = switch (range) { case H1 -> 3_600_000L; case D1 -> 86_400_000L; case W1 -> 7 * 86_400_000L; };
        List<Bars.Point> src;
        if (range == Command.Range.W1) {
            List<Bars.Point> wk = Feed.week(item);
            src = wk == null ? new ArrayList<>() : Bars.merge(wk, Feed.RECORDER.points(item));
        } else {
            Feed.day(item); // make sure it's requested
            src = Feed.recent(item);
        }
        List<Bars.Point> pts = new ArrayList<>(Bars.window(src, now - span, now));
        pts.add(new Bars.Point(now, p.bid(), p.ask()));

        int px = x + 6, py = y + LH + 8, pw = w - 62, ph = h - LH - 22;
        double[] rg = Bars.range(pts);
        if (rg == null || pts.size() < 2) {
            text(g, Feed.loading(item) ? "Loading history from Coflnet..." : "No history for this range yet.", px + 6, py + ph / 2, GRAY);
            return;
        }
        double pad = Math.max((rg[1] - rg[0]) * 0.08, rg[1] * 0.002);
        double lo = rg[0] - pad, hi = rg[1] + pad;
        // grid + y labels
        for (int i = 0; i <= 4; i++) {
            double v = lo + (hi - lo) * i / 4;
            int gy = py + ph - (int) Math.round((v - lo) / (hi - lo) * ph);
            for (int gx = px; gx < px + pw; gx += 4) g.fill(gx, gy, gx + 1, gy + 1, LINE);
            text(g, Fmt.px(v), px + pw + 4, gy - 4, DIM);
        }
        // x labels
        long from = now - span;
        text(g, label(from, span), px, py + ph + 3, DIM);
        String mid = label(from + span / 2, span);
        text(g, mid, px + pw / 2 - font.width(mid) / 2, py + ph + 3, DIM);
        textRight(g, "now", px + pw, py + ph + 3, DIM);
        // high/low wicks for coarse bars
        for (Bars.Point pt : pts) {
            if (pt.askHigh() <= pt.ask() && pt.bidLow() >= pt.bid()) continue;
            int cx = px + (int) ((pt.time() - from) * (double) pw / span);
            int y1 = yOf(pt.askHigh(), lo, hi, py, ph), y2 = yOf(pt.bidLow(), lo, hi, py, ph);
            g.fill(cx, y1, cx + 1, y2 + 1, 0xFF2A2A2A);
        }
        // lines
        plot(g, pts, true, from, span, lo, hi, px, py, pw, ph, GREEN);
        plot(g, pts, false, from, span, lo, hi, px, py, pw, ph, BLUE);
        // last price tags
        tag(g, p.ask(), lo, hi, px + pw, py, ph, BLUE);
        tag(g, p.bid(), lo, hi, px + pw, py, ph, GREEN);
        // crosshair readout
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            long t = from + (long) ((mx - px) * (double) span / pw);
            Bars.Point near = null;
            for (Bars.Point pt : pts) if (near == null || Math.abs(pt.time() - t) < Math.abs(near.time() - t)) near = pt;
            for (int gy = py; gy < py + ph; gy += 2) g.fill(mx, gy, mx + 1, gy + 1, 0xFF444444);
            if (near != null) {
                String s = label(near.time(), span) + "  bid " + Fmt.px(near.bid()) + "  ask " + Fmt.px(near.ask());
                int sw = font.width(s) + 6, sx = Math.min(mx + 6, px + pw - sw);
                g.fill(sx, py + 2, sx + sw, py + 2 + LH, 0xEE000000);
                text(g, s, sx + 3, py + 3, WHITE);
            }
        }
    }

    private void plot(GuiGraphicsExtractor g, List<Bars.Point> pts, boolean bid, long from, long span, double lo, double hi,
                      int px, int py, int pw, int ph, int color) {
        int lx = Integer.MIN_VALUE, ly = 0;
        for (Bars.Point pt : pts) {
            double v = bid ? pt.bid() : pt.ask();
            if (v <= 0) { lx = Integer.MIN_VALUE; continue; }
            int cx = px + (int) ((pt.time() - from) * (double) pw / span);
            int cy = yOf(v, lo, hi, py, ph);
            if (lx != Integer.MIN_VALUE) line(g, lx, ly, cx, cy, color);
            lx = cx;
            ly = cy;
        }
    }

    private static int yOf(double v, double lo, double hi, int py, int ph) {
        return py + ph - (int) Math.round((v - lo) / (hi - lo) * ph);
    }

    private void tag(GuiGraphicsExtractor g, double v, double lo, double hi, int x, int py, int ph, int color) {
        if (v <= 0) return;
        int y = yOf(v, lo, hi, py, ph);
        String s = Fmt.px(v);
        g.fill(x + 2, y - 5, x + 6 + font.width(s), y + 5, color);
        text(g, s, x + 4, y - 4, BLACK);
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
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

    private String label(long t, long span) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault());
        return span <= 86_400_000L ? z.format(DateTimeFormatter.ofPattern("HH:mm")) : z.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US));
    }

    private void drawStats(GuiGraphicsExtractor g, Market.Product p, int x, int y, int w, long now) {
        List<Bars.Point> day = Bars.window(Feed.recent(item), now - 86_400_000L, now);
        double[] r24 = Bars.range(day);
        String[][] cells = {
                {"MID", Fmt.px(p.mid())}, {"SPREAD", Fmt.px(p.spread())},
                {"24H LOW / HIGH", r24 == null ? "-" : Fmt.px(r24[0]) + " / " + Fmt.px(r24[1])},
                {"INSTA-BUYS 7D", Fmt.qty(p.instaBuyWeek())}, {"INSTA-SELLS 7D", Fmt.qty(p.instaSellWeek())},
                {"COIN FLOW / DAY", Fmt.px(p.coinFlowDay())},
                {"BUY ORDERS", p.bidOrders() + " orders, " + Fmt.qty(p.bidUnits()) + " units"},
                {"SELL OFFERS", p.askOrders() + " orders, " + Fmt.qty(p.askUnits()) + " units"},
                {"BUY/SELL PRESSURE", pressure(p)},
                {"TOP BID SIZE", p.bidLevels().length >= 3 ? Fmt.qty(p.bidLevels()[1]) + " in " + (int) p.bidLevels()[2] : "-"},
        };
        int cols = 2, colW = w / cols;
        for (int i = 0; i < cells.length; i++) {
            int cx = x + (i % cols) * colW, cy = y + (i / cols) * LH;
            text(g, cells[i][0], cx, cy, GRAY);
            textRight(g, cells[i][1], cx + colW - 10, cy, WHITE);
        }
    }

    /** Weekly insta-buys vs insta-sells: who's in a hurry. */
    private static String pressure(Market.Product p) {
        double b = p.instaBuyWeek(), s = p.instaSellWeek();
        if (b + s <= 0) return "-";
        double r = b / (b + s) * 100;
        return String.format(Locale.US, "%.0f%% buy / %.0f%% sell", r, 100 - r);
    }

    private void drawBook(GuiGraphicsExtractor g, Market.Product p, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, LINE);
        text(g, "ORDER BOOK", x + 4, y + 3, AMBER);
        text(g, "PRICE", x + 4, y + 3 + LH, DIM);
        textRight(g, "UNITS", x + (int) (w * 0.72), y + 3 + LH, DIM);
        textRight(g, "ORD", x + w - 4, y + 3 + LH, DIM);
        int rowsEach = Math.max(1, (h - 3 * LH - 8) / 2 / LH);
        double max = 1;
        for (int i = 1; i < p.askLevels().length; i += 3) max = Math.max(max, p.askLevels()[i]);
        for (int i = 1; i < p.bidLevels().length; i += 3) max = Math.max(max, p.bidLevels()[i]);
        int mid = y + 3 + 2 * LH + rowsEach * LH;
        // asks: best ask just above the middle line
        int nA = Math.min(rowsEach, p.askLevels().length / 3);
        for (int i = 0; i < nA; i++) bookRow(g, p.askLevels(), i, x, mid - (i + 1) * LH, w, max, 0x40FF4848, RED);
        g.fill(x + 2, mid, x + w - 2, mid + 1, LINE);
        textRight(g, "spread " + Fmt.px(p.spread()), x + w - 4, mid - 1 - 0, DIM);
        int nB = Math.min(rowsEach, p.bidLevels().length / 3);
        for (int i = 0; i < nB; i++) bookRow(g, p.bidLevels(), i, x, mid + 2 + i * LH, w, max, 0x403CDC50, GREEN);
    }

    private void bookRow(GuiGraphicsExtractor g, double[] lv, int i, int x, int y, int w, double max, int barColor, int color) {
        double price = lv[3 * i], units = lv[3 * i + 1];
        int orders = (int) lv[3 * i + 2];
        int bar = (int) ((w - 8) * Math.min(1, units / max));
        g.fill(x + w - 4 - bar, y, x + w - 4, y + LH - 1, barColor);
        text(g, Fmt.px(price), x + 4, y + 1, color);
        textRight(g, Fmt.qty(units), x + (int) (w * 0.72), y + 1, WHITE);
        textRight(g, Integer.toString(orders), x + w - 4, y + 1, GRAY);
    }

    // ---------- HELP / PICK ----------

    private void drawHelp(GuiGraphicsExtractor g, int top, int bottom) {
        String[][] lines = {
                {"COMMANDS", ""},
                {"radar", "open an item (any part of its name, or initials: ec = Enchanted Coal)"},
                {"radar GP 1W", "item chart, range 1H / 1D / 1W      radar OB = order book focus"},
                {"MON", "your Monitor (watchlist)"},
                {"ADD radar / DEL radar", "add to / remove from your Monitor"},
                {"MOV", "biggest movers, 24h or 1h (Left/Right)"},
                {"SCR VOL>50K SPR>3 PX<1M CHG<-5", "screen every item. Fields: VOL FLOW PX SPR CHG CHG1H ORD; K/M/B allowed"},
                {"", ""},
                {"KEYS", ""},
                {"Up / Down, Enter", "pick a row, open it        click a row also opens it"},
                {"Left / Right", "chart range, movers window, screener sort"},
                {"F1 F2 F3 F4", "HELP, MON, MOV, SCR        F5 on an item: add/remove from Monitor"},
                {"Esc", "clear the command line, then go back, then close"},
                {"", ""},
                {"DATA", ""},
                {"Live", "Hypixel's public Bazaar API, checked every few seconds (header shows how old it is)"},
                {"History", "Coflnet (free): about 5-minute points for a day, 2-hour bars for a week, plus the terminal's own recording"},
        };
        int y = top + 2;
        for (String[] l : lines) {
            if (y > bottom - LH) break;
            if (l[1].isEmpty()) text(g, l[0], 6, y, AMBER);
            else { text(g, l[0], 12, y, YELLOW); text(g, l[1], 12 + (int) (width * 0.30), y, WHITE); }
            y += LH;
        }
    }

    private void drawPick(GuiGraphicsExtractor g, int top, int bottom, int mx, int my) {
        text(g, "More than one item matches. Pick one:", 6, top, AMBER);
        Table t = new Table(width, new String[]{"ITEM", "BID", "ASK", "SPR%", "1H", "24H", "VOL 7D", "FLOW/D"},
                new double[]{0.30, 0.10, 0.10, 0.08, 0.09, 0.09, 0.12, 0.12});
        t.header(g, top + LH + 2);
        rows = pick;
        listRows(g, t, pick, top + 2 * LH + 4, bottom, mx, my);
    }

    // ---------- helpers ----------

    private void rowBackground(GuiGraphicsExtractor g, int y, boolean selected, int mx, int my, Runnable click) {
        boolean hover = my >= y - 1 && my < y + LH - 1 && mx >= 0 && mx < width;
        if (selected || hover) g.fill(0, y - 1, width, y + LH - 1, selected ? SEL : 0xFF101010);
        hits.add(new Hit(0, y - 1, width, y + LH - 1, click));
    }

    private void clampSel(int n, int fit) {
        if (n == 0) { sel = 0; scroll = 0; return; }
        sel = Math.max(0, Math.min(sel, n - 1));
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + fit) scroll = sel - fit + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - fit)));
    }

    private void scrollHint(GuiGraphicsExtractor g, int n, int fit, int bottom) {
        if (n > fit) textRight(g, (scroll + 1) + "-" + Math.min(n, scroll + fit) + " of " + n, width - 6, bottom - LH + 1, DIM);
    }

    static int changeColor(double c) {
        return !Double.isFinite(c) ? DIM : c > 0 ? GREEN : c < 0 ? RED : WHITE;
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, false);
    }

    private int textW(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        text(g, s, x, y, color);
        return x + font.width(s);
    }

    private void textRight(GuiGraphicsExtractor g, String s, int right, int y, int color) {
        text(g, s, right - font.width(s), y, color);
    }

    private int pair(GuiGraphicsExtractor g, String label, String value, int x, int y, int color) {
        x = textW(g, label, x, y, GRAY);
        return textW(g, value, x, y, color) + 12;
    }

    /** Fixed columns: first left-aligned, the rest right-aligned at their right edge. */
    private final class Table {
        final String[] heads;
        final int[] right;
        final int left = 6;

        Table(int width, String[] heads, double[] widths) {
            this.heads = heads;
            right = new int[heads.length];
            double acc = 0;
            for (int i = 0; i < heads.length; i++) {
                acc += widths[i];
                right[i] = left + (int) ((width - 2 * left) * acc);
            }
        }

        void header(GuiGraphicsExtractor g, int y) {
            for (int i = 0; i < heads.length; i++) cell(g, i, y, heads[i], AMBER);
            g.fill(left, y + LH - 1, width - left, y + LH, LINE);
        }

        void underline(GuiGraphicsExtractor g, int col, int y, int color) {
            int r = right[col], l = r - font.width(heads[col]);
            g.fill(l, y, r, y + 1, color);
        }

        void cell(GuiGraphicsExtractor g, int col, int y, String s, int color) {
            if (col == 0) {
                text(g, font.plainSubstrByWidth(s, right[0] - left - 6), left, y, color);
            } else {
                textRight(g, s, right[col], y, color);
            }
        }
    }

    private void status(String s, int color) {
        status = s;
        statusColor = color;
    }

    // =====================================================================================
    // input
    // =====================================================================================

    @Override
    public boolean charTyped(CharacterEvent e) {
        String c = e.codepointAsString();
        if (c.isEmpty() || c.charAt(0) < 32 || cmd.length() > 120) return true;
        cmd += c;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        switch (k) {
            case 256 -> { // Esc
                if (!cmd.isEmpty()) cmd = "";
                else if (!back.isEmpty()) { Place pl = back.pop(); view = pl.view(); item = pl.item(); sel = 0; scroll = 0; }
                else onClose();
                return true;
            }
            case 259 -> { if (!cmd.isEmpty()) cmd = cmd.substring(0, cmd.length() - 1); return true; } // Backspace
            case 257, 335 -> { // Enter
                if (!cmd.isBlank()) { run(cmd); cmd = ""; }
                else if (view != View.ITEM && sel < rows.size()) open(rows.get(sel));
                return true;
            }
            case 265 -> { sel = Math.max(0, sel - 1); return true; } // Up
            case 264 -> { sel = Math.min(Math.max(0, rows.size() - 1), sel + 1); return true; } // Down
            case 266 -> { sel = Math.max(0, sel - 10); return true; } // Page Up
            case 267 -> { sel = Math.min(Math.max(0, rows.size() - 1), sel + 10); return true; } // Page Down
            case 263, 262 -> { horizontal(k == 262 ? 1 : -1); return true; } // Left / Right
            case 261 -> { // Delete
                if (view == View.MON && sel < rows.size()) {
                    String id = rows.get(sel);
                    Terminal.CONFIG.watchlist.remove(id);
                    Terminal.CONFIG.save();
                    status("Removed " + Feed.name(id), GRAY);
                }
                return true;
            }
            case 290 -> { go(View.HELP, null); return true; }
            case 291 -> { go(View.MON, null); return true; }
            case 292 -> { go(View.MOV, null); return true; }
            case 293 -> { go(View.SCR, null); return true; }
            case 294 -> { if (view == View.ITEM) toggleWatch(item); return true; }
            default -> { return super.keyPressed(e); }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() != 0) return super.mouseClicked(e, doubleClick);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (e.x() >= h.x1() && e.x() < h.x2() && e.y() >= h.y1() && e.y() < h.y2()) { h.action().run(); return true; }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dy > 0) sel = Math.max(0, sel - 3);
        else if (dy < 0) sel = Math.min(Math.max(0, rows.size() - 1), sel + 3);
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc is handled above (clear, back, close)
    }

    private void horizontal(int dir) {
        switch (view) {
            case ITEM -> {
                Command.Range[] rs = Command.Range.values();
                range = rs[Math.floorMod(range.ordinal() + dir, rs.length)];
            }
            case MOV -> { moversDay = !moversDay; sel = 0; scroll = 0; }
            case SCR -> {
                Screener.Field[] fs = {Screener.Field.FLOW, Screener.Field.VOL, Screener.Field.CHG, Screener.Field.CHG1H,
                        Screener.Field.SPR, Screener.Field.PX};
                int i = 0;
                for (int k = 0; k < fs.length; k++) if (fs[k] == sortField) i = k;
                sortField = fs[Math.floorMod(i + dir, fs.length)];
                sel = 0;
                scroll = 0;
            }
            default -> {}
        }
    }

    // ---------- commands ----------

    private void run(String line) {
        Command.Parsed c = Command.parse(line);
        if (c.error() != null) { status(c.error(), RED); return; }
        switch (c.fn()) {
            case MON -> go(View.MON, null);
            case MOV -> go(View.MOV, null);
            case HELP -> go(View.HELP, null);
            case SCR -> { filters = c.filters(); go(View.SCR, null); status(filters.isEmpty() ? "All items. Add filters like SCR VOL>50K SPR>3" : "", GRAY); }
            case ADD, DEL -> {
                List<String> m = find(c.item());
                if (m.isEmpty()) { status("No Bazaar item matches \"" + c.item() + "\"", RED); return; }
                String id = m.get(0);
                if (c.fn() == Command.Fn.ADD) {
                    if (!Terminal.CONFIG.watchlist.contains(id)) Terminal.CONFIG.watchlist.add(id);
                    status("Added " + Feed.name(id) + " to your Monitor", GREEN);
                } else {
                    Terminal.CONFIG.watchlist.remove(id);
                    status("Removed " + Feed.name(id), GRAY);
                }
                Terminal.CONFIG.save();
            }
            case ITEM -> {
                List<String> m = find(c.item());
                if (m.isEmpty()) { status("No Bazaar item matches \"" + c.item() + "\". Try part of the name.", RED); return; }
                if (c.range() != null) range = c.range();
                if (m.size() == 1 || exact(c.item(), m.get(0))) open(m.get(0));
                else { pick = m; go(View.PICK, null); }
            }
            case EMPTY -> {}
        }
    }

    private List<String> find(String q) {
        return Items.find(q, Feed.snapshot.products().keySet(), Feed.names, Feed::rankVolume, 30);
    }

    /** True when the query names the item outright (id or full name), so no picker is needed. */
    private static boolean exact(String q, String id) {
        String n = Items.norm(q);
        return n.equals(Items.norm(Feed.name(id))) || q.trim().equalsIgnoreCase(id) || n.length() >= 3 && Items.norm(Feed.name(id)).startsWith(n);
    }

    private void open(String id) {
        go(View.ITEM, id);
        Feed.day(id);
        Feed.week(id);
    }

    private void go(View v, String id) {
        if (v != view || (id != null && !id.equals(item))) {
            back.push(new Place(view, item));
            while (back.size() > 30) back.removeLast();
        }
        view = v;
        if (id != null) item = id;
        sel = 0;
        scroll = 0;
    }

    private void toggleWatch(String id) {
        if (id == null) return;
        if (Terminal.CONFIG.watchlist.remove(id)) status("Removed " + Feed.name(id) + " from your Monitor", GRAY);
        else { Terminal.CONFIG.watchlist.add(id); status("Added " + Feed.name(id) + " to your Monitor", GREEN); }
        Terminal.CONFIG.save();
    }
}
