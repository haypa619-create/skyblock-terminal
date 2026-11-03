package com.hayden.sbterminal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/** Crafting recipes from the NotEnoughUpdates item repo (community-maintained JSON). Pure parsing. */
public final class Recipes {
    public static final String URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/%s.json";
    static final String[] SLOTS = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};

    public record Recipe(String output, int outCount, Map<String, Integer> inputs) {}

    private Recipes() {}

    /** The 3x3 crafting recipe of an item, or null if it has none. */
    public static Recipe parse(String id, String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (o.has("recipe") && o.get("recipe").isJsonObject()) {
            Recipe r = grid(id, o.getAsJsonObject("recipe"));
            if (r != null) return r;
        }
        if (o.has("recipes") && o.get("recipes").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("recipes")) {
                if (!e.isJsonObject()) continue;
                JsonObject r = e.getAsJsonObject();
                String type = r.has("type") ? r.get("type").getAsString() : "crafting";
                if (!type.equals("crafting")) continue;
                Recipe g = grid(id, r);
                if (g != null) return g;
            }
        }
        return null;
    }

    private static Recipe grid(String id, JsonObject r) {
        Map<String, Integer> in = new LinkedHashMap<>();
        for (String s : SLOTS) {
            if (!r.has(s) || !r.get(s).isJsonPrimitive()) continue;
            String v = r.get(s).getAsString().trim();
            if (v.isEmpty()) continue;
            int c = v.lastIndexOf(':');
            String item = v;
            int n = 1;
            if (c > 0) {
                try { n = Integer.parseInt(v.substring(c + 1)); item = v.substring(0, c); } catch (NumberFormatException ignored) { }
            }
            if (n <= 0) continue;
            in.merge(item.replace('-', ':'), n, Integer::sum);
        }
        if (in.isEmpty() || in.containsKey(id)) return null;
        int out = 1;
        if (r.has("count")) try { out = Math.max(1, r.get("count").getAsInt()); } catch (Exception ignored) { }
        return new Recipe(id, out, in);
    }
}
