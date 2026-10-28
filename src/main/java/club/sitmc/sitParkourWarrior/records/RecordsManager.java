package club.sitmc.sitParkourWarrior.records;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages records.yml — active runs (for disconnect/reconnect) and
 * per-world leaderboards (standard / advance / expect / countdown).
 * Uses in-memory cache; all reads hit the cache, writes update both
 * cache and disk synchronously.
 */
public class RecordsManager {

    private final File file;
    private YamlConfiguration cache;
    private Runnable onLeaderboardChanged;

    public RecordsManager(File dataFolder) {
        this.file = new File(dataFolder, "records.yml");
        this.cache = loadFromDisk();
        migrateLegacyCountupRecords();
        rebuildAllCountupLeaderboards();
    }

    /** Reload cache from disk (called on /sitpkw reload). */
    public void reload() {
        this.cache = loadFromDisk();
        migrateLegacyCountupRecords();
        rebuildAllCountupLeaderboards();
    }

    /** Migrate the previous one-record-per-player format into the raw result store. */
    private void migrateLegacyCountupRecords() {
        ConfigurationSection worlds = cache.getConfigurationSection("worlds");
        if (worlds == null) return;
        boolean changed = false;
        for (String worldName : worlds.getKeys(false)) {
            ConfigurationSection countup = cache.getConfigurationSection("worlds." + worldName + ".countup");
            if (countup != null) {
                for (String key : new ArrayList<>(countup.getKeys(false))) {
                    String legacyBase = "worlds." + worldName + ".countup." + key;
                    if (!cache.contains(legacyBase + ".time_ms")) continue;
                    try {
                        UUID.fromString(key);
                        int medals = cache.getInt(legacyBase + ".medals");
                        String target = legacyBase + "." + medals;
                        long oldTime = cache.getLong(legacyBase + ".time_ms", Long.MAX_VALUE);
                        long targetTime = cache.getLong(target + ".time_ms", Long.MAX_VALUE);
                        if (oldTime < targetTime) {
                            cache.set(target + ".name", cache.getString(legacyBase + ".name"));
                            cache.set(target + ".time_ms", oldTime);
                            cache.set(target + ".medals", medals);
                        }
                        cache.set(legacyBase, null);
                        changed = true;
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed legacy keys instead of interrupting plugin startup.
                    }
                }
            }

            // Very old files may only have the derived standard view. Preserve it as raw data.
            ConfigurationSection standard = cache.getConfigurationSection("worlds." + worldName + ".standard");
            if (standard == null) continue;
            for (String key : standard.getKeys(false)) {
                String raw = "worlds." + worldName + ".countup." + key;
                int medals = standard.getInt(key + ".medals");
                String target = raw + "." + medals;
                if (cache.contains(target + ".time_ms")) continue;
                cache.set(target + ".name", standard.getString(key + ".name"));
                cache.set(target + ".time_ms", standard.getLong(key + ".time_ms"));
                cache.set(target + ".medals", medals);
                changed = true;
            }
        }
        if (changed) persist();
    }

    private void rebuildAllCountupLeaderboards() {
        ConfigurationSection worlds = cache.getConfigurationSection("worlds");
        if (worlds == null) return;
        for (String worldName : worlds.getKeys(false)) {
            rebuildCountupLeaderboards(worldName, false);
        }
    }

    public void setOnLeaderboardChanged(Runnable callback) {
        this.onLeaderboardChanged = callback;
    }

    // ---------------------------------------------------------------
    // Active-run persistence (disconnect / reconnect)
    // ---------------------------------------------------------------

    public void saveActiveRunFull(String worldName, UUID playerId, String playerName,
                                  long elapsedMs, int medals, int stone, int bronze, int silver, int gold,
                                  Map<String, String> claimedLevels,
                                  String locWorldName, double locX, double locY, double locZ,
                                  float locYaw, float locPitch) {
        saveActiveRunFull(worldName, playerId, playerName,
                elapsedMs, medals, stone, bronze, silver, gold, claimedLevels,
                locWorldName, locX, locY, locZ, locYaw, locPitch,
                null, null, 0, false, false, false, null, 0,
                null, 0, 0, 0, 0, 0, null, null,
                true); // old callers always had a RunProgress
    }

