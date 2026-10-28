package club.sitmc.sitParkourWarrior.config;

/**
 * Per-world timing mode for PKW courses.
 */
public enum TimingMode {
    COUNTUP,
    COUNTDOWN;

    /**
     * Parse from config string. Returns COUNTUP for null or unknown values.
     */
    public static TimingMode fromString(String value) {
        if (value == null) return COUNTUP;
        switch (value.trim().toLowerCase()) {
            case "countdown": return COUNTDOWN;
            default:          return COUNTUP;
        }
    }

    public String toConfigString() {
        return name().toLowerCase();
    }
}
