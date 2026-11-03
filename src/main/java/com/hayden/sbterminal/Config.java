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
        c.file = f;
        c.save();
        return c;
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
