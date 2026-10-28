package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.editor.EditSession;
import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.editor.StateListGui;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles the dynamic-level edit controller tool (COMPARATOR).
 * <p>
 * <b>Strict left/right separation (Phase 2 rev 3):</b>
 * <ul>
 *   <li>Left click ({@link Action#LEFT_CLICK_AIR} / {@link Action#LEFT_CLICK_BLOCK}):
 *       toggle play/pause only.</li>
 *   <li>Right click ({@link Action#RIGHT_CLICK_AIR} / {@link Action#RIGHT_CLICK_BLOCK}):
 *       open state-list GUI only (paused sessions only; playing →提示).</li>
 * </ul>
 * State papers are display-only — no backpack interaction.
 */
public class EditControlListener implements Listener {

    /**
     * Set to {@code true} to print per-click branch tracing to the server log.
     * Set to {@code false} in production.
     * <p>
     * Implementation note: the guard-level "收到交互" line is always printed
     * (independent of this flag) so that the very first event-handler entry
     * is visible in the server console without any recompilation. Change the
     * {@code return} at the top of {@link #onInteract} to {@code plugin.getLogger()...}
     * back to a no-op to silence it permanently.
     */
    private static final boolean DEBUG_BRANCH = false;

    /** Set to {@code true} for one-session validation; flip back to {@code false} after. */
    private static final boolean DEBUG_ENTRY = false;

    private final EditSessionManager editSessionManager;
    private final MapManager mapManager;
    private final JavaPlugin plugin;

    public EditControlListener(EditSessionManager editSessionManager, MapManager mapManager,
                               JavaPlugin plugin) {
        this.editSessionManager = editSessionManager;
        this.mapManager = mapManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // ---- every-entry trace (disable by flipping DEBUG_ENTRY to false) ----
        if (DEBUG_ENTRY) {
            plugin.getLogger().info("[EditControl] 事件进入 — action=" + event.getAction()
                    + " hand=" + event.getHand()
                    + " player=" + event.getPlayer().getName()
                    + " item=" + (event.getItem() == null ? "null" : event.getItem().getType()));
        }

        // ---- Guard 1: only main hand. Right-click fires twice (HAND + OFF_HAND). ----
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        // ---- Guard 2: item must exist ----
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        // ---- Guard 3: must be our controller tool ----
        if (!ItemUtil.isEditTool(item)) {
            // State papers are display-only — cancel clicks on them, do nothing else.
            if (ItemUtil.isEditPaper(item)) {
                event.setCancelled(true);
            }
            return;
        }

        // ================================================================
        // Below this line: item IS the controller tool, main-hand only.
        // Use ACTION directly — no isLeft/isRight boolean intermediates.
        // Each branch MUST end with return; no fall-through.
        // ================================================================

        Action action = event.getAction();

        // ----- LEFT CLICK: toggle play/pause ONLY -----
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (DEBUG_BRANCH) {
                plugin.getLogger().info("[EditControl] → 左键分支 → togglePlayPause");
            }
            editSessionManager.togglePlayPause(event.getPlayer());
            return;   // <-- MANDATORY: never continue to right-click branch
        }

        // ----- RIGHT CLICK: open state-list GUI ONLY -----
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (DEBUG_BRANCH) {
                plugin.getLogger().info("[EditControl] → 右键分支 → 打开/提示GUI");
            }
            Player player = event.getPlayer();
            EditSession session = editSessionManager.getSession(player);
            if (session == null) {
                Msg.send(player, "请先使用 /sitpkw edit <id> 进入编辑模式。");
                return;
            }
            if (session.isPlaying()) {
                Msg.send(player, "播放中，请先暂停。");
                return;
            }
            ParkourMap map = mapManager.getMap(session.getMapId());
            if (map != null) {
                StateListGui.open(player, map, editSessionManager);
            }
            return;   // <-- MANDATORY: never continue to any other logic
        }

        // Any other action (PHYSICAL, etc.) — ignore.
        return;
    }
}
