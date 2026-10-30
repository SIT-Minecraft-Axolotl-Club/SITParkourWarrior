package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.records.RecordsManager;
import club.sitmc.sitParkourWarrior.session.RunProgress;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final SessionManager sessionManager;
    private final PkwWorldManager pkwWorldManager;
    private final RecordsManager recordsManager;
    private final PkwsRaceManager pkwsRaceManager;

    public PlayerJoinListener(SessionManager sessionManager, PkwWorldManager pkwWorldManager,
                              RecordsManager recordsManager, PkwsRaceManager pkwsRaceManager) {
        this.sessionManager = sessionManager;
        this.pkwWorldManager = pkwWorldManager;
        this.recordsManager = recordsManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        String worldName = player.getWorld().getName();
        if (pkwWorldManager.isPkwsWorld(worldName)) {
            pkwsRaceManager.onPlayerEnterWorld(player);
            return;
        }
        if (!pkwWorldManager.isPkwWorld(worldName)) return;

        // Give interactive items
        WorldChangeListener.givePkwItems(player);

        // Restore saved run
        RecordsManager.SavedRunData saved = recordsManager.loadAndClearActiveRun(worldName, player.getUniqueId());
        if (saved == null) return;

        // Only restore RunProgress if the player had one before leaving (had stepped on
        // GLOBAL_START). Otherwise we'd be creating a global timer out of thin air.
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
        if (saved.hasLocation()) {
            World targetWorld = Bukkit.getWorld(saved.locWorldName);
            if (targetWorld != null) {
                Location loc = new Location(targetWorld, saved.locX, saved.locY, saved.locZ,
                        saved.locYaw, saved.locPitch);
                player.setFallDistance(0f);
                sessionManager.beginInternalTeleport(player.getUniqueId());
                try {
                    player.teleport(loc);
                } finally {
                    sessionManager.endInternalTeleport(player.getUniqueId());
                }
            } else {
                Bukkit.getLogger().warning("[SITPKW] 无法传送玩家 " + player.getName()
                        + " 到离开坐标：世界 " + saved.locWorldName + " 未加载，回退到出生点。");
            }
        }
    }
}
