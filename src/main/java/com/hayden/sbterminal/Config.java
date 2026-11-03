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
