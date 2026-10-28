package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.session.RunProgress;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Saves active run progress (with exact location) when a player is teleported
 * away from their PKW position by an external source (menu, command, other plugin).
 * <p>
 * Internal teleports (death-line respawn, checkpoint return, handoff, fork fallback,
 * fork-return item, quit item) are marked via {@link SessionManager#isInternalTeleport}
 * and skipped here — they are part of normal course mechanics, not a "leave".
 */
public class PlayerTeleportListener implements Listener {

    private final SessionManager sessionManager;
    private final PkwWorldManager pkwWorldManager;
    private final VisibilityManager visibilityManager;
    private final PkwsRaceManager pkwsRaceManager;

    public PlayerTeleportListener(SessionManager sessionManager, PkwWorldManager pkwWorldManager,
                                  VisibilityManager visibilityManager) {
        this(sessionManager, pkwWorldManager, visibilityManager, null);
    }

    public PlayerTeleportListener(SessionManager sessionManager, PkwWorldManager pkwWorldManager,
                                  VisibilityManager visibilityManager,
                                  PkwsRaceManager pkwsRaceManager) {
        this.sessionManager = sessionManager;
        this.pkwWorldManager = pkwWorldManager;
        this.visibilityManager = visibilityManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) return;

        World fromWorld = from.getWorld();
        if (fromWorld == null) return;

        World toWorld = to.getWorld();
        boolean crossWorldPkws = pkwsRaceManager != null
                && pkwWorldManager.isPkwsWorld(fromWorld)
                && (toWorld == null || !fromWorld.equals(toWorld));
        boolean pkwsLeaveHandled = false;
        if (crossWorldPkws && pkwsRaceManager.isRunning(fromWorld.getName())
                && pkwsRaceManager.isParticipant(player.getUniqueId())) {
            // Cross-world movement during an active PKWS race is an explicit
            // leave, even if another listener marked this teleport internal.
            pkwsRaceManager.abandon(player);
            if (!pkwsRaceManager.isRunning(fromWorld.getName())) {
                // finishRace may return the player before this event's
                // requested destination is applied; restore them after it.
                pkwsRaceManager.scheduleReturnToWorldSpawn(player, fromWorld.getName());
            }
            pkwsLeaveHandled = true;
        }

        // PlayerTeleportEvent fires before Bukkit changes the player's world.
        // Queue a destination-side PKWS entry check as a fallback for command
        // integrations that do not deliver PlayerChangedWorldEvent reliably.
        if (pkwsRaceManager != null && toWorld != null
                && pkwWorldManager.isPkwsWorld(toWorld)) {
            pkwsRaceManager.schedulePlayerEntryCheck(player);
        }

        // Skip internal teleports (death respawn, checkpoint, handoff, fork fallback, etc.)
        // after the PKWS cross-world leave check above.
        if (sessionManager.isInternalTeleport(player.getUniqueId())) return;

        if (crossWorldPkws && !pkwsLeaveHandled) {
            if (pkwsRaceManager.isRunning(fromWorld.getName())) {
                // A non-participant spectator still needs the normal PKWS
                // cleanup; the race manager handles this in its world-leave
                // callback without admitting the player to the race.
                pkwsRaceManager.onPlayerLeaveWorld(player, fromWorld.getName(), from.clone());
            } else {
                // PlayerChangedWorldEvent no longer has the departure
                // coordinates; capture the exact pre-teleport location here.
                // The world-change listener remains a defensive fallback for
                // non-teleport paths.
                pkwsRaceManager.onPlayerLeaveWorld(player, fromWorld.getName(), from.clone());
            }
        }

        // Only care about teleports originating from a PKW world
        if (!pkwWorldManager.isPkwWorld(fromWorld)) return;

        // Same-world teleport: the run is NOT interrupted — let it continue
        // as-is (global timer keeps running, session state, medals, and
        // in-level progress are all untouched). Only the player's position changes.
        if (toWorld != null && fromWorld.equals(toWorld)) return;

        // Different-world teleport from a PKW world: save and pause the run
        // so it can be resumed when the player returns to the origin world.

        // Nothing to save if the player has no active run or session
        RunProgress rp = sessionManager.getRunProgress(player.getUniqueId());
        boolean hasSession = sessionManager.getSession(player.getUniqueId()) != null;
        if (rp == null && !hasSession) return;

        // Save active run (with session snapshot) and clean up in-memory state
        sessionManager.saveAndClearActiveRun(player);

        // Clean up visibility effects
        visibilityManager.cleanupPlayer(player);

        // Remove PKW items if leaving the PKW world entirely
        if (toWorld == null || !pkwWorldManager.isPkwWorld(toWorld)) {
            WorldChangeListener.removePkwItems(player);
        }
    }
}
