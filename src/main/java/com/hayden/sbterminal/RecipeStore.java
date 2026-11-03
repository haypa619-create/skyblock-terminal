package com.hayden.sbterminal;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Downloads the crafting recipe of every Bazaar item once a week (NEU repo) and keeps it on disk. */
public final class RecipeStore {
    public static volatile Map<String, Recipes.Recipe> recipes = Map.of();
    public static volatile int done = 0, total = 0;
    public static volatile boolean running = false;
    private static final Gson GSON = new Gson();
    private static final long REFRESH = 7 * 86_400_000L;
    private static Path file;

    private static final class Saved {
        long at;
        Map<String, Recipes.Recipe> recipes = new HashMap<>();
    }

    private RecipeStore() {}

    public static void init(Path dir) {
        file = dir.resolve("sbterminal-recipes.json");
        long at = 0;
        try {
            if (Files.exists(file)) {
                Saved s = GSON.fromJson(Files.readString(file), Saved.class);
                if (s != null && s.recipes != null) { recipes = Map.copyOf(s.recipes); at = s.at; }
            }
        } catch (Exception e) { Terminal.LOG.warn("Recipes load: {}", e.toString()); }
        if (System.currentTimeMillis() - at < REFRESH && !recipes.isEmpty()) return;
        Thread t = new Thread(RecipeStore::download, "sbterminal-recipes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void download() {
        running = true;
        try {
            while (Feed.snapshot.products().isEmpty()) Thread.sleep(2000);
            List<String> ids = new ArrayList<>();
            for (String id : Feed.snapshot.products().keySet()) if (!id.startsWith("ENCHANTMENT_") && !id.startsWith("ESSENCE_")) ids.add(id);
            total = ids.size();
            Map<String, Recipes.Recipe> got = new HashMap<>();
            for (String id : ids) {
                try {
                    Recipes.Recipe r = Recipes.parse(id, Http.get(String.format(Recipes.URL, id.replace(':', '-'))));
                    if (r != null) got.put(id, r);
                } catch (Exception ignored) { } // no file for this item = no recipe
                done++;
                if (done % 50 == 0) recipes = Map.copyOf(got);
                Thread.sleep(150);
            }
            recipes = Map.copyOf(got);
            Saved s = new Saved();
            s.at = System.currentTimeMillis();
            s.recipes = got;
            Files.writeString(file, GSON.toJson(s));
        } catch (Throwable t) {
            Terminal.LOG.warn("Recipes: {}", t.toString());
        } finally {
            running = false;
        }
    }
}
