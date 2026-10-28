package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.editor.StateListGui;
import club.sitmc.sitParkourWarrior.map.MapManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Delegates inventory events to {@link StateListGui} when the open inventory
 * is our state-list chest GUI (identified by {@link StateListGui.StateListHolder}).
 * <p>
 * Follows the same single-listener pattern as {@link EditPaperRenameListener}.
 */
public class StateListListener implements Listener {

    private final EditSessionManager editSessionManager;
    private final MapManager mapManager;

    public StateListListener(EditSessionManager editSessionManager, MapManager mapManager) {
        this.editSessionManager = editSessionManager;
        this.mapManager = mapManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StateListGui.StateListHolder)) return;
        StateListGui.handleClick(event, editSessionManager, mapManager);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof StateListGui.StateListHolder)) return;
        // No drag-sorting allowed — only left-click pickup/swap/place.
        event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof StateListGui.StateListHolder)) return;
        StateListGui.handleClose(event, editSessionManager, mapManager);
    }
}
