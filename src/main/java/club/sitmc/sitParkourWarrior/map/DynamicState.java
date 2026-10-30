package club.sitmc.sitParkourWarrior.map;

/**
 * A single state (schematic frame) in a map's dynamic sequence.
 * The list order in {@link DynamicData} determines playback order; {@link #id}
 * is a stable identity used only for external references (legacy /sitpkw save
 * &lt;seqId&gt;, state paper PDC tags) and does not affect ordering.
 */
public class DynamicState {
    public static final int DEFAULT_INTERVAL_TICKS = 20;
    public static final int MIN_INTERVAL_TICKS = 1;
    public static final int MAX_INTERVAL_TICKS = 1200;

    private final int id;
    private String file;
    private String name;
    private int interval;

    public DynamicState(int id, String file, String name, int interval) {
        this.id = id;
        this.file = file;
        this.name = name;
        this.interval = clampInterval(interval);
    }

    public int getId() {
        return id;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    /** Raw display name, may be null/blank if never set by the player. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = clampInterval(interval);
    }

    /** Resolves the display name, falling back to "状态<position>" (1-based) when unset. */
    public String getDisplayName(int oneBasedPosition) {
        return (name != null && !name.isBlank()) ? name : "状态" + oneBasedPosition;
    }

    public static int clampInterval(int ticks) {
        if (ticks < MIN_INTERVAL_TICKS) return MIN_INTERVAL_TICKS;
        if (ticks > MAX_INTERVAL_TICKS) return MAX_INTERVAL_TICKS;
        return ticks;
    }
}
