package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Stores one generated PKWS track per world in a single small YAML file.
 * World names are encoded for use as stable YAML section keys; the original
 * name is always stored inside the section as well.
 */
public final class PkwsTrackStore {
    private static final int PIECE_COUNT = 33;

    private final File file;
    private final Logger logger;

    public PkwsTrackStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pkws-track.yml");
        this.logger = logger;
    }

    public synchronized boolean save(PkwsTrack track) {
        if (track == null || track.getWorldName() == null || track.getWorldName().isBlank()
                || track.getOrientation() == null || track.getPieces().size() != PIECE_COUNT) {
            return false;
        }
        YamlConfiguration config = loadConfig();
        String path = trackPath(track.getWorldName());
        config.set(path, null);
        ConfigurationSection section = config.createSection(path);
        section.set("world_name", track.getWorldName());
        section.set("orientation", track.getOrientation().name());
        section.set("generated_at", track.getGeneratedAt());

        ConfigurationSection pieces = section.createSection("pieces");
        for (int index = 0; index < track.getPieces().size(); index++) {
            PkwsTrack.Piece piece = track.getPieces().get(index);
            if (piece == null || piece.getType() == null || piece.getRegion() == null
                    || piece.getStart() == null || piece.getEnd() == null) {
                logger.warning("PKWS track contains an incomplete piece at index " + index + ".");
                return false;
            }
            ConfigurationSection pieceSection = pieces.createSection(String.valueOf(index));
            pieceSection.set("type", piece.getType().name());
            pieceSection.set("segment", piece.getSegment());
            pieceSection.set("position", piece.getPosition());
            pieceSection.set("source_id", piece.getSourceId());
            pieceSection.set("source_title", piece.getSourceTitle());
            if (piece.getDifficulty() != null) {
                pieceSection.set("difficulty", piece.getDifficulty());
            }
            pieceSection.set("weight", piece.getWeight());
            if (piece.getGateState() != null) {
                pieceSection.set("gate_state", piece.getGateState());
            }
            if (piece.getDeploymentId() != null && !piece.getDeploymentId().isBlank()) {
                pieceSection.set("deployment_id", piece.getDeploymentId());
            }
            saveRegion(pieceSection.createSection("region"), piece.getRegion());
            savePoint(pieceSection, "start", piece.getStart());
            savePoint(pieceSection, "end", piece.getEnd());
        }

        return saveConfig(config);
    }

    public synchronized PkwsTrack load(String worldName) {
        if (worldName == null || worldName.isBlank() || !file.isFile()) {
            return null;
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection section = config.getConfigurationSection(trackPath(worldName));
        if (section == null) {
            return null;
        }

        String storedWorld = section.getString("world_name", worldName);
        PkwsOrientation orientation = PkwsOrientation.fromString(section.getString("orientation"));
        ConfigurationSection piecesSection = section.getConfigurationSection("pieces");
        if (!worldName.equals(storedWorld) || orientation == null || piecesSection == null) {
            warnInvalid(worldName, "缺少世界、朝向或块记录");
            return null;
        }

        List<PkwsTrack.Piece> pieces = new ArrayList<>(PIECE_COUNT);
        for (int index = 0; index < PIECE_COUNT; index++) {
            ConfigurationSection pieceSection = piecesSection.getConfigurationSection(String.valueOf(index));
            PkwsTrack.Piece piece = loadPiece(pieceSection, worldName, index);
            if (piece == null) {
                warnInvalid(worldName, "第 " + (index + 1) + " 块记录不完整");
                return null;
            }
            pieces.add(piece);
        }

        return new PkwsTrack(worldName, orientation,
                section.getString("generated_at", "unknown"), pieces);
    }

    public synchronized boolean hasTrack(String worldName) {
        return load(worldName) != null;
    }

    /** Returns true even when the stored section is malformed. */
    public synchronized boolean containsRecord(String worldName) {
        if (worldName == null || worldName.isBlank() || !file.isFile()) {
            return false;
        }
        return loadConfig().getConfigurationSection(trackPath(worldName)) != null;
    }

    public synchronized boolean remove(String worldName) {
        if (worldName == null || worldName.isBlank() || !file.isFile()) {
            return false;
        }
        YamlConfiguration config = loadConfig();
        String path = trackPath(worldName);
        if (!config.contains(path)) {
            return false;
        }
        config.set(path, null);
        ConfigurationSection tracks = config.getConfigurationSection("tracks");
        if (tracks != null && tracks.getKeys(false).isEmpty()) {
            config.set("tracks", null);
        }
        return saveConfig(config);
    }

    private PkwsTrack.Piece loadPiece(ConfigurationSection section, String worldName, int index) {
        if (section == null) {
            return null;
        }
        PkwsTrack.PieceType type;
        try {
            type = PkwsTrack.PieceType.valueOf(section.getString("type", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Region region = loadRegion(section.getConfigurationSection("region"), worldName);
        PointLocation start = loadPoint(section, "start", worldName);
        PointLocation end = loadPoint(section, "end", worldName);
        if (region == null || start == null || end == null) {
            return null;
        }
        Integer difficulty = readNullableInt(section.get("difficulty"));
        String sourceId = section.getString("source_id", "");
        String sourceTitle = section.getString("source_title", sourceId);
        String deploymentId = section.getString("deployment_id");
        return new PkwsTrack.Piece(type, section.getInt("segment"), section.getInt("position"),
                sourceId, sourceTitle, difficulty, Math.max(0, section.getInt("weight")),
                region, start, end, section.getString("gate_state"), deploymentId);
    }

    private void saveRegion(ConfigurationSection section, Region region) {
        section.set("world", region.getWorldName());
        section.set("min.x", region.getMinX());
        section.set("min.y", region.getMinY());
        section.set("min.z", region.getMinZ());
        section.set("max.x", region.getMaxX());
        section.set("max.y", region.getMaxY());
        section.set("max.z", region.getMaxZ());
    }

    private Region loadRegion(ConfigurationSection section, String fallbackWorld) {
        if (section == null) {
            return null;
        }
        String world = section.getString("world", fallbackWorld);
        if (world == null || world.isBlank()) {
            return null;
        }
        return new Region(world,
                section.getInt("min.x"), section.getInt("min.y"), section.getInt("min.z"),
                section.getInt("max.x"), section.getInt("max.y"), section.getInt("max.z"));
    }

    private void savePoint(ConfigurationSection parent, String path, PointLocation point) {
        ConfigurationSection section = parent.createSection(path);
        section.set("world", point.getWorldName());
        section.set("x", point.getX());
        section.set("y", point.getY());
        section.set("z", point.getZ());
        section.set("yaw", point.getYaw());
        section.set("pitch", point.getPitch());
    }

    private PointLocation loadPoint(ConfigurationSection parent, String path, String fallbackWorld) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            return null;
        }
        String world = section.getString("world", fallbackWorld);
        if (world == null || world.isBlank()) {
            return null;
        }
        return new PointLocation(world, section.getDouble("x"), section.getDouble("y"),
                section.getDouble("z"), (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch"));
    }

    private Integer readNullableInt(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d)
                    && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                return number.intValue();
            }
        }
        if (value instanceof String string) {
            try {
                return Integer.valueOf(string.trim());
            } catch (NumberFormatException ignored) {
                // Treat malformed optional metadata as absent.
            }
        }
        return null;
    }

    private YamlConfiguration loadConfig() {
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private boolean saveConfig(YamlConfiguration config) {
        file.getParentFile().mkdirs();
        try {
            config.save(file);
            return true;
        } catch (IOException ex) {
            logger.warning("无法保存 pkws-track.yml: " + ex.getMessage());
            return false;
        }
    }

    private String trackPath(String worldName) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(worldName.getBytes(StandardCharsets.UTF_8));
        return "tracks." + encoded;
    }

    private void warnInvalid(String worldName, String detail) {
        logger.warning("[PKWS] 赛道记录 " + worldName + " 无效：" + detail);
    }
}
