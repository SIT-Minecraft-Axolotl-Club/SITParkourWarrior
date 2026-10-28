package club.sitmc.sitParkourWarrior.map;

/**
 * Independent difficulty tier for GLOBAL_END nodes.
 * Distinct from {@link Difficulty} (course difficulty) — this labels
 * which route the global end belongs to (easy / normal / hard).
 */
public enum EndTier {
    EASY,
    NORMAL,
    HARD;

    public static EndTier fromString(String value) {
        if (value == null) {
            return NORMAL;
        }
        switch (value.trim().toLowerCase()) {
            case "easy":   return EASY;
            case "normal": return NORMAL;
            case "hard":   return HARD;
            default:       return NORMAL;
        }
    }

    public String toConfigString() {
        return name().toLowerCase();
    }
}
