package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Persistent PKWS difficulty and per-stage time configuration.
 *
 * <p>The two orientations deliberately live in separate arrays.  The loader
 * accepts the original single-range/single-minute layout and writes the new
 * canonical layout after copying the legacy values to both orientations.</p>
 *
 * <p>Two countdown values live here and are deliberately independent:</p>
 * <ul>
 *   <li>{@code gate-open-delay-seconds} — the start-opening countdown. Its
 *       default is 10 seconds and an administrator may set any positive whole
 *       number of seconds.</li>
 *   <li>{@code relay-gate-open-delay-seconds} — the wait before a relay gate
 *       opens. It keeps its original 3..5 second range.</li>
 * </ul>
 */
public final class PkwsRangeConfig {
    public static final int SEGMENT_COUNT = 8;
    public static final int POSITION_COUNT = 3;
    public static final int TIME_CONFIG_COUNT = 8;
    public static final int FINISH_TIME_SLOT = 8;

    private static final int[] DEFAULT_MIN = {1, 2, 4, 5, 7, 9, 11, 13};
    private static final int[] DEFAULT_MAX = {3, 5, 7, 9, 11, 13, 15, 16};
    /** Eight independent stage durations, in seconds. */
    private static final int[] DEFAULT_TIME_SECONDS = {
            180, 180, 180, 180, 180, 180, 180, 180
    };
    /** Default seconds of the PKWS start-opening countdown (free value). */
    private static final int DEFAULT_START_COUNTDOWN_SECONDS = 10;
    /** The relay gate wait is a separate value and keeps its 3..5 range. */
    private static final int DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS = 5;
    private static final int MIN_RELAY_GATE_OPEN_DELAY_SECONDS = 3;
    private static final int MAX_RELAY_GATE_OPEN_DELAY_SECONDS = 5;
    private static final int RANGE_VALUE_COUNT = SEGMENT_COUNT * POSITION_COUNT * 2;

    private final File file;
    private final Logger logger;
    private final int[][][] min = new int[PkwsOrientation.values().length][SEGMENT_COUNT][POSITION_COUNT];
    private final int[][][] max = new int[PkwsOrientation.values().length][SEGMENT_COUNT][POSITION_COUNT];
    /** Slots 1..8 are independent stage durations. */
    private final int[][] timeSeconds = new int[PkwsOrientation.values().length][TIME_CONFIG_COUNT];
    private int startCountdownSeconds = DEFAULT_START_COUNTDOWN_SECONDS;
    private int relayGateOpenDelaySeconds = DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS;

