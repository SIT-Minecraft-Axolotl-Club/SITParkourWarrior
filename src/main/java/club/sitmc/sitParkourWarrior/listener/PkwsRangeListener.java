package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRangeConfig;
import club.sitmc.sitParkourWarrior.pkws.PkwsRangeGui;
import club.sitmc.sitParkourWarrior.pkws.PkwsTimeGui;
import club.sitmc.sitParkourWarrior.pkws.PkwsTemplateStore;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Routes the separate PKWS difficulty-range administrator inventory. */
public final class PkwsRangeListener implements Listener {
    private final JavaPlugin plugin;
    private final PkwsRangeConfig ranges;
    private final PkwsTemplateStore templates;
    private final MapManager mapManager;

    public PkwsRangeListener(PkwsRangeConfig ranges, MapManager mapManager) {
        this(null, ranges, new PkwsTemplateStore(new java.io.File("."),
                java.util.logging.Logger.getLogger(PkwsRangeListener.class.getName())), mapManager);
    }

    public PkwsRangeListener(JavaPlugin plugin, PkwsRangeConfig ranges,
                             PkwsTemplateStore templates, MapManager mapManager) {
        this.plugin = plugin;
        this.ranges = ranges;
        this.templates = templates;
        this.mapManager = mapManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object holder = event.getInventory().getHolder();
        if (isTimeInventory(holder)) {
            PkwsTimeGui.handleClick(event, plugin, ranges, templates, mapManager);
            return;
        }
        if (!isRangeInventory(holder)) {
            return;
        }
        PkwsRangeGui.handleClick(event, ranges, templates, mapManager);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder();
        if (isTimeInventory(holder)) {
            PkwsTimeGui.handleDrag(event);
            return;
        }
        if (!isRangeInventory(holder)) {
            return;
        }
        PkwsRangeGui.handleDrag(event);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (plugin == null) {
            return;
        }
        PkwsRangeGui.handleChat(event, plugin, ranges, templates, mapManager);
    }

    @EventHandler
    public void onTimeChat(AsyncChatEvent event) {
        if (plugin == null) {
            return;
        }
        PkwsTimeGui.handleChat(event, plugin, ranges, templates, mapManager);
    }

    private boolean isTimeInventory(Object holder) {
        return holder instanceof PkwsTimeGui.Holder
                || holder instanceof PkwsTimeGui.TemplateListHolder
                || holder instanceof PkwsTimeGui.TemplateConfirmHolder;
    }

    private boolean isRangeInventory(Object holder) {
        return holder instanceof PkwsRangeGui.Holder
                || holder instanceof PkwsRangeGui.TemplateListHolder
                || holder instanceof PkwsRangeGui.TemplateConfirmHolder;
    }
}
