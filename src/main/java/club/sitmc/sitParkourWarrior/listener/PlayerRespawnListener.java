package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.session.ParkourSession;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsTrack;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerRespawnListener implements Listener {
    private final SessionManager sessionManager;
    private final PkwsRaceManager pkwsRaceManager;

    public PlayerRespawnListener(SessionManager sessionManager, PkwsRaceManager pkwsRaceManager) {
        this.sessionManager = sessionManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (pkwsRaceManager != null && pkwsRaceManager.isParticipant(event.getPlayer().getUniqueId())) {
            Location checkpoint = pkwsRaceManager.getRespawnCheckpoint(
                    event.getPlayer().getUniqueId());
            if (checkpoint == null) {
                PkwsTrack.Piece piece = pkwsRaceManager.getCurrentPiece(
                        event.getPlayer().getUniqueId());
                if (piece != null && piece.getStart() != null) {
                    checkpoint = piece.getStart().toLocation();
                }
            }
            if (checkpoint != null && checkpoint.getWorld() != null) {
                event.setRespawnLocation(checkpoint);
            }
            event.getPlayer().setGameMode(org.bukkit.GameMode.ADVENTURE);
            return;
        }
        // A non-contestant (for example an eliminated player) keeps observing
        // the running race: respawning cleared his adventure flight and
        // invisibility, so the observer state is re-applied here.
        if (pkwsRaceManager != null && pkwsRaceManager.reapplyObserverState(event.getPlayer())) {
            return;
        }
        ParkourSession session = sessionManager.getSession(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        Location checkpoint = session.getCheckpoint();
        if (checkpoint != null && checkpoint.getWorld() != null) {
            session.setInsideRegion(true);
            session.setInsideStart(false);
            session.setSkipResetAtStartOnce(true);
            event.setRespawnLocation(checkpoint);
        }
    }
}
