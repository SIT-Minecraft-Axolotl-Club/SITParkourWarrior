package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Flushes and tears down a player's dynamic-level edit session (save-if-dirty,
 * stop any preview playback, wipe the temp edit region, reclaim controller/paper
 * items, clear the selection cache) when they leave the server or change worlds,
 * so nothing leaks into other contexts.
 */
public class EditSessionCleanupListener implements Listener {
    private final EditSessionManager editSessionManager;
    private final SelectionManager selectionManager;

    public EditSessionCleanupListener(EditSessionManager editSessionManager, SelectionManager selectionManager) {
        this.editSessionManager = editSessionManager;
        this.selectionManager = selectionManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Always sweep legacy papers, even when there's no active edit session.
        editSessionManager.cleanupLegacyPapers(player);
        if (editSessionManager.getSession(player) != null) {
            editSessionManager.handlePlayerLeaving(player);
            selectionManager.clearEditingMap(player);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // Always sweep legacy papers, even when there's no active edit session.
        editSessionManager.cleanupLegacyPapers(player);
        if (editSessionManager.getSession(player) != null) {
            editSessionManager.handlePlayerLeaving(player);
            selectionManager.clearEditingMap(player);
        }
    }
}
