package com.hayden.sbterminal;

import net.minecraft.client.Minecraft;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * The terminal, laid out like a professional market terminal:
 *
 *   [ panel bar: function, title                     purse  data age  clock ]
 *   [ command line_                                                 <GO>    ]
 *   [ item / screen line                                  big bid x ask     ]
 *   [ 1) action  2) action  3) action ...           (dark red toolbar)      ]
 *   [ body: tables, chart, order book                                       ]
 *   [ GO  MENU  F1 HELP  F2 MON  F3 MOV  F4 SCR             (key caps)      ]
 *
 * Everything is drawn by hand on a character grid in a monospace font. Read-only: it shows
 * data and never clicks, buys or sells. The only thing it sends is /bz when you pick
 * "Open in Bazaar" yourself.
 */
public final class TerminalScreen extends Screen {
    // ---- palette ----
    static final int BG = 0xFF000000, BAR = 0xFF232323, PANEL = 0xFF070707, GRID = 0xFF1E1E1E, LINE = 0xFF333333,
            AMBER = 0xFFFFA028, AMBER_DIM = 0xFFB87A1E, WHITE = 0xFFF2F2F2, GRAY = 0xFF9A9A9A, DIM = 0xFF5C5C5C,
            GREEN = 0xFF2BE04A, RED = 0xFFFF3B3B, BLUE = 0xFF4FA3FF, YELLOW = 0xFFFFE04A, BLACK = 0xFF000000,
            SEL = 0xFF0E3A78, HOVER = 0xFF141414, TOOLBAR = 0xFF6E1010, TOOL_HOVER = 0xFF9A1C1C;
    static final int LH = 11;

    enum View { MON, MOV, SCR, ITEM, PORT, LIM, PNL, ALRT, SECT, SECX, HELP, PICK }

    private record Place(View view, String item) {}

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

    private record Action(String label, Runnable run) {}

    // ---- state that survives closing ----
    private static View view = View.MON;
    private static String item = null;
    private static final Deque<Place> back = new ArrayDeque<>();
    private static Command.Range range = Command.Range.D1;
    private static boolean moversDay = true;
    private static List<Screener.Filter> filters = new ArrayList<>();
    private static Screener.Field sortField = Screener.Field.FLOW;
    private static boolean sortDesc = true;
    /** Monitor sort column (-1 = your own order). */
    private static int monSort = -1;
    private static boolean monDesc = true;
    private static int sel = 0, scroll = 0;
    private static List<String> pick = List.of();
    private static boolean restored = false;
    /** KEYS command: echo every key code the screen receives, with the frame rate, to find lost key presses. */
    private static boolean keyDebug = false;
    // order ticket (survives closing)
    private static Ticket.Side tSide = Ticket.Side.BUY;
    private static double tPrice = 0; // 0 = auto (join the best bid or ask)
    /** Item overlaid on the chart (CMP), or null. */
    private static String cmpItem = null;
    /** Sector being drilled into (SECX). */
    private static String sector = "Mining";

    // ---- per-screen ----
    private String cmd = "";
    private String status = "";
    private int statusColor = GRAY;
    private final List<Hit> hits = new ArrayList<>();
    private List<String> rows = new ArrayList<>();
    private List<String> suggest = List.of();
    private int suggestSel = 0;
    private List<Action> actions = List.of();
    private Gfx gx;

    public TerminalScreen() {
        super(Component.literal("SkyBlock Terminal"));
    }

    /** Opens straight to an item (hover + B, /sbt radar). */
    public static TerminalScreen forItem(String id) {
        TerminalScreen s = new TerminalScreen();
        restoreOnce();
        if (id != null) {
            if (view != View.ITEM || !id.equals(item)) back.push(new Place(view, item));
            view = View.ITEM;
            item = id;
            sel = 0;
            scroll = 0;
        }
        return s;
    }

    private static void restoreOnce() {
        if (restored) return;
        restored = true;
        Config c = Terminal.CONFIG;
        try { view = View.valueOf(c.lastView); } catch (Exception ignored) {}
        if (view == View.PICK) view = View.MON;
        item = c.lastItem;
        if (view == View.ITEM && item == null) view = View.MON;
        try { range = Command.Range.valueOf(c.lastRange); } catch (Exception ignored) {}
    }

    @Override
    protected void init() {
        restoreOnce();
        Feed.terminalOpen = true;
        if (view == View.ITEM && item != null) { Feed.day(item); Feed.week(item); }
        if (Terminal.CONFIG.watchlist.isEmpty() && view == View.MON) status("Type an item and press Enter. ADD <item> builds your Monitor.", GRAY);
    }

