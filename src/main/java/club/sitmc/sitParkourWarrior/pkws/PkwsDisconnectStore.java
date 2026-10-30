package club.sitmc.sitParkourWarrior.pkws;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Small, synchronous persistence store for PKWS participants who leave while
 * a race is running.  A PKWS race itself is intentionally not restorable
 * after a server restart, so these records are cleared when the race manager
 * initializes; the store only bridges PlayerQuit/PlayerJoin and world changes.
 */
public final class PkwsDisconnectStore {
    private final File file;
    private final Logger logger;

    public PkwsDisconnectStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pkws-disconnect.yml");
        this.logger = logger;
    }

    public synchronized void save(Snapshot snapshot) {
        if (snapshot == null || snapshot.worldName == null || snapshot.playerId == null) {
            return;
        }
        YamlConfiguration config = loadConfig();
        String base = base(snapshot.worldName, snapshot.playerId);
        config.set(base, null);
        config.set(base + ".match_id", snapshot.matchId);
        config.set(base + ".player_name", snapshot.playerName);
        config.set(base + ".lives", snapshot.lives);
        config.set(base + ".eliminated", snapshot.eliminated);
        config.set(base + ".piece_index", snapshot.pieceIndex);
        config.set(base + ".active_level_index", snapshot.activeLevelPieceIndex);
        config.set(base + ".level_elapsed_ms", snapshot.levelElapsedMs);
        config.set(base + ".level_timer_running", snapshot.levelTimerRunning);
        config.set(base + ".inside_level_start", snapshot.insideLevelStart);
        config.set(base + ".level_completed", snapshot.levelCompleted);
        config.set(base + ".level_inside_region", snapshot.levelInsideRegion);
        config.set(base + ".level_pending_title", snapshot.levelPendingTitleAtStart);
        config.set(base + ".checkpoint.world", snapshot.checkpointWorld);
        config.set(base + ".checkpoint.x", snapshot.checkpointX);
        config.set(base + ".checkpoint.y", snapshot.checkpointY);
        config.set(base + ".checkpoint.z", snapshot.checkpointZ);
        config.set(base + ".checkpoint.yaw", (double) snapshot.checkpointYaw);
        config.set(base + ".checkpoint.pitch", (double) snapshot.checkpointPitch);
        config.set(base + ".last_location.world", snapshot.locationWorld);
        config.set(base + ".last_location.x", snapshot.locationX);
        config.set(base + ".last_location.y", snapshot.locationY);
        config.set(base + ".last_location.z", snapshot.locationZ);
        config.set(base + ".last_location.yaw", (double) snapshot.locationYaw);
        config.set(base + ".last_location.pitch", (double) snapshot.locationPitch);
        config.set(base + ".arrived_relay_piece_indices", snapshot.arrivedRelayPieceIndices);
        persist(config);
    }

    public synchronized Snapshot load(String worldName, UUID playerId) {
        if (worldName == null || worldName.isBlank() || playerId == null) {
            return null;
        }
        YamlConfiguration config = loadConfig();
        String base = base(worldName, playerId);
        ConfigurationSection section = config.getConfigurationSection(base);
        if (section == null) {
            return null;
        }
        String locationWorld = section.getString("last_location.world");
        String checkpointWorld = section.getString("checkpoint.world");
        List<Integer> arrived = new ArrayList<>(section.getIntegerList("arrived_relay_piece_indices"));
        return new Snapshot(worldName, playerId,
                section.getString("match_id"), section.getString("player_name", ""),
                section.getInt("lives", 0), section.getBoolean("eliminated", false),
                section.getInt("piece_index", 0), section.getInt("active_level_index", -1),
                section.getLong("level_elapsed_ms", 0L),
                section.getBoolean("level_timer_running", false),
                section.getBoolean("inside_level_start", false),
                section.getBoolean("level_completed", false),
                section.contains("level_inside_region")
                        ? section.getBoolean("level_inside_region", true) : true,
                section.getBoolean("level_pending_title", false),
                checkpointWorld,
                section.getDouble("checkpoint.x"),
                section.getDouble("checkpoint.y"),
                section.getDouble("checkpoint.z"),
                (float) section.getDouble("checkpoint.yaw"),
                (float) section.getDouble("checkpoint.pitch"),
                locationWorld,
                section.getDouble("last_location.x"),
                section.getDouble("last_location.y"),
                section.getDouble("last_location.z"),
                (float) section.getDouble("last_location.yaw"),
                (float) section.getDouble("last_location.pitch"),
                arrived);
    }

    public synchronized void remove(String worldName, UUID playerId) {
        if (worldName == null || worldName.isBlank() || playerId == null) {
            return;
        }
        YamlConfiguration config = loadConfig();
        config.set(base(worldName, playerId), null);
        persist(config);
    }

    public synchronized void clearWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        YamlConfiguration config = loadConfig();
        config.set("worlds." + worldName + ".active", null);
        persist(config);
    }

    public synchronized void clearAll() {
        YamlConfiguration config = loadConfig();
        config.set("worlds", null);
        persist(config);
    }

    private String base(String worldName, UUID playerId) {
        return "worlds." + worldName + ".active." + playerId;
    }

    private YamlConfiguration loadConfig() {
        if (!file.isFile()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void persist(YamlConfiguration config) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            if (logger != null) {
                logger.warning("[PKWS] 无法保存断线参赛者状态: " + ex.getMessage());
            }
        }
    }

    public static final class Snapshot {
        private final String worldName;
        private final UUID playerId;
        private final String matchId;
        private final String playerName;
        private final int lives;
        private final boolean eliminated;
        private final int pieceIndex;
        private final int activeLevelPieceIndex;
        private final long levelElapsedMs;
        private final boolean levelTimerRunning;
        private final boolean insideLevelStart;
        private final boolean levelCompleted;
        private final boolean levelInsideRegion;
        private final boolean levelPendingTitleAtStart;
        private final String checkpointWorld;
        private final double checkpointX;
        private final double checkpointY;
        private final double checkpointZ;
        private final float checkpointYaw;
        private final float checkpointPitch;
        private final String locationWorld;
        private final double locationX;
        private final double locationY;
        private final double locationZ;
        private final float locationYaw;
        private final float locationPitch;
        private final List<Integer> arrivedRelayPieceIndices;

        private Snapshot(String worldName, UUID playerId, String matchId, String playerName,
                         int lives, boolean eliminated, int pieceIndex,
                         int activeLevelPieceIndex, long levelElapsedMs,
                         boolean levelTimerRunning, boolean insideLevelStart,
                         boolean levelCompleted, boolean levelInsideRegion,
                         boolean levelPendingTitleAtStart,
                         String checkpointWorld, double checkpointX, double checkpointY,
                         double checkpointZ, float checkpointYaw, float checkpointPitch,
                         String locationWorld, double locationX, double locationY,
                         double locationZ, float locationYaw, float locationPitch,
                         List<Integer> arrivedRelayPieceIndices) {
            this.worldName = worldName;
            this.playerId = playerId;
            this.matchId = matchId;
            this.playerName = playerName;
            this.lives = lives;
            this.eliminated = eliminated;
            this.pieceIndex = pieceIndex;
            this.activeLevelPieceIndex = activeLevelPieceIndex;
            this.levelElapsedMs = Math.max(0L, levelElapsedMs);
            this.levelTimerRunning = levelTimerRunning;
            this.insideLevelStart = insideLevelStart;
            this.levelCompleted = levelCompleted;
            this.levelInsideRegion = levelInsideRegion;
            this.levelPendingTitleAtStart = levelPendingTitleAtStart;
            this.checkpointWorld = checkpointWorld;
            this.checkpointX = checkpointX;
            this.checkpointY = checkpointY;
            this.checkpointZ = checkpointZ;
            this.checkpointYaw = checkpointYaw;
            this.checkpointPitch = checkpointPitch;
            this.locationWorld = locationWorld;
            this.locationX = locationX;
            this.locationY = locationY;
            this.locationZ = locationZ;
            this.locationYaw = locationYaw;
            this.locationPitch = locationPitch;
            this.arrivedRelayPieceIndices = arrivedRelayPieceIndices == null
                    ? List.of() : List.copyOf(arrivedRelayPieceIndices);
        }

        public String getWorldName() { return worldName; }
        public UUID getPlayerId() { return playerId; }
        public String getMatchId() { return matchId; }
        public String getPlayerName() { return playerName; }
        public int getLives() { return lives; }
        public boolean isEliminated() { return eliminated; }
        public int getPieceIndex() { return pieceIndex; }
        public int getActiveLevelPieceIndex() { return activeLevelPieceIndex; }
        public long getLevelElapsedMs() { return levelElapsedMs; }
        public boolean isLevelTimerRunning() { return levelTimerRunning; }
        public boolean isInsideLevelStart() { return insideLevelStart; }
        public boolean isLevelCompleted() { return levelCompleted; }
        public boolean isLevelInsideRegion() { return levelInsideRegion; }
        public boolean isLevelPendingTitleAtStart() { return levelPendingTitleAtStart; }
        public String getCheckpointWorld() { return checkpointWorld; }
        public double getCheckpointX() { return checkpointX; }
        public double getCheckpointY() { return checkpointY; }
        public double getCheckpointZ() { return checkpointZ; }
        public float getCheckpointYaw() { return checkpointYaw; }
        public float getCheckpointPitch() { return checkpointPitch; }
        public String getLocationWorld() { return locationWorld; }
        public double getLocationX() { return locationX; }
        public double getLocationY() { return locationY; }
        public double getLocationZ() { return locationZ; }
        public float getLocationYaw() { return locationYaw; }
        public float getLocationPitch() { return locationPitch; }
        public List<Integer> getArrivedRelayPieceIndices() { return arrivedRelayPieceIndices; }
    }

    public static Snapshot create(String worldName, UUID playerId, String matchId,
                                  String playerName, int lives, boolean eliminated,
                                   int pieceIndex, int activeLevelPieceIndex,
                                   long levelElapsedMs, boolean levelTimerRunning,
                                   boolean insideLevelStart, boolean levelCompleted,
                                   boolean levelInsideRegion, boolean levelPendingTitleAtStart,
                                   String checkpointWorld, double checkpointX, double checkpointY,
                                   double checkpointZ, float checkpointYaw, float checkpointPitch,
                                   String locationWorld,
                                   double locationX, double locationY, double locationZ,
                                  float locationYaw, float locationPitch,
                                  List<Integer> arrivedRelayPieceIndices) {
        return new Snapshot(worldName, playerId, matchId, playerName, lives, eliminated,
                pieceIndex, activeLevelPieceIndex, levelElapsedMs, levelTimerRunning,
                insideLevelStart, levelCompleted, levelInsideRegion,
                levelPendingTitleAtStart, checkpointWorld, checkpointX, checkpointY,
                checkpointZ, checkpointYaw, checkpointPitch,
                locationWorld, locationX, locationY, locationZ,
                locationYaw, locationPitch, arrivedRelayPieceIndices);
    }
}
