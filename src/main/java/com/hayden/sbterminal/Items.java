package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the item you mean from what you type: an id (ENCHANTED_COAL), a name (enchanted coal),
 * part of a name (radar), or initials (ec = Enchanted Coal). Best match first: exact id, exact
 * name, name starts with it, initials, then contains; ties go to the most traded item.
 */
public final class Items {
    private Items() {}

    public record Match(String id, int rank) {}

    public static List<String> find(String query, Collection<String> ids, Map<String, String> names,
                                    java.util.function.ToDoubleFunction<String> volume, int max) {
        String q = norm(query);
        if (q.isEmpty()) return List.of();
        String qId = query.trim().toUpperCase(Locale.US).replace(' ', '_');
        List<Match> out = new ArrayList<>();
        for (String id : ids) {
            String name = norm(names.getOrDefault(id, Market.prettyId(id)));
            int rank;
            if (id.equals(qId)) rank = 0;
            else if (name.equals(q)) rank = 1;
            else if (name.startsWith(q)) rank = 2;
            else if (initials(name).equals(q.replace(" ", ""))) rank = 3;
            else if (wordsStartWith(name, q)) rank = 4;
            else if (name.contains(q)) rank = 5;
            else continue;
            out.add(new Match(id, rank));
        }
        out.sort(Comparator.comparingInt(Match::rank)
                .thenComparing(m -> -volume.applyAsDouble(m.id()))
                .thenComparing(Match::id));
        List<String> ids2 = new ArrayList<>();
        for (int i = 0; i < Math.min(max, out.size()); i++) ids2.add(out.get(i).id());
        return ids2;
    }

    static String norm(String s) {
        return Market.strip(s).toLowerCase(Locale.US).replace('_', ' ').replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.split(" ")) if (!w.isEmpty()) sb.append(w.charAt(0));
        return sb.toString();
    }

    /** Every word of the query starts a word of the name, in order ("ench coal"). */
    static boolean wordsStartWith(String name, String q) {
        String[] nw = name.split(" "), qw = q.split(" ");
        int j = 0;
        for (String w : nw) if (j < qw.length && w.startsWith(qw[j])) j++;
        return j == qw.length && qw.length > 1;
    }
}
