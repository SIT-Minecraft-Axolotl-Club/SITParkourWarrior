package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.map.SelectionManager;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class SelectionToolListener implements Listener {
    private final SelectionManager selectionManager;

    public SelectionToolListener(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!selectionManager.isEditing(player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.STRING) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            Msg.send(player, "选区工具仅限创造模式使用。");
            event.setCancelled(true);
            return;
        }
        Location blockLocation = event.getClickedBlock() == null ? player.getLocation() : event.getClickedBlock().getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            selectionManager.setPos(player, true, blockLocation);
        } else {
            selectionManager.setPos(player, false, blockLocation);
        }
        event.setCancelled(true);
    }
}