    public PkwsRangeConfig(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "pkws.yml");
        this.logger = logger;
        load();
    }

    public synchronized void reload() {
        load();
    }

    public synchronized DifficultyRange getRange(PkwsOrientation orientation,
                                                  int segment, int position) {
        checkOrientation(orientation);
        checkIndex(segment, position);
        int index = orientation.ordinal();
        return new DifficultyRange(min[index][segment - 1][position - 1],
                max[index][segment - 1][position - 1]);
    }

    /** Compatibility overload: old integrations read the NS set. */
    public synchronized DifficultyRange getRange(int segment, int position) {
        return getRange(PkwsOrientation.NS, segment, position);
    }

    public synchronized int getMin(PkwsOrientation orientation, int segment, int position) {
        return getRange(orientation, segment, position).min();
    }

    public synchronized int getMax(PkwsOrientation orientation, int segment, int position) {
        return getRange(orientation, segment, position).max();
    }

    public synchronized int getMin(int segment, int position) {
        return getMin(PkwsOrientation.NS, segment, position);
    }

    public synchronized int getMax(int segment, int position) {
        return getMax(PkwsOrientation.NS, segment, position);
    }

    public synchronized int getTimeSeconds(PkwsOrientation orientation, int slot) {
        checkOrientation(orientation);
        checkTimeSlot(slot);
        return timeSeconds[orientation.ordinal()][slot - 1];
    }

    public synchronized int getForceOpenSeconds(PkwsOrientation orientation, int node) {
        checkNode(node);
        return getTimeSeconds(orientation, node);
    }

    public synchronized int getFinishSeconds(PkwsOrientation orientation) {
        return getTimeSeconds(orientation, FINISH_TIME_SLOT);
    }

    /**
     * Seconds of the PKWS start-opening countdown used when a player steps onto
     * a closed start. The value is free (any positive whole number of seconds)
     * and never influences the relay gate wait.
     */
    public synchronized int getStartCountdownSeconds() {
        return startCountdownSeconds;
    }

    public synchronized boolean setStartCountdownSeconds(int seconds) {
        if (!isValidStartCountdownSeconds(seconds)) {
            return false;
        }
        startCountdownSeconds = seconds;
        return save();
    }

    /** The start countdown accepts any positive whole number of seconds. */
    public static boolean isValidStartCountdownSeconds(int seconds) {
        return seconds >= 1;
    }

    /**
     * Seconds the relay gate waits before it opens. This value stays in its
     * original 3..5 range and is independent of the start countdown.
     */
    public synchronized int getRelayGateOpenDelaySeconds() {
        return relayGateOpenDelaySeconds;
    }

    public static boolean isValidRelayGateOpenDelaySeconds(int seconds) {
        return seconds >= MIN_RELAY_GATE_OPEN_DELAY_SECONDS
                && seconds <= MAX_RELAY_GATE_OPEN_DELAY_SECONDS;
    }

    /**
     * Compatibility accessor for old integrations that only knew one countdown.
     *
     * @deprecated the start countdown and the relay gate wait are now separate
     *             values; use {@link #getStartCountdownSeconds()} for the start
     *             and {@link #getRelayGateOpenDelaySeconds()} for the relay.
     */
    @Deprecated
    public synchronized int getGateOpenDelaySeconds() {
        return startCountdownSeconds;
    }

    /**
     * Compatibility accessor for old integrations that only knew one countdown.
     *
     * @deprecated use {@link #isValidStartCountdownSeconds(int)} or
     *             {@link #isValidRelayGateOpenDelaySeconds(int)}.
     */
    @Deprecated
    public static boolean isValidGateOpenDelaySeconds(int seconds) {
        return isValidRelayGateOpenDelaySeconds(seconds);
    }

    /**
     * Compatibility accessor for the pre-v2 API.  New callers must use
     * {@link #getForceOpenSeconds(PkwsOrientation, int)}.
     */
    @Deprecated
    public synchronized int getForceOpenMinutes(int node) {
        return getForceOpenSeconds(PkwsOrientation.NS, node) / 60;
    }

    public synchronized boolean setRange(PkwsOrientation orientation, int segment,
                                          int position, int newMin, int newMax) {
        checkOrientation(orientation);
        if (!isValidIndex(segment, position) || !isValidRange(newMin, newMax)) {
            return false;
        }
        int index = orientation.ordinal();
        min[index][segment - 1][position - 1] = newMin;
        max[index][segment - 1][position - 1] = newMax;
        return save();
    }

    /** Compatibility overload: old integrations update the NS set. */
    public synchronized boolean setRange(int segment, int position, int newMin, int newMax) {
        return setRange(PkwsOrientation.NS, segment, position, newMin, newMax);
    }

    public synchronized boolean setTimeSeconds(PkwsOrientation orientation, int slot, int seconds) {
        checkOrientation(orientation);
        if (!isValidTimeSlot(slot) || !isValidTimeSeconds(seconds)) {
            return false;
        }
        timeSeconds[orientation.ordinal()][slot - 1] = seconds;
        return save();
    }

    public synchronized boolean setForceOpenSeconds(PkwsOrientation orientation, int node,
                                                     int seconds) {
        return setTimeSeconds(orientation, node, seconds);
    }

    public synchronized boolean setFinishSeconds(PkwsOrientation orientation, int seconds) {
        return setTimeSeconds(orientation, FINISH_TIME_SLOT, seconds);
    }

    /** Compatibility setter for old integrations; the old unit was minutes. */
    @Deprecated
    public synchronized boolean setForceOpenMinutes(int node, int minutes) {
        if (!isValidNode(node) || minutes < 0 || minutes > Integer.MAX_VALUE / 60) {
            return false;
        }
        return setForceOpenSeconds(PkwsOrientation.NS, node, minutes * 60);
    }

    /** Restores both orientation sets and persists them. */
    public synchronized boolean resetDefaults() {
        for (PkwsOrientation orientation : PkwsOrientation.values()) {
            applyRangeDefaults(orientation);
            applyTimeDefaults(orientation);
        }
        return save();
    }

    public synchronized boolean resetDefaults(PkwsOrientation orientation) {
        checkOrientation(orientation);
        applyRangeDefaults(orientation);
        return save();
    }

    public synchronized boolean resetTimeDefaults(PkwsOrientation orientation) {
        checkOrientation(orientation);
        applyTimeDefaults(orientation);
        return save();
    }

    public synchronized int[] snapshotRanges(PkwsOrientation orientation) {
        checkOrientation(orientation);
        int[] values = new int[RANGE_VALUE_COUNT];
        int cursor = 0;
        int index = orientation.ordinal();
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            for (int position = 0; position < POSITION_COUNT; position++) {
                values[cursor++] = min[index][segment][position];
                values[cursor++] = max[index][segment][position];
            }
        }
        return values;
    }

    public synchronized boolean applyRanges(PkwsOrientation orientation, int[] values) {
        checkOrientation(orientation);
        if (values == null || values.length != RANGE_VALUE_COUNT) {
            return false;
        }
        int cursor = 0;
        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= POSITION_COUNT; position++) {
                int newMin = values[cursor++];
                int newMax = values[cursor++];
                if (!isValidRange(newMin, newMax)) {
                    return false;
                }
            }
        }
        int index = orientation.ordinal();
        cursor = 0;
        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            for (int position = 0; position < POSITION_COUNT; position++) {
                min[index][segment][position] = values[cursor++];
                max[index][segment][position] = values[cursor++];
            }
        }
        return save();
    }

    public synchronized int[] snapshotTimes(PkwsOrientation orientation) {
        checkOrientation(orientation);
        return timeSeconds[orientation.ordinal()].clone();
    }

    public synchronized boolean applyTimes(PkwsOrientation orientation, int[] values) {
        checkOrientation(orientation);
        if (values == null || values.length != TIME_CONFIG_COUNT) {
            return false;
        }
        for (int value : values) {
            if (!isValidTimeSeconds(value)) {
                return false;
            }
        }
        System.arraycopy(values, 0, timeSeconds[orientation.ordinal()], 0, TIME_CONFIG_COUNT);
        return save();
    }

    public static int[] defaultRangeValues() {
        int[] values = new int[RANGE_VALUE_COUNT];
        int cursor = 0;
        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= POSITION_COUNT; position++) {
                values[cursor++] = defaultMin(segment);
                values[cursor++] = defaultMax(segment);
            }
        }
        return values;
    }

    public static int[] defaultTimeValues() {
        return DEFAULT_TIME_SECONDS.clone();
    }

    public static boolean isValidRange(int min, int max) {
        return min >= 1 && max <= 16 && min <= max;
    }

    public static boolean isValidTimeSeconds(int seconds) {
        return seconds >= 0;
    }

    public static int defaultMin(int segment) {
        if (segment < 1 || segment > SEGMENT_COUNT) {
            throw new IllegalArgumentException("segment must be 1..8");
        }
        return DEFAULT_MIN[segment - 1];
    }

    public static int defaultMax(int segment) {
        if (segment < 1 || segment > SEGMENT_COUNT) {
            throw new IllegalArgumentException("segment must be 1..8");
        }
        return DEFAULT_MAX[segment - 1];
    }

    public static int defaultTimeSeconds(int slot) {
        checkTimeSlot(slot);
        return DEFAULT_TIME_SECONDS[slot - 1];
    }

    private void load() {
        applyDefaults();
        startCountdownSeconds = DEFAULT_START_COUNTDOWN_SECONDS;
        relayGateOpenDelaySeconds = DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS;
        if (!file.exists()) {
            save();
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        boolean hasLegacyRanges = hasLegacyRanges(config);
        boolean hasLegacyTimes = hasLegacyTimes(config);
        int version = config.getInt("version", 0);

        for (PkwsOrientation orientation : PkwsOrientation.values()) {
            int orientationIndex = orientation.ordinal();
            for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
                for (int position = 1; position <= POSITION_COUNT; position++) {
                    String newBase = rangePath(orientation, segment, position);
                    String oldBase = legacyRangePath(segment, position);
                    Object rawMin = config.get(newBase + ".min");
                    Object rawMax = config.get(newBase + ".max");
                    boolean fromLegacy = rawMin == null && rawMax == null
                            && (config.get(oldBase + ".min") != null
                            || config.get(oldBase + ".max") != null);
                    if (fromLegacy) {
                        rawMin = config.get(oldBase + ".min");
                        rawMax = config.get(oldBase + ".max");
                    }
                    int fallbackMin = defaultMin(segment);
                    int fallbackMax = defaultMax(segment);
                    int loadedMin = readInt(rawMin, newBase + ".min", fallbackMin);
                    int loadedMax = readInt(rawMax, newBase + ".max", fallbackMax);
                    if (!isValidRange(loadedMin, loadedMax)) {
                        logger.warning("PKWS range invalid at " + orientation + " segment "
                                + segment + ", position " + position + "; using default ["
                                + fallbackMin + "," + fallbackMax + "].");
                        loadedMin = fallbackMin;
                        loadedMax = fallbackMax;
                        changed = true;
                    }
                    if (rawMin == null || rawMax == null || fromLegacy
                            || !isIntegral(rawMin) || !isIntegral(rawMax)) {
                        changed = true;
                    }
                    min[orientationIndex][segment - 1][position - 1] = loadedMin;
                    max[orientationIndex][segment - 1][position - 1] = loadedMax;
                }
            }

            if (version >= 3 && hasStageTimes(config, orientation)) {
                for (int stage = 1; stage <= TIME_CONFIG_COUNT; stage++) {
                    String path = timePath(orientation, stage);
                    Object raw = config.get(path);
                    int fallback = defaultTimeSeconds(stage);
                    int loaded = readInt(raw, path, fallback);
                    if (!isValidTimeSeconds(loaded)) {
                        logger.warning("PKWS stage duration invalid at " + orientation
                                + " stage " + stage + "; using default " + fallback + " seconds.");
                        loaded = fallback;
                        changed = true;
                    }
                    if (raw == null || !isIntegral(raw)) {
                        changed = true;
                    }
                    timeSeconds[orientationIndex][stage - 1] = loaded;
                }
            } else {
                int[] absoluteSeconds = loadLegacyAbsoluteTimes(config, orientation);
                int[] durations = absoluteToDurations(absoluteSeconds);
                System.arraycopy(durations, 0, timeSeconds[orientationIndex], 0,
                        TIME_CONFIG_COUNT);
                logger.info("[PKWS] 已迁移 " + orientation.name()
                        + " 绝对时刻 " + Arrays.toString(absoluteSeconds)
                        + " 为分段时长 " + Arrays.toString(durations) + "（秒）。");
                changed = true;
            }
        }

        Object rawRelayDelay = config.get("relay-gate-open-delay-seconds");
        Object rawStartDelay = config.get("gate-open-delay-seconds");
        if (rawRelayDelay == null && rawStartDelay != null) {
            // Legacy layout: the single key used to hold the relay gate wait.
            // Move that value to the relay key so the relay gate keeps its
            // original wait, and let the start countdown use its own default.
            int legacyRelayDelay = readInt(rawStartDelay, "gate-open-delay-seconds",
                    DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS);
            if (isValidRelayGateOpenDelaySeconds(legacyRelayDelay)) {
                relayGateOpenDelaySeconds = legacyRelayDelay;
                rawStartDelay = null;
                changed = true;
            }
        }
        if (rawRelayDelay != null) {
            int loadedRelayDelay = readInt(rawRelayDelay, "relay-gate-open-delay-seconds",
                    DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS);
            if (!isValidRelayGateOpenDelaySeconds(loadedRelayDelay)) {
                logger.warning("PKWS relay-gate-open-delay-seconds must be 3..5; using default "
                        + DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS + ".");
                loadedRelayDelay = DEFAULT_RELAY_GATE_OPEN_DELAY_SECONDS;
                changed = true;
            }
            relayGateOpenDelaySeconds = loadedRelayDelay;
            if (!isIntegral(rawRelayDelay)) {
                changed = true;
            }
        }
        if (rawStartDelay != null) {
            int loadedStartDelay = readInt(rawStartDelay, "gate-open-delay-seconds",
                    DEFAULT_START_COUNTDOWN_SECONDS);
            if (!isValidStartCountdownSeconds(loadedStartDelay)) {
                logger.warning("PKWS gate-open-delay-seconds must be a positive integer;"
                        + " using default " + DEFAULT_START_COUNTDOWN_SECONDS + ".");
                loadedStartDelay = DEFAULT_START_COUNTDOWN_SECONDS;
                changed = true;
            }
            startCountdownSeconds = loadedStartDelay;
            if (!isIntegral(rawStartDelay)) {
                changed = true;
            }
        }
        if (rawRelayDelay == null || rawStartDelay == null) {
            changed = true;
        }

        // These flags make the migration intent explicit and ensure a legacy
        // file is rewritten even when every legacy value happened to be valid.
        if (hasLegacyRanges || hasLegacyTimes || version < 3) {
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private void applyDefaults() {
        for (PkwsOrientation orientation : PkwsOrientation.values()) {
            applyRangeDefaults(orientation);
            applyTimeDefaults(orientation);
        }
    }

    private void applyRangeDefaults(PkwsOrientation orientation) {
        int index = orientation.ordinal();
        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= POSITION_COUNT; position++) {
                min[index][segment - 1][position - 1] = defaultMin(segment);
                max[index][segment - 1][position - 1] = defaultMax(segment);
            }
        }
    }

    private void applyTimeDefaults(PkwsOrientation orientation) {
        System.arraycopy(DEFAULT_TIME_SECONDS, 0, timeSeconds[orientation.ordinal()],
                0, TIME_CONFIG_COUNT);
    }

    private int readInt(Object raw, String path, int fallback) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value == Math.rint(value)
                    && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return number.intValue();
            }
            logger.warning("PKWS value is not an integer at " + path + ": " + raw);
            return fallback;
        }
        if (raw instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                logger.warning("PKWS value is not an integer at " + path + ": " + raw);
            }
        } else if (raw != null) {
            logger.warning("PKWS value is not an integer at " + path + ": " + raw);
        }
        return fallback;
    }

    private boolean isIntegral(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) && value == Math.rint(value);
        }
        if (raw instanceof String string) {
            try {
                Integer.parseInt(string.trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean save() {
        // Keep unrelated plugin settings that may share pkws.yml.  The old
        // range/time sections are removed after their values have already been
        // copied into the canonical orientation-specific sections.
        YamlConfiguration config = file.isFile()
                ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        config.set("version", 3);
        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            config.set("ranges.segment" + segment, null);
        }
        for (int node = 1; node <= 7; node++) {
            config.set("force-open.node" + node, null);
        }
        config.set("time", null);
        config.set("gate-open-delay-seconds", startCountdownSeconds);
        config.set("relay-gate-open-delay-seconds", relayGateOpenDelaySeconds);
        for (PkwsOrientation orientation : PkwsOrientation.values()) {
            int index = orientation.ordinal();
            for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
                for (int position = 1; position <= POSITION_COUNT; position++) {
                    String base = rangePath(orientation, segment, position);
                    config.set(base + ".min", min[index][segment - 1][position - 1]);
                    config.set(base + ".max", max[index][segment - 1][position - 1]);
                }
            }
            for (int slot = 1; slot <= TIME_CONFIG_COUNT; slot++) {
                config.set(timePath(orientation, slot), timeSeconds[index][slot - 1]);
            }
        }
        file.getParentFile().mkdirs();
        try {
            config.save(file);
            return true;
        } catch (IOException ex) {
            logger.warning("Failed to save pkws.yml: " + ex.getMessage());
            return false;
        }
    }

    private static String rangePath(PkwsOrientation orientation, int segment, int position) {
        return "ranges." + orientation.name() + ".segment" + segment + ".position" + position;
    }

    private static String legacyRangePath(int segment, int position) {
        return "ranges.segment" + segment + ".position" + position;
    }

    private static String timePath(PkwsOrientation orientation, int slot) {
        return "stage-time." + orientation.name() + ".stage" + slot + ".seconds";
    }

    private static String versionTwoTimePath(PkwsOrientation orientation, int slot) {
        return "time." + orientation.name() + "." + timeName(slot) + ".seconds";
    }

    private static String legacyTimePath(int node) {
        return "force-open.node" + node + ".minutes";
    }

    private static String timeName(int slot) {
        return slot == FINISH_TIME_SLOT ? "finish" : "node" + slot;
    }

    private boolean hasStageTimes(YamlConfiguration config, PkwsOrientation orientation) {
        for (int stage = 1; stage <= TIME_CONFIG_COUNT; stage++) {
            if (config.contains(timePath(orientation, stage))) {
                return true;
            }
        }
        return false;
    }

    private int[] loadLegacyAbsoluteTimes(YamlConfiguration config,
                                          PkwsOrientation orientation) {
        int[] values = new int[TIME_CONFIG_COUNT];
        for (int slot = 1; slot <= TIME_CONFIG_COUNT; slot++) {
            String v2Path = versionTwoTimePath(orientation, slot);
            Object raw = config.get(v2Path);
            if (raw == null && slot <= 7) {
                Object minutesRaw = config.get(legacyTimePath(slot));
                if (minutesRaw != null) {
                    int fallbackMinutes = slot * 3;
                    int minutes = readInt(minutesRaw, legacyTimePath(slot), fallbackMinutes);
                    long seconds = Math.max(0L, (long) minutes * 60L);
                    raw = seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
                }
            }
            int fallbackAbsolute = slot * 180;
            int value = readInt(raw, v2Path, fallbackAbsolute);
            values[slot - 1] = Math.max(0, value);
        }
        return values;
    }

    public static int[] absoluteToDurations(int[] absoluteSeconds) {
        if (absoluteSeconds == null || absoluteSeconds.length != TIME_CONFIG_COUNT) {
            throw new IllegalArgumentException("absoluteSeconds must contain 8 entries");
        }
        int[] durations = new int[TIME_CONFIG_COUNT];
        long previous = 0L;
        for (int index = 0; index < TIME_CONFIG_COUNT; index++) {
            long current = Math.max(0L, absoluteSeconds[index]);
            long delta = Math.max(0L, current - previous);
            durations[index] = delta > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) delta;
            previous = current;
        }
        return durations;
    }

    private static boolean hasLegacyRanges(YamlConfiguration config) {
        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= POSITION_COUNT; position++) {
                if (config.contains(legacyRangePath(segment, position) + ".min")
                        || config.contains(legacyRangePath(segment, position) + ".max")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasLegacyTimes(YamlConfiguration config) {
        for (int node = 1; node <= 7; node++) {
            if (config.contains(legacyTimePath(node))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidIndex(int segment, int position) {
        return segment >= 1 && segment <= SEGMENT_COUNT
                && position >= 1 && position <= POSITION_COUNT;
    }

    private static boolean isValidNode(int node) {
        return node >= 1 && node <= 7;
    }

    private static boolean isValidTimeSlot(int slot) {
        return slot >= 1 && slot <= TIME_CONFIG_COUNT;
    }

    private static void checkOrientation(PkwsOrientation orientation) {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation must be NS or EW");
        }
    }

    private static void checkNode(int node) {
        if (!isValidNode(node)) {
            throw new IllegalArgumentException("node must be 1..7");
        }
    }

    private static void checkTimeSlot(int slot) {
        if (!isValidTimeSlot(slot)) {
            throw new IllegalArgumentException("time slot must be 1..8");
        }
    }

    private static void checkIndex(int segment, int position) {
        if (!isValidIndex(segment, position)) {
            throw new IllegalArgumentException("segment must be 1..8 and position must be 1..3");
        }
    }

    public record DifficultyRange(int min, int max) {
        @Override
        public String toString() {
            return "[" + min + "-" + max + "]";
        }
    }
}
