package club.sitmc.sitParkourWarrior.map;

/**
 * PKWS level orientation.  A missing value is represented by {@code null}
 * on {@link ParkourMap}; this enum only contains the two configured values.
 */
public enum PkwsOrientation {
    NS,
    EW;

    public static PkwsOrientation fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String toConfigString() {
        return name();
    }
}
