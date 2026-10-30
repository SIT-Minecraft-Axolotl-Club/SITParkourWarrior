package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.editor.EditSession;
import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Marks a player's paused edit session dirty when they place/break a block
 * inside the session's temporary edit-region copy (generated at their feet on
 * entry, see {@link EditSessionManager#startSession}). Plugin-driven pastes go
 * through WorldEdit directly and never fire these Bukkit events, so this can't
 * misfire on our own state-switch/playback pastes.
 */
public class EditRegionChangeListener implements Listener {
    private final SelectionManager selectionManager;
    private final EditSessionManager editSessionManager;

    public EditRegionChangeListener(SelectionManager selectionManager, EditSessionManager editSessionManager) {
        this.selectionManager = selectionManager;
        this.editSessionManager = editSessionManager;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        checkDirty(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        checkDirty(event.getPlayer(), event.getBlock().getLocation());
    }

    private void checkDirty(Player player, Location location) {
        EditSession session = editSessionManager.getSession(player);
        if (session == null || session.isPlaying()) {
            return;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null || !map.getId().equalsIgnoreCase(session.getMapId())) {
            return;
        }
        // Edits happen in the temporary copy generated at the player's feet,
        // not at the map's original template region location.
        Region region = session.getEditRegion();
        if (region == null || !region.contains(location)) {
            return;
        }
        editSessionManager.markDirty(player);
    }
}
