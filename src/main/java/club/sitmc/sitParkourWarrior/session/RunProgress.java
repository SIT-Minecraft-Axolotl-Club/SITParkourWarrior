package club.sitmc.sitParkourWarrior.session;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-player full-course run state. Supports pausable cumulative timing
 * (for disconnect/reconnect) and in-run medal tracking for both COUNTUP
 * (simple count) and COUNTDOWN (categorized: stone/bronze/silver/gold).
 */
public class RunProgress {

    private long elapsedMs;
    private long segmentStart;
    private boolean running;

    // Last visited FORK (persists across handoffs, used by fork-return item)
    private String lastForkMapId;
    private String lastForkDepId;
    private org.bukkit.Location lastForkPoint;

    // COUNTUP medals
    private int medals;

    // COUNTDOWN categorized medals
    private int stoneCount;
    private int bronzeCount;
    private int silverCount;
    private int goldCount;

    // Map tracking the medal type awarded per level (key = "mapId:deploymentId" → medalType)
    // This replaces the old claimedLevels set with type-aware tracking.
    private final java.util.Map<String, String> claimedLevels = new LinkedHashMap<>();

    public RunProgress() {
        this.segmentStart = System.currentTimeMillis();
        this.elapsedMs = 0;
        this.running = true;
        this.medals = 0;
        this.stoneCount = 0;
        this.bronzeCount = 0;
        this.silverCount = 0;
        this.goldCount = 0;
    }

    // ---- timing ----

    public long getElapsedMs() {
        if (running) {
            return elapsedMs + Math.max(0, System.currentTimeMillis() - segmentStart);
        }
        return elapsedMs;
    }

    public boolean isRunning() {
        return running;
    }

    public void pause() {
        if (running) {
            elapsedMs += Math.max(0, System.currentTimeMillis() - segmentStart);
            running = false;
        }
    }

    public void resume() {
        if (!running) {
            segmentStart = System.currentTimeMillis();
            running = true;
        }
    }

    // ---- COUNTUP medals ----

    public int getMedals() {
        return medals;
    }

    /** Award a COUNTUP medal. Returns true if newly claimed. */
    public boolean claimLevel(String mapId, String deploymentId) {
        String key = mapId + ":" + deploymentId;
        if (!claimedLevels.containsKey(key)) {
            claimedLevels.put(key, "countup");
            medals++;
            return true;
        }
        return false;
    }

    public void addEndTierMedals(int bonus) {
        medals += bonus;
    }

    // ---- Last visited FORK ----

    public void setLastFork(String mapId, String depId, org.bukkit.Location point) {
        this.lastForkMapId = mapId;
        this.lastForkDepId = depId;
        this.lastForkPoint = point != null ? point.clone() : null;
    }

    public String getLastForkMapId()   { return lastForkMapId; }
    public String getLastForkDepId()   { return lastForkDepId; }
    public org.bukkit.Location getLastForkPoint() { return lastForkPoint; }

    // ---- COUNTDOWN categorized medals ----

    /**
     * Award a COUNTDOWN medal of a specific type. Returns the medal type if
     * newly claimed, or null if this level was already claimed.
     */
    public String claimLevelByType(String mapId, String deploymentId, String medalType) {
        String key = mapId + ":" + deploymentId;
        if (!claimedLevels.containsKey(key)) {
            claimedLevels.put(key, medalType);
            switch (medalType) {
                case "stone":  stoneCount++;  break;
                case "bronze": bronzeCount++; break;
                case "silver": silverCount++; break;
                case "gold":   goldCount++;   break;
            }
            return medalType;
        }
        return null;
    }

    public int getStoneCount()  { return stoneCount; }
    public int getBronzeCount() { return bronzeCount; }
    public int getSilverCount() { return silverCount; }
    public int getGoldCount()   { return goldCount; }

    // ---- persistence helpers ----

    public java.util.Map<String, String> getClaimedLevelsWithTypes() {
        return claimedLevels;
    }

    /**
     * Restore state from a saved run.
     * For COUNTUP: claimedLevels values are "countup".
     * For COUNTDOWN: claimedLevels values are medal types (stone/bronze/silver/gold).
     *
     * @param running if true, the timer will continue from where it left off;
     *                if false, only elapsed time and medals are restored but
     *                the timer is NOT started (player hasn't begun global timing)
     */
    public void restoreFull(long elapsedMs, int medals, int stone, int bronze, int silver, int gold,
                            java.util.Map<String, String> claimed, boolean running) {
        this.elapsedMs = elapsedMs;
        this.medals = medals;
        this.stoneCount = stone;
        this.bronzeCount = bronze;
        this.silverCount = silver;
        this.goldCount = gold;
        this.running = false;
        if (running) {
            this.segmentStart = System.currentTimeMillis();
            this.running = true;
        }
        this.claimedLevels.clear();
        if (claimed != null) {
            this.claimedLevels.putAll(claimed);
        }
    }

    /** Backward-compat restore for COUNTUP (old format). Always starts timer — old format
     * only existed when the player had an active RunProgress. */
    public void restore(long elapsedMs, int medals, java.util.List<String> claimed) {
        java.util.Map<String, String> map = new LinkedHashMap<>();
        if (claimed != null) {
            for (String c : claimed) map.put(c, "countup");
        }
        restoreFull(elapsedMs, medals, 0, 0, 0, 0, map, true);
    }

    /** Set elapsed ms directly (for countdown timer restore without overwriting medals). */
    public void setElapsedMs(long ms) {
        this.elapsedMs = ms;
    }
}
