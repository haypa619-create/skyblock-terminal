package com.hayden.sbterminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mayor, election and news from Hypixel's public endpoints. Parsing is pure; refresh() does the requests. */
public final class World {
    public static final String ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election";
    public static final String NEWS_URL = "https://api.hypixel.net/v2/skyblock/news";

    public record Perk(String name, String description, boolean minister) {}

    public record Candidate(String key, String name, double votes) {}

    public record Mayor(String key, String name, List<Perk> perks, String ministerName, Perk ministerPerk) {}

    public record Election(Mayor mayor, int mayorElectionYear, int voteYear, List<Candidate> candidates, long fetchedAt) {}

    public record NewsItem(String title, String text, String link) {}

    public static volatile Election election = null;
    public static volatile List<NewsItem> news = List.of();
    public static volatile long newsAt = 0;
    private static volatile long lastTry = 0;

    private World() {}

    public static Election parseElection(String json, long now) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("mayor")) return null;
        JsonObject m = root.getAsJsonObject("mayor");
        List<Perk> perks = new ArrayList<>();
        if (m.has("perks")) for (JsonElement e : m.getAsJsonArray("perks")) perks.add(perk(e.getAsJsonObject(), false));
        String minName = "";
        Perk minPerk = null;
        if (m.has("minister") && m.get("minister").isJsonObject()) {
            JsonObject mi = m.getAsJsonObject("minister");
            minName = str(mi, "name");
            if (mi.has("perk") && mi.get("perk").isJsonObject()) minPerk = perk(mi.getAsJsonObject("perk"), true);
        }
        Mayor mayor = new Mayor(str(m, "key"), str(m, "name"), perks, minName, minPerk);
        int mayorYear = m.has("election") && m.get("election").isJsonObject() ? (int) Market.num(m.getAsJsonObject("election"), "year") : 0;
        int voteYear = 0;
        List<Candidate> cands = new ArrayList<>();
        if (root.has("current") && root.get("current").isJsonObject()) {
            JsonObject cur = root.getAsJsonObject("current");
            voteYear = (int) Market.num(cur, "year");
            if (cur.has("candidates")) for (JsonElement e : cur.getAsJsonArray("candidates")) {
                JsonObject c = e.getAsJsonObject();
                cands.add(new Candidate(str(c, "key"), str(c, "name"), Market.num(c, "votes")));
            }
        }
        return new Election(mayor, mayorYear, voteYear, cands, now);
    }

    public static List<NewsItem> parseNews(String json) {
        List<NewsItem> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("items")) return out;
        JsonArray arr = root.getAsJsonArray("items");
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            out.add(new NewsItem(str(o, "title"), str(o, "text"), str(o, "link")));
        }
        return out;
    }

    private static Perk perk(JsonObject o, boolean minister) {
        return new Perk(str(o, "name"), str(o, "description"), minister);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** Sectors a perk text is likely to push (mining, farming, fishing, combat). Keyword based, a hint rather than a fact. */
    public static Set<String> sectorsFor(String text) {
        Set<String> out = new LinkedHashSet<>();
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        if (t.contains("mining") || t.contains(" ore") || t.contains("gemstone") || t.contains("powder")) { out.add("Mining"); out.add("Gemstones"); }
        if (t.contains("farming") || t.contains("crop") || t.contains("garden") || t.contains("jacob")) out.add("Farming");
        if (t.contains("fishing") || t.contains("sea creature") || t.contains("fish")) out.add("Woods & Fishes");
        if (t.contains("slayer") || t.contains("combat") || t.contains("mob") || t.contains("dungeon")) out.add("Combat");
        return out;
    }

    /** Sectors the current mayor and minister perks lean on. */
    public static Set<String> boostedSectors(Election e) {
        Set<String> out = new LinkedHashSet<>();
        if (e == null) return out;
        for (Perk p : e.mayor().perks()) out.addAll(sectorsFor(p.name() + " " + p.description()));
        if (e.mayor().ministerPerk() != null) out.addAll(sectorsFor(e.mayor().ministerPerk().name() + " " + e.mayor().ministerPerk().description()));
        return out;
    }

    /** Refreshes at most every 10 minutes. Safe to call often, from a background thread. */
    public static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastTry < 10 * 60_000L) return;
        lastTry = now;
        try { Election e = parseElection(Http.get(ELECTION_URL), now); if (e != null) election = e; } catch (Exception ex) { Terminal.LOG.warn("Election: {}", ex.toString()); }
        try { news = parseNews(Http.get(NEWS_URL)); newsAt = now; } catch (Exception ex) { Terminal.LOG.warn("News: {}", ex.toString()); }
    }
}
