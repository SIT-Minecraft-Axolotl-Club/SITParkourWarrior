package club.sitmc.sitParkourWarrior.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages countdown-scoring.yml with auto-generated defaults.
 */
public class CountdownScoringConfig {

    private final File file;
    private int stonePerMedal = 3;
    private final Map<Integer, Integer> bronzeCumulative = new LinkedHashMap<>();
    private final Map<Integer, Integer> silverCumulative = new LinkedHashMap<>();
    private final Map<Integer, Integer> goldCumulative = new LinkedHashMap<>();
    private double easyMultiplier = 0.35;
    private double normalMultiplier = 0.6;
    private double hardMultiplier = 1.0;

    public CountdownScoringConfig(File dataFolder) {
        this.file = new File(dataFolder, "countdown-scoring.yml");
        if (!file.exists()) {
            writeDefaults();
        }
        load();
    }

    private void writeDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("stone-per-medal", 3);

        config.set("bronze-cumulative.1", 10);
        config.set("bronze-cumulative.2", 20);
        config.set("bronze-cumulative.3", 35);
        config.set("bronze-cumulative.4", 55);
        config.set("bronze-cumulative.5", 85);

        config.set("silver-cumulative.1", 20);
        config.set("silver-cumulative.2", 45);
        config.set("silver-cumulative.3", 80);
        config.set("silver-cumulative.4", 115);
        config.set("silver-cumulative.5", 180);

        config.set("gold-cumulative.1", 30);
        config.set("gold-cumulative.2", 70);
        config.set("gold-cumulative.3", 120);
        config.set("gold-cumulative.4", 180);
        config.set("gold-cumulative.5", 290);

        config.set("end-tier-multiplier.easy", 0.35);
        config.set("end-tier-multiplier.normal", 0.6);
        config.set("end-tier-multiplier.hard", 1.0);

        try {
            config.save(file);
        } catch (IOException e) {
            System.err.println("[SITPKW] Failed to create countdown-scoring.yml: " + e.getMessage());
        }
    }

    /** Reload from disk (called on /sitpkw reload). */
    public void load() {
        bronzeCumulative.clear();
        silverCumulative.clear();
        goldCumulative.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        stonePerMedal = config.getInt("stone-per-medal", 3);

        loadCumulative(config, "bronze-cumulative", bronzeCumulative);
        loadCumulative(config, "silver-cumulative", silverCumulative);
        loadCumulative(config, "gold-cumulative", goldCumulative);

        easyMultiplier = config.getDouble("end-tier-multiplier.easy", 0.35);
        normalMultiplier = config.getDouble("end-tier-multiplier.normal", 0.6);
        hardMultiplier = config.getDouble("end-tier-multiplier.hard", 1.0);
    }

    private void loadCumulative(YamlConfiguration config, String path, Map<Integer, Integer> target) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                int n = Integer.parseInt(key);
                int val = section.getInt(key);
                target.put(n, val);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ---- queries ----

    public int getStoneScore(int count) {
        return count * stonePerMedal;
    }

    public int getBronzeScore(int count) {
        return getCumulative(bronzeCumulative, count);
    }

    public int getSilverScore(int count) {
        return getCumulative(silverCumulative, count);
    }

    public int getGoldScore(int count) {
        return getCumulative(goldCumulative, count);
    }

    private int getCumulative(Map<Integer, Integer> table, int count) {
        if (count <= 0) return 0;
        // Find the highest key <= count (cap at max key)
        int best = 0;
        for (Map.Entry<Integer, Integer> e : table.entrySet()) {
            if (e.getKey() <= count && e.getKey() > best) {
                best = e.getKey();
            }
        }
        if (best == 0) {
            // count below all keys → use smallest key
            for (int k : table.keySet()) {
                if (best == 0 || k < best) best = k;
            }
        }
        return table.getOrDefault(best, 0);
    }

    public double getEndTierMultiplier(String endTier) {
        if (endTier == null) return 0.0;
        switch (endTier.toLowerCase()) {
            case "hard":   return hardMultiplier;
            case "normal": return normalMultiplier;
            default:       return easyMultiplier;
        }
    }
}
