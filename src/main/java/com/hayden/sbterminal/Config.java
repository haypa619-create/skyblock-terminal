package com.hayden.sbterminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved to config/sbterminal.json in your Modrinth profile. */
public final class Config {
    /** Your Monitor list, item ids in order. */
    public List<String> watchlist = new ArrayList<>();
    /** Movers and the screener's default filter ignore items trading less than this per week. */
    public double minWeekVolume = 10_000;
    /** Seconds between live price checks while the terminal is open / closed. */
    public int pollOpenSecs = 5, pollClosedSecs = 20;
    /** Terminal font: true = bundled monospace (IBM Plex Mono), false = Minecraft's. */
    public boolean monoFont = true;
    /** Bazaar tax used to value positions (claim lines are already net). Lower it if you own the Community upgrades. */
    public double taxPct = 1.25;
    /** Chat + sound when an order is outbid or filled. */
    public boolean alerts = true;
    /** Daily Bazaar limit in coins. 0 = not known yet (set with LIMIT or learned from Hypixel's limit message). */
    public double limitCoins = 0;
    /** "manual" or "learned" (empty while unknown). */
    public String limitSource = "";
    /** When the limit window resets, as minutes after midnight UTC. Set with LIMIT RESET, corrected when seen. */
    public int resetMinuteUtc = 0;
    public String resetSource = "";
    /** Price alerts (ALERT coal < 120). */
    public List<Alerts.Rule> priceAlerts = new ArrayList<>();
    /** Order ticket: last quantity used for buy orders. */
    public long ticketQty = 1000;
    /** false: B and /sbt always open the Monitor. true: reopen on the screen you left. */
    public boolean reopenLast = false;
    /** Extra named Monitor lists (the built-in one is MAIN, stored in watchlist). */
    public java.util.Map<String, List<String>> lists = new java.util.LinkedHashMap<>();
    /** Which list the Monitor shows: MAIN or a key of lists. */
    public String activeList = "MAIN";
    /** Notifications. */
    public boolean alertFilled = true, alertOutbid = true;
    /** Warn once per limit window when this % of the daily limit is used. 0 = off. */
    public int limitWarnPct = 85;
    /** Sounds: PLING BELL CHIME HARP BIT ORB BASS. */
    public String soundGood = "PLING", soundBad = "BASS";
    /** Flip finder: share of the market's hourly volume you could realistically capture, and whether risky rows show. */
    public double flipShare = 0.05;
    public boolean flipRisky = false;
    /** Flip finder: one inventory (36 slots x 64) is the most you can hold, and items cheaper than flipMinPrice are skipped. */
    public double flipInvUnits = 2304, flipMinPrice = 1000;
    /** Chart: Bollinger bands (20 points, 2 sigma). */
    public boolean bollinger = false;
    /** Where you were, so the terminal reopens there. */
    public String lastView = "MON", lastItem = null, lastRange = "D1";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private transient Path file;

    public static Config load(Path dir) {
        Path f = dir.resolve("sbterminal.json");
        Config c = null;
        try {
            if (Files.exists(f)) c = GSON.fromJson(Files.readString(f), Config.class);
        } catch (Exception e) {
            Terminal.LOG.warn("Could not read config, using defaults", e);
        }
        if (c == null) c = new Config();
        if (c.watchlist == null) c.watchlist = new ArrayList<>();
        if (c.priceAlerts == null) c.priceAlerts = new ArrayList<>();
        if (c.lists == null) c.lists = new java.util.LinkedHashMap<>();
        if (c.activeList == null || (!c.activeList.equals("MAIN") && !c.lists.containsKey(c.activeList))) c.activeList = "MAIN";
        if (c.soundGood == null) c.soundGood = "PLING";
        if (c.soundBad == null) c.soundBad = "BASS";
        c.file = f;
        c.save();
        return c;
    }

    /** The list the Monitor is showing (never null). */
    public List<String> wl() {
        if (activeList == null || activeList.equals("MAIN")) return watchlist;
        return lists.computeIfAbsent(activeList, k -> new ArrayList<>());
    }

    /** MAIN first, then your lists in creation order. */
    public List<String> listNames() {
        List<String> out = new ArrayList<>();
        out.add("MAIN");
        out.addAll(lists.keySet());
        return out;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            Terminal.LOG.warn("Could not save config", e);
        }
    }
}
