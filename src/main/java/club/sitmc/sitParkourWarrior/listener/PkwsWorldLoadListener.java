package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * PKWS 世界可能在插件启用之后才加载（大厅插件延迟建世界）。启用阶段的空闲复位
 * 那时还粘不了结构，赛道文件里会保留上一次进程中断时开放的起点，玩家一进起点
 * 区域就等于直接开赛。世界加载完成后按“服务器重启后起点回到关闭状态”复位该
 * 世界的起点：这是世界作用域的幂等操作，比赛进行中的世界会被 {@link PkwsRaceManager}
 * 拒绝，没有赛道记录时也只会返回一句提示，不会抛错。
 */
public final class PkwsWorldLoadListener implements Listener {

    private final PkwWorldManager worldManager;
    private final PkwsRaceManager raceManager;

    public PkwsWorldLoadListener(PkwWorldManager worldManager, PkwsRaceManager raceManager) {
        this.worldManager = worldManager;
        this.raceManager = raceManager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        if (world == null || worldManager == null || raceManager == null) {
            return;
        }
        String worldName = world.getName();
        if (!worldManager.isPkwsWorld(worldName)) {
            return;
        }
        raceManager.setStartState(worldName, false);
    }
}
