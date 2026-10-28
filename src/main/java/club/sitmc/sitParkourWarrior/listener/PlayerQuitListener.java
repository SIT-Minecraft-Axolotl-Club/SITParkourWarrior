package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    private final SessionManager sessionManager;
    private final PkwWorldManager pkwWorldManager;
    private final VisibilityManager visibilityManager;
    private final PkwsRaceManager pkwsRaceManager;

    public PlayerQuitListener(SessionManager sessionManager, PkwWorldManager pkwWorldManager,
                              VisibilityManager visibilityManager,
                              PkwsRaceManager pkwsRaceManager) {
        this.sessionManager = sessionManager;
        this.pkwWorldManager = pkwWorldManager;
        this.visibilityManager = visibilityManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (pkwWorldManager.isPkwWorld(player.getWorld())) {
            sessionManager.saveAndClearActiveRun(player);
        }
        if (pkwWorldManager.isPkwsWorld(player.getWorld())) {
            pkwsRaceManager.onPlayerLeaveWorld(player, player.getWorld().getName());
        }
        visibilityManager.cleanupPlayer(player);
        // Belt-and-suspenders: ensure any non-PKW session state is also cleared
        sessionManager.removeRunProgress(event.getPlayer().getUniqueId());
        sessionManager.endSession(event.getPlayer(), false);
    }
}
