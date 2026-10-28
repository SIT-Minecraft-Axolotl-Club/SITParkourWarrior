package club.sitmc.sitParkourWarrior.pkws;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/** Small YAML store for PKWS wins. It intentionally keeps no player cache. */
public final class PkwsWinsStore {
    private final File file;
    private final Logger logger;

    public PkwsWinsStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pkws-records.yml");
        this.logger = logger;
    }

    public synchronized int getWins(UUID playerId) {
        if (playerId == null || !file.isFile()) {
            return 0;
        }
        YamlConfiguration config = loadConfig();
        return Math.max(0, config.getInt(path(playerId) + ".wins", 0));
    }

    public synchronized boolean recordWin(UUID playerId, String playerName) {
        if (playerId == null) {
            return false;
        }
        YamlConfiguration config = loadConfig();
        String base = path(playerId);
        int wins = Math.max(0, config.getInt(base + ".wins", 0)) + 1;
        config.set(base + ".name", playerName == null || playerName.isBlank()
                ? playerId.toString() : playerName);
        config.set(base + ".wins", wins);
        return saveConfig(config);
    }

    public synchronized List<WinEntry> top(int limit) {
        if (!file.isFile()) {
            return List.of();
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return List.of();
        }
        List<WinEntry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            int wins = Math.max(0, section.getInt("wins", 0));
            String name = section.getString("name", uuid.toString());
            entries.add(new WinEntry(uuid, name, wins));
        }
        entries.sort(Comparator.comparingInt(WinEntry::wins).reversed()
                .thenComparing(WinEntry::name, String.CASE_INSENSITIVE_ORDER));
        if (limit <= 0 || entries.size() <= limit) {
            return entries;
        }
        return new ArrayList<>(entries.subList(0, limit));
    }

    public synchronized List<String> getKnownNames() {
        List<String> names = new ArrayList<>();
        for (WinEntry entry : top(Integer.MAX_VALUE)) {
            if (entry.name() != null && !entry.name().isBlank()) {
                names.add(entry.name());
            }
        }
        return names;
    }

    public synchronized UUID findPlayerByName(String name) {
        if (name == null || name.isBlank() || !file.isFile()) {
            return null;
        }
        for (WinEntry entry : top(Integer.MAX_VALUE)) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry.playerId();
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
            logger.warning("无法保存 pkws-records.yml: " + ex.getMessage());
            return false;
        }
    }

    private String path(UUID playerId) {
        return "players." + playerId.toString().toLowerCase(Locale.ROOT);
    }

    public record WinEntry(UUID playerId, String name, int wins) {
    }
}