    /**
     * Save active run with full session snapshot for disconnect/reconnect resume.
     * Session fields are optional — pass null/empty/defaults to skip session save.
     */
    public void saveActiveRunFull(String worldName, UUID playerId, String playerName,
                                  long elapsedMs, int medals, int stone, int bronze, int silver, int gold,
                                  Map<String, String> claimedLevels,
                                  String locWorldName, double locX, double locY, double locZ,
                                  float locYaw, float locPitch,
                                  String sessionMapId, String sessionDeploymentId,
                                  long sessionElapsedMs, boolean sessionStarted,
                                  boolean sessionCompleted, boolean sessionInsideRegion,
                                  String sessionState, double sessionDeathLineY,
                                  String checkpointWorld, double checkpointX, double checkpointY, double checkpointZ,
                                  float checkpointYaw, float checkpointPitch,
                                  String visitedForkPointsSerial, String initialForkFallbackSerial,
                                  boolean hasRunProgress) {
        String base = "worlds." + worldName + ".active." + playerId.toString();
        cache.set(base + ".name", playerName);
        cache.set(base + ".has_run", hasRunProgress);
        cache.set(base + ".elapsed_ms", elapsedMs);
        cache.set(base + ".medals", medals);
        cache.set(base + ".stone", stone);
        cache.set(base + ".bronze", bronze);
        cache.set(base + ".silver", silver);
        cache.set(base + ".gold", gold);
        // 先清除旧的 claimed_levels section，再写入新的，
        // 避免空 claimedLevels 时旧数据残留（与 Bug1 同模式的隐患）。
        cache.set(base + ".claimed_levels", null);
        if (!claimedLevels.isEmpty()) {
            cache.createSection(base + ".claimed_levels", claimedLevels);
        }
        // 玩家离开时的精确坐标（用于恢复时传送回离开位置）
        if (locWorldName != null && !locWorldName.isEmpty()) {
            cache.set(base + ".loc_world", locWorldName);
            cache.set(base + ".loc_x", locX);
            cache.set(base + ".loc_y", locY);
            cache.set(base + ".loc_z", locZ);
            cache.set(base + ".loc_yaw", (double) locYaw);
            cache.set(base + ".loc_pitch", (double) locPitch);
        } else {
            // 清除旧的位置数据（如果之前保存过但这次没有有效位置）
            cache.set(base + ".loc_world", null);
        }

        // ---- Per-level session snapshot ----
        String sessBase = base + ".session";
        if (sessionMapId != null && sessionDeploymentId != null) {
            cache.set(sessBase + ".map_id", sessionMapId);
            cache.set(sessBase + ".deployment_id", sessionDeploymentId);
            cache.set(sessBase + ".elapsed_ms", sessionElapsedMs);
            cache.set(sessBase + ".started", sessionStarted);
            cache.set(sessBase + ".completed", sessionCompleted);
            cache.set(sessBase + ".inside_region", sessionInsideRegion);
            if (sessionState != null) {
                cache.set(sessBase + ".state", sessionState);
            }
            cache.set(sessBase + ".death_line_y", sessionDeathLineY);
            if (checkpointWorld != null && !checkpointWorld.isEmpty()) {
                cache.set(sessBase + ".cp_world", checkpointWorld);
                cache.set(sessBase + ".cp_x", checkpointX);
                cache.set(sessBase + ".cp_y", checkpointY);
                cache.set(sessBase + ".cp_z", checkpointZ);
                cache.set(sessBase + ".cp_yaw", (double) checkpointYaw);
                cache.set(sessBase + ".cp_pitch", (double) checkpointPitch);
            } else {
                cache.set(sessBase + ".cp_world", null);
            }
            if (visitedForkPointsSerial != null && !visitedForkPointsSerial.isEmpty()) {
                cache.set(sessBase + ".visited_fork_points", visitedForkPointsSerial);
            } else {
                cache.set(sessBase + ".visited_fork_points", null);
            }
            if (initialForkFallbackSerial != null && !initialForkFallbackSerial.isEmpty()) {
                cache.set(sessBase + ".initial_fork_fallback", initialForkFallbackSerial);
            } else {
                cache.set(sessBase + ".initial_fork_fallback", null);
            }
        } else {
            // Clear any stale session data
            cache.set(sessBase, null);
        }

        persist();
    }

