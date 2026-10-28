package club.sitmc.sitParkourWarrior.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages config.yml — PKW-world list with timing modes and countdown durations.
 * （author/status 元数据已移交 SITParkourLobby 统一管理，本插件不再存储。）
 */
public class PkwWorldManager {

    private final File configFile;
    private final Map<String, TimingMode> worldModes = new LinkedHashMap<>();
    private final Map<String, Integer> durations = new LinkedHashMap<>();
    private final Set<String> pkwsWorlds = new LinkedHashSet<>();

    public PkwWorldManager(File dataFolder) {
        this.configFile = new File(dataFolder, "config.yml");
        load();
    }

    // ---- persistence ----

    private void load() {
        worldModes.clear();
        durations.clear();
        pkwsWorlds.clear();
        if (!configFile.exists()) {
            save();
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        // PKWS worlds are deliberately stored separately from pkw-worlds.
        // Accept both a simple list and a section/map for forward/backward
        // compatibility with hand-written configuration files.
        ConfigurationSection pkwsSection = config.getConfigurationSection("pkws-worlds");
        if (pkwsSection != null) {
            pkwsWorlds.addAll(pkwsSection.getKeys(false));
        } else {
            pkwsWorlds.addAll(config.getStringList("pkws-worlds"));
        }

        // pkw-worlds: supports three formats (backward-compatible):
        // 1. Legacy section format (each value is a section; only mode is read; author/status safely ignored)
        // 2. Current plain string format (each value is "countup"/"countdown")
        // 3. Ancient list format (a plain list of world name strings)
        ConfigurationSection worldsSection = config.getConfigurationSection("pkw-worlds");
        if (worldsSection != null) {
            for (String key : worldsSection.getKeys(false)) {
                if (worldsSection.isConfigurationSection(key)) {
                    // ---- Section format (legacy: may carry mode / author / status) ----
                    // author/status 已移交 Lobby 管理，安全忽略；仅读取 mode
                    ConfigurationSection worldSection = worldsSection.getConfigurationSection(key);
                    worldModes.put(key, TimingMode.fromString(worldSection.getString("mode")));
                } else {
                    // ---- Plain string format: world → TimingMode ----
                    worldModes.put(key, TimingMode.fromString(worldsSection.getString(key)));
                }
            }
        } else {
            // Ancient format: list of world names → default COUNTUP
            List<String> list = config.getStringList("pkw-worlds");
            for (String name : list) {
                worldModes.put(name, TimingMode.COUNTUP);
            }
        }

        // pkw-world-durations: world → seconds
        ConfigurationSection durSection = config.getConfigurationSection("pkw-world-durations");
        if (durSection != null) {
            for (String key : durSection.getKeys(false)) {
                try {
                    durations.put(key, Integer.parseInt(durSection.getString(key, "0")));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // Preserve the existing PKW meaning if an old/manual config happens
        // to list the same world in both sections.  Runtime mutations already
        // reject this combination, and this keeps the invariant true after a
        // restart without changing any PKW world into PKWS.
        pkwsWorlds.removeAll(worldModes.keySet());
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();

        // Write simple world→mode string format (author/status 已移交 Lobby，不再写入)
        Map<String, Object> worldsMap = new LinkedHashMap<>();
        for (Map.Entry<String, TimingMode> e : worldModes.entrySet()) {
            worldsMap.put(e.getKey(), e.getValue().toConfigString());
        }
        config.set("pkw-worlds", worldsMap);

        if (!durations.isEmpty()) {
            Map<String, Object> durMap = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : durations.entrySet()) {
                durMap.put(e.getKey(), e.getValue());
            }
            config.set("pkw-world-durations", durMap);
        }
        if (!pkwsWorlds.isEmpty()) {
            config.set("pkws-worlds", new ArrayList<>(pkwsWorlds));
        }

        try {
            config.save(configFile);
        } catch (IOException e) {
            System.err.println("[SITPKW] Failed to save config.yml: " + e.getMessage());
        }
    }

    // ---- query ----

    public boolean isPkwWorld(String worldName) {
        return worldName != null && worldModes.containsKey(worldName);
    }

    public boolean isPkwWorld(World world) {
        return world != null && isPkwWorld(world.getName());
    }

    public boolean isPkwsWorld(String worldName) {
        return worldName != null && pkwsWorlds.contains(worldName);
    }

    public boolean isPkwsWorld(World world) {
        return world != null && isPkwsWorld(world.getName());
    }

    public TimingMode getTimingMode(String worldName) {
        TimingMode mode = worldModes.get(worldName);
        return mode != null ? mode : TimingMode.COUNTUP;
    }

    public TimingMode getTimingMode(World world) {
        return world != null ? getTimingMode(world.getName()) : TimingMode.COUNTUP;
    }

    public Set<String> getWorlds() {
        return Collections.unmodifiableSet(worldModes.keySet());
    }

    public Set<String> getPkwsWorlds() {
        return Collections.unmodifiableSet(pkwsWorlds);
    }

    public int getCountdownDuration(String worldName) {
        Integer d = durations.get(worldName);
        return d != null ? d : 0;
    }

    // ---- mutations ----

    public boolean add(String worldName, TimingMode mode) {
        if (worldName == null || worldName.isBlank()) return false;
        if (worldModes.containsKey(worldName)) return false;
        if (pkwsWorlds.contains(worldName)) return false;
        worldModes.put(worldName, mode != null ? mode : TimingMode.COUNTUP);
        save();
        return true;
    }

    public boolean addPkwsWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        if (pkwsWorlds.contains(worldName) || worldModes.containsKey(worldName)) return false;
        pkwsWorlds.add(worldName);
        save();
        return true;
    }

    public boolean removePkwsWorld(String worldName) {
        if (worldName == null) return false;
        boolean removed = pkwsWorlds.remove(worldName);
        if (removed) save();
        return removed;
    }

    public boolean setMode(String worldName, TimingMode mode) {
        if (worldName == null || !worldModes.containsKey(worldName)) return false;
        worldModes.put(worldName, mode != null ? mode : TimingMode.COUNTUP);
        save();
        return true;
    }

    public void setCountdownDuration(String worldName, int seconds) {
        durations.put(worldName, Math.max(1, seconds));
        save();
    }

    public boolean remove(String worldName) {
        if (worldName == null) return false;
        boolean removed = worldModes.remove(worldName) != null;
        durations.remove(worldName);
        if (removed) save();
        return removed;
    }
}
