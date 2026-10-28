package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.records.RecordsManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.session.RunProgress;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.ArrayList;

/**
 * Gives/removes PKW items on world change, and saves active runs when
 * leaving a PKW world to prevent cross-world state leakage.
 */
public class WorldChangeListener implements Listener {

    private final PkwWorldManager pkwWorldManager;
    private final SessionManager sessionManager;
    private final RecordsManager recordsManager;
    private final VisibilityManager visibilityManager;
    private final PkwsRaceManager pkwsRaceManager;

    public WorldChangeListener(PkwWorldManager pkwWorldManager,
                               SessionManager sessionManager, RecordsManager recordsManager,
                               VisibilityManager visibilityManager,
                               PkwsRaceManager pkwsRaceManager) {
        this.pkwWorldManager = pkwWorldManager;
        this.sessionManager = sessionManager;
        this.recordsManager = recordsManager;
        this.visibilityManager = visibilityManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        boolean fromPkw = pkwWorldManager.isPkwWorld(event.getFrom());
        boolean toPkw = pkwWorldManager.isPkwWorld(player.getWorld());
        boolean fromPkws = pkwWorldManager.isPkwsWorld(event.getFrom());
        boolean toPkws = pkwWorldManager.isPkwsWorld(player.getWorld());

        if (fromPkw) {
            visibilityManager.cleanupPlayer(player);
            // Leaving a PKW world: save active run (with session snapshot) and clean up
            sessionManager.saveAndClearActiveRun(player);
            removePkwItems(player);
        }

        if (fromPkws) {
            pkwsRaceManager.onPlayerLeaveWorld(player, event.getFrom().getName());
        }

        if (toPkw) {
            givePkwItems(player);
            // Try to restore a saved active run in this world
            RecordsManager.SavedRunData saved = recordsManager.loadAndClearActiveRun(
                    player.getWorld().getName(), player.getUniqueId());
            if (saved != null) {
                // Only restore RunProgress if the player had one before leaving (had stepped
                // on GLOBAL_START). Otherwise we'd be creating a global timer out of thin air.
                if (saved.hasRunProgress) {
                    RunProgress rp = new RunProgress();
                    rp.restoreFull(saved.elapsedMs, saved.medals,
                            saved.stone, saved.bronze, saved.silver, saved.gold,
                            saved.claimedLevels, true);
                    sessionManager.restoreRunProgress(player.getUniqueId(), rp);
                }
                // Restore per-level session state (checkpoint, timer, inside-region, etc.)
                sessionManager.restoreSessionFromSaved(player, saved);
                // Teleport player to last known location in this world
                teleportToSavedLocation(player, saved);
            }
        }

        if (toPkws) {
            pkwsRaceManager.onPlayerEnterWorld(player);
        }
    }

    public static void givePkwItems(Player player) {
        if (!hasPkwItem(player, ItemUtil.KEY_FORK_RETURN)) {
            player.getInventory().setItem(0, ItemUtil.createForkReturnItem());
        }
        if (!hasPkwItem(player, ItemUtil.KEY_QUIT)) {
            player.getInventory().setItem(7, ItemUtil.createQuitItem());
        }
    }

    public static void removePkwItems(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            var item = inv.getItem(i);
            if (ItemUtil.isPkwItem(item)) {
                inv.setItem(i, null);
            }
        }
    }

    private static boolean hasPkwItem(Player player, String key) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            var item = inv.getItem(i);
            if (item != null && (key.equals(ItemUtil.KEY_FORK_RETURN) ? ItemUtil.isForkReturnItem(item) : ItemUtil.isQuitItem(item))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Teleport a player to their last saved location after restoring a run.
     * Marks the teleport as internal to prevent PlayerTeleportListener from
     * treating it as a "leave" and corrupting the just-restored data.
     * Falls back to world spawn if the target world is not loaded or coordinates are invalid.
     */
    private void teleportToSavedLocation(Player player, RecordsManager.SavedRunData saved) {
        if (!saved.hasLocation()) return;
        World targetWorld = Bukkit.getWorld(saved.locWorldName);
        if (targetWorld == null) {
            Bukkit.getLogger().warning("[SITPKW] 无法传送玩家 " + player.getName()
                    + " 到离开坐标：世界 " + saved.locWorldName + " 未加载，回退到出生点。");
            return;
        }
        Location loc = new Location(targetWorld, saved.locX, saved.locY, saved.locZ,
                saved.locYaw, saved.locPitch);
        player.setFallDistance(0f);
        sessionManager.beginInternalTeleport(player.getUniqueId());
        try {
            player.teleport(loc);
        } finally {
            sessionManager.endInternalTeleport(player.getUniqueId());
        }
    }
}