    @Override
    public void removed() {
        Feed.terminalOpen = false;
        Config c = Terminal.CONFIG;
        c.lastView = view.name();
        c.lastItem = item;
        c.lastRange = range.name();
        c.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // =====================================================================================
    // frame
    // =====================================================================================

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float d) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        gx = new Gfx(g, font, Terminal.CONFIG.monoFont);
        hits.clear();
        gx.fill(0, 0, width, height, BG);
        actions = actionsFor(view);
        drawPanelBar();
        drawCommandLine();
        int y = 30;
        y = drawTitleLine(y);
        y = drawToolbar(y, mx, my);
        int bottom = height - 14;
        switch (view) {
            case MON -> drawMonitor(y, bottom, mx, my);
            case MOV -> drawMovers(y, bottom, mx, my);
            case SCR -> drawScreener(y, bottom, mx, my);
            case ITEM -> drawItem(y, bottom, mx, my);
            case PORT -> drawPortfolio(y, bottom, mx, my);
            case PNL -> drawPnl(y, bottom, mx, my);
            case LIM -> drawLimit(y, bottom, mx, my);
            case ALRT -> drawAlerts(y, bottom, mx, my);
            case SECT -> drawSectors(y, bottom, mx, my);
            case SECX -> drawSectorItems(y, bottom, mx, my);
            case HELP -> drawHelp(y, bottom);
            case PICK -> drawPick(y, bottom, mx, my);
        }
        drawKeys();
        drawSuggestions(mx, my);
    }

    private void drawPanelBar() {
        gx.fill(0, 0, width, 13, BAR);
        int x = 3;
        gx.fill(x, 2, x + gx.w(" 1 "), 11, AMBER);
        x = gx.t(" 1 ", x, 3, BLACK) + 6;
        x = gx.t(mnemonic(), x, 3, AMBER) + 8;
        gx.t(gx.fit(screenTitle(), width / 2 - x), x, 3, WHITE);
        StringBuilder r = new StringBuilder();
        if (Terminal.purse >= 0) r.append("PURSE ").append(Fmt.px(Terminal.purse)).append("  ");
        long nowT = System.currentTimeMillis();
        double used = Terminal.PORT.usedSince(Terminal.windowStart(nowT));
        if (used > 0) {
            r.append("LIMIT ").append(Fmt.px(used)).append("/").append(Terminal.CONFIG.limitCoins > 0 ? "" : "~").append(Fmt.px(Terminal.limitAmount()))
                    .append(" resets ").append(Limit.countdown(Limit.nextReset(nowT, Terminal.CONFIG.resetMinuteUtc) - nowT)).append("  ");
        }
        Market.Snapshot s = Feed.snapshot;
        if (s.hypixelUpdated() > 0) r.append("DATA ").append(Fmt.ago(System.currentTimeMillis() - s.hypixelUpdated())).append("  ");
        r.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        gx.tr(r.toString(), width - 4, 3, GRAY);
    }

    private String mnemonic() {
        return switch (view) {
            case MON -> "MON";
            case MOV -> "MOV";
            case SCR -> "SCR";
            case ITEM -> "DES";
            case PORT -> "PORT";
            case PNL -> "PNL";
            case LIM -> "LIM";
            case ALRT -> "ALRT";
            case SECT, SECX -> "SECT";
            case HELP -> "HELP";
            case PICK -> "FIND";
        };
    }

    private String screenTitle() {
        return switch (view) {
            case MON -> "Monitor";
            case MOV -> "Movers";
            case SCR -> "Screener";
            case ITEM -> Feed.name(item) + "  Bazaar";
            case PORT -> "Portfolio";
            case PNL -> "P&L history";
            case LIM -> "Daily limit";
            case ALRT -> "Alerts";
            case SECT -> "Sectors";
            case SECX -> sector + " sector";
            case HELP -> "Help";
            case PICK -> "Item search";
        };
    }

    private void drawCommandLine() {
        int y = 15;
        gx.fill(0, 13, width, 28, BG);
        int x = gx.t(cmd, 4, y + 1, AMBER);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (blink) gx.fill(x + 1, y, x + 1 + gx.cw(), y + 9, AMBER);
        String st = Feed.error != null ? Feed.error : status;
        int sc = Feed.error != null ? RED : statusColor;
        if (!st.isEmpty()) gx.tr(gx.fit(st, width / 2), width - 4, y + 1, sc);
        else gx.tr("<GO>", width - 4, y + 1, DIM);
        gx.hline(0, width, 28, LINE);
    }

    private int drawTitleLine(int y) {
        if (view == View.ITEM && item != null) {
            Market.Product p = Feed.snapshot.get(item);
            int x = gx.t(Feed.name(item).toUpperCase(Locale.US), 4, y + 3, AMBER) + 8;
            x = gx.t("Bazaar", x, y + 3, WHITE) + 8;
            x = gx.t(item, x, y + 3, DIM) + 10;
            if (Terminal.CONFIG.watchlist.contains(item)) gx.t("★ MON", x, y + 3, YELLOW);
            if (p != null) {
                float sc = 1.6f;
                String bid = Fmt.px(p.bid()), ask = Fmt.px(p.ask());
                String sep = "  x  ";
                int total = gx.bigW(bid + sep + ask, sc);
                int bx = width - 6 - total;
                long now = System.currentTimeMillis();
                int fb = Flash.bg("hdr-bid-" + item, p.bid(), now), fa = Flash.bg("hdr-ask-" + item, p.ask(), now);
                if (fb != 0) gx.fill(bx - 2, y, bx + gx.bigW(bid, sc) + 2, y + 15, fb);
                gx.big(bid, bx, y + 1, sc, GREEN);
                gx.big(sep, bx + gx.bigW(bid, sc), y + 1, sc, DIM);
                int ax = bx + gx.bigW(bid + sep, sc);
                if (fa != 0) gx.fill(ax - 2, y, ax + gx.bigW(ask, sc) + 2, y + 15, fa);
                gx.big(ask, ax, y + 1, sc, BLUE);
            }
            return y + 17;
        }
        gx.t(screenTitle().toUpperCase(Locale.US), 4, y + 3, AMBER);
        return y + 14;
    }

    private int drawToolbar(int y, int mx, int my) {
        if (actions.isEmpty()) return y + 2;
        gx.fill(0, y, width, y + 13, 0xFF140404);
        int x = 4;
        for (int i = 0; i < actions.size(); i++) {
            String num = (i + 1) + ")";
            String lab = actions.get(i).label();
            int w = gx.w(num + " " + lab) + 10;
            if (x + w > width - 4) break;
            boolean hover = mx >= x && mx < x + w && my >= y + 1 && my < y + 12;
            gx.fill(x, y + 1, x + w, y + 12, hover ? TOOL_HOVER : TOOLBAR);
            int tx = gx.t(num, x + 5, y + 3, AMBER);
            gx.t(" " + lab, tx, y + 3, WHITE);
            hits.add(new Hit(x, y + 1, x + w, y + 12, actions.get(i).run()));
            x += w + 3;
        }
        return y + 16;
    }

    private void drawKeys() {
        int y = height - 13;
        gx.fill(0, y, width, height, BAR);
        int x = 4;
        x = cap("GO", "Enter", GREEN, x, y, this::enter);
        x = cap("ESC", "close", AMBER, x, y, this::escape);
        x = cap("BKSP", "back", GRAY, x, y, this::goBack);
        x = cap("F1", "HELP", YELLOW, x, y, () -> go(View.HELP, null));
        x = cap("F2", "MON", YELLOW, x, y, () -> go(View.MON, null));
        x = cap("F3", "MOV", YELLOW, x, y, () -> go(View.MOV, null));
        x = cap("F4", "SCR", YELLOW, x, y, () -> go(View.SCR, null));
        x = cap("F7", "PORT", YELLOW, x, y, () -> go(View.PORT, null));
        if (view == View.ITEM) { x = cap("F5", "BUY", GREEN, x, y, () -> tSide = Ticket.Side.BUY); x = cap("F6", "SELL", RED, x, y, () -> tSide = Ticket.Side.SELL); cap("←→", "range", GRAY, x, y); }
        else if (view == View.MOV) cap("←→", "1H/24H", GRAY, x, y);
        else if (view == View.SCR) cap("←→", "sort", GRAY, x, y);
        else if (view == View.MON) cap("Shift↑↓", "move", GRAY, x, y);
        String bf = Feed.backfillStatus();
        if (!bf.isEmpty()) gx.tr(bf, width - 4, y + 3, DIM);
    }

    private int cap(String key, String label, int color, int x, int y) {
        return cap(key, label, color, x, y, null);
    }

    /** A key cap; with an action it is also a button, so every shortcut can be clicked instead. */
    private int cap(String key, String label, int color, int x, int y, Runnable click) {
        int w = gx.w(key) + 6;
        gx.fill(x, y + 2, x + w, y + 12, color);
        gx.t(key, x + 3, y + 3, BLACK);
        int end = gx.t(label, x + w + 4, y + 3, GRAY);
        if (click != null) hits.add(new Hit(x, y + 1, end + 4, y + 13, click));
        return end + 10;
    }

    /** Esc: clears what you typed, otherwise closes the terminal. One press, however many screens you visited. */
    private void escape() {
        if (!cmd.isEmpty() || !suggest.isEmpty()) { cmd = ""; suggest = List.of(); }
        else onClose();
    }

    /** One screen back (Backspace on an empty line, or the BACK key cap). */
    private void goBack() {
        if (back.isEmpty()) return;
        Place pl = back.pop();
        view = pl.view();
        item = pl.item();
        sel = 0;
        scroll = 0;
        status = "";
    }

    // ---------- toolbar actions per screen ----------

    private List<Action> actionsFor(View v) {
        List<Action> a = new ArrayList<>();
        switch (v) {
            case ITEM -> {
                if (item == null) break;
                boolean on = Terminal.CONFIG.watchlist.contains(item);
                a.add(new Action("BUY order", () -> { tSide = Ticket.Side.BUY; placeTicket(false); }));
                a.add(new Action("SELL offer", () -> { tSide = Ticket.Side.SELL; placeTicket(false); }));
                a.add(new Action("Buy now", () -> { tSide = Ticket.Side.BUY; placeTicket(true); }));
                a.add(new Action("Sell now", () -> { tSide = Ticket.Side.SELL; placeTicket(true); }));
                a.add(new Action(on ? "Remove from MON" : "Add to MON", () -> toggleWatch(item)));
                a.add(new Action("Copy price", () -> copyPrice(tSide == Ticket.Side.BUY)));
                a.add(new Action("Alert", () -> { cmd = "ALERT " + Feed.name(item) + " < "; status("Type the price, then Enter", GRAY); }));
                a.add(new Action(cmpItem == null ? "Compare" : "Stop compare", () -> {
                    if (cmpItem != null) cmpItem = null;
                    else { cmd = "CMP "; status("Type the item to compare with and press Enter", GRAY); }
                }));
                for (Command.Range r : Command.Range.values()) a.add(new Action(r.label, () -> range = r));
            }
            case MON -> {
                a.add(new Action("Add item", () -> { cmd = "ADD "; status("Type the item and press Enter", GRAY); }));
                a.add(new Action("Remove selected", this::removeSelected));
                a.add(new Action("Move up", () -> moveSelected(-1)));
                a.add(new Action("Move down", () -> moveSelected(1)));
                a.add(new Action("My order", () -> monSort = -1));
            }
            case PORT -> {
                a.add(new Action("Open item", () -> { if (sel < rows.size()) open(rows.get(sel)); }));
                a.add(new Action("Set cost", () -> { if (sel < rows.size()) { cmd = "POS " + Feed.name(rows.get(sel)) + " "; status("Type units and cost, e.g. 500 130.5", GRAY); } }));
                a.add(new Action("Remove position", this::removeSelected));
                a.add(new Action("Reprice outbid", this::repriceFirstOutbid));
                a.add(new Action("P&L history", () -> go(View.PNL, null)));
                a.add(new Action("Clear orders", () -> { Terminal.PORT.orders.clear(); Terminal.PORT.save(); status("Cleared the tracked order list", GRAY); }));
            }
            case ALRT -> {
                a.add(new Action("New alert", () -> { cmd = "ALERT "; status("e.g. ALERT enchanted coal < 120   or   ALERT coal SPR > 5", GRAY); }));
                a.add(new Action("Delete", this::deleteAlert));
                a.add(new Action("Re-arm", () -> rearm(false)));
                a.add(new Action("Re-arm all", () -> rearm(true)));
                a.add(new Action("Clear fired", () -> { Terminal.CONFIG.priceAlerts.removeIf(r -> r.fired); Terminal.CONFIG.save(); }));
            }
            case LIM -> {
                a.add(new Action("Set limit", () -> { cmd = "LIMIT "; status("e.g. LIMIT 15B", GRAY); }));
                a.add(new Action("Set reset time", () -> { cmd = "LIMIT RESET "; status("Your local time, e.g. 7:00 PM, or UTC 23:00", GRAY); }));
                a.add(new Action("Forget learned", () -> {
                    if (!"manual".equals(Terminal.CONFIG.limitSource)) { Terminal.CONFIG.limitCoins = 0; Terminal.CONFIG.limitSource = ""; }
                    if (!"manual".equals(Terminal.CONFIG.resetSource)) Terminal.CONFIG.resetSource = "";
                    Terminal.CONFIG.save();
                    status("Cleared what was learned (manual settings stay)", GRAY);
                }));
            }
            case SECT -> a.add(new Action("Open sector", () -> openSector(sel)));
            case SECX -> a.add(new Action("All sectors", () -> go(View.SECT, null)));
            case MOV -> {
                a.add(new Action("24 hours", () -> { moversDay = true; sel = 0; scroll = 0; }));
                a.add(new Action("1 hour", () -> { moversDay = false; sel = 0; scroll = 0; }));
            }
            case SCR -> {
                a.add(new Action("Most traded", () -> setScreen(List.of(), Screener.Field.FLOW, true)));
                a.add(new Action("Wide spreads", () -> setScreen(List.of(Screener.Filter.parse("SPR>5"), Screener.Filter.parse("VOL>50K")), Screener.Field.SPR, true)));
                a.add(new Action("Rising 24h", () -> setScreen(List.of(Screener.Filter.parse("CHG>3")), Screener.Field.CHG, true)));
                a.add(new Action("Falling 24h", () -> setScreen(List.of(Screener.Filter.parse("CHG<-3")), Screener.Field.CHG, false)));
                a.add(new Action("Under 10k", () -> setScreen(List.of(Screener.Filter.parse("PX<10K")), Screener.Field.FLOW, true)));
            }
            default -> {}
        }
        return a;
    }

    private void setScreen(List<Screener.Filter> fs, Screener.Field sort, boolean desc) {
        filters = new ArrayList<>(fs);
        sortField = sort;
        sortDesc = desc;
        sel = 0;
        scroll = 0;
    }

    // ---------- MON ----------

    private static final String[] COLS = {"ITEM", "BID", "ASK", "SPR%", "1H", "24H", "VOL 7D", "FLOW/D"};
    private static final double[] COLW = {0.30, 0.10, 0.10, 0.08, 0.09, 0.09, 0.12, 0.12};

    private double colValue(String id, int col) {
        Market.Product p = Feed.snapshot.get(id);
        if (p == null) return Double.NaN;
        return switch (col) {
            case 1 -> p.bid();
            case 2 -> p.ask();
            case 3 -> p.spreadPct();
            case 4 -> Feed.change(id, 3_600_000L);
            case 5 -> Feed.change(id, 86_400_000L);
            case 6 -> p.weekVolume();
            case 7 -> p.coinFlowDay();
            default -> Double.NaN;
        };
    }

    private void drawMonitor(int top, int bottom, int mx, int my) {
        List<String> ids = new ArrayList<>(Terminal.CONFIG.watchlist);
        if (ids.isEmpty()) {
            rows = ids;
            gx.t("Your Monitor is empty.", 6, top + 4, WHITE);
            gx.t("ADD enchanted coal   add an item          MOV   what's moving", 6, top + 4 + LH, GRAY);
            gx.t("radar                open an item         SCR   screen every item", 6, top + 4 + 2 * LH, GRAY);
            gx.t("Hover any item in a menu and press B to open it here.", 6, top + 4 + 3 * LH, GRAY);
            return;
        }
        if (monSort == 0) ids.sort(Comparator.comparing(Feed::name));
        else if (monSort > 0) sortBy(ids, id -> colValue(id, monSort), monDesc);
        rows = ids;
        Table t = new Table(COLS, COLW);
        t.header(top + 2, monSort, monDesc, col -> {
            if (monSort == col) monDesc = !monDesc; else { monSort = col; monDesc = col != 0; }
        });
        listRows(t, ids, top + LH + 4, bottom, mx, my);
    }

    private static void sortBy(List<String> ids, ToDoubleFunction<String> f, boolean desc) {
        Comparator<String> c = Comparator.comparingDouble(id -> {
            double v = f.applyAsDouble(id);
            return Double.isFinite(v) ? v : (desc ? -Double.MAX_VALUE : Double.MAX_VALUE);
        });
        ids.sort(desc ? c.reversed() : c);
    }

    /** Item rows in the standard columns, with selection, hover, flash and scrolling. */
    private void listRows(Table t, List<String> ids, int y0, int bottom, int mx, int my) {
        int fit = Math.max(1, (bottom - y0) / LH);
        clampSel(ids.size(), fit);
        Market.Snapshot s = Feed.snapshot;
        long now = System.currentTimeMillis();
        for (int i = scroll; i < Math.min(ids.size(), scroll + fit); i++) {
            String id = ids.get(i);
            int y = y0 + (i - scroll) * LH;
            Market.Product p = s.get(id);
            int idx = i;
            rowBackground(y, i == sel, mx, my, () -> { sel = idx; open(id); });
            t.cell(0, y, Feed.name(id), i == sel ? AMBER : WHITE, 0);
            if (p == null) { t.cell(1, y, "no data", DIM, 0); continue; }
            t.cell(1, y, Fmt.px(p.bid()), GREEN, Flash.bg("r-bid-" + id, p.bid(), now));
            t.cell(2, y, Fmt.px(p.ask()), BLUE, Flash.bg("r-ask-" + id, p.ask(), now));
            t.cell(3, y, String.format(Locale.US, "%.2f", p.spreadPct()), WHITE, 0);
            double c1 = Feed.change(id, 3_600_000L), c24 = Feed.change(id, 86_400_000L);
            t.cell(4, y, Fmt.pct(c1), changeColor(c1), 0);
            t.cell(5, y, Fmt.pct(c24), changeColor(c24), 0);
            t.cell(6, y, Fmt.qty(p.weekVolume()), WHITE, 0);
            t.cell(7, y, Fmt.px(p.coinFlowDay()), GRAY, 0);
        }
        if (ids.size() > fit) gx.tr((scroll + 1) + "-" + Math.min(ids.size(), scroll + fit) + " of " + ids.size(), width - 6, bottom - LH + 1, DIM);
    }

    // ---------- MOV ----------

    private void drawMovers(int top, int bottom, int mx, int my) {
        long window = moversDay ? 86_400_000L : 3_600_000L;
        List<List<Movers.Move>> mv = Movers.top(Feed.snapshot.products().values(), id -> Feed.change(id, window),
                Terminal.CONFIG.minWeekVolume, 60);
        int tx = gx.t((moversDay ? "24 HOUR" : "1 HOUR") + " MOVERS", 6, top + 2, AMBER) + 12;
        gx.t("items trading " + Fmt.qty(Terminal.CONFIG.minWeekVolume) + "+ a week", tx, top + 2, DIM);
        long rec = Feed.RECORDER.recordingMs(System.currentTimeMillis());
        if (!moversDay && rec < 3_600_000L) gx.tr("1H fills in after an hour of recording (" + Fmt.ago(rec) + " so far)", width - 6, top + 2, DIM);
        int half = width / 2;
        int y0 = top + LH + 4;
        gx.t("▲ GAINERS", 6, y0, GREEN);
        gx.t("▼ LOSERS", half + 6, y0, RED);
        gx.hline(4, width - 4, y0 + LH - 1, LINE);
        y0 += LH + 2;
        rows = new ArrayList<>();
        for (Movers.Move m : mv.get(0)) rows.add(m.p().id());
        int gainers = rows.size();
        for (Movers.Move m : mv.get(1)) rows.add(m.p().id());
        int fit = Math.max(1, (bottom - y0) / LH);
        if (sel >= rows.size()) sel = Math.max(0, rows.size() - 1);
        moveColumn(mv.get(0), 0, half, y0, fit, 0, mx, my);
        moveColumn(mv.get(1), half, half, y0, fit, gainers, mx, my);
        if (rows.isEmpty()) gx.t(moversDay ? "Loading 24h history..." : "Not enough recording yet.", 6, y0, GRAY);
    }

    private void moveColumn(List<Movers.Move> list, int x, int w, int y0, int fit, int off, int mx, int my) {
        for (int i = 0; i < Math.min(list.size(), fit); i++) {
            Movers.Move m = list.get(i);
            int y = y0 + i * LH, idx = off + i;
            String id = m.p().id();
            boolean selected = sel == idx, hover = mx >= x && mx < x + w && my >= y - 1 && my < y + LH - 1;
            if (selected || hover) gx.fill(x, y - 1, x + w, y + LH - 1, selected ? SEL : HOVER);
            hits.add(new Hit(x, y - 1, x + w, y + LH - 1, () -> { sel = idx; open(id); }));
            gx.t(gx.fit(Feed.name(id), (int) (w * 0.48)), x + 6, y, selected ? AMBER : WHITE);
            gx.tr(Fmt.pct(m.change()), x + (int) (w * 0.70), y, changeColor(m.change()));
            gx.tr(Fmt.px(m.p().mid()), x + (int) (w * 0.86), y, WHITE);
            gx.tr(Fmt.qty(m.p().weekVolume()), x + w - 6, y, GRAY);
        }
    }

    // ---------- SCR ----------

    private void drawScreener(int top, int bottom, int mx, int my) {
        List<Screener.Filter> fs = new ArrayList<>(filters);
        if (fs.stream().noneMatch(f -> f.field() == Screener.Field.VOL)) fs.add(new Screener.Filter(Screener.Field.VOL, ">=", Terminal.CONFIG.minWeekVolume));
        List<Screener.Row> res = Screener.run(Feed.snapshot.products().values(), fs,
                id -> Feed.change(id, 86_400_000L), id -> Feed.change(id, 3_600_000L), sortField, sortDesc);
        StringBuilder fl = new StringBuilder();
        for (Screener.Filter f : fs) fl.append(f).append("  ");
        int x = gx.t(res.size() + " ITEMS", 6, top + 2, AMBER) + 10;
        gx.t(gx.fit(fl.toString(), width - x - 10), x, top + 2, GRAY);
        Table t = new Table(COLS, COLW);
        int col = switch (sortField) { case PX -> 1; case SPR -> 3; case CHG1H -> 4; case CHG -> 5; case VOL -> 6; case FLOW -> 7; case ORD -> -1; };
        t.header(top + LH + 4, col, sortDesc, c -> {
            Screener.Field f = switch (c) { case 1, 2 -> Screener.Field.PX; case 3 -> Screener.Field.SPR; case 4 -> Screener.Field.CHG1H;
                case 5 -> Screener.Field.CHG; case 6 -> Screener.Field.VOL; case 7 -> Screener.Field.FLOW; default -> null; };
            if (f == null) return;
            if (f == sortField) sortDesc = !sortDesc; else { sortField = f; sortDesc = true; }
            sel = 0;
            scroll = 0;
        });
        List<String> ids = new ArrayList<>();
        for (Screener.Row r : res) ids.add(r.p().id());
        rows = ids;
        listRows(t, ids, top + 2 * LH + 6, bottom, mx, my);
    }

    // ---------- ITEM ----------

    private void drawItem(int top, int bottom, int mx, int my) {
        Market.Product p = Feed.snapshot.get(item);
        if (p == null) { gx.t("No live data for " + item + " yet.", 6, top + 4, GRAY); return; }
        long now = System.currentTimeMillis();
        int bookW = Math.max(150, (int) (width * 0.28));
        int leftW = width - bookW - 14;
        double c1 = Feed.change(item, 3_600_000L), c6 = Feed.change(item, 6 * 3_600_000L), c24 = Feed.change(item, 86_400_000L);
        List<Bars.Point> wk = Feed.week(item);
        double c7 = wk == null || wk.isEmpty() ? Double.NaN : Bars.change(wk, p.mid(), now, 7 * 86_400_000L);
        int x = 6;
        x = pair("SPR ", Fmt.px(p.spread()) + String.format(Locale.US, " %.2f%%", p.spreadPct()), x, top + 2, WHITE);
        x = pair("1H ", Fmt.pct(c1), x, top + 2, changeColor(c1));
        x = pair("6H ", Fmt.pct(c6), x, top + 2, changeColor(c6));
        x = pair("1D ", Fmt.pct(c24), x, top + 2, changeColor(c24));
        pair("1W ", Fmt.pct(c7), x, top + 2, changeColor(c7));
        int y = top + LH + 4;
        int statsH = 6 * LH + 8;
        int chartH = Math.max(60, bottom - y - statsH - 4);
        if (cmpItem != null && !cmpItem.equals(item)) drawCompare(4, y, leftW, chartH, now, mx, my);
        else drawChart(p, 4, y, leftW, chartH, now, mx, my);
        drawStats(p, 4, y + chartH + 4, leftW, now);
        int ticketH = 14 * LH + 10;
        int bookH = Math.max(6 * LH, bottom - y - ticketH - 4);
        drawBook(p, width - bookW - 4, y, bookW, bookH);
        drawTicket(p, width - bookW - 4, y + bookH + 4, bookW, bottom - (y + bookH + 4), mx, my);
    }

    private void drawChart(Market.Product p, int x, int y, int w, int h, long now, int mx, int my) {
        gx.fill(x, y, x + w, y + h, PANEL);
        gx.box(x, y, w, h, LINE);
        int tx = x + 4;
        for (Command.Range r : Command.Range.values()) {
            int tw = gx.w(r.label) + 8;
            boolean on = r == range;
            gx.fill(tx, y + 3, tx + tw, y + 13, on ? AMBER : 0xFF1C1C1C);
            gx.t(r.label, tx + 4, y + 4, on ? BLACK : GRAY);
            hits.add(new Hit(tx, y + 3, tx + tw, y + 13, () -> range = r));
            tx += tw + 3;
        }
        tx += 8;
        gx.fill(tx, y + 7, tx + 8, y + 9, GREEN);
        tx = gx.t("bid", tx + 11, y + 4, GRAY) + 8;
        gx.fill(tx, y + 7, tx + 8, y + 9, BLUE);
        gx.t("ask", tx + 11, y + 4, GRAY);

        long span = range.span;
        List<Bars.Point> src;
        if (range == Command.Range.W1 || range == Command.Range.D3) {
            List<Bars.Point> wkPts = Feed.week(item);
            src = wkPts == null ? new ArrayList<>() : Bars.merge(wkPts, Feed.RECORDER.points(item));
        } else {
            Feed.day(item);
            src = Feed.recent(item);
        }
        List<Bars.Point> pts = new ArrayList<>(Bars.window(src, now - span, now));
        pts.add(new Bars.Point(now, p.bid(), p.ask()));
        int px = x + 6, py = y + 20, pw = w - 64, ph = h - 34;
        double[] rg = Bars.range(pts);
        if (rg == null || pts.size() < 2) {
            gx.t(Feed.loading(item) ? "Loading history..." : "No history for this range yet.", px + 6, py + ph / 2, GRAY);
            return;
        }
        double pad = Math.max((rg[1] - rg[0]) * 0.08, rg[1] * 0.002);
        double lo = rg[0] - pad, hi = rg[1] + pad;
        for (int i = 0; i <= 4; i++) {
            double v = lo + (hi - lo) * i / 4;
            int gy = yOf(v, lo, hi, py, ph);
            gx.dotted(px, px + pw, gy, GRID);
            gx.t(Fmt.px(v), px + pw + 4, gy - 4, DIM);
        }
        long from = now - span;
        for (int i = 0; i <= 4; i++) {
            long t = from + span * i / 4;
            int lx = px + pw * i / 4;
            for (int gy = py; gy < py + ph; gy += 3) gx.fill(lx, gy, lx + 1, gy + 1, GRID);
            String lab = i == 4 ? "now" : label(t, span);
            if (i == 0) gx.t(lab, lx, py + ph + 3, DIM);
            else if (i == 4) gx.tr(lab, lx, py + ph + 3, DIM);
            else gx.tc(lab, lx, py + ph + 3, DIM);
        }
        for (Bars.Point pt : pts) {
            if (pt.askHigh() <= pt.ask() && pt.bidLow() >= pt.bid()) continue;
            int cx = px + (int) ((pt.time() - from) * (double) pw / span);
            gx.fill(cx, yOf(pt.askHigh(), lo, hi, py, ph), cx + 1, yOf(pt.bidLow(), lo, hi, py, ph) + 1, 0xFF2C2C2C);
        }
        plot(pts, true, from, span, lo, hi, px, py, pw, ph, GREEN);
        plot(pts, false, from, span, lo, hi, px, py, pw, ph, BLUE);
        tag(p.ask(), lo, hi, px + pw, py, ph, BLUE);
        tag(p.bid(), lo, hi, px + pw, py, ph, GREEN);
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            long t = from + (long) ((mx - px) * (double) span / pw);
            Bars.Point near = null;
            for (Bars.Point pt : pts) if (near == null || Math.abs(pt.time() - t) < Math.abs(near.time() - t)) near = pt;
            for (int gy = py; gy < py + ph; gy += 2) gx.fill(mx, gy, mx + 1, gy + 1, 0xFF505050);
            if (near != null) {
                String s = label(near.time(), span) + "  BID " + Fmt.px(near.bid()) + "  ASK " + Fmt.px(near.ask());
                int sw = gx.w(s) + 8, sx = Math.min(mx + 6, px + pw - sw);
                gx.fill(sx, py + 2, sx + sw, py + 13, 0xF0000000);
                gx.box(sx, py + 2, sw, 11, AMBER_DIM);
                gx.t(s, sx + 4, py + 4, WHITE);
            }
        }
    }

    private void plot(List<Bars.Point> pts, boolean bid, long from, long span, double lo, double hi, int px, int py, int pw, int ph, int color) {
        int lx = Integer.MIN_VALUE, ly = 0;
        for (Bars.Point pt : pts) {
            double v = bid ? pt.bid() : pt.ask();
            if (v <= 0) { lx = Integer.MIN_VALUE; continue; }
            int cx = px + (int) ((pt.time() - from) * (double) pw / span), cy = yOf(v, lo, hi, py, ph);
            if (lx != Integer.MIN_VALUE) gx.line(lx, ly, cx, cy, color);
            lx = cx;
            ly = cy;
        }
    }

    private static int yOf(double v, double lo, double hi, int py, int ph) {
        return py + ph - (int) Math.round((v - lo) / (hi - lo) * ph);
    }

    private void tag(double v, double lo, double hi, int x, int py, int ph, int color) {
        if (v <= 0) return;
        int y = yOf(v, lo, hi, py, ph);
        String s = Fmt.px(v);
        gx.fill(x + 2, y - 5, x + 6 + gx.w(s), y + 5, color);
        gx.t(s, x + 4, y - 4, BLACK);
    }

    private String label(long t, long span) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault());
        return span <= 86_400_000L ? z.format(DateTimeFormatter.ofPattern("HH:mm")) : z.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US));
    }

    private void drawStats(Market.Product p, int x, int y, int w, long now) {
        List<Bars.Point> day = Bars.window(Feed.recent(item), now - 86_400_000L, now);
        double[] r24 = Bars.range(day);
        String[][] cells = {
                {"Mid", Fmt.px(p.mid())}, {"Spread", Fmt.px(p.spread()) + String.format(Locale.US, " (%.2f%%)", p.spreadPct())},
                {"24h Low / High", r24 == null ? "-" : Fmt.px(r24[0]) + " / " + Fmt.px(r24[1])}, {"Coin flow / day", Fmt.px(p.coinFlowDay())},
                {"Insta-buys 7d", Fmt.qty(p.instaBuyWeek())}, {"Insta-sells 7d", Fmt.qty(p.instaSellWeek())},
                {"Buy orders", p.bidOrders() + " / " + Fmt.qty(p.bidUnits()) + " units"}, {"Sell offers", p.askOrders() + " / " + Fmt.qty(p.askUnits()) + " units"},
                {"Buy / sell pressure", pressure(p)}, {"Top bid size", p.bidLevels().length >= 3 ? Fmt.qty(p.bidLevels()[1]) + " in " + (int) p.bidLevels()[2] + " ord" : "-"},
                {"Your buy fills", fillText(true)}, {"Your sell fills", fillText(false)},
        };
        int rowsN = (cells.length + 1) / 2;
        gx.fill(x, y, x + w, y + rowsN * LH + 6, PANEL);
        gx.box(x, y, w, rowsN * LH + 6, LINE);
        int colW = w / 2;
        for (int i = 0; i < cells.length; i++) {
            int cx = x + 6 + (i % 2) * colW, cy = y + 4 + (i / 2) * LH;
            gx.t(cells[i][0], cx, cy, AMBER_DIM);
            gx.tr(cells[i][1], cx + colW - 14, cy, WHITE);
        }
    }

    private String fillText(boolean buy) {
        long[] f = Terminal.PORT.fillTime(Feed.name(item), buy);
        return f == null ? "no data yet" : "median " + Fmt.ago(f[0]) + " (" + f[1] + ")";
    }

    private static String pressure(Market.Product p) {
        double b = p.instaBuyWeek(), s = p.instaSellWeek();
        if (b + s <= 0) return "-";
        double r = b / (b + s) * 100;
        return String.format(Locale.US, "%.0f%% buy / %.0f%% sell", r, 100 - r);
    }

    private void drawBook(Market.Product p, int x, int y, int w, int h) {
        gx.fill(x, y, x + w, y + h, PANEL);
        gx.box(x, y, w, h, LINE);
        gx.t("ORDER BOOK", x + 5, y + 4, AMBER);
        gx.t("PRICE", x + 5, y + 4 + LH, DIM);
        gx.tr("UNITS", x + (int) (w * 0.72), y + 4 + LH, DIM);
        gx.tr("ORD", x + w - 5, y + 4 + LH, DIM);
        int each = Math.max(1, (h - 3 * LH - 10) / 2 / LH);
        double max = 1;
        for (int i = 1; i < p.askLevels().length; i += 3) max = Math.max(max, p.askLevels()[i]);
        for (int i = 1; i < p.bidLevels().length; i += 3) max = Math.max(max, p.bidLevels()[i]);
        int mid = y + 4 + 2 * LH + each * LH + 2;
        int nA = Math.min(each, p.askLevels().length / 3);
        for (int i = 0; i < nA; i++) bookRow(p.askLevels(), i, x, mid - (i + 1) * LH, w, max, 0x50FF3B3B, RED);
        gx.hline(x + 3, x + w - 3, mid, AMBER_DIM);
        int nB = Math.min(each, p.bidLevels().length / 3);
        for (int i = 0; i < nB; i++) bookRow(p.bidLevels(), i, x, mid + 2 + i * LH, w, max, 0x502BE04A, GREEN);
    }

    private void bookRow(double[] lv, int i, int x, int y, int w, double max, int barColor, int color) {
        double price = lv[3 * i], units = lv[3 * i + 1];
        int bar = (int) ((w - 10) * Math.min(1, units / max));
        gx.fill(x + w - 5 - bar, y, x + w - 5, y + LH - 1, barColor);
        gx.t(Fmt.px(price), x + 5, y + 1, color);
        gx.tr(Fmt.qty(units), x + (int) (w * 0.72), y + 1, WHITE);
        gx.tr(Integer.toString((int) lv[3 * i + 2]), x + w - 5, y + 1, GRAY);
    }

    // ---------- HELP / PICK ----------

    private void drawHelp(int top, int bottom) {
        String[][] lines = {
                {"COMMANDS", ""},
                {"radar", "item page (part of a name, or initials: ec = Enchanted Coal)"},
                {"radar 1W", "item page with the chart on 1H 6H 1D 3D or 1W"},
                {"MON", "Monitor (your watchlist)     ADD radar / DEL radar"},
                {"MOV", "movers, 24h or 1h"},
                {"SCR VOL>50K SPR>3", "screener. VOL FLOW PX SPR CHG CHG1H ORD, with > < >= <=, K/M/B"},
                {"BUY 5k 120.5", "on an item page: open that buy order in the Bazaar, numbers filled in"},
                {"SELL 130.5 / BUY 5k NOW", "list what you carry / buy or sell instantly"},
                {"QTY 5k / PX 123.4", "ticket quantity and price (PX AUTO = join the best bid or ask)"},
                {"PORT / F7", "portfolio: positions, cost, P&L, open orders (built from Bazaar chat)"},
                {"POS coal 500 130.5", "set a position by hand (units 0 removes it)"},
                {"ALERT coal < 120", "alert once in chat (also BID ASK SPR CHG1H). ALRT lists them"},
                {"PNL / SECT", "P&L history / sector index moves"},
                {"CMP coal", "on an item page: overlay another item (% change). CMP alone clears"},
                {"LIMIT", "daily limit ledger: what counted, when, and the reset countdown"},
                {"LIMIT 15B / LIMIT RESET 7:00 PM", "set the cap and the daily reset (your time). Both are learned from Hypixel if you don't"},
                {"FONT MONO / FONT MC", "terminal font or Minecraft font"},
                {"2 <GO>", "run toolbar action 2 (or click it)"},
                {"", ""},
                {"KEYS", ""},
                {"typing", "matches show as you type; Up/Down pick, Tab or Enter opens"},
                {"Up / Down, Enter", "pick a row, open it. Click works too. Shift+Up/Down moves a Monitor row"},
                {"Left / Right", "chart range, movers window, screener sort"},
                {"F1 F2 F3 F4", "HELP, MON, MOV, SCR"},
                {"Ctrl+V", "paste into the command line"},
                {"Esc", "clear what you typed, then close the terminal (one press from any screen)"},
                {"Backspace", "on an empty line: go back one screen"},
                {"B on a hovered item", "in any menu or your inventory: opens that item here"},
                {"/sbt radar", "open the terminal on an item from chat"},
                {"", ""},
                {"DATA", ""},
                {"Live", "Hypixel's public Bazaar API every 5s while open (header shows data age)"},
                {"History", "Coflnet (free): 5-minute points for a day, 2-hour bars for a week, plus own recording"},
        };
        int y = top + 4;
        for (String[] l : lines) {
            if (y > bottom - LH) break;
            if (l[1].isEmpty()) gx.t(l[0], 6, y, AMBER);
            else { gx.t(l[0], 14, y, YELLOW); gx.t(l[1], 14 + (int) (width * 0.26), y, WHITE); }
            y += LH;
        }
    }

    private void drawPick(int top, int bottom, int mx, int my) {
        gx.t("More than one item matches. Pick one:", 6, top + 2, AMBER);
        Table t = new Table(COLS, COLW);
        t.header(top + LH + 4, -2, true, c -> {});
        rows = pick;
        listRows(t, pick, top + 2 * LH + 6, bottom, mx, my);
    }

    // ---------- search dropdown ----------

    private void updateSuggestions() {
        String q = cmd.trim();
        suggestSel = 0;
        if (q.isEmpty() || q.matches("\\d+")) { suggest = List.of(); return; }
        Command.Parsed c = Command.parse(q);
        String term = switch (c.fn()) {
            case ITEM, ADD, DEL, CMP -> c.item();
            case ALERT, POS -> itemPart(c.fn(), c.item())[0];
            default -> null;
        };
        List<String> out = new ArrayList<>();
        String up = q.toUpperCase(Locale.US);
        if (!up.contains(" ")) for (String[] code : Command.CODES) if (code[0].startsWith(up)) out.add("#" + code[0]);
        if (term != null && !term.isBlank()) {
            List<String> f = find(term);
            out.addAll(f.subList(0, Math.min(8, f.size())));
        }
        suggest = out;
    }

    private static final java.util.regex.Pattern ALERT_TAIL = java.util.regex.Pattern.compile("(?i)^(.*?)(\\s+(?:BID|ASK|SPR|CHG1H|PX|PRICE|MID)\\b.*|\\s*[<>].*)?$");
    private static final java.util.regex.Pattern POS_TAIL = java.util.regex.Pattern.compile("(?i)^(.*?)(\\s+[\\d.,]+[kmb]?(?:\\s.*)?)?$");

    /** Splits "enchanted coal BID > 5" or "coal 500 130" into the item words and what follows them. */
    private static String[] itemPart(Command.Fn fn, String args) {
        java.util.regex.Matcher m = (fn == Command.Fn.ALERT ? ALERT_TAIL : POS_TAIL).matcher(args == null ? "" : args.trim());
        if (!m.matches()) return new String[]{args == null ? "" : args, ""};
        return new String[]{m.group(1).trim(), m.group(2) == null ? "" : m.group(2)};
    }

    private void drawSuggestions(int mx, int my) {
        if (suggest.isEmpty()) return;
        int x = 4, y = 29, w = Math.min(width - 8, Math.max(320, (int) (width * 0.6)));
        int h = suggest.size() * LH + 6;
        gx.fill(x, y, x + w, y + h, 0xF5101010);
        gx.box(x, y, w, h, AMBER_DIM);
        for (int i = 0; i < suggest.size(); i++) {
            String s = suggest.get(i);
            int ry = y + 3 + i * LH;
            boolean on = i == suggestSel, hover = mx >= x && mx < x + w && my >= ry - 1 && my < ry + LH - 1;
            if (on || hover) gx.fill(x + 1, ry - 1, x + w - 1, ry + LH - 1, on ? AMBER : HOVER);
            int fg = on ? BLACK : WHITE;
            int idx = i;
            hits.add(new Hit(x, ry - 1, x + w, ry + LH - 1, () -> { suggestSel = idx; acceptSuggestion(); }));
            if (s.startsWith("#")) {
                String code = s.substring(1);
                gx.t(code, x + 6, ry, on ? BLACK : AMBER);
                for (String[] c : Command.CODES) if (c[0].equals(code)) gx.t(c[1], x + 60, ry, on ? BLACK : GRAY);
                continue;
            }
            Market.Product p = Feed.snapshot.get(s);
            gx.t(gx.fit(Feed.name(s), (int) (w * 0.42)), x + 6, ry, fg);
            gx.t(gx.fit(s, (int) (w * 0.25)), x + (int) (w * 0.45), ry, on ? BLACK : DIM);
            if (p != null) {
                gx.tr(Fmt.px(p.mid()), x + (int) (w * 0.86), ry, fg);
                double c = Feed.change(s, 86_400_000L);
                gx.tr(Fmt.pct(c), x + w - 6, ry, on ? BLACK : changeColor(c));
            }
        }
    }

    private void acceptSuggestion() {
        if (suggest.isEmpty()) return;
        String s = suggest.get(Math.min(suggestSel, suggest.size() - 1));
        if (s.startsWith("#")) {
            String code = s.substring(1);
            if (java.util.Set.of("ADD", "DEL", "FONT", "CMP", "ALERT", "POS", "BUY", "SELL", "QTY", "PX").contains(code)) { cmd = code + " "; updateSuggestions(); }
            else { cmd = ""; suggest = List.of(); run(code); }
            return;
        }
        Command.Parsed c = Command.parse(cmd);
        String typed = cmd;
        cmd = "";
        suggest = List.of();
        if (c.fn() == Command.Fn.ADD || c.fn() == Command.Fn.DEL) { addOrDel(c.fn(), s); return; }
        if (c.fn() == Command.Fn.CMP) { run("CMP " + Feed.name(s)); return; }
        if (c.fn() == Command.Fn.ALERT || c.fn() == Command.Fn.POS) {
            String head = c.fn() == Command.Fn.ALERT ? "ALERT " : "POS ";
            String tail = itemPart(c.fn(), c.item())[1];
            if (tail.isBlank()) tail = c.fn() == Command.Fn.ALERT ? " < " : " ";
            cmd = head + Feed.name(s) + (tail.startsWith(" ") ? tail : " " + tail);
            status(c.fn() == Command.Fn.ALERT ? "Type the price and press Enter" : "Type units and cost, e.g. 500 130.5", GRAY);
            return;
        }
        if (c.range() != null) range = c.range();
        open(s);
    }

    // ---------- table ----------

    private final class Table {
        final String[] heads;
        final int[] right;
        final int left = 6;

        Table(String[] heads, double[] widths) {
            this.heads = heads;
            right = new int[heads.length];
            double acc = 0;
            for (int i = 0; i < heads.length; i++) {
                acc += widths[i];
                right[i] = left + (int) ((width - 2 * left) * acc);
            }
        }

        int leftOf(int col) {
            return col == 0 ? left : right[col - 1] + 4;
        }

        void header(int y, int sortCol, boolean desc, java.util.function.IntConsumer onClick) {
            for (int i = 0; i < heads.length; i++) {
                String h = heads[i] + (i == sortCol ? (desc ? " ▼" : " ▲") : "");
                cell(i, y, h, i == sortCol ? YELLOW : AMBER, 0);
                int c = i;
                hits.add(new Hit(leftOf(i), y - 1, right[i] + 2, y + LH - 1, () -> onClick.accept(c)));
            }
            gx.hline(left, width - left, y + LH - 1, LINE);
        }

        void cell(int col, int y, String s, int color, int bg) {
            if (col == 0) {
                gx.t(gx.fit(s, right[0] - left - 6), left, y, color);
            } else {
                if (bg != 0) gx.fill(right[col] - gx.w(s) - 2, y - 1, right[col] + 2, y + LH - 2, bg);
                gx.tr(s, right[col], y, color);
            }
        }
    }

    private void rowBackground(int y, boolean selected, int mx, int my, Runnable click) {
        boolean hover = my >= y - 1 && my < y + LH - 1;
        if (selected || hover) gx.fill(0, y - 1, width, y + LH - 1, selected ? SEL : HOVER);
        hits.add(new Hit(0, y - 1, width, y + LH - 1, click));
    }

    private void clampSel(int n, int fit) {
        if (n == 0) { sel = 0; scroll = 0; return; }
        sel = Math.max(0, Math.min(sel, n - 1));
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + fit) scroll = sel - fit + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - fit)));
    }

    static int changeColor(double c) {
        return !Double.isFinite(c) ? DIM : c > 0 ? GREEN : c < 0 ? RED : WHITE;
    }

    private int pair(String label, String value, int x, int y, int color) {
        x = gx.t(label, x, y, AMBER_DIM);
        return gx.t(value, x, y, color) + 14;
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
        updateSuggestions();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (keyDebug) status("key " + k + " received, " + Minecraft.getInstance().getFps() + " fps", AMBER);
        boolean dropdown = !suggest.isEmpty();
        if (e.hasControlDown() && k == 86) { // Ctrl+V
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip != null) { cmd += clip.replaceAll("[\\r\\n]", " ").trim(); updateSuggestions(); }
            return true;
        }
        switch (k) {
            case 256 -> { escape(); return true; } // Esc: clear, back, close
            case 259 -> { if (!cmd.isEmpty()) { cmd = cmd.substring(0, cmd.length() - 1); updateSuggestions(); } else goBack(); return true; }
            case 258 -> { if (dropdown) acceptSuggestion(); return true; } // Tab
            case 257, 335 -> { enter(); return true; }
            case 265 -> { // Up
                if (dropdown) suggestSel = Math.max(0, suggestSel - 1);
                else if (e.hasShiftDown() && view == View.MON) moveSelected(-1);
                else sel = Math.max(0, sel - 1);
                return true;
            }
            case 264 -> { // Down
                if (dropdown) suggestSel = Math.min(suggest.size() - 1, suggestSel + 1);
                else if (e.hasShiftDown() && view == View.MON) moveSelected(1);
                else sel = Math.min(Math.max(0, rows.size() - 1), sel + 1);
                return true;
            }
            case 266 -> { sel = Math.max(0, sel - 10); return true; }
            case 267 -> { sel = Math.min(Math.max(0, rows.size() - 1), sel + 10); return true; }
            case 268 -> { sel = 0; return true; }
            case 269 -> { sel = Math.max(0, rows.size() - 1); return true; }
            case 263, 262 -> { horizontal(k == 262 ? 1 : -1); return true; }
            case 261 -> { if (view == View.MON || view == View.PORT) removeSelected(); else if (view == View.ALRT) deleteAlert(); return true; }
            case 290 -> { go(View.HELP, null); return true; }
            case 291 -> { go(View.MON, null); return true; }
            case 292 -> { go(View.MOV, null); return true; }
            case 293 -> { go(View.SCR, null); return true; }
            case 296 -> { go(View.PORT, null); return true; }
            case 294 -> { if (view == View.ITEM) tSide = Ticket.Side.BUY; return true; }
            case 295 -> { if (view == View.ITEM) tSide = Ticket.Side.SELL; return true; }
            default -> { return super.keyPressed(e); }
        }
    }

    private void enter() {
        String q = cmd.trim();
        if (q.matches("\\d+")) { // toolbar action by number
            int n = Integer.parseInt(q);
            cmd = "";
            if (n >= 1 && n <= actions.size()) actions.get(n - 1).run().run();
            else status("No action " + n + " here", RED);
            return;
        }
        if (!q.isEmpty()) {
            Command.Parsed c = Command.parse(q);
            int firstItem = -1;
            for (int i = 0; i < suggest.size(); i++) if (!suggest.get(i).startsWith("#")) { firstItem = i; break; }
            if (c.fn() == Command.Fn.ITEM && firstItem >= 0) {
                if (suggest.get(suggestSel).startsWith("#")) suggestSel = firstItem; // Enter opens an item, not a code
                acceptSuggestion();
                return;
            }
            run(q);
            cmd = "";
            suggest = List.of();
            return;
        }
        if (view == View.ITEM) placeTicket(false);
        else if (view == View.SECT) openSector(sel);
        else if (sel < rows.size() && Feed.snapshot.get(rows.get(sel)) != null) open(rows.get(sel));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() != 0) return super.mouseClicked(e, doubleClick);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (e.x() >= h.x1() && e.x() < h.x2() && e.y() >= h.y1() && e.y() < h.y2()) { h.action().run(); return true; }
        }
        if (!suggest.isEmpty()) { suggest = List.of(); return true; }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!suggest.isEmpty()) {
            suggestSel = (int) Math.max(0, Math.min(suggest.size() - 1, suggestSel - Math.signum(dy)));
            return true;
        }
        if (dy > 0) sel = Math.max(0, sel - 3);
        else if (dy < 0) sel = Math.min(Math.max(0, rows.size() - 1), sel + 3);
        return true;
    }

    private void horizontal(int dir) {
        switch (view) {
            case ITEM -> {
                Command.Range[] rs = Command.Range.values();
                range = rs[Math.floorMod(range.ordinal() + dir, rs.length)];
            }
            case MOV -> { moversDay = !moversDay; sel = 0; scroll = 0; }
            case SCR -> {
                Screener.Field[] fs = {Screener.Field.FLOW, Screener.Field.VOL, Screener.Field.CHG, Screener.Field.CHG1H, Screener.Field.SPR, Screener.Field.PX};
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
            case SCR -> { setScreen(c.filters(), Screener.Field.FLOW, true); go(View.SCR, null); }
            case KEYS -> {
                keyDebug = !keyDebug;
                status(keyDebug ? "Key echo ON: every key press shows here with the FPS. KEYS turns it off." : "Key echo off", GREEN);
            }
            case FONT -> {
                if (c.item().equals("MC")) Terminal.CONFIG.monoFont = false;
                else if (c.item().equals("MONO")) Terminal.CONFIG.monoFont = true;
                else { status("FONT MONO or FONT MC", GRAY); return; }
                Terminal.CONFIG.save();
                status("Font: " + (Terminal.CONFIG.monoFont ? "terminal mono" : "Minecraft"), GREEN);
            }
            case ADD, DEL -> {
                List<String> m = find(c.item());
                if (m.isEmpty()) { status("No Bazaar item matches \"" + c.item() + "\"", RED); return; }
                addOrDel(c.fn(), m.get(0));
            }
            case ITEM -> {
                List<String> m = find(c.item());
                if (m.isEmpty()) { status("No Bazaar item matches \"" + c.item() + "\"", RED); return; }
                if (c.range() != null) range = c.range();
                if (m.size() == 1 || exact(c.item(), m.get(0))) open(m.get(0));
                else { pick = m; go(View.PICK, null); }
            }
            case PORT -> go(View.PORT, null);
            case PNL -> go(View.PNL, null);
            case ALRT -> go(View.ALRT, null);
            case SECT -> go(View.SECT, null);
            case ALERT -> addAlert(c.item());
            case CMP -> {
                if (c.item().isEmpty()) { cmpItem = null; status("Compare off", GRAY); return; }
                List<String> m = find(c.item());
                if (m.isEmpty()) { status("No Bazaar item matches \"" + c.item() + "\"", RED); return; }
                cmpItem = m.get(0);
                Feed.day(cmpItem);
                Feed.week(cmpItem);
                if (view != View.ITEM) { status("Open an item, then CMP shows both on its chart", GRAY); return; }
                status("Comparing with " + Feed.name(cmpItem) + " (% change from the start of the range). CMP alone clears.", GREEN);
            }
            case LIMIT -> limitCommand(c.item());
            case POS -> posCommand(c.item());
            case BUY, SELL -> ticketCommand(c);
            case QTY -> {
                if (c.item().equals("MAX")) { Terminal.CONFIG.ticketQty = Math.max(1, maxQty()); }
                else {
                    double v = Ticket.parseNum(c.item());
                    if (v < 1) { status("QTY 5k, QTY 64, or QTY MAX", RED); return; }
                    Terminal.CONFIG.ticketQty = clampQty((long) v);
                }
                Terminal.CONFIG.save();
                long eff = effectiveQty();
                status("Quantity " + Fmt.qty(eff) + (eff < Terminal.CONFIG.ticketQty ? " (capped by your purse)" : ""), GREEN);
            }
            case PX -> {
                if (c.item().equals("AUTO") || c.item().isEmpty()) { tPrice = 0; status("Price: auto", GREEN); return; }
                double v = Ticket.parseNum(c.item());
                if (v <= 0) { status("PX 123.4 or PX AUTO", RED); return; }
                tPrice = Ticket.round1(v);
                status("Price " + Fmt.px(tPrice) + " (custom)", GREEN);
            }
            case EMPTY -> {}
        }
    }

    // ---------- portfolio ----------

    /** POS enchanted coal 500 130.5   (units 0 removes it) */
    private void posCommand(String args) {
        String[] w = args.trim().split("\\s+");
        if (w.length < 2) { status("POS <item> <units> <cost>, e.g. POS enchanted coal 500 130.5", RED); return; }
        double cost = -1, units;
        int end = w.length;
        if (w.length >= 3 && Ticket.parseNum(w[w.length - 1]) >= 0 && Ticket.parseNum(w[w.length - 2]) >= 0) {
            cost = Ticket.parseNum(w[w.length - 1]);
            units = Ticket.parseNum(w[w.length - 2]);
            end = w.length - 2;
        } else if (Ticket.parseNum(w[w.length - 1]) >= 0) {
            units = Ticket.parseNum(w[w.length - 1]);
            end = w.length - 1;
        } else { status("POS <item> <units> <cost>", RED); return; }
        String q = String.join(" ", java.util.Arrays.copyOfRange(w, 0, end));
        List<String> m = find(q);
        if (m.isEmpty()) { status("No Bazaar item matches \"" + q + "\"", RED); return; }
        String id = m.get(0);
        if (units <= 0) { Terminal.PORT.removePosition(Feed.name(id)); Terminal.PORT.save(); status("Removed " + Feed.name(id), GRAY); return; }
        if (cost < 0) {
            Market.Product p = Feed.snapshot.get(id);
            if (p == null) { status("Give a cost too: POS " + q + " " + Fmt.qty(units) + " <cost>", RED); return; }
            cost = p.mid();
        }
        Terminal.PORT.setPosition(Feed.name(id), units, cost);
        Terminal.PORT.save();
        status("Position: " + Fmt.qty(units) + " " + Feed.name(id) + " at " + Fmt.px(cost), GREEN);
    }

    private static final String[] PCOLS = {"ITEM", "UNITS", "AVG COST", "BID", "VALUE", "P&L", "P&L %"};
    private static final double[] PCOLW = {0.30, 0.11, 0.14, 0.13, 0.14, 0.10, 0.08};

    private void drawPortfolio(int top, int bottom, int mx, int my) {
        long now = System.currentTimeMillis();
        double tax = 1 - Terminal.CONFIG.taxPct / 100.0;
        List<Portfolio.Pos> ps = Terminal.PORT.positionList();
        double value = 0, cost = 0;
        for (Portfolio.Pos p : ps) {
            String id = Feed.idForName(p.name);
            Market.Product mk = id == null ? null : Feed.snapshot.get(id);
            cost += p.units * p.cost;
            value += p.units * (mk == null ? p.cost : mk.bid() * tax);
        }
        double unreal = value - cost, today = Terminal.PORT.realizedOn(Portfolio.day(now));
        int x = 6;
        x = pair("VALUE ", Fmt.px(value), x, top + 2, WHITE);
        x = pair("COST ", Fmt.px(cost), x, top + 2, GRAY);
        x = pair("UNREAL ", signed(unreal), x, top + 2, changeColor(unreal));
        x = pair("TODAY ", signed(today), x, top + 2, changeColor(today));
        x = pair("ALL TIME ", signed(Terminal.PORT.realizedAll), x, top + 2, changeColor(Terminal.PORT.realizedAll));
        x = pair("LOCKED ", Fmt.px(Terminal.PORT.lockedInBuys()), x, top + 2, GRAY);
        if (Terminal.purse >= 0) pair("PURSE ", Fmt.px(Terminal.purse), x, top + 2, AMBER);

        List<Portfolio.Order> os = Terminal.PORT.orderList();
        int orderRows = os.isEmpty() ? 2 : Math.min(os.size(), 8) + 2;
        int ordersTop = bottom - orderRows * LH - 2;
        int y0 = top + LH + 4;
        Table t = new Table(PCOLS, PCOLW);
        t.header(y0, -1, false, c -> {});
        y0 += LH + 2;
        ps.sort(Comparator.comparingDouble((Portfolio.Pos p) -> -p.units * p.cost));
        List<String> ids = new ArrayList<>();
        for (Portfolio.Pos p : ps) { String id = Feed.idForName(p.name); ids.add(id == null ? p.name : id); }
        rows = ids;
        if (ps.isEmpty()) {
            gx.t("No positions yet. They build themselves from your Bazaar claims and insta-trades.", 6, y0 + 2, GRAY);
            gx.t("Bought something before? POS enchanted coal 500 130.5 sets it by hand.", 6, y0 + 2 + LH, DIM);
        }
        int fit = Math.max(1, (ordersTop - y0) / LH);
        clampSel(ids.size(), fit);
        for (int i = scroll; i < Math.min(ps.size(), scroll + fit); i++) {
            Portfolio.Pos p = ps.get(i);
            String id = ids.get(i);
            int y = y0 + (i - scroll) * LH, idx = i;
            Market.Product mk = Feed.snapshot.get(id);
            rowBackground(y, i == sel, mx, my, () -> { sel = idx; if (Feed.snapshot.get(id) != null) open(id); });
            t.cell(0, y, p.name, i == sel ? AMBER : WHITE, 0);
            t.cell(1, y, Fmt.qty(p.units), WHITE, 0);
            t.cell(2, y, Fmt.px(p.cost), GRAY, 0);
            if (mk == null) { t.cell(3, y, "-", DIM, 0); continue; }
            double v = p.units * mk.bid() * tax, pnl = v - p.units * p.cost, pct = p.cost > 0 ? pnl / (p.units * p.cost) * 100 : 0;
            t.cell(3, y, Fmt.px(mk.bid()), GREEN, 0);
            t.cell(4, y, Fmt.px(v), WHITE, 0);
            t.cell(5, y, signed(pnl), changeColor(pnl), 0);
            t.cell(6, y, Fmt.pct(pct), changeColor(pnl), 0);
        }
        if (Terminal.PORT.untrackedUnits > 0) gx.tr(Fmt.qty(Terminal.PORT.untrackedUnits) + " units sold with no tracked cost", width - 6, top + LH + 4 + 0, DIM);

        // open orders
        gx.hline(4, width - 4, ordersTop - 1, LINE);
        int oy = ordersTop + 1;
        gx.t("OPEN ORDERS (" + os.size() + ")", 6, oy, AMBER);
        long sa = Terminal.PORT.syncedAt;
        String syn = sa == 0 ? "never synced: open the Bazaar and click Manage Orders" : "synced " + Fmt.ago(now - sa) + " ago from Manage Orders";
        gx.t(syn, 14 + gx.w("OPEN ORDERS (99)"), oy, sa == 0 || now - sa > 600_000L ? YELLOW : DIM);
        oy += LH;
        if (os.isEmpty()) gx.t("None tracked. Orders you place after the terminal loads show up here.", 6, oy, DIM);
        os.sort(Comparator.comparingLong((Portfolio.Order o) -> -o.at));
        for (int i = 0; i < Math.min(os.size(), 8); i++, oy += LH) {
            Portfolio.Order o = os.get(i);
            String id = Feed.idForName(o.name);
            Market.Product mk = id == null ? null : Feed.snapshot.get(id);
            Portfolio.Status st = Portfolio.status(o, mk);
            final String fid = id;
            if (mx >= 0 && my >= oy - 1 && my < oy + LH - 1) gx.fill(0, oy - 1, width, oy + LH - 1, HOVER);
            hits.add(new Hit(0, oy - 1, width, oy + LH - 1, () -> { if (fid != null) open(fid); }));
            gx.t(o.buy ? "BUY" : "SELL", 6, oy, o.buy ? GREEN : RED);
            gx.t(gx.fit(o.name, (int) (width * 0.26)), 6 + gx.cw() * 5, oy, WHITE);
            gx.tr(Fmt.qty(o.units) + " @ " + Fmt.px(o.price), (int) (width * 0.55), oy, GRAY);
            String s = switch (st) {
                case FILLED -> o.filledUnits > 0 && o.filledUnits < o.units ? "PARTIAL " + Fmt.qty(o.filledUnits) + "/" + Fmt.qty(o.units) + " - claim" : "FILLED - claim it";
                case TOP -> "ON TOP";
                case BEHIND -> (o.buy ? "OUTBID, best " : "UNDERCUT, best ") + (mk == null ? "-" : Fmt.px(o.buy ? mk.bid() : mk.ask()));
            };
            gx.t(s, (int) (width * 0.58), oy, st == Portfolio.Status.TOP ? GREEN : st == Portfolio.Status.FILLED ? YELLOW : RED);
            if (st == Portfolio.Status.BEHIND && fid != null) {
                String b = " REPRICE ";
                int bw = gx.w(b) + 2, bx = width - 6 - gx.w("99h 59m") - 8 - bw;
                boolean hv = mx >= bx && mx < bx + bw && my >= oy - 1 && my < oy + LH - 1;
                gx.fill(bx, oy - 1, bx + bw, oy + LH - 2, hv ? AMBER : TOOLBAR);
                gx.t(b, bx + 1, oy, hv ? BLACK : WHITE);
                final Portfolio.Order fo = o;
                hits.add(new Hit(bx, oy - 1, bx + bw, oy + LH - 1, () -> reprice(fo)));
            }
            gx.tr(Fmt.ago(now - o.at), width - 6, oy, DIM);
        }
    }

    private void repriceFirstOutbid() {
        for (Portfolio.Order o : Terminal.PORT.orderList()) {
            String id = Feed.idForName(o.name);
            if (id != null && Portfolio.status(o, Feed.snapshot.get(id)) == Portfolio.Status.BEHIND) { reprice(o); return; }
        }
        status("No outbid or undercut orders right now", GRAY);
    }

    /** Cancel the old order, then put the rest back up at the best price. Two guided steps, you click both. */
    private void reprice(Portfolio.Order o) {
        String id = Feed.idForName(o.name);
        Market.Product p = id == null ? null : Feed.snapshot.get(id);
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.getConnection() == null) { status("No live data for " + o.name, RED); return; }
        Ticket.Side side = o.buy ? Ticket.Side.BUY : Ticket.Side.SELL;
        double price = Ticket.joinPrice(side, p.bid(), p.ask());
        long left = (long) Math.max(1, o.units - o.filledUnits);
        onClose();
        Guide.launch(new Ticket.Active(id, o.name, side, Ticket.Mode.CANCEL, price, left, System.currentTimeMillis(), o.price));
    }

    private static String signed(double v) { return Math.abs(v) < 0.05 ? "0" : (v >= 0 ? "+" : "-") + Fmt.px(Math.abs(v)); }

    // ---------- LIMIT ----------

    private void limitCommand(String args) {
        String a = args == null ? "" : args.trim();
        long now = System.currentTimeMillis();
        if (a.isEmpty()) { go(View.LIM, null); return; }
        String up = a.toUpperCase(Locale.US);
        if (up.startsWith("RESET")) {
            String t = a.substring(5).trim();
            if (t.equalsIgnoreCase("AUTO")) { Terminal.CONFIG.resetSource = ""; Terminal.CONFIG.save(); status("Reset time will be learned again", GREEN); return; }
            int m = Limit.parseClock(t, java.time.ZoneId.systemDefault(), now);
            if (m < 0) { status("LIMIT RESET 7:00 PM (your time) or LIMIT RESET UTC 23:00", RED); return; }
            Terminal.CONFIG.resetMinuteUtc = m;
            Terminal.CONFIG.resetSource = "manual";
            Terminal.CONFIG.save();
            status("Limit resets daily at " + Limit.clock(m, java.time.ZoneId.systemDefault(), now), GREEN);
            return;
        }
        if (up.equals("AUTO") || up.equals("CLEAR")) {
            Terminal.CONFIG.limitCoins = 0;
            Terminal.CONFIG.limitSource = "";
            Terminal.CONFIG.save();
            status("Limit cleared. It is learned from Hypixel's limit message.", GRAY);
            return;
        }
        double v = Ticket.parseNum(a);
        if (v <= 0) { status("LIMIT 15B, LIMIT RESET 7:00 PM, or LIMIT AUTO", RED); return; }
        Terminal.CONFIG.limitCoins = v;
        Terminal.CONFIG.limitSource = "manual";
        Terminal.CONFIG.save();
        status("Daily limit " + Fmt.px(v), GREEN);
    }

    private void drawLimit(int top, int bottom, int mx, int my) {
        long now = System.currentTimeMillis();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Config c = Terminal.CONFIG;
        long ws = Terminal.windowStart(now), nr = Limit.nextReset(now, c.resetMinuteUtc);
        double used = Terminal.PORT.usedSince(ws), lim = Terminal.limitAmount(), left = Math.max(0, lim - used);
        String lsrc = c.limitCoins > 0 ? c.limitSource : "not known, ~15B assumed";
        String rsrc = c.resetSource.isEmpty() ? "assumed" : c.resetSource;
        int x = 6;
        x = pair("USED ", Fmt.px(used), x, top + 2, WHITE);
        x = pair("LIMIT ", (c.limitCoins > 0 ? "" : "~") + Fmt.px(lim) + " (" + lsrc + ")", x, top + 2, c.limitCoins > 0 ? AMBER : GRAY);
        x = pair("LEFT ", Fmt.px(left), x, top + 2, left <= 0 ? RED : GREEN);
        int y = top + LH + 4;
        x = pair("RESETS IN ", Limit.countdown(nr - now), 6, y, WHITE);
        x = pair("RESET AT ", Limit.clock(c.resetMinuteUtc, zone, now) + " (" + rsrc + ")", x, y, GRAY);
        boolean blocked = Terminal.PORT.blockedAt >= ws;
        if (blocked) gx.t("HYPIXEL BLOCKED YOU " + Fmt.ago(now - Terminal.PORT.blockedAt) + " AGO", x + 10, y, RED);
        y += LH + 3;
        int bw = width - 12;
        gx.fill(6, y, 6 + bw, y + 7, 0xFF1C1C1C);
        gx.fill(6, y, 6 + (int) (bw * Math.min(1, used / lim)), y + 7, used / lim > 0.9 ? RED : used / lim > 0.7 ? YELLOW : GREEN);
        gx.box(6, y, bw, 7, LINE);
        y += 12;
        gx.t("COUNTED TODAY", 6, y, AMBER);
        gx.t("every order, offer and instant trade; cancelling does not give it back", 14 + gx.w("COUNTED TODAY"), y, DIM);
        y += LH + 1;
        gx.t("TIME", 6, y, AMBER);
        gx.t("TYPE", 6 + gx.cw() * 11, y, AMBER);
        gx.t("ITEM", 6 + gx.cw() * 23, y, AMBER);
        gx.tr("VALUE", (int) (width * 0.74), y, AMBER);
        gx.tr("RUNNING", width - 6, y, AMBER);
        gx.hline(4, width - 4, y + LH - 1, LINE);
        y += LH + 1;
        List<Portfolio.Entry> es = Terminal.PORT.ledgerSince(ws);
        double[] run = new double[es.size()];
        double acc = 0;
        for (int i = 0; i < es.size(); i++) { acc += es.get(i).value; run[i] = acc; }
        int fit = Math.max(1, (bottom - y - LH - 2) / LH);
        if (es.isEmpty()) gx.t("Nothing counted since the window opened. Orders placed while the terminal is loaded are logged here.", 6, y, GRAY);
        for (int k = 0; k < Math.min(fit, es.size()); k++) {
            int i = es.size() - 1 - k, yy = y + k * LH;
            Portfolio.Entry e = es.get(i);
            gx.t(DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.Instant.ofEpochMilli(e.at).atZone(zone)), 6, yy, GRAY);
            gx.t(e.kind, 6 + gx.cw() * 11, yy, e.kind.startsWith("BUY") || e.kind.equals("INSTA BUY") ? GREEN : RED);
            gx.t(gx.fit(e.name, (int) (width * 0.74) - gx.cw() * 23 - 90), 6 + gx.cw() * 23, yy, WHITE);
            gx.tr(Fmt.px(e.value), (int) (width * 0.74), yy, WHITE);
            gx.tr(Fmt.px(run[i]), width - 6, yy, GRAY);
        }
        double prev = Terminal.PORT.usedSince(ws - Limit.DAY) - used;
        gx.t("PREVIOUS WINDOW " + Fmt.px(prev) + (Terminal.PORT.trackingSince > ws - Limit.DAY ? "  (tracking began partway, so this is partial)" : ""), 6, bottom - LH, DIM);
        rows = new ArrayList<>();
    }

    // ---------- compare (CMP) ----------

    private List<Bars.Point> seriesFor(String id, long now) {
        List<Bars.Point> src;
        if (range == Command.Range.W1 || range == Command.Range.D3) {
            List<Bars.Point> wk = Feed.week(id);
            src = wk == null ? new ArrayList<>() : Bars.merge(wk, Feed.RECORDER.points(id));
        } else {
            Feed.day(id);
            src = Feed.recent(id);
        }
        List<Bars.Point> pts = new ArrayList<>(Bars.window(src, now - range.span, now));
        Market.Product p = Feed.snapshot.get(id);
        if (p != null) pts.add(new Bars.Point(now, p.bid(), p.ask()));
        return pts;
    }

    /** % change from the first point of the range, NaN where there is no price. */
    private static double[] rebased(List<Bars.Point> pts) {
        double[] out = new double[pts.size()];
        double base = Double.NaN;
        for (int i = 0; i < pts.size(); i++) {
            Bars.Point pt = pts.get(i);
            double m = pt.bid() > 0 && pt.ask() > 0 ? (pt.bid() + pt.ask()) / 2 : Double.NaN;
            if (Double.isNaN(base) && Double.isFinite(m)) base = m;
            out[i] = Double.isFinite(m) && base > 0 ? (m / base - 1) * 100 : Double.NaN;
        }
        return out;
    }

    private void drawCompare(int x, int y, int w, int h, long now, int mx, int my) {
        gx.fill(x, y, x + w, y + h, PANEL);
        gx.box(x, y, w, h, LINE);
        int tx = x + 4;
        for (Command.Range r : Command.Range.values()) {
            int tw = gx.w(r.label) + 8;
            boolean on = r == range;
            gx.fill(tx, y + 3, tx + tw, y + 13, on ? AMBER : 0xFF1C1C1C);
            gx.t(r.label, tx + 4, y + 4, on ? BLACK : GRAY);
            hits.add(new Hit(tx, y + 3, tx + tw, y + 13, () -> range = r));
            tx += tw + 3;
        }
        List<Bars.Point> a = seriesFor(item, now), b = seriesFor(cmpItem, now);
        double[] ra = rebased(a), rb = rebased(b);
        tx += 8;
        gx.fill(tx, y + 7, tx + 8, y + 9, GREEN);
        tx = gx.t(gx.fit(Feed.name(item), 120) + " " + Fmt.pct(last(ra)), tx + 11, y + 4, GRAY) + 8;
        gx.fill(tx, y + 7, tx + 8, y + 9, AMBER);
        gx.t(gx.fit(Feed.name(cmpItem), 120) + " " + Fmt.pct(last(rb)), tx + 11, y + 4, GRAY);
        int px = x + 6, py = y + 20, pw = w - 64, ph = h - 34;
        double lo = 0, hi = 0;
        for (double v : ra) if (Double.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        for (double v : rb) if (Double.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (a.size() < 2 && b.size() < 2) { gx.t("Loading history...", px + 6, py + ph / 2, GRAY); return; }
        double pad = Math.max((hi - lo) * 0.08, 0.5);
        lo -= pad;
        hi += pad;
        for (int i = 0; i <= 4; i++) {
            double v = lo + (hi - lo) * i / 4;
            int gy = yOf(v, lo, hi, py, ph);
            gx.dotted(px, px + pw, gy, GRID);
            gx.t(String.format(Locale.US, "%+.1f%%", v), px + pw + 4, gy - 4, DIM);
        }
        int zy = yOf(0, lo, hi, py, ph);
        gx.hline(px, px + pw, zy, AMBER_DIM);
        long from = now - range.span;
        plotPct(a, ra, from, lo, hi, px, py, pw, ph, GREEN);
        plotPct(b, rb, from, lo, hi, px, py, pw, ph, AMBER);
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            for (int gy = py; gy < py + ph; gy += 2) gx.fill(mx, gy, mx + 1, gy + 1, 0xFF505050);
            long t = from + (long) ((mx - px) * (double) range.span / pw);
            String s = label(t, range.span) + "  " + Fmt.pct(nearest(a, ra, t)) + " vs " + Fmt.pct(nearest(b, rb, t));
            int sw = gx.w(s) + 8, sx = Math.min(mx + 6, px + pw - sw);
            gx.fill(sx, py + 2, sx + sw, py + 13, 0xF0000000);
            gx.box(sx, py + 2, sw, 11, AMBER_DIM);
            gx.t(s, sx + 4, py + 4, WHITE);
        }
    }

    private static double last(double[] v) {
        for (int i = v.length - 1; i >= 0; i--) if (Double.isFinite(v[i])) return v[i];
        return Double.NaN;
    }

    private static double nearest(List<Bars.Point> pts, double[] v, long t) {
        int best = -1;
        for (int i = 0; i < pts.size(); i++) if (best < 0 || Math.abs(pts.get(i).time() - t) < Math.abs(pts.get(best).time() - t)) best = i;
        return best < 0 ? Double.NaN : v[best];
    }

    private void plotPct(List<Bars.Point> pts, double[] v, long from, double lo, double hi, int px, int py, int pw, int ph, int color) {
        int lx = Integer.MIN_VALUE, ly = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (!Double.isFinite(v[i])) { lx = Integer.MIN_VALUE; continue; }
            int cx = px + (int) ((pts.get(i).time() - from) * (double) pw / range.span), cy = yOf(v[i], lo, hi, py, ph);
            if (lx != Integer.MIN_VALUE) gx.line(lx, ly, cx, cy, color);
            lx = cx;
            ly = cy;
        }
    }

    // ---------- PNL ----------

    private void drawPnl(int top, int bottom, int mx, int my) {
        long now = System.currentTimeMillis();
        Portfolio pf = Terminal.PORT;
        List<Portfolio.Trade> tr = pf.tradeList();
        tr.sort(Comparator.comparingLong((Portfolio.Trade t) -> t.at));
        double d7 = 0, d30 = 0;
        int wins = 0;
        for (Portfolio.Trade t : tr) {
            if (now - t.at < 7 * 86_400_000L) d7 += t.pnl;
            if (now - t.at < 30 * 86_400_000L) d30 += t.pnl;
            if (t.pnl > 0) wins++;
        }
        double today = pf.realizedOn(Portfolio.day(now));
        int x = 6;
        x = pair("TODAY ", signed(today), x, top + 2, changeColor(today));
        x = pair("7D ", signed(d7), x, top + 2, changeColor(d7));
        x = pair("30D ", signed(d30), x, top + 2, changeColor(d30));
        x = pair("ALL ", signed(pf.realizedAll), x, top + 2, changeColor(pf.realizedAll));
        x = pair("TRADES ", Integer.toString(tr.size()), x, top + 2, WHITE);
        pair("WIN ", tr.isEmpty() ? "-" : String.format(Locale.US, "%.0f%%", wins * 100.0 / tr.size()), x, top + 2, WHITE);
        if (tr.isEmpty()) {
            rows = new ArrayList<>();
            gx.t("No closed trades yet. Each sale of something with a tracked cost (claims, insta-sells) adds one.", 6, top + LH + 8, GRAY);
            return;
        }
        // equity curve
        int cy = top + LH + 6, ch = Math.max(50, (bottom - cy) * 2 / 5), cw = width - 70;
        gx.fill(4, cy, width - 4, cy + ch, PANEL);
        gx.box(4, cy, width - 8, ch, LINE);
        gx.t("REALIZED P&L, CUMULATIVE", 9, cy + 4, AMBER);
        long from = tr.get(0).at, span = Math.max(60_000L, now - from);
        double cum = 0, lo = 0, hi = 0;
        double[] cumv = new double[tr.size()];
        for (int i = 0; i < tr.size(); i++) { cum += tr.get(i).pnl; cumv[i] = cum; lo = Math.min(lo, cum); hi = Math.max(hi, cum); }
        double pad = Math.max((hi - lo) * 0.1, 1);
        lo -= pad;
        hi += pad;
        int px = 10, py = cy + 16, pw = cw, ph = ch - 22;
        gx.hline(px, px + pw, yOf(0, lo, hi, py, ph), AMBER_DIM);
        gx.t(Fmt.px(hi), px + pw + 4, py, DIM);
        gx.t(Fmt.px(lo), px + pw + 4, py + ph - 8, DIM);
        int lx = px, ly = yOf(0, lo, hi, py, ph);
        for (int i = 0; i < tr.size(); i++) {
            int tx = px + (int) ((tr.get(i).at - from) * (double) pw / span), ty = yOf(cumv[i], lo, hi, py, ph);
            gx.line(lx, ly, tx, ly, cumv[i] >= 0 ? GREEN : RED);
            gx.line(tx, ly, tx, ty, cumv[i] >= 0 ? GREEN : RED);
            lx = tx;
            ly = ty;
        }
        gx.line(lx, ly, px + pw, ly, cum >= 0 ? GREEN : RED);

        // daily (left) and by item (right)
        int y0 = cy + ch + 6, half = width / 2;
        gx.t("BY DAY", 6, y0, AMBER);
        gx.t("BY ITEM", half + 6, y0, AMBER);
        gx.tr("TRADES  WIN   P&L", width - 6, y0, DIM);
        y0 += LH + 2;
        int fit = Math.max(1, (bottom - y0) / LH);
        List<String> days = new ArrayList<>(pf.daily.keySet());
        days.sort(Comparator.reverseOrder());
        double maxAbs = 1;
        for (String d : days) maxAbs = Math.max(maxAbs, Math.abs(pf.daily.get(d)));
        for (int i = 0; i < Math.min(days.size(), fit); i++) {
            double v = pf.daily.get(days.get(i));
            int yy = y0 + i * LH, bw = (int) ((half * 0.35) * Math.abs(v) / maxAbs), bx = 6 + gx.w("2026-11-02") + 8;
            gx.t(days.get(i), 6, yy, GRAY);
            gx.fill(bx, yy, bx + bw, yy + LH - 3, v >= 0 ? 0x802BE04A : 0x80FF3B3B);
            gx.tr(signed(v), half - 8, yy, changeColor(v));
        }
        Map<String, double[]> by = new java.util.HashMap<>(); // trades, wins, pnl
        for (Portfolio.Trade t : tr) {
            double[] a = by.computeIfAbsent(t.name, k -> new double[3]);
            a[0]++;
            if (t.pnl > 0) a[1]++;
            a[2] += t.pnl;
        }
        List<String> names = new ArrayList<>(by.keySet());
        names.sort(Comparator.comparingDouble((String n) -> -Math.abs(by.get(n)[2])));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(names.size(), fit); i++) {
            String n = names.get(i);
            double[] a = by.get(n);
            String id = Feed.idForName(n);
            ids.add(id == null ? n : id);
            int yy = y0 + i * LH;
            boolean hv = mx >= half && my >= yy - 1 && my < yy + LH - 1;
            if (hv) gx.fill(half, yy - 1, width, yy + LH - 1, HOVER);
            if (id != null) hits.add(new Hit(half, yy - 1, width, yy + LH - 1, () -> open(id)));
            gx.t(gx.fit(n, (int) (half * 0.45)), half + 6, yy, WHITE);
            gx.tr(String.format(Locale.US, "%d  %3.0f%%  ", (int) a[0], a[1] * 100 / a[0]), width - 6 - gx.w("+999.9M"), yy, GRAY);
            gx.tr(signed(a[2]), width - 6, yy, changeColor(a[2]));
        }
        rows = ids;
    }

    // ---------- ALRT ----------

    private void addAlert(String args) {
        Alerts.Parsed a = Alerts.parse(args);
        if (a.error() != null) { status(a.error(), RED); return; }
        List<String> m = find(a.item());
        if (m.isEmpty()) { status("No Bazaar item matches \"" + a.item() + "\"", RED); return; }
        Alerts.Rule r = new Alerts.Rule();
        r.id = m.get(0);
        r.field = a.field().name();
        r.op = a.op();
        r.value = a.value();
        Terminal.CONFIG.priceAlerts.add(r);
        Terminal.CONFIG.save();
        status("Alert set: " + Feed.name(r.id) + " " + r.describe(), GREEN);
    }

    private void deleteAlert() {
        List<Alerts.Rule> rs = Terminal.CONFIG.priceAlerts;
        if (view != View.ALRT || sel >= rs.size()) return;
        rs.remove(sel);
        Terminal.CONFIG.save();
        status("Alert deleted", GRAY);
    }

    private void rearm(boolean all) {
        List<Alerts.Rule> rs = Terminal.CONFIG.priceAlerts;
        for (int i = 0; i < rs.size(); i++) if (all || i == sel) rs.get(i).fired = false;
        Terminal.CONFIG.save();
        status(all ? "All alerts re-armed" : "Alert re-armed", GREEN);
    }

    private static final String[] ACOLS = {"ITEM", "CONDITION", "NOW", "STATUS"};
    private static final double[] ACOLW = {0.34, 0.26, 0.16, 0.24};

    private void drawAlerts(int top, int bottom, int mx, int my) {
        List<Alerts.Rule> rs = new ArrayList<>(Terminal.CONFIG.priceAlerts);
        List<String> ids = new ArrayList<>();
        for (Alerts.Rule r : rs) ids.add(r.id);
        rows = ids;
        if (rs.isEmpty()) {
            gx.t("No alerts. ALERT enchanted coal < 120 sets one; it fires once in chat with a sound.", 6, top + 4, GRAY);
            gx.t("Fields: price (default), BID, ASK, SPR (spread %), CHG1H (1 hour % move). Operators > < >= <=.", 6, top + 4 + LH, DIM);
            return;
        }
        Table t = new Table(ACOLS, ACOLW);
        t.header(top + 2, -1, false, c -> {});
        int y0 = top + LH + 4, fit = Math.max(1, (bottom - y0) / LH);
        clampSel(rs.size(), fit);
        long now = System.currentTimeMillis();
        for (int i = scroll; i < Math.min(rs.size(), scroll + fit); i++) {
            Alerts.Rule r = rs.get(i);
            int y = y0 + (i - scroll) * LH, idx = i;
            rowBackground(y, i == sel, mx, my, () -> sel = idx);
            t.cell(0, y, Feed.name(r.id), i == sel ? AMBER : WHITE, 0);
            t.cell(1, y, r.describe(), GRAY, 0);
            double v = Alerts.value(r.f(), Feed.snapshot.get(r.id), Feed.change(r.id, 3_600_000L));
            t.cell(2, y, r.f() == Alerts.Field.SPR || r.f() == Alerts.Field.CHG1H ? Fmt.pct(v) : Fmt.px(v), WHITE, 0);
            t.cell(3, y, r.fired ? "FIRED " + Fmt.ago(now - r.firedAt) + " ago" : "ARMED", r.fired ? YELLOW : GREEN, 0);
        }
    }

    // ---------- SECT ----------

    private static final String[] SCOLS = {"SECTOR", "ITEMS", "UP", "DOWN", "1H", "24H", "FLOW/D"};
    private static final double[] SCOLW = {0.30, 0.10, 0.10, 0.10, 0.12, 0.12, 0.16};

    private void drawSectors(int top, int bottom, int mx, int my) {
        List<Sectors.Stat> st = Sectors.stats(Feed.snapshot.products().values(), id -> Feed.change(id, 86_400_000L),
                id -> Feed.change(id, 3_600_000L), Terminal.CONFIG.minWeekVolume);
        gx.t("Flow-weighted moves, items trading " + Fmt.qty(Terminal.CONFIG.minWeekVolume) + "+ a week. Enter opens a sector.", 6, top + 2, DIM);
        Table t = new Table(SCOLS, SCOLW);
        t.header(top + LH + 4, -1, false, c -> {});
        int y0 = top + 2 * LH + 6;
        List<String> names = new ArrayList<>();
        for (Sectors.Stat s : st) names.add(s.name());
        rows = names;
        clampSel(st.size(), st.size());
        for (int i = 0; i < st.size(); i++) {
            Sectors.Stat s = st.get(i);
            int y = y0 + i * LH, idx = i;
            rowBackground(y, i == sel, mx, my, () -> { sel = idx; openSector(idx); });
            t.cell(0, y, s.name(), i == sel ? AMBER : WHITE, 0);
            t.cell(1, y, Integer.toString(s.items()), GRAY, 0);
            t.cell(2, y, Integer.toString(s.up()), GREEN, 0);
            t.cell(3, y, Integer.toString(s.down()), RED, 0);
            t.cell(4, y, Fmt.pct(s.chg1h()), changeColor(s.chg1h()), 0);
            t.cell(5, y, Fmt.pct(s.chg24()), changeColor(s.chg24()), 0);
            t.cell(6, y, Fmt.px(s.flow()), GRAY, 0);
        }
    }

    private void openSector(int i) {
        if (i < 0 || i >= Sectors.ALL.length) return;
        sector = Sectors.ALL[i];
        go(View.SECX, null);
    }

    private void drawSectorItems(int top, int bottom, int mx, int my) {
        List<String> ids = new ArrayList<>();
        for (Market.Product p : Feed.snapshot.products().values())
            if (p.weekVolume() >= Terminal.CONFIG.minWeekVolume && Sectors.of(p.id()).equals(sector)) ids.add(p.id());
        sortBy(ids, id -> { Market.Product p = Feed.snapshot.get(id); return p == null ? 0 : p.coinFlowDay(); }, true);
        rows = ids;
        Table t = new Table(COLS, COLW);
        t.header(top + 2, 7, true, c -> {});
        listRows(t, ids, top + LH + 4, bottom, mx, my);
    }

    // ---------- order ticket ----------

    private static final long MAX_ORDER_UNITS = 71_680;

    private static long clampQty(long q) { return Math.max(1, Math.min(MAX_ORDER_UNITS, q)); }

    /** The most you can buy at the ticket price with your purse (capped at Hypixel's order limit). */
    private long maxQty() {
        Market.Product p = Feed.snapshot.get(item);
        double px = ticketPrice(p, Ticket.Side.BUY);
        if (p == null || px <= 0) return MAX_ORDER_UNITS;
        double budget = Terminal.purse > 0 ? Terminal.purse : Double.MAX_VALUE;
        if ("manual".equals(Terminal.CONFIG.limitSource) && Terminal.CONFIG.limitCoins > 0) {
            double left = Terminal.CONFIG.limitCoins - Terminal.PORT.usedSince(Terminal.windowStart(System.currentTimeMillis()));
            budget = Math.min(budget, Math.max(0, left));
        }
        if (budget == Double.MAX_VALUE) return MAX_ORDER_UNITS;
        return Math.max(0, Math.min(MAX_ORDER_UNITS, (long) Math.floor(budget / px)));
    }

    /** The buy quantity actually used: what you picked, never more than your purse (or a limit you set) allows. */
    private long effectiveQty() {
        long want = clampQty(Terminal.CONFIG.ticketQty), max = maxQty();
        return max >= 1 ? Math.min(want, max) : want;
    }

    /** Rounds down to 1, 2 or 5 times a power of ten, so chips read cleanly. */
    private static long nice(long n) {
        if (n < 10) return Math.max(1, n);
        long m = 1;
        while (m * 10 <= n) m *= 10;
        for (long lead : new long[]{5, 2, 1}) if (lead * m <= n) return lead * m;
        return m;
    }

    /** Four quantity chips scaled to the price: 5%, 10%, 25% and 50% of what your purse can buy. */
    private long[] scaledChips() {
        long max = maxQty();
        if (max < 1) return new long[0];
        double[] fr = {0.05, 0.10, 0.25, 0.50};
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (double f : fr) {
            long q = nice((long) Math.max(1, Math.floor(max * f)));
            if (q < max) out.add(q);
        }
        long[] r = new long[out.size()];
        int i = 0;
        for (long q : out) r[i++] = q;
        return r;
    }

    private static double ticketPrice(Market.Product p, Ticket.Side side) {
        if (p == null) return 0;
        return tPrice > 0 ? tPrice : Ticket.joinPrice(side, p.bid(), p.ask());
    }

    /** BUY 5k 120.5 NOW / SELL 130 NOW */
    private void ticketCommand(Command.Parsed c) {
        if (view != View.ITEM || item == null) { status("Open an item first, then " + c.fn() + ".", RED); return; }
        boolean buy = c.fn() == Command.Fn.BUY, now = false;
        double qty = -1, price = -1;
        for (String t : c.item().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.equals("NOW") || t.equals("INSTA")) { now = true; continue; }
            double v = Ticket.parseNum(t);
            if (v < 0) { status("Can't read \"" + t + "\". Try " + c.fn() + (buy ? " 5k 120.5" : " 130.5") + " [NOW]", RED); return; }
            if (buy && qty < 0) qty = v; else price = v;
        }
        tSide = buy ? Ticket.Side.BUY : Ticket.Side.SELL;
        if (qty >= 1) { Terminal.CONFIG.ticketQty = clampQty((long) qty); Terminal.CONFIG.save(); }
        if (price > 0) tPrice = Ticket.round1(price);
        placeTicket(now);
    }

    /** Hands the ticket to the Bazaar menus: opens /bz for the item and starts the green-slot guide. */
    private void placeTicket(boolean instant) {
        Market.Product p = Feed.snapshot.get(item);
        if (p == null || item == null) { status("No live data for this item yet.", RED); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) { status("Not connected to a server.", RED); return; }
        Ticket.Side side = tSide;
        long qty = 0;
        if (side == Ticket.Side.BUY) {
            qty = effectiveQty();
            double px = instant ? p.ask() : ticketPrice(p, side);
            if (Terminal.purse > 0 && px * qty > Terminal.purse) {
                status("Not enough coins: " + Fmt.px(px * qty) + " needed, purse " + Fmt.px(Terminal.purse) + ". Try QTY MAX.", RED);
                return;
            }
        } else if (Guide.carrying(item) <= 0) {
            status("You are not carrying any " + Feed.name(item) + ". A sell offer lists what is in your inventory.", RED);
            return;
        }
        double price = instant ? 0 : ticketPrice(p, side);
        double value = side == Ticket.Side.BUY ? qty * (instant ? p.ask() : price) : Guide.carrying(item) * (instant ? p.bid() : price);
        long nowT = System.currentTimeMillis();
        long ws = Terminal.windowStart(nowT);
        double used = Terminal.PORT.usedSince(ws);
        long untilReset = Limit.nextReset(nowT, Terminal.CONFIG.resetMinuteUtc) - nowT;
        if (Terminal.PORT.blockedAt >= ws) {
            status("Hypixel said the daily limit was reached today. Resets in about " + Limit.countdown(untilReset) + " (LIMIT shows the ledger).", RED);
            return;
        }
        if ("manual".equals(Terminal.CONFIG.limitSource) && used + value > Terminal.CONFIG.limitCoins) {
            status("Over your daily limit: " + Fmt.px(used) + " used + " + Fmt.px(value) + " > " + Fmt.px(Terminal.CONFIG.limitCoins)
                    + ". Resets in " + Limit.countdown(untilReset) + ". Lower QTY.", RED);
            return;
        }
        Ticket.Active a = new Ticket.Active(item, Feed.name(item), side, instant ? Ticket.Mode.INSTA : Ticket.Mode.ORDER, price, qty, System.currentTimeMillis());
        onClose();
        Guide.launch(a);
    }

    private void drawTicket(Market.Product p, int x, int y, int w, int h, int mx, int my) {
        gx.fill(x, y, x + w, y + h, PANEL);
        gx.box(x, y, w, h, LINE);
        boolean buy = tSide == Ticket.Side.BUY;
        int side = buy ? GREEN : RED;
        gx.t("ORDER TICKET", x + 5, y + 4, AMBER);
        // side tabs
        int tw = (w - 12) / 2, ty = y + 4 + LH + 1;
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 0) == buy;
            int tx = x + 4 + i * (tw + 4), col = i == 0 ? GREEN : RED;
            gx.fill(tx, ty, tx + tw, ty + 12, on ? col : 0xFF1C1C1C);
            String lab = i == 0 ? "BUY" : "SELL";
            gx.tc(lab, tx + tw / 2, ty + 2, on ? BLACK : GRAY);
            final Ticket.Side sd = i == 0 ? Ticket.Side.BUY : Ticket.Side.SELL;
            hits.add(new Hit(tx, ty, tx + tw, ty + 12, () -> tSide = sd));
        }
        int ry = ty + 16;
        long qty = effectiveQty();
        double px = ticketPrice(p, tSide);
        String pxNote = tPrice > 0 ? "custom" : buy ? "bid+0.1" : "ask-0.1";
        if (buy) {
            gx.t("QTY", x + 5, ry, AMBER_DIM);
            gx.tr(Fmt.qty(qty), x + w - 5, ry, WHITE);
            ry += LH + 1;
            long[] sc = scaledChips();
            long[] chips = new long[sc.length + 1];
            String[] labs = new String[sc.length + 1];
            for (int i = 0; i < sc.length; i++) { chips[i] = sc[i]; labs[i] = Fmt.qty(sc[i]); }
            chips[sc.length] = -1;
            labs[sc.length] = "MAX";
            int cx = x + 4, cw = (w - 8 - (chips.length - 1) * 2) / chips.length;
            long maxQ = maxQty();
            for (int i = 0; i < chips.length; i++) {
                final long q = chips[i];
                boolean on = q > 0 ? q == qty : qty == maxQ;
                gx.fill(cx, ry, cx + cw, ry + 11, on ? AMBER : 0xFF1C1C1C);
                gx.tc(labs[i], cx + cw / 2, ry + 2, on ? BLACK : GRAY);
                hits.add(new Hit(cx, ry, cx + cw, ry + 11, () -> {
                    Terminal.CONFIG.ticketQty = q < 0 ? Math.max(1, maxQty()) : q;
                    Terminal.CONFIG.save();
                }));
                cx += cw + 2;
            }
            ry += 13;
            boolean hc = mx >= x + 4 && mx < x + w - 4 && my >= ry && my < ry + 11;
            gx.fill(x + 4, ry, x + w - 4, ry + 11, hc ? 0xFF2C2C2C : 0xFF1C1C1C);
            gx.box(x + 4, ry, w - 8, 11, AMBER_DIM);
            gx.tc("CUSTOM QUANTITY", x + w / 2, ry + 2, AMBER);
            hits.add(new Hit(x + 4, ry, x + w - 4, ry + 11, () -> { cmd = "QTY "; status("Type a quantity like 250 or 5k, then Enter. MAX works too.", GRAY); }));
            ry += 14;
        } else {
            gx.t("CARRYING", x + 5, ry, AMBER_DIM);
            gx.tr(Fmt.qty(Guide.carrying(item)), x + w - 5, ry, WHITE);
            ry += LH + 1;
            gx.t("sell offers list all you carry", x + 5, ry, DIM);
            ry += 14;
        }
        gx.t("PRICE", x + 5, ry, AMBER_DIM);
        gx.tr(Fmt.px(px) + " " + pxNote, x + w - 5, ry, side);
        ry += LH + 1;
        double total = (buy ? qty : Math.max(1, Guide.carrying(item))) * px;
        gx.t(buy ? "TOTAL" : "PROCEEDS", x + 5, ry, AMBER_DIM);
        String pct = buy && Terminal.purse > 0 ? String.format(Locale.US, "  %.0f%% of purse", total / Terminal.purse * 100) : "";
        gx.tr(Fmt.px(total) + pct, x + w - 5, ry, buy && Terminal.purse > 0 && total > Terminal.purse ? RED : WHITE);
        ry += LH + 1;
        double tax = 1 - Terminal.CONFIG.taxPct / 100.0;
        if (buy) {
            gx.t("BREAK-EVEN", x + 5, ry, AMBER_DIM);
            gx.tr(Fmt.px(px / tax) + " sell", x + w - 5, ry, WHITE);
            ry += LH + 1;
            double flip = ((p.ask() - 0.1) * tax - px) * qty;
            gx.t("FLIP AT ASK", x + 5, ry, AMBER_DIM);
            gx.tr(signed(flip) + " net", x + w - 5, ry, changeColor(flip));
        } else {
            Portfolio.Pos pos = Terminal.PORT.positions.get(Items.norm(Feed.name(item)));
            gx.t("YOUR COST", x + 5, ry, AMBER_DIM);
            gx.tr(pos == null ? "not tracked" : Fmt.px(pos.cost), x + w - 5, ry, pos == null ? DIM : WHITE);
            ry += LH + 1;
            gx.t("P&L AT PRICE", x + 5, ry, AMBER_DIM);
            if (pos == null) gx.tr("-", x + w - 5, ry, DIM);
            else { double pl = (px * tax - pos.cost) * Math.max(1, Guide.carrying(item)); gx.tr(signed(pl) + " net", x + w - 5, ry, changeColor(pl)); }
        }
        ry += LH + 1;
        double used = Terminal.PORT.usedSince(Terminal.windowStart(System.currentTimeMillis()));
        double left = Terminal.limitAmount() - used;
        gx.t("LIMIT LEFT", x + 5, ry, AMBER_DIM);
        gx.tr((Terminal.CONFIG.limitCoins > 0 ? "" : "~") + Fmt.px(Math.max(0, left)), x + w - 5, ry, left < total ? RED : GRAY);
        ry += LH + 3;
        // big GO button
        int bx = x + 4, bw = w - 8;
        String go = (buy ? "BUY ORDER" : "SELL OFFER") + "  <GO>";
        boolean hov = mx >= bx && mx < bx + bw && my >= ry && my < ry + 14;
        gx.fill(bx, ry, bx + bw, ry + 14, hov ? side : (side & 0x00FFFFFF) | 0xCC000000);
        gx.tc(go, bx + bw / 2, ry + 3, BLACK);
        hits.add(new Hit(bx, ry, bx + bw, ry + 14, () -> placeTicket(false)));
        ry += 17;
        String now = buy ? "BUY NOW at " + Fmt.px(p.ask()) : "SELL NOW at " + Fmt.px(p.bid());
        boolean hov2 = mx >= bx && mx < bx + bw && my >= ry && my < ry + 12;
        gx.fill(bx, ry, bx + bw, ry + 12, hov2 ? 0xFF2C2C2C : 0xFF1C1C1C);
        gx.box(bx, ry, bw, 12, side);
        gx.tc(now, bx + bw / 2, ry + 2, side);
        hits.add(new Hit(bx, ry, bx + bw, ry + 12, () -> placeTicket(true)));
    }

    private void addOrDel(Command.Fn fn, String id) {
        if (fn == Command.Fn.ADD) {
            if (!Terminal.CONFIG.watchlist.contains(id)) Terminal.CONFIG.watchlist.add(id);
            status("Added " + Feed.name(id) + " to MON", GREEN);
        } else {
            Terminal.CONFIG.watchlist.remove(id);
            status("Removed " + Feed.name(id) + " from MON", GRAY);
        }
        Terminal.CONFIG.save();
    }

    private List<String> find(String q) {
        return Items.find(q, Feed.snapshot.products().keySet(), Feed.names, Feed::rankVolume, 30);
    }

    private static boolean exact(String q, String id) {
        String n = Items.norm(q), name = Items.norm(Feed.name(id));
        return n.equals(name) || q.trim().equalsIgnoreCase(id) || n.length() >= 3 && name.startsWith(n);
    }

    private void open(String id) {
        go(View.ITEM, id);
        Feed.day(id);
        Feed.week(id);
    }

    private void go(View v, String id) {
        if (v != view || (id != null && !id.equals(item))) {
            back.push(new Place(view, item));
            while (back.size() > 40) back.removeLast();
        }
        view = v;
        status = "";
        if (id != null) item = id;
        sel = 0;
        scroll = 0;
    }

    private void toggleWatch(String id) {
        if (id == null) return;
        addOrDel(Terminal.CONFIG.watchlist.contains(id) ? Command.Fn.DEL : Command.Fn.ADD, id);
    }

    private void removeSelected() {
        if (view == View.PORT && sel < rows.size()) {
            Terminal.PORT.removePosition(Feed.name(rows.get(sel)));
            Terminal.PORT.save();
            status("Removed " + Feed.name(rows.get(sel)) + " from the portfolio", GRAY);
            return;
        }
        if (view != View.MON || sel >= rows.size()) return;
        addOrDel(Command.Fn.DEL, rows.get(sel));
    }

    private void moveSelected(int dir) {
        List<String> wl = Terminal.CONFIG.watchlist;
        if (sel >= rows.size()) return;
        monSort = -1;
        String id = rows.get(sel);
        int i = wl.indexOf(id), j = i + dir;
        if (i < 0 || j < 0 || j >= wl.size()) return;
        wl.set(i, wl.get(j));
        wl.set(j, id);
        sel = j;
        Terminal.CONFIG.save();
    }

    private void copyPrice(boolean bid) {
        Market.Product p = Feed.snapshot.get(item);
        if (p == null) return;
        double v = bid ? p.bid() + 0.1 : p.ask() - 0.1;
        String s = String.format(Locale.US, "%.1f", Math.round(v * 10) / 10.0);
        Minecraft.getInstance().keyboardHandler.setClipboard(s);
        status("Copied " + s + (bid ? " (best bid + 0.1)" : " (best ask - 0.1)") + ". Paste it into Hypixel's price sign.", GREEN);
    }

    private void openBazaar() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || item == null) return;
        String name = Feed.name(item);
        onClose();
        mc.getConnection().sendCommand("bz " + name);
    }
}
