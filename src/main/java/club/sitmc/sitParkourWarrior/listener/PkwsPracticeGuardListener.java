package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Removes Segment practice mode when a player enters a PKWS world.
 *
 * <p>The LOWEST priority lets this guard see the Segment marker before the
 * existing PKWS entry handlers perform their normal state transitions.</p>
 */
public final class PkwsPracticeGuardListener implements Listener {
    private static final NamespacedKey SPECIAL_MODE_KEY =
            new NamespacedKey("sitsegment", "special_mode");

    private final SITParkourWarrior plugin;

    public PkwsPracticeGuardListener(SITParkourWarrior plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        enforce(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        enforce(event.getPlayer());
    }

    private void enforce(Player player) {
        if (plugin == null || player == null || player.getWorld() == null
                || player.getGameMode() == GameMode.SPECTATOR
                || !plugin.isPkwsWorld(player.getWorld().getName())) {
            return;
        }

        Byte marker = player.getPersistentDataContainer().get(
                SPECIAL_MODE_KEY, PersistentDataType.BYTE);
        if (marker == null || marker != (byte) 1) {
            return;
        }

        boolean commandExecuted = false;
        try {
            commandExecuted = player.performCommand("unprac");
        } catch (Exception ignored) {
            // The warning below deliberately omits the exception/stack trace:
            // an optional Segment command must not break PKWS world handling.
        }
        if (!commandExecuted) {
            plugin.getLogger().warning("[PKWS] 无法为玩家 " + player.getName()
                    + " 执行 /unprac，练习模式标记可能仍然存在。");
        }
        Msg.send(player, "PKWS 世界内不可使用练习模式，已尝试退出练习模式。");
    }
}
