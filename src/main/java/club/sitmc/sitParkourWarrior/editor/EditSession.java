package club.sitmc.sitParkourWarrior.editor;

import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.Region;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-player editing session for the dynamic-level editor (阶段1).
 * Lives only in memory; one player edits at most one map at a time.
 * <p>
 * Invariant: all mutations to map data (dirty edits, state switches, interval
 * changes) only ever happen while {@link #playing} is false.
 */
public class EditSession {
    private final String mapId;
    /** The state the player's edits currently belong to; null if the map has no states yet. */
    private DynamicState currentState;
    /**
     * The temporary in-world working copy generated at the player's feet on entry
     * (same size as the map's template {@link club.sitmc.sitParkourWarrior.map.ParkourMap#getRegion()},
     * but positioned wherever the player was standing). Null if the map has no
     * template region yet, in which case nothing is auto-generated. This is purely
     * session-local and is never written back into the map's own region field.
     */
    private Region editRegion;
    private boolean playing = false;
    private boolean dirty = false;
    /** Index into the map's state list of the frame most recently pasted during preview playback. */
    private int previewIndex = 0;
    private BukkitTask previewTask;

    public EditSession(String mapId, DynamicState currentState) {
        this.mapId = mapId;
        this.currentState = currentState;
    }

    public String getMapId() {
        return mapId;
    }

    public DynamicState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(DynamicState currentState) {
        this.currentState = currentState;
    }

    public Region getEditRegion() {
        return editRegion;
    }

    public void setEditRegion(Region editRegion) {
        this.editRegion = editRegion;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public int getPreviewIndex() {
        return previewIndex;
    }

    public void setPreviewIndex(int previewIndex) {
        this.previewIndex = previewIndex;
    }

    public BukkitTask getPreviewTask() {
        return previewTask;
    }

    public void setPreviewTask(BukkitTask previewTask) {
        this.previewTask = previewTask;
    }
}
