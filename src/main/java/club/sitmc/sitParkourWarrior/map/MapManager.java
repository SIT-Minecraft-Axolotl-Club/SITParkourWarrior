package club.sitmc.sitParkourWarrior.map;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MapManager {
    private static final long MAX_REGION_VOLUME = 2_000_000L;

    private final SITParkourWarrior plugin;
    private final Map<String, ParkourMap> maps = new HashMap<>();
    private final File mapsFolder;

    public MapManager(SITParkourWarrior plugin) {
        this.plugin = plugin;
        this.mapsFolder = new File(plugin.getDataFolder(), "maps");
        if (!mapsFolder.exists()) {
            mapsFolder.mkdirs();
        }
    }

    public Map<String, ParkourMap> getMaps() {
        return Collections.unmodifiableMap(maps);
    }

    /** A deployment together with its owning map, keyed by world name. */
    public static class WorldDeployment {
        public final ParkourMap map;
        public final Deployment dep;
        WorldDeployment(ParkourMap map, Deployment dep) { this.map = map; this.dep = dep; }
    }

    private final Map<String, List<WorldDeployment>> worldDeployments = new HashMap<>();

    /** Get all deployments in a specific world (from the cached index). */
    public List<WorldDeployment> getWorldDeployments(String worldName) {
        List<WorldDeployment> list = worldDeployments.get(worldName);
        return list != null ? list : Collections.emptyList();
    }

    /** Rebuild the world→deployment index. Call after loadAll / deploy / undeploy. */
    public void rebuildWorldIndex() {
        worldDeployments.clear();
        for (ParkourMap map : maps.values()) {
            for (Deployment dep : map.getDeployments()) {
                Region r = dep.getRegion();
                if (r == null) continue;
                String worldName = r.getWorldName();
                if (worldName == null || worldName.isBlank()) continue;
                worldDeployments.computeIfAbsent(worldName, k -> new ArrayList<>())
                        .add(new WorldDeployment(map, dep));
            }
        }
    }

    public ParkourMap getMap(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase();
        ParkourMap map = maps.get(key);
        if (map != null) {
            return map;
        }
        String altKey = id.replace("_", " ").toLowerCase();
        return maps.get(altKey);
    }

    public ParkourMap createMap(String id) {
        String key = id.toLowerCase();
        if (maps.containsKey(key)) {
            return null;
        }
        String cleanedId = id.trim();
        ParkourMap map = new ParkourMap(cleanedId);
        maps.put(cleanedId.toLowerCase(), map);
        ensureMapFolder(map);
        return map;
    }

    public boolean saveMap(ParkourMap map) {
        if (map == null) {
            return false;
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", map.getId());
        config.set("title", map.getTitle());
        config.set("subtitle", map.getSubtitle());
        config.set("difficulty", map.getDifficulty().name().toLowerCase());
        // PKWS metadata lives in its own section because the legacy PKW
        // difficulty field above already uses the same top-level key.
        // Do not create this section for untouched legacy maps.
        if (map.hasPkwsMetadata()) {
            config.set("pkws.orientation", map.getPkwsOrientation() != null
                    ? map.getPkwsOrientation().toConfigString() : null);
            config.set("pkws.difficulty", map.getPkwsDifficulty());
            config.set("pkws.weight", map.getPkwsWeight());
        }
        config.set("deployed", map.isDeployed());
        config.set("effects.particles", map.isParticlesEnabled());
        config.set("effects.sound", map.isSoundEnabled());
        config.set("node_type", map.getNodeType().toConfigString());

        // Keep the legacy end-tier value when a GLOBAL_END is temporarily
        // marked as PKWS_END; it is ignored by PKWS and can be restored if
        // the node is later changed back to a PKW GLOBAL_END.
        if (map.getNodeType() == NodeType.GLOBAL_END || map.getNodeType() == NodeType.PKWS_END) {
            config.set("end_tier", map.getEndTier().toConfigString());
        }

        List<PointLocation> forkBranchPoints = map.getForkBranchPoints();
        if (!forkBranchPoints.isEmpty()) {
            ConfigurationSection forkSection = config.createSection("fork");
            ConfigurationSection branchesSection = forkSection.createSection("branch_points");
            for (int i = 0; i < forkBranchPoints.size(); i++) {
                String branchPath = "branch_" + i;
                saveLocation(branchesSection, branchPath, forkBranchPoints.get(i));
            }
        }

        Region region = map.getRegion();
        if (region != null) {
            ConfigurationSection section = config.createSection("region");
            section.set("world", region.getWorldName());
            section.set("pos1.x", region.getMinX());
            section.set("pos1.y", region.getMinY());
            section.set("pos1.z", region.getMinZ());
            section.set("pos2.x", region.getMaxX());
            section.set("pos2.y", region.getMaxY());
            section.set("pos2.z", region.getMaxZ());
        }

        saveLocation(config, "start", map.getStart());
        saveLocation(config, "end", map.getEnd());
        saveLocation(config, "start_pos1", map.getStartPos1());
        saveLocation(config, "start_pos2", map.getStartPos2());
        saveLocation(config, "end_pos1", map.getEndPos1());
        saveLocation(config, "end_pos2", map.getEndPos2());

        DynamicData dynamicData = map.getDynamicData();
        // Auto-enable when there are enough states for a rotation;
        // catches legacy maps and non-addstate save paths (deploy, set*, etc.).
        if (!dynamicData.isEnabled() && dynamicData.getStates().size() >= 2) {
            dynamicData.setEnabled(true);
        }
        ConfigurationSection dynamicSection = config.createSection("dynamic");
        dynamicSection.set("enabled", dynamicData.isEnabled());
        List<Map<String, Object>> stateMaps = new ArrayList<>();
        for (DynamicState state : dynamicData.getStates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", state.getId());
            m.put("file", state.getFile());
            if (state.getName() != null && !state.getName().isBlank()) {
                m.put("name", state.getName());
            }
            m.put("interval", state.getInterval());
            stateMaps.add(m);
        }
        dynamicSection.set("state_list", stateMaps);

        List<Deployment> deployments = new ArrayList<>(map.getDeployments());
        if (!deployments.isEmpty()) {
            ConfigurationSection deploymentsSection = config.createSection("deployments");
            int index = 0;
            for (Deployment deployment : deployments) {
                ConfigurationSection deploymentSection = deploymentsSection.createSection(String.valueOf(index++));
                deploymentSection.set("id", deployment.getId());
                Region depRegion = deployment.getRegion();
                if (depRegion != null) {
                    deploymentSection.set("region.world", depRegion.getWorldName());
                    deploymentSection.set("region.pos1.x", depRegion.getMinX());
                    deploymentSection.set("region.pos1.y", depRegion.getMinY());
                    deploymentSection.set("region.pos1.z", depRegion.getMinZ());
                    deploymentSection.set("region.pos2.x", depRegion.getMaxX());
                    deploymentSection.set("region.pos2.y", depRegion.getMaxY());
                    deploymentSection.set("region.pos2.z", depRegion.getMaxZ());
                }
                saveLocation(deploymentSection, "start", deployment.getStart());
                saveLocation(deploymentSection, "end", deployment.getEnd());
                saveLocation(deploymentSection, "start_pos1", deployment.getStartPos1());
                saveLocation(deploymentSection, "start_pos2", deployment.getStartPos2());
                saveLocation(deploymentSection, "end_pos1", deployment.getEndPos1());
                saveLocation(deploymentSection, "end_pos2", deployment.getEndPos2());
                List<PointLocation> forkPoints = deployment.getForkBranchPoints();
                if (!forkPoints.isEmpty()) {
                    ConfigurationSection forkSection = deploymentSection.createSection("fork_branch_points");
                    for (int i = 0; i < forkPoints.size(); i++) {
                        saveLocation(forkSection, "bp_" + i, forkPoints.get(i));
                    }
                }
            }
        }

        File mapFolder = ensureMapFolder(map);
        File file = new File(mapFolder, "map.yml");
        try {
            config.save(file);
            rebuildWorldIndex();
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save map " + map.getId() + ": " + e.getMessage());
            return false;
        }
    }

    public void saveAll() {
        for (ParkourMap map : maps.values()) {
            saveMap(map);
        }
    }

    public boolean deleteMap(String id) {
        ParkourMap map = getMap(id);
        if (map == null) {
            return false;
        }
        maps.remove(map.getId().toLowerCase());
        rebuildWorldIndex();
        File folder = getMapFolder(map);
        return deleteFolder(folder);
    }

    public void loadAll() {
        maps.clear();
        if (!mapsFolder.exists()) {
            return;
        }
        File[] folders = mapsFolder.listFiles(File::isDirectory);
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            File file = new File(folder, "map.yml");
            if (!file.exists()) {
                continue;
            }
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                String id = config.getString("id", folder.getName().replace("_", " "));
                ParkourMap map = new ParkourMap(id);
                map.setTitle(config.getString("title", id));
                map.setSubtitle(config.getString("subtitle", null));
                map.setDifficulty(Difficulty.fromString(config.getString("difficulty", "easy")));
                loadPkwsMetadata(config, map, file);
                map.setDeployed(config.getBoolean("deployed", false));
                map.setParticlesEnabled(config.getBoolean("effects.particles", true));
                map.setSoundEnabled(config.getBoolean("effects.sound", true));
                map.setNodeType(NodeType.fromString(config.getString("node_type", "level")));
                map.setEndTier(EndTier.fromString(config.getString("end_tier")));

                ConfigurationSection forkSection = config.getConfigurationSection("fork");
                if (forkSection != null) {
                    ConfigurationSection branchesSection = forkSection.getConfigurationSection("branch_points");
                    if (branchesSection != null) {
                        for (String branchKey : branchesSection.getKeys(false)) {
                            PointLocation branchLoc = loadLocation(branchesSection, branchKey);
                            if (branchLoc != null) {
                                map.getForkBranchPoints().add(branchLoc);
                            }
                        }
                    }
                }

                ConfigurationSection regionSection = config.getConfigurationSection("region");
                if (regionSection != null) {
                    String worldName = regionSection.getString("world");
                    int x1 = regionSection.getInt("pos1.x");
                    int y1 = regionSection.getInt("pos1.y");
                    int z1 = regionSection.getInt("pos1.z");
                    int x2 = regionSection.getInt("pos2.x");
                    int y2 = regionSection.getInt("pos2.y");
                    int z2 = regionSection.getInt("pos2.z");
                    if (worldName != null) {
                        map.setRegion(new Region(worldName, x1, y1, z1, x2, y2, z2));
                    }
                }

                map.setStart(loadLocation(config, "start"));
                map.setEnd(loadLocation(config, "end"));
                map.setStartPos1(loadLocation(config, "start_pos1"));
                map.setStartPos2(loadLocation(config, "start_pos2"));
                map.setEndPos1(loadLocation(config, "end_pos1"));
                map.setEndPos2(loadLocation(config, "end_pos2"));

                ConfigurationSection dynamicSection = config.getConfigurationSection("dynamic");
                if (dynamicSection != null) {
                    DynamicData dynamicData = map.getDynamicData();
                    dynamicData.setEnabled(dynamicSection.getBoolean("enabled", false));
                    loadDynamicStates(dynamicSection, dynamicData);
                    // Auto-enable legacy maps that already have ≥2 states
                    // but were saved before the dynamic.enabled default fix.
                    if (!dynamicData.isEnabled() && dynamicData.getStates().size() >= 2) {
                        dynamicData.setEnabled(true);
                    }
                }

                map.clearDeployments();
                ConfigurationSection deploymentsSection = config.getConfigurationSection("deployments");
                if (deploymentsSection != null) {
                    for (String key : deploymentsSection.getKeys(false)) {
                        ConfigurationSection deploymentSection = deploymentsSection.getConfigurationSection(key);
                        if (deploymentSection == null) {
                            continue;
                        }
                        String deploymentId = deploymentSection.getString("id", key);
                        ConfigurationSection depRegionSection = deploymentSection.getConfigurationSection("region");
                        Region depRegion = null;
                        if (depRegionSection != null) {
                            String depWorldName = depRegionSection.getString("world");
                            int x1 = depRegionSection.getInt("pos1.x");
                            int y1 = depRegionSection.getInt("pos1.y");
                            int z1 = depRegionSection.getInt("pos1.z");
                            int x2 = depRegionSection.getInt("pos2.x");
                            int y2 = depRegionSection.getInt("pos2.y");
                            int z2 = depRegionSection.getInt("pos2.z");
                            if (depWorldName != null) {
                                depRegion = new Region(depWorldName, x1, y1, z1, x2, y2, z2);
                            }
                        }
                        PointLocation depStart = loadLocation(deploymentSection, "start");
                        PointLocation depEnd = loadLocation(deploymentSection, "end");
                        List<PointLocation> depForkPoints = new ArrayList<>();
                        ConfigurationSection depForkSection = deploymentSection.getConfigurationSection("fork_branch_points");
                        if (depForkSection != null) {
                            for (String bpKey : depForkSection.getKeys(false)) {
                                PointLocation bp = loadLocation(depForkSection, bpKey);
                                if (bp != null) {
                                    depForkPoints.add(bp);
                                }
                            }
                        }
                        PointLocation depStartPos1 = loadLocation(deploymentSection, "start_pos1");
                        PointLocation depStartPos2 = loadLocation(deploymentSection, "start_pos2");
                        PointLocation depEndPos1   = loadLocation(deploymentSection, "end_pos1");
                        PointLocation depEndPos2   = loadLocation(deploymentSection, "end_pos2");
                        if (depRegion != null) {
                            map.addDeployment(new Deployment(deploymentId, depRegion, depStart, depEnd, depForkPoints,
                                    depStartPos1, depStartPos2, depEndPos1, depEndPos2));
                        }
                    }
                }

                maps.put(id.toLowerCase(), map);
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "[SITPKW] 加载地图失败: 文件夹=" + folder.getName()
                                + ", 文件=" + file.getAbsolutePath()
                                + ", 异常=" + e.getClass().getName()
                                + ", 消息=" + e.getMessage(), e);
            }
        }
        rebuildWorldIndex();
    }

    /**
     * Loads PKWS fields defensively.  The namespaced form is canonical:
     * pkws.orientation, pkws.difficulty and pkws.weight.  A few early PKWS
     * prototypes used top-level orientation/weight fields, so those are read
     * as a non-destructive compatibility fallback.  A numeric top-level
     * difficulty is also accepted; the existing string difficulty values
     * (easy/normal/hard/extreme) remain the legacy PKW field.
     */
    private void loadPkwsMetadata(YamlConfiguration config, ParkourMap map, File file) {
        boolean hasOrientation = config.contains("pkws.orientation");
        Object orientationRaw = config.get("pkws.orientation");
        if (!hasOrientation && config.contains("orientation")) {
            hasOrientation = true;
            orientationRaw = config.get("orientation");
        }

        boolean hasDifficulty = config.contains("pkws.difficulty");
        Object difficultyRaw = config.get("pkws.difficulty");
        if (!hasDifficulty && isIntegralValue(config.get("difficulty"))) {
            hasDifficulty = true;
            difficultyRaw = config.get("difficulty");
        }

        boolean hasWeight = config.contains("pkws.weight");
        Object weightRaw = config.get("pkws.weight");
        if (!hasWeight && config.contains("weight")) {
            hasWeight = true;
            weightRaw = config.get("weight");
        }

        if (!hasOrientation && !hasDifficulty && !hasWeight) {
            plugin.getLogger().warning("[PKWS] 关卡 " + map.getId()
                    + " 缺少 orientation/difficulty/weight，按默认值处理（未设置/未设置/0）："
                    + file.getAbsolutePath());
            map.loadPkwsMetadata(null, null, 0, false);
            return;
        }

        PkwsOrientation orientation = null;
        if (hasOrientation) {
            if (orientationRaw == null) {
                warnInvalidPkwsField(map, file, "orientation", null, "NS 或 EW");
            } else {
                orientation = PkwsOrientation.fromString(String.valueOf(orientationRaw));
                if (orientation == null) {
                    warnInvalidPkwsField(map, file, "orientation", orientationRaw, "NS 或 EW");
                }
            }
        } else {
            warnMissingPkwsField(map, file, "orientation", "未设置");
        }

        Integer difficulty = null;
        if (hasDifficulty) {
            Long value = toIntegralValue(difficultyRaw);
            if (value == null || value < 1 || value > 16) {
                warnInvalidPkwsField(map, file, "difficulty", difficultyRaw, "1-16");
            } else {
                difficulty = value.intValue();
            }
        } else {
            warnMissingPkwsField(map, file, "difficulty", "未设置");
        }

        int weight = 0;
        if (hasWeight) {
            Long value = toIntegralValue(weightRaw);
            if (value == null || value < 0 || value > Integer.MAX_VALUE) {
                warnInvalidPkwsField(map, file, "weight", weightRaw, "大于等于 0 的整数");
            } else {
                weight = value.intValue();
            }
        } else {
            warnMissingPkwsField(map, file, "weight", "0");
        }

        map.loadPkwsMetadata(orientation, difficulty, weight, true);
    }

    private void warnMissingPkwsField(ParkourMap map, File file, String field, String fallback) {
        plugin.getLogger().warning("[PKWS] 关卡 " + map.getId() + " 缺少 " + field
                + "，按默认值 " + fallback + " 处理：" + file.getAbsolutePath());
    }

    private void warnInvalidPkwsField(ParkourMap map, File file, String field,
                                      Object value, String expected) {
        plugin.getLogger().warning("[PKWS] 关卡 " + map.getId() + " 的 " + field
                + " 值非法（" + String.valueOf(value) + "），按默认值处理；可选："
                + expected + "：" + file.getAbsolutePath());
    }

    private boolean isIntegralValue(Object value) {
        return toIntegralValue(value) != null;
    }

    private Long toIntegralValue(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number) && number == Math.rint(number)
                    && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE) {
                return (long) number;
            }
            return null;
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Loads the dynamic state list, preferring the new ordered {@code state_list}
     * format and falling back to migrating the legacy parallel-list format
     * ({@code states} / {@code interval_sequence} / {@code state_ids}).
     * The legacy file on disk is left untouched; the new format is only written
     * back the next time {@link #saveMap} runs.
     */
    private void loadDynamicStates(ConfigurationSection dynamicSection, DynamicData dynamicData) {
        dynamicData.getStates().clear();
        if (dynamicSection.isList("state_list")) {
            for (Object raw : dynamicSection.getList("state_list", Collections.emptyList())) {
                if (!(raw instanceof Map<?, ?> map)) {
                    continue;
                }
                int id = toInt(map.get("id"), dynamicData.nextId());
                String file = map.get("file") != null ? String.valueOf(map.get("file")) : null;
                if (file == null || file.isBlank()) {
                    continue;
                }
                String name = map.get("name") != null ? String.valueOf(map.get("name")) : null;
                int interval = toInt(map.get("interval"), DynamicState.DEFAULT_INTERVAL_TICKS);
                dynamicData.getStates().add(new DynamicState(id, file, name, interval));
            }
            return;
        }

        // Legacy migration: three parallel lists (states / interval_sequence / state_ids),
        // historically not always kept in sync (addstate never appended to state_ids,
        // delstate never removed from it). Pair up by index defensively, fabricate a
        // synthetic id for any entry missing one, then sort by id ascending so playback
        // order matches the old state_ids-driven order.
        List<String> legacyStates = dynamicSection.getStringList("states");
        List<Integer> legacyIntervals = dynamicSection.getIntegerList("interval_sequence");
        List<Integer> legacyIds = dynamicSection.getIntegerList("state_ids");
        if (legacyStates.isEmpty()) {
            return;
        }
        int maxKnownId = 0;
        for (Integer id : legacyIds) {
            if (id != null && id > maxKnownId) {
                maxKnownId = id;
            }
        }
        int syntheticNext = maxKnownId + 1;
        List<DynamicState> migrated = new ArrayList<>();
        for (int i = 0; i < legacyStates.size(); i++) {
            String file = legacyStates.get(i);
            int interval = i < legacyIntervals.size() ? legacyIntervals.get(i) : DynamicState.DEFAULT_INTERVAL_TICKS;
            int id = i < legacyIds.size() ? legacyIds.get(i) : syntheticNext++;
            migrated.add(new DynamicState(id, file, null, interval));
        }
        migrated.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        dynamicData.getStates().addAll(migrated);
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean isRegionTooLarge(Region region) {
        return region != null && region.volume() > MAX_REGION_VOLUME;
    }

    private void saveLocation(ConfigurationSection section, String path, PointLocation point) {
        if (section == null || point == null || !point.hasWorld()) {
            if (point != null && !point.hasWorld()) {
                plugin.getLogger().warning("Skipping save of '" + path + "': worldName is blank, data would be lost.");
            }
            return;
        }
        section.set(path + ".world", point.getWorldName());
        section.set(path + ".x", point.getX());
        section.set(path + ".y", point.getY());
        section.set(path + ".z", point.getZ());
        section.set(path + ".yaw", point.getYaw());
        section.set(path + ".pitch", point.getPitch());
    }

    private PointLocation loadLocation(ConfigurationSection section, String path) {
        if (section == null) {
            return null;
        }
        String worldName = section.getString(path + ".world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        double x = section.getDouble(path + ".x");
        double y = section.getDouble(path + ".y");
        double z = section.getDouble(path + ".z");
        float yaw = (float) section.getDouble(path + ".yaw");
        float pitch = (float) section.getDouble(path + ".pitch");
        return new PointLocation(worldName, x, y, z, yaw, pitch);
    }

    public File getMapFolder(ParkourMap map) {
        return ensureMapFolder(map);
    }

    private File ensureMapFolder(ParkourMap map) {
        String folderName = map.getId().replace(" ", "_");
        File folder = new File(mapsFolder, folderName);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    private boolean deleteFolder(File folder) {
        if (folder == null || !folder.exists()) {
            return false;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        return folder.delete();
    }
}