    @SuppressWarnings("unchecked")
    public SavedRunData loadAndClearActiveRun(String worldName, UUID playerId) {
        String base = "worlds." + worldName + ".active." + playerId.toString();
        if (!cache.contains(base)) return null;

        String name = cache.getString(base + ".name");
        boolean hasRunProgress = cache.getBoolean(base + ".has_run", true); // default true for old-format data
        long elapsedMs = cache.getLong(base + ".elapsed_ms");
        int medals = cache.getInt(base + ".medals");
        int stone = cache.getInt(base + ".stone");
        int bronze = cache.getInt(base + ".bronze");
        int silver = cache.getInt(base + ".silver");
        int gold = cache.getInt(base + ".gold");

        Map<String, String> claimed = new LinkedHashMap<>();
        ConfigurationSection cs = cache.getConfigurationSection(base + ".claimed_levels");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                claimed.put(key, cs.getString(key, "countup"));
            }
        } else {
            List<String> oldList = cache.getStringList(base + ".claimed_levels");
            for (String key : oldList) claimed.put(key, "countup");
        }

        // 读取离开坐标（可能为 null，表示旧版本数据没有位置信息）
        String locWorldName = cache.getString(base + ".loc_world");
        double locX = 0, locY = 0, locZ = 0;
        float locYaw = 0, locPitch = 0;
        if (locWorldName != null && !locWorldName.isEmpty()) {
            locX = cache.getDouble(base + ".loc_x");
            locY = cache.getDouble(base + ".loc_y");
            locZ = cache.getDouble(base + ".loc_z");
            locYaw = (float) cache.getDouble(base + ".loc_yaw");
            locPitch = (float) cache.getDouble(base + ".loc_pitch");
        }

        // 读取关内状态快照（旧版本数据无此节点 → 所有字段回退到 null/default）
        String sessBase = base + ".session";
        String sessionMapId = cache.getString(sessBase + ".map_id");
        String sessionDeploymentId = cache.getString(sessBase + ".deployment_id");
        long sessionElapsedMs = cache.getLong(sessBase + ".elapsed_ms");
        boolean sessionStarted = cache.getBoolean(sessBase + ".started");
        boolean sessionCompleted = cache.getBoolean(sessBase + ".completed");
        boolean sessionInsideRegion = cache.getBoolean(sessBase + ".inside_region");
        String sessionState = cache.getString(sessBase + ".state");
        double sessionDeathLineY = cache.getDouble(sessBase + ".death_line_y");
        String checkpointWorld = cache.getString(sessBase + ".cp_world");
        double checkpointX = 0, checkpointY = 0, checkpointZ = 0;
        float checkpointYaw = 0, checkpointPitch = 0;
        if (checkpointWorld != null && !checkpointWorld.isEmpty()) {
            checkpointX = cache.getDouble(sessBase + ".cp_x");
            checkpointY = cache.getDouble(sessBase + ".cp_y");
            checkpointZ = cache.getDouble(sessBase + ".cp_z");
            checkpointYaw = (float) cache.getDouble(sessBase + ".cp_yaw");
            checkpointPitch = (float) cache.getDouble(sessBase + ".cp_pitch");
        }
        String visitedForkPointsSerial = cache.getString(sessBase + ".visited_fork_points");
        String initialForkFallbackSerial = cache.getString(sessBase + ".initial_fork_fallback");

        cache.set(base, null);
        persist();
        return new SavedRunData(name, elapsedMs, medals, stone, bronze, silver, gold, claimed,
                locWorldName, locX, locY, locZ, locYaw, locPitch,
                sessionMapId, sessionDeploymentId,
                sessionElapsedMs, sessionStarted,
                sessionCompleted, sessionInsideRegion,
                sessionState, sessionDeathLineY,
                checkpointWorld, checkpointX, checkpointY, checkpointZ,
                checkpointYaw, checkpointPitch,
                visitedForkPointsSerial, initialForkFallbackSerial,
                hasRunProgress);
    }

    public void clearActiveRun(String worldName, UUID playerId) {
        cache.set("worlds." + worldName + ".active." + playerId, null);
        persist();
    }

    // ---------------------------------------------------------------
    // Location serialization helpers (for session snapshot save/load)
    // ---------------------------------------------------------------

    /**
     * Serialize a Bukkit Location to "world,x,y,z,yaw,pitch".
     * Returns null if the location or its world is null.
     */
    public static String serializeLocation(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getName() + ","
                + loc.getX() + "," + loc.getY() + "," + loc.getZ() + ","
                + loc.getYaw() + "," + loc.getPitch();
    }

    /**
     * Deserialize a "world,x,y,z,yaw,pitch" string back to a Bukkit Location.
     * Returns null if the string is null/empty or the world is not loaded.
     */
    public static org.bukkit.Location deserializeLocation(String serialized) {
        if (serialized == null || serialized.isEmpty()) return null;
        String[] parts = serialized.split(",", 6);
        if (parts.length < 6) return null;
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new org.bukkit.Location(world,
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Serialize a list of Locations to a semicolon-delimited string.
     */
    public static String serializeLocationList(java.util.List<org.bukkit.Location> list) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String s = serializeLocation(list.get(i));
            if (s == null) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(s);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Deserialize a semicolon-delimited string back to a list of Locations.
     */
    public static java.util.List<org.bukkit.Location> deserializeLocationList(String serialized) {
        java.util.List<org.bukkit.Location> list = new java.util.ArrayList<>();
        if (serialized == null || serialized.isEmpty()) return list;
        for (String part : serialized.split(";")) {
            org.bukkit.Location loc = deserializeLocation(part.trim());
            if (loc != null) list.add(loc);
        }
        return list;
    }

    // ---------------------------------------------------------------
    // COUNTUP leaderboards (best time)
    // ---------------------------------------------------------------

    /** Save the player's best result for this exact medal count, then rebuild all views. */
    public boolean saveCountupRecord(String worldName, UUID playerId,
                                     String playerName, long timeMs, int medals) {
        if (medals < 0 || timeMs < 0) return false;
        String base = "worlds." + worldName + ".countup." + playerId.toString() + "." + medals;
        long existing = cache.getLong(base + ".time_ms", Long.MAX_VALUE);
        if (timeMs < existing) {
            cache.set(base + ".name", playerName);
            cache.set(base + ".time_ms", timeMs);
            cache.set(base + ".medals", medals);
            rebuildCountupLeaderboards(worldName, false);
            return true;
        }
        if (!playerName.equals(cache.getString(base + ".name"))) {
            cache.set(base + ".name", playerName);
            rebuildCountupLeaderboards(worldName, false);
        }
        return false;
    }

    /** Compatibility entry point for older callers; new code should save the raw result. */
    public boolean saveRecord(String worldName, String tier, UUID playerId,
                              String playerName, long timeMs, int medals) {
        return saveCountupRecord(worldName, playerId, playerName, timeMs, medals);
    }

    public int getCountupThreshold(String worldName, String tier) {
        int fallback = "standard".equals(tier) ? 0 : ("advance".equals(tier) ? 16 : 21);
        return cache.getInt("pkw-world-thresholds." + worldName + "." + tier, fallback);
    }

    /** Set one threshold and immediately rebuild only this world's three leaderboards. */
    public boolean setCountupThreshold(String worldName, String tier, int threshold) {
        if (!"standard".equals(tier) && !"advance".equals(tier) && !"expect".equals(tier)) return false;
        if (threshold < 0) return false;
        cache.set("pkw-world-thresholds." + worldName + "." + tier, threshold);
        rebuildCountupLeaderboards(worldName, true);
        return true;
    }

    private void rebuildCountupLeaderboards(String worldName, boolean notify) {
        for (String tier : new String[]{"standard", "advance", "expect"}) {
            cache.set("worlds." + worldName + "." + tier, null);
        }
        ConfigurationSection raw = cache.getConfigurationSection("worlds." + worldName + ".countup");
        if (raw != null) {
            for (String playerKey : raw.getKeys(false)) {
                try {
                    UUID.fromString(playerKey);
                    ConfigurationSection playerRecords = raw.getConfigurationSection(playerKey);
                    if (playerRecords == null) continue;
                    for (String medalKey : playerRecords.getKeys(false)) {
                        int medals;
                        try { medals = Integer.parseInt(medalKey); }
                        catch (NumberFormatException ignored) { continue; }
                        String name = playerRecords.getString(medalKey + ".name");
                        long time = playerRecords.getLong(medalKey + ".time_ms", Long.MAX_VALUE);
                        if (name == null || time == Long.MAX_VALUE) continue;
                        for (String tier : new String[]{"standard", "advance", "expect"}) {
                            if (medals >= getCountupThreshold(worldName, tier)) {
                                ConfigurationSection tierSection = cache.getConfigurationSection("worlds." + worldName + "." + tier);
                                long current = tierSection == null ? Long.MAX_VALUE
                                        : tierSection.getLong(playerKey + ".time_ms", Long.MAX_VALUE);
                                if (time < current) {
                                    String target = "worlds." + worldName + "." + tier + "." + playerKey;
                                    cache.set(target + ".name", name);
                                    cache.set(target + ".time_ms", time);
                                    cache.set(target + ".medals", medals);
                                }
                            }
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed player UUIDs in persisted data.
                }
            }
        }
        persist();
        if (notify && onLeaderboardChanged != null) {
            try { onLeaderboardChanged.run(); } catch (RuntimeException ignored) { }
        }
    }

    public List<RankEntry> getTop(String worldName, String tier, int limit) {
        ConfigurationSection section = cache.getConfigurationSection("worlds." + worldName + "." + tier);
        if (section == null) return Collections.emptyList();

        List<RankEntry> entries = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = section.getString(key + ".name");
                long timeMs = section.getLong(key + ".time_ms");
                int medals = section.getInt(key + ".medals");
                entries.add(new RankEntry(id, name, timeMs, medals));
            } catch (IllegalArgumentException ignored) {}
        }
        entries.sort(Comparator.comparingLong(e -> e.timeMs));
        if (entries.size() > limit) entries = entries.subList(0, limit);
        return entries;
    }

    // ---------------------------------------------------------------
    // COUNTDOWN leaderboard (best score, descending)
    // ---------------------------------------------------------------

    public boolean saveCountdownRecord(String worldName, UUID playerId,
                                        String playerName, int score,
                                        int stone, int bronze, int silver, int gold,
                                        long timeMs, String endTier) {
        String base = "worlds." + worldName + ".countdown." + playerId.toString();
        int existing = cache.getInt(base + ".score", Integer.MIN_VALUE);
        if (score > existing) {
            cache.set(base + ".name", playerName);
            cache.set(base + ".score", score);
            cache.set(base + ".stone", stone);
            cache.set(base + ".bronze", bronze);
            cache.set(base + ".silver", silver);
            cache.set(base + ".gold", gold);
            cache.set(base + ".time_ms", timeMs);
            // Bug1修复：始终显式设置 end_tier（含 null），
            // 避免上一趟 PB 的 end_tier 残留在 records.yml 中。
            cache.set(base + ".end_tier", endTier);
            persist();
            return true;
        }
        if (!playerName.equals(cache.getString(base + ".name"))) {
            cache.set(base + ".name", playerName);
            persist();
        }
        return false;
    }

    public List<CountdownRankEntry> getCountdownTop(String worldName, int limit) {
        ConfigurationSection section = cache.getConfigurationSection("worlds." + worldName + ".countdown");
        if (section == null) return Collections.emptyList();

        List<CountdownRankEntry> entries = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = section.getString(key + ".name");
                int score = section.getInt(key + ".score");
                int stone = section.getInt(key + ".stone");
                int bronze = section.getInt(key + ".bronze");
                int silver = section.getInt(key + ".silver");
                int gold = section.getInt(key + ".gold");
                long timeMs = section.getLong(key + ".time_ms");
                String endTier = section.getString(key + ".end_tier");
                entries.add(new CountdownRankEntry(id, name, score, stone, bronze, silver, gold, timeMs, endTier));
            } catch (IllegalArgumentException ignored) {}
        }
        entries.sort((a, b) -> Integer.compare(b.score, a.score));
        if (entries.size() > limit) entries = entries.subList(0, limit);
        return entries;
    }

    public String deleteRecord(String worldName, String tier, String uuidOrName) {
        String base = "worlds." + worldName + "." + tier;
        ConfigurationSection section = cache.getConfigurationSection(base);
        if (section == null) return null;

        if (section.contains(uuidOrName)) {
            String name = section.getString(uuidOrName + ".name");
            cache.set(base + "." + uuidOrName, null);
            persist();
            return name != null ? name : uuidOrName;
        }

        String lower = uuidOrName.toLowerCase();
        for (String key : section.getKeys(false)) {
            String storedName = section.getString(key + ".name");
            if (storedName != null && storedName.toLowerCase().equals(lower)) {
                cache.set(base + "." + key, null);
                persist();
                return storedName;
            }
        }
        return null;
    }

    /**
     * Deletes one raw count-up result for the given medal count and rebuilds
     * all three derived count-up leaderboards.
     */
    public String deleteCountupRecord(String worldName, int medals, String uuidOrName) {
        if (medals < 0 || uuidOrName == null || uuidOrName.isBlank()) return null;

        ConfigurationSection raw = cache.getConfigurationSection("worlds." + worldName + ".countup");
        if (raw == null) return null;

        String matchingPlayerKey = null;
        String matchingName = null;
        try {
            UUID requestedId = UUID.fromString(uuidOrName);
            if (raw.contains(requestedId.toString() + "." + medals + ".time_ms")) {
                matchingPlayerKey = requestedId.toString();
                matchingName = raw.getString(matchingPlayerKey + "." + medals + ".name");
            }
        } catch (IllegalArgumentException ignored) {
            // The argument may be a player name; resolve it below.
        }

        if (matchingPlayerKey == null) {
            String lowerName = uuidOrName.toLowerCase(java.util.Locale.ROOT);
            for (String playerKey : raw.getKeys(false)) {
                String storedName = raw.getString(playerKey + "." + medals + ".name");
                if (storedName != null && storedName.toLowerCase(java.util.Locale.ROOT).equals(lowerName)
                        && raw.contains(playerKey + "." + medals + ".time_ms")) {
                    matchingPlayerKey = playerKey;
                    matchingName = storedName;
                    break;
                }
            }
        }

        if (matchingPlayerKey == null) return null;
        cache.set("worlds." + worldName + ".countup." + matchingPlayerKey + "." + medals, null);
        rebuildCountupLeaderboards(worldName, true);
        return matchingName != null ? matchingName : uuidOrName;
    }

    /** Medal counts currently present in a world's raw count-up records. */
    public java.util.List<String> getCountupMedalCounts(String worldName) {
        java.util.Set<String> medals = new java.util.TreeSet<>((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (NumberFormatException ignored) { return a.compareTo(b); }
        });
        ConfigurationSection raw = cache.getConfigurationSection("worlds." + worldName + ".countup");
        if (raw == null) return new java.util.ArrayList<>();
        for (String playerKey : raw.getKeys(false)) {
            ConfigurationSection playerRecords = raw.getConfigurationSection(playerKey);
            if (playerRecords != null) medals.addAll(playerRecords.getKeys(false));
        }
        return new java.util.ArrayList<>(medals);
    }

    public java.util.List<String> getCountupPlayerNames(String worldName, int medals) {
        java.util.List<String> names = new java.util.ArrayList<>();
        ConfigurationSection raw = cache.getConfigurationSection("worlds." + worldName + ".countup");
        if (raw == null) return names;
        for (String playerKey : raw.getKeys(false)) {
            if (!raw.contains(playerKey + "." + medals + ".time_ms")) continue;
            String name = raw.getString(playerKey + "." + medals + ".name");
            if (name != null) names.add(name);
        }
        return names;
    }

    public java.util.Set<String> getRecordedWorldNames() {
        ConfigurationSection worlds = cache.getConfigurationSection("worlds");
        if (worlds == null) return java.util.Collections.emptySet();
        return worlds.getKeys(false);
    }

    public int[] countPlayerStats(String uuidOrName) {
        int[] counts = new int[3];
        ConfigurationSection worlds = cache.getConfigurationSection("worlds");
        if (worlds == null) return counts;

        String lower = uuidOrName.toLowerCase();
        for (String worldName : worlds.getKeys(false)) {
            for (int t = 0; t < 3; t++) {
                String tier = t == 0 ? "standard" : (t == 1 ? "advance" : "expect");
                ConfigurationSection tierSec = cache.getConfigurationSection("worlds." + worldName + "." + tier);
                if (tierSec == null) continue;
                if (tierSec.contains(uuidOrName)) { counts[t]++; continue; }
                boolean found = false;
                for (String key : tierSec.getKeys(false)) {
                    String stored = tierSec.getString(key + ".name");
                    if (stored != null && stored.toLowerCase().equals(lower)) {
                        found = true; break;
                    }
                }
                if (found) counts[t]++;
            }
        }
        return counts;
    }

    public java.util.List<String> getPlayerNames(String worldName, String tier) {
        java.util.List<String> names = new java.util.ArrayList<>();
        ConfigurationSection section = cache.getConfigurationSection("worlds." + worldName + "." + tier);
        if (section == null) return names;
        for (String key : section.getKeys(false)) {
            String name = section.getString(key + ".name");
            if (name != null) names.add(name);
        }
        return names;
    }

    // ---------------------------------------------------------------
    // File I/O
    // ---------------------------------------------------------------

    private YamlConfiguration loadFromDisk() {
        if (file.exists()) return YamlConfiguration.loadConfiguration(file);
        return new YamlConfiguration();
    }

    private void persist() {
        try { cache.save(file); }
        catch (IOException e) { System.err.println("[SITPKW] Failed to save records.yml: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------
    // Data types
    // ---------------------------------------------------------------

    public static class SavedRunData {
        public final String playerName;
        public final long elapsedMs;
        public final int medals;
        public final int stone, bronze, silver, gold;
        public final Map<String, String> claimedLevels;
        /** Player's exact location when leaving the PKW world (null if not saved). */
        public final String locWorldName;
        public final double locX, locY, locZ;
        public final float locYaw, locPitch;

        // ---- Per-level session snapshot (ParkourSession) ----
        /** The map ID of the current level, or null if no active session was saved. */
        public final String sessionMapId;
        /** The deployment ID of the current level. */
        public final String sessionDeploymentId;
        /** Accumulated per-level elapsed time in ms. */
        public final long sessionElapsedMs;
        /** Whether the per-level timer was actively running. */
        public final boolean sessionStarted;
        /** Whether this level had been completed. */
        public final boolean sessionCompleted;
        /** Whether the player was inside the deployment region. */
        public final boolean sessionInsideRegion;
        /** SessionState name: "RUNNING" or "AWAITING_HANDOFF". */
        public final String sessionState;
        /** Death-line Y coordinate for this level. */
        public final double sessionDeathLineY;
        /** Checkpoint location fields (null worldName → no checkpoint saved). */
        public final String checkpointWorld;
        public final double checkpointX, checkpointY, checkpointZ;
        public final float checkpointYaw, checkpointPitch;
        /** Visited fork points, each serialized as "world,x,y,z,yaw,pitch", semicolon-delimited. */
        public final String visitedForkPointsSerial;
        /** Initial fork fallback, serialized as "world,x,y,z,yaw,pitch", or null. */
        public final String initialForkFallbackSerial;
        /** Whether the player had an active RunProgress (global timer) when saved. */
        public final boolean hasRunProgress;

        SavedRunData(String playerName, long elapsedMs, int medals,
                     int stone, int bronze, int silver, int gold,
                     Map<String, String> claimedLevels,
                     String locWorldName, double locX, double locY, double locZ,
                     float locYaw, float locPitch) {
            this(playerName, elapsedMs, medals, stone, bronze, silver, gold, claimedLevels,
                    locWorldName, locX, locY, locZ, locYaw, locPitch,
                    null, null, 0, false, false, false, null, 0,
                    null, 0, 0, 0, 0, 0, null, null, true);
        }

        SavedRunData(String playerName, long elapsedMs, int medals,
                     int stone, int bronze, int silver, int gold,
                     Map<String, String> claimedLevels,
                     String locWorldName, double locX, double locY, double locZ,
                     float locYaw, float locPitch,
                     String sessionMapId, String sessionDeploymentId,
                     long sessionElapsedMs, boolean sessionStarted,
                     boolean sessionCompleted, boolean sessionInsideRegion,
                     String sessionState, double sessionDeathLineY,
                     String checkpointWorld, double checkpointX, double checkpointY, double checkpointZ,
                     float checkpointYaw, float checkpointPitch,
                     String visitedForkPointsSerial, String initialForkFallbackSerial,
                     boolean hasRunProgress) {
            this.playerName = playerName;
            this.elapsedMs = elapsedMs;
            this.medals = medals;
            this.stone = stone;
            this.bronze = bronze;
            this.silver = silver;
            this.gold = gold;
            this.claimedLevels = claimedLevels != null ? claimedLevels : Collections.emptyMap();
            this.locWorldName = locWorldName;
            this.locX = locX;
            this.locY = locY;
            this.locZ = locZ;
            this.locYaw = locYaw;
            this.locPitch = locPitch;
            this.sessionMapId = sessionMapId;
            this.sessionDeploymentId = sessionDeploymentId;
            this.sessionElapsedMs = sessionElapsedMs;
            this.sessionStarted = sessionStarted;
            this.sessionCompleted = sessionCompleted;
            this.sessionInsideRegion = sessionInsideRegion;
            this.sessionState = sessionState;
            this.sessionDeathLineY = sessionDeathLineY;
            this.checkpointWorld = checkpointWorld;
            this.checkpointX = checkpointX;
            this.checkpointY = checkpointY;
            this.checkpointZ = checkpointZ;
            this.checkpointYaw = checkpointYaw;
            this.checkpointPitch = checkpointPitch;
            this.visitedForkPointsSerial = visitedForkPointsSerial;
            this.initialForkFallbackSerial = initialForkFallbackSerial;
            this.hasRunProgress = hasRunProgress;
        }

        /** Returns true if this saved run includes a last-known location. */
        public boolean hasLocation() {
            return locWorldName != null && !locWorldName.isEmpty();
        }

        /** Returns true if a per-level session snapshot was saved. */
        public boolean hasSessionState() {
            return sessionMapId != null && sessionDeploymentId != null;
        }

        /** Returns true if a valid checkpoint location was saved. */
        public boolean hasCheckpoint() {
            return checkpointWorld != null && !checkpointWorld.isEmpty();
        }
    }

    public static class RankEntry {
        public final UUID playerId;
        public final String playerName;
        public final long timeMs;
        public final int medals;
        RankEntry(UUID playerId, String playerName, long timeMs, int medals) {
            this.playerId = playerId; this.playerName = playerName;
            this.timeMs = timeMs; this.medals = medals;
        }
    }

    public static class CountdownRankEntry {
        public final UUID playerId;
        public final String playerName;
        public final int score, stone, bronze, silver, gold;
        public final long timeMs;
        public final String endTier;
        CountdownRankEntry(UUID id, String name, int score, int st, int br, int si, int go, long ms, String endTier) {
            this.playerId = id; this.playerName = name; this.score = score;
            this.stone = st; this.bronze = br; this.silver = si; this.gold = go; this.timeMs = ms;
            this.endTier = endTier;
        }
    }
}
