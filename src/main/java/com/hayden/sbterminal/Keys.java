package com.hayden.sbterminal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Terminal hotkeys. A binding is a GLFW key code, plus 1000 for Ctrl or 2000 for Alt.
 * Only F-keys and Ctrl/Alt combinations can be bound: plain letters type into the command line.
 * Pure logic.
 */
public final class Keys {
    /** Action -> default binding, in the order they are listed. */
    public static final Map<String, Integer> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("HELP", 290);
        DEFAULTS.put("MON", 291);
        DEFAULTS.put("MOV", 292);
        DEFAULTS.put("SCR", 293);
        DEFAULTS.put("BUY", 294);
        DEFAULTS.put("SELL", 295);
        DEFAULTS.put("PORT", 296);
        DEFAULTS.put("FLIP", 297);
        DEFAULTS.put("LIST", 298);
        DEFAULTS.put("ALGO", 299);
        DEFAULTS.put("NEWS", -1);
        DEFAULTS.put("ML", -1);
        DEFAULTS.put("SEAS", -1);
    }

    private Keys() {}

    public static int code(int key, boolean ctrl, boolean alt) {
        return key + (ctrl ? 1000 : 0) + (alt ? 2000 : 0);
    }

    /** The binding for an action: yours, else the default. */
    public static int of(Map<String, Integer> user, String action) {
        Integer u = user == null ? null : user.get(action);
        return u != null ? u : DEFAULTS.getOrDefault(action, -1);
    }

    /** The action a key press triggers, or null. Your bindings win over defaults you moved away from. */
    public static String resolve(Map<String, Integer> user, int code) {
        for (String a : DEFAULTS.keySet()) if (of(user, a) == code) return a;
        return null;
    }

    public static boolean bindable(int code) {
        int key = code % 1000;
        boolean mod = code >= 1000;
        if (key >= 290 && key <= 314) return true; // F1..F25
        return mod && (key >= 65 && key <= 90 || key >= 48 && key <= 57) && !(code == 1086); // Ctrl/Alt + letter or digit, but not Ctrl+V (paste)
    }

    public static String name(int code) {
        if (code < 0) return "-";
        int key = code % 1000;
        String mod = code >= 2000 ? "Alt+" : code >= 1000 ? "Ctrl+" : "";
        if (key >= 290 && key <= 314) return mod + "F" + (key - 289);
        if (key >= 65 && key <= 90 || key >= 48 && key <= 57) return mod + (char) key;
        return mod + "key" + key;
    }

    /** "F6", "ctrl+g", "alt+1" -> code, or -1. */
    public static int parse(String s) {
        if (s == null) return -1;
        String t = s.trim().toUpperCase(Locale.US).replace(" ", "");
        int add = 0;
        if (t.startsWith("CTRL+")) { add = 1000; t = t.substring(5); }
        else if (t.startsWith("ALT+")) { add = 2000; t = t.substring(4); }
        int key;
        if (t.matches("F\\d{1,2}")) {
            int n = Integer.parseInt(t.substring(1));
            if (n < 1 || n > 25) return -1;
            key = 289 + n;
        } else if (t.length() == 1 && (Character.isLetterOrDigit(t.charAt(0)) && t.charAt(0) < 128)) {
            key = t.charAt(0);
        } else return -1;
        int code = key + add;
        return bindable(code) ? code : -1;
    }
}
