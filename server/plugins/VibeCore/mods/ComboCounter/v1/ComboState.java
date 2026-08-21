package vibemod.combocounter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared combo tracking state: current combo count and last-hit clock tick per player. */
public final class ComboState {

    private final Map<UUID, Integer> combos = new HashMap<>();
    private final Map<UUID, Long> lastHitClock = new HashMap<>();
    private long clock = 0L;

    public void tickClock() {
        clock++;
    }

    public long clock() {
        return clock;
    }

    public int getCombo(UUID id) {
        Integer v = combos.get(id);
        return v == null ? 0 : v;
    }

    public int increment(UUID id, int maxCombo) {
        int cur = getCombo(id) + 1;
        if (maxCombo < 1) {
            maxCombo = 1;
        }
        if (cur > maxCombo) {
            cur = maxCombo;
        }
        combos.put(id, cur);
        lastHitClock.put(id, clock);
        return cur;
    }

    public void reset(UUID id) {
        combos.put(id, 0);
    }

    public Long lastHit(UUID id) {
        return lastHitClock.get(id);
    }

    public Map<UUID, Integer> all() {
        return combos;
    }
}
