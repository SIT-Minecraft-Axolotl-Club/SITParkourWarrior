package club.sitmc.sitParkourWarrior.util;

import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ParticleService {
    private static final int INTERVAL_TICKS = 10;

    private final Plugin plugin;
    private BukkitTask task;
    private SelectionManager selectionManager;

    public ParticleService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    continue;
                }
                if (!isHoldingString(player)) {
                    continue;
                }
                if (selectionManager == null) {
                    continue;
                }
                Region region = selectionManager.getSelectionRegion(player);
                if (region == null) {
                    continue;
                }
                spawnRegionParticles(player, region);
            }
        }, 0L, INTERVAL_TICKS);
    }

    public void stopAll() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void spawnRegionParticles(Player player, Region region) {
        World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) {
            return;
        }
        int minX = region.getMinX();
        int minY = region.getMinY();
        int minZ = region.getMinZ();
        int maxX = region.getMaxX();
        int maxY = region.getMaxY();
        int maxZ = region.getMaxZ();

        int sizeX = Math.max(1, maxX - minX);
        int sizeY = Math.max(1, maxY - minY);
        int sizeZ = Math.max(1, maxZ - minZ);
        int step = Math.max(1, Math.max(sizeX, Math.max(sizeY, sizeZ)) / 40);

        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0, 255, 0), 1.0f);

        for (int x = minX; x <= maxX; x += step) {
            spawn(player, world, x, minY, minZ, dust);
            spawn(player, world, x, minY, maxZ, dust);
            spawn(player, world, x, maxY, minZ, dust);
            spawn(player, world, x, maxY, maxZ, dust);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            spawn(player, world, minX, minY, z, dust);
            spawn(player, world, maxX, minY, z, dust);
            spawn(player, world, minX, maxY, z, dust);
            spawn(player, world, maxX, maxY, z, dust);
        }
        for (int y = minY; y <= maxY; y += step) {
            spawn(player, world, minX, y, minZ, dust);
            spawn(player, world, maxX, y, minZ, dust);
            spawn(player, world, minX, y, maxZ, dust);
            spawn(player, world, maxX, y, maxZ, dust);
        }
    }

    private void spawn(Player player, World world, int x, int y, int z, Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST, world.getBlockAt(x, y, z).getLocation().add(0.5, 0.5, 0.5), 1, 0, 0, 0, 0, dust);
    }

    private boolean isHoldingString(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return (main != null && main.getType() == Material.STRING)
                || (off != null && off.getType() == Material.STRING);
    }
}
