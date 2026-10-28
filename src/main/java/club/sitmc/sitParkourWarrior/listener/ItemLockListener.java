package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Prevents PKW interactive items from being dropped, moved, or swapped.
 */
public class ItemLockListener implements Listener {

    private final PkwWorldManager pkwWorldManager;

    public ItemLockListener(PkwWorldManager pkwWorldManager) {
        this.pkwWorldManager = pkwWorldManager;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!isProtectedWorld(event.getPlayer())) return;
        if (ItemUtil.isPkwItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isProtectedWorld(player)) return;

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Block clicking on PKW items to move them
        if (current != null && ItemUtil.isPkwItem(current)) {
            event.setCancelled(true);
            return;
        }
        // Block swapping PKW items via number keys
        if (event.getHotbarButton() >= 0) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (hotbar != null && ItemUtil.isPkwItem(hotbar)) {
                event.setCancelled(true);
                return;
            }
        }
        // Block placing other items into PKW slots via cursor
        if (cursor != null && ItemUtil.isPkwItem(cursor) && event.getClickedInventory() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isProtectedWorld(player)) return;
        if (ItemUtil.isPkwItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedWorld(Player player) {
        return pkwWorldManager.isPkwWorld(player.getWorld())
                || pkwWorldManager.isPkwsWorld(player.getWorld());
    }
}
