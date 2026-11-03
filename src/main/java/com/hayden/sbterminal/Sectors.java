package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Groups Bazaar items into sectors (Hypixel's Bazaar tabs, with enchantments, essence and
 * gemstones split out), and builds a flow-weighted index move for each. Pure logic.
 */
public final class Sectors {
    public static final String[] ALL = {"Farming", "Mining", "Gemstones", "Combat", "Woods & Fishes", "Enchantments", "Essence", "Oddities"};

    private static final Set<String> GEMS = Set.of("RUBY", "AMBER", "SAPPHIRE", "JADE", "AMETHYST", "TOPAZ", "JASPER", "OPAL", "ONYX", "CITRINE", "AQUAMARINE", "PERIDOT");
    private static final Set<String> COMBAT = Set.of("FLESH", "BONE", "STRING", "SPIDER", "SULPHUR", "PEARL", "GHAST", "SLIME", "BLAZE", "MAGMA",
            "REVENANT", "TARANTULA", "WOLF", "NULL", "EYE", "GUNPOWDER", "ECTOPLASM", "WEB", "SILK", "FANG", "TOOTH", "ENDER");
    private static final Set<String> WOODS = Set.of("LOG", "WOOD", "OAK", "BIRCH", "SPRUCE", "ACACIA", "JUNGLE", "FISH", "SALMON", "PUFFERFISH",
            "PRISMARINE", "SPONGE", "INK", "LILY", "CLAY", "SHARK", "SHRIMP", "CLOWNFISH", "MANGROVE");
    private static final Set<String> FARMING = Set.of("WHEAT", "SEEDS", "CARROT", "POTATO", "PUMPKIN", "MELON", "MUSHROOM", "COCOA", "CACTUS",
            "SUGAR", "CANE", "STALK", "WART", "HAY", "LEATHER", "BEEF", "PORK", "PORKCHOP", "CHICKEN", "MUTTON", "RABBIT", "FEATHER", "EGG",
            "CROPIE", "SQUASH", "FERMENTO", "COMPOST", "MYCELIUM", "BEANS", "CARROTS");
    private static final Set<String> MINING = Set.of("COAL", "IRON", "GOLD", "DIAMOND", "LAPIS", "EMERALD", "REDSTONE", "QUARTZ", "OBSIDIAN",
            "GLOWSTONE", "GRAVEL", "FLINT", "ICE", "SNOW", "SAND", "NETHERRACK", "MITHRIL", "TITANIUM", "COBBLESTONE", "STONE", "STARFALL",
            "UMBER", "TUNGSTEN", "GLACITE", "ENDSTONE", "ORE", "INGOT", "TREASURITE", "SULPHUR_ORE", "GEMSTONE");

    private Sectors() {}

    public static String of(String id) {
        if (id.startsWith("ENCHANTMENT_")) return "Enchantments";
        if (id.startsWith("ESSENCE_")) return "Essence";
        String[] t = id.split("_");
        for (String s : t) if (GEMS.contains(s)) return "Gemstones";
        for (String s : t) if (COMBAT.contains(s)) return "Combat";
        for (String s : t) if (WOODS.contains(s)) return "Woods & Fishes";
        for (String s : t) if (FARMING.contains(s)) return "Farming";
        for (String s : t) if (MINING.contains(s)) return "Mining";
        return "Oddities";
    }

    public record Stat(String name, int items, int up, int down, double chg24, double chg1h, double flow) {}

    /** Items under minWeekVolume are left out so dead items don't move the index. */
    public static List<Stat> stats(Collection<Market.Product> all, ToDoubleFunction<String> chg24, ToDoubleFunction<String> chg1h, double minWeekVolume) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // items, up, down, w24, sum24, w1, sum1, flow
        for (String s : ALL) acc.put(s, new double[8]);
        for (Market.Product p : all) {
            if (p.weekVolume() < minWeekVolume) continue;
            double[] a = acc.get(of(p.id()));
            double w = Math.max(1, p.coinFlowDay());
            a[0]++;
            a[7] += p.coinFlowDay();
            double c = chg24.applyAsDouble(p.id());
            if (Double.isFinite(c)) {
                if (c > 0) a[1]++; else if (c < 0) a[2]++;
                a[3] += w;
                a[4] += w * c;
            }
            double h = chg1h.applyAsDouble(p.id());
            if (Double.isFinite(h)) { a[5] += w; a[6] += w * h; }
        }
        List<Stat> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            out.add(new Stat(e.getKey(), (int) a[0], (int) a[1], (int) a[2], a[3] > 0 ? a[4] / a[3] : Double.NaN, a[5] > 0 ? a[6] / a[5] : Double.NaN, a[7]));
        }
        return out;
    }
}
