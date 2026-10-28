package club.sitmc.sitParkourWarrior.map;

import java.util.ArrayList;
import java.util.List;

/**
 * A map's dynamic (multi-state) schematic sequence.
 * Playback order is the order of {@link #states}; each state's {@code id} is a
 * stable identity independent of position (see {@link DynamicState}).
 */
public class DynamicData {
    private boolean enabled = true;
    private final List<DynamicState> states = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Ordered list of states; list order is playback order. */
    public List<DynamicState> getStates() {
        return states;
    }

    public DynamicState findById(int id) {
        for (DynamicState state : states) {
            if (state.getId() == id) {
                return state;
            }
        }
        return null;
    }

    /** Next unused stable id, for creating brand-new states without an explicit id. */
    public int nextId() {
        int max = 0;
        for (DynamicState state : states) {
            if (state.getId() > max) {
                max = state.getId();
            }
        }
        return max + 1;
    }
}
