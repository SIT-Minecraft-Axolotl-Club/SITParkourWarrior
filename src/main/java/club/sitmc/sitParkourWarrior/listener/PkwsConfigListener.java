package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsConfigGui;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Routes only the independent PKWS administrator inventories. */
public final class PkwsConfigListener implements Listener {

    private final MapManager mapManager;

    public PkwsConfigListener(MapManager mapManager) {
        this.mapManager = mapManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!isPkwsInventory(event.getInventory().getHolder())) return;
        PkwsConfigGui.handleClick(event, mapManager);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!isPkwsInventory(event.getInventory().getHolder())) return;
        PkwsConfigGui.handleDrag(event);
    }

    private boolean isPkwsInventory(Object holder) {
        return holder instanceof PkwsConfigGui.OverviewHolder
                || holder instanceof PkwsConfigGui.CategoryHolder;
    }
}
