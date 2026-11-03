package com.hayden.sbterminal;

import java.util.HashMap;
import java.util.Map;

/** Ticker flash: a value that just went up flashes green, down flashes red, for FLASH_MS. */
final class Flash {
    static final long FLASH_MS = 900;
    private record State(double value, long changedAt, int dir) {}
    private static final Map<String, State> STATES = new HashMap<>();

    private Flash() {}

    /** Background color for this cell right now (0 = none). */
    static int bg(String key, double value, long now) {
        State s = STATES.get(key);
        if (s == null || !Double.isFinite(value)) {
            STATES.put(key, new State(value, 0, 0));
            return 0;
        }
        if (Math.abs(value - s.value()) > 1e-9) {
            s = new State(value, now, value > s.value() ? 1 : -1);
            STATES.put(key, s);
        }
        if (s.dir() == 0 || now - s.changedAt() > FLASH_MS) return 0;
        int alpha = (int) (0x90 * (1 - (now - s.changedAt()) / (double) FLASH_MS));
        return (alpha << 24) | (s.dir() > 0 ? 0x1E8C2A : 0xB01E1E);
    }
}
