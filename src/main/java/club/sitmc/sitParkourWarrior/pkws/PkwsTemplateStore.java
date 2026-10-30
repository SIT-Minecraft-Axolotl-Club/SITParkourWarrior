package club.sitmc.sitParkourWarrior.pkws;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/** Persistent, orientation-neutral PKWS difficulty/time templates. */
public final class PkwsTemplateStore {
    private static final String DIFFICULTY_ROOT = "difficulty_templates";
    private static final String TIME_ROOT = "time_templates";
    private static final String STAGE_TIME_MODEL = "stage-duration-v1";
    private final File file;
    private final Logger logger;

    public PkwsTemplateStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pkws-templates.yml");
        this.logger = logger;
    }

    public synchronized boolean saveDifficultyTemplate(String name, int[] values) {
        if (!isUserName(name) || values == null
                || values.length != PkwsRangeConfig.SEGMENT_COUNT
                * PkwsRangeConfig.POSITION_COUNT * 2) {
            return false;
        }
        for (int index = 0; index < values.length; index += 2) {
            if (!PkwsRangeConfig.isValidRange(values[index], values[index + 1])) {
                return false;
            }
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = getOrCreateRoot(config, DIFFICULTY_ROOT);
        String existingKey = findKey(root, name);
        String key = existingKey == null ? encodedKey(name) : existingKey;
        root.set(key, null);
        ConfigurationSection template = root.createSection(key);
        template.set("name", name);
        int cursor = 0;
        for (int segment = 1; segment <= PkwsRangeConfig.SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= PkwsRangeConfig.POSITION_COUNT; position++) {
                String base = "ranges.segment" + segment + ".position" + position;
                template.set(base + ".min", values[cursor++]);
                template.set(base + ".max", values[cursor++]);
            }
        }
        return saveConfig(config);
    }

    public synchronized int[] loadDifficultyTemplate(String name) {
        if (isDefault(name)) {
            return PkwsRangeConfig.defaultRangeValues();
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = config.getConfigurationSection(DIFFICULTY_ROOT);
        ConfigurationSection template = findTemplate(root, name);
        if (template == null) {
            return null;
        }
        int[] values = new int[PkwsRangeConfig.SEGMENT_COUNT
                * PkwsRangeConfig.POSITION_COUNT * 2];
        int cursor = 0;
        for (int segment = 1; segment <= PkwsRangeConfig.SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= PkwsRangeConfig.POSITION_COUNT; position++) {
                String base = "ranges.segment" + segment + ".position" + position;
                values[cursor++] = template.getInt(base + ".min", -1);
                values[cursor++] = template.getInt(base + ".max", -1);
            }
        }
        return isValidRanges(values) ? values : null;
    }

    public synchronized boolean deleteDifficultyTemplate(String name) {
        return deleteTemplate(DIFFICULTY_ROOT, name);
    }

    public synchronized List<String> listDifficultyTemplates() {
        return listTemplates(DIFFICULTY_ROOT);
    }

    public synchronized boolean saveTimeTemplate(String name, int[] values) {
        if (!isUserName(name) || values == null
                || values.length != PkwsRangeConfig.TIME_CONFIG_COUNT) {
            return false;
        }
        for (int value : values) {
            if (!PkwsRangeConfig.isValidTimeSeconds(value)) {
                return false;
            }
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = getOrCreateRoot(config, TIME_ROOT);
        String existingKey = findKey(root, name);
        String key = existingKey == null ? encodedKey(name) : existingKey;
        root.set(key, null);
        ConfigurationSection template = root.createSection(key);
        template.set("name", name);
        template.set("time_model", STAGE_TIME_MODEL);
        for (int slot = 1; slot <= PkwsRangeConfig.TIME_CONFIG_COUNT; slot++) {
            template.set(timePath(slot), values[slot - 1]);
        }
        return saveConfig(config);
    }

    public synchronized int[] loadTimeTemplate(String name) {
        if (isDefault(name)) {
            return PkwsRangeConfig.defaultTimeValues();
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = config.getConfigurationSection(TIME_ROOT);
        ConfigurationSection template = findTemplate(root, name);
        if (template == null) {
            return null;
        }
        boolean stageModel = STAGE_TIME_MODEL.equals(template.getString("time_model"));
        int[] values = new int[PkwsRangeConfig.TIME_CONFIG_COUNT];
        for (int slot = 1; slot <= PkwsRangeConfig.TIME_CONFIG_COUNT; slot++) {
            values[slot - 1] = template.getInt(
                    stageModel ? timePath(slot) : legacyTimePath(slot), -1);
        }
        for (int value : values) {
            if (!PkwsRangeConfig.isValidTimeSeconds(value)) {
                return null;
            }
        }
        if (!stageModel) {
            values = PkwsRangeConfig.absoluteToDurations(values);
            template.set("time_model", STAGE_TIME_MODEL);
            template.set("finish", null);
            for (int slot = 1; slot < PkwsRangeConfig.FINISH_TIME_SLOT; slot++) {
                template.set("node" + slot, null);
            }
            for (int slot = 1; slot <= PkwsRangeConfig.TIME_CONFIG_COUNT; slot++) {
                template.set(timePath(slot), values[slot - 1]);
            }
            saveConfig(config);
            logger.info("[PKWS] 已将旧时间模板 " + name + " 从绝对时刻迁移为 8 段独立时长。");
        }
        return values;
    }

    public synchronized boolean deleteTimeTemplate(String name) {
        return deleteTemplate(TIME_ROOT, name);
    }

    public synchronized List<String> listTimeTemplates() {
        return listTemplates(TIME_ROOT);
    }

    public static boolean isDefault(String name) {
        return name != null && name.equalsIgnoreCase("default");
    }

    private boolean deleteTemplate(String rootName, String name) {
        if (!isUserName(name)) {
            return false;
        }
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = config.getConfigurationSection(rootName);
        String key = findKey(root, name);
        if (key == null) {
            return false;
        }
        root.set(key, null);
        if (root.getKeys(false).isEmpty()) {
            config.set(rootName, null);
        }
        return saveConfig(config);
    }

    private List<String> listTemplates(String rootName) {
        List<String> names = new ArrayList<>();
        names.add("default");
        YamlConfiguration config = loadConfig();
        ConfigurationSection root = config.getConfigurationSection(rootName);
        if (root != null) {
            for (String key : root.getKeys(false)) {
                String name = root.getString(key + ".name");
                if (name != null && !name.isBlank() && !isDefault(name)) {
                    names.add(name);
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private ConfigurationSection findTemplate(ConfigurationSection root, String name) {
        if (root == null || !isUserName(name)) {
            return null;
        }
        String key = findKey(root, name);
        return key == null ? null : root.getConfigurationSection(key);
    }

    private String findKey(ConfigurationSection root, String name) {
        if (root == null || name == null) {
            return null;
        }
        for (String key : root.getKeys(false)) {
            String stored = root.getString(key + ".name");
            if (name.equalsIgnoreCase(stored)) {
                return key;
            }
        }
        return null;
    }

    private ConfigurationSection getOrCreateRoot(YamlConfiguration config, String rootName) {
        ConfigurationSection root = config.getConfigurationSection(rootName);
        return root == null ? config.createSection(rootName) : root;
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
            logger.warning("Failed to save pkws-templates.yml: " + ex.getMessage());
            return false;
        }
    }

    private String encodedKey(String name) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String timePath(int slot) {
        return "stage" + slot + ".seconds";
    }

    private static String legacyTimePath(int slot) {
        return slot == PkwsRangeConfig.FINISH_TIME_SLOT
                ? "finish.seconds" : "node" + slot + ".seconds";
    }

    private boolean isValidRanges(int[] values) {
        if (values == null || values.length % 2 != 0) {
            return false;
        }
        for (int index = 0; index < values.length; index += 2) {
            if (!PkwsRangeConfig.isValidRange(values[index], values[index + 1])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUserName(String name) {
        return name != null && !name.isBlank() && name.length() <= 64
                && !name.equalsIgnoreCase("default");
    }
}
