package club.sitmc.sitParkourWarrior.map;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundGroup;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamicService {
    private final SITParkourWarrior plugin;
    private final MapManager mapManager;
    private final Map<String, DynamicTask> activeTasks = new HashMap<>();

    /** Radius (blocks) within which at least one player must be present for switching to occur. */
    private static final double NEARBY_PLAYER_RADIUS = 48.0;
    /** Tick interval for re-checking player presence when switching is paused. */
    private static final long PAUSED_CHECK_INTERVAL_TICKS = 20L; // 1 second

    public DynamicService(SITParkourWarrior plugin, MapManager mapManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
    }

    public void startAllDeployed() {
        for (ParkourMap map : mapManager.getMaps().values()) {
            for (Deployment deployment : map.getDeployments()) {
                startForDeployment(map, deployment);
            }
        }
    }

    public void startForDeployment(ParkourMap map, Deployment deployment) {
        // PKWS START/RELAY (and the static PKWS END) are controlled only by
        // explicit PKWS logic.  Ordinary LEVEL maps placed into a PKWS track
        // still use this same dynamic engine and task implementation.
        if (map == null || deployment == null || isPkwsManualStateNode(map)
                || !isDynamicActive(map)) {
            return;
        }
        String key = buildKey(map.getId(), deployment.getId());
        DynamicTask task = activeTasks.get(key);
        if (task == null) {
            DynamicTask newTask = new DynamicTask(map, deployment);
            activeTasks.put(key, newTask);
            newTask.start();
        }
    }

    public void stopForDeployment(ParkourMap map, Deployment deployment) {
        if (map == null || deployment == null) {
            return;
        }
        String key = buildKey(map.getId(), deployment.getId());
        DynamicTask task = activeTasks.remove(key);
        if (task != null) {
            task.stop();
        }
    }

    public void onPlayerJoin(ParkourMap map, Deployment deployment) {
        // No longer tied to player presence, but keep as a safety start.
        startForDeployment(map, deployment);
    }

    public void onPlayerLeave(ParkourMap map, Deployment deployment) {
        // Do nothing. Dynamic runs while deployed regardless of players.
    }

    public void stopAll() {
        for (DynamicTask task : activeTasks.values()) {
            task.stop();
        }
        activeTasks.clear();
    }

    private String buildKey(String mapId, String deploymentId) {
        return mapId.toLowerCase() + ":" + deploymentId;
    }

    private boolean isDynamicActive(ParkourMap map) {
        if (map == null || isPkwsManualStateNode(map)) {
            return false;
        }
        DynamicData data = map.getDynamicData();
        // Consider dynamic active only when enabled and there are at least 2 states.
        return data.isEnabled() && data.getStates().size() > 1;
    }

    private boolean isPkwsManualStateNode(ParkourMap map) {
        if (map == null) {
            return false;
        }
        NodeType type = map.getNodeType();
        return type == NodeType.PKWS_START
                || type == NodeType.PKWS_RELAY
                || type == NodeType.PKWS_END;
    }

    /**
     * Check whether any player is within {@link #NEARBY_PLAYER_RADIUS} blocks
     * of the deployment region's center. Uses 3D Euclidean distance against
     * the region center point.
     * <p>
     * This is intentionally lightweight: it iterates {@code world.getPlayers()}
     * (bounded by online player count, typically dozens) rather than scanning
     * all deployments per player (hundreds). No object allocation per check.
     */
    private boolean isAnyPlayerNearby(Deployment deployment) {
        Region region = deployment.getRegion();
        if (region == null) {
            return false;
        }
        org.bukkit.World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) {
            return false;
        }
        double centerX = (region.getMinX() + region.getMaxX()) / 2.0;
        double centerY = (region.getMinY() + region.getMaxY()) / 2.0;
        double centerZ = (region.getMinZ() + region.getMaxZ()) / 2.0;
        double radiusSq = NEARBY_PLAYER_RADIUS * NEARBY_PLAYER_RADIUS;
        for (org.bukkit.entity.Player player : world.getPlayers()) {
            org.bukkit.Location loc = player.getLocation();
            double dx = loc.getX() - centerX;
            double dy = loc.getY() - centerY;
            double dz = loc.getZ() - centerZ;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private class DynamicTask {
        private final ParkourMap map;
        private final Deployment deployment;
        private int sequenceIndex = 0;
        private int stateIndex = 0;
        private BukkitTask pendingTask;

        private DynamicTask(ParkourMap map, Deployment deployment) {
            this.map = map;
            this.deployment = deployment;
        }

        public void start() {
            scheduleNext(1);
        }

        public void stop() {
            if (pendingTask != null) {
                pendingTask.cancel();
                pendingTask = null;
            }
        }

        private void scheduleNext(long delayTicks) {
            pendingTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!isDynamicActive(map)) {
                    stop();
                    return;
                }
                // Pause switching when no players are nearby to save CPU.
                // The level hangs at its current state; when a player re-enters
                // range, switching resumes from that state with no compensation.
                if (!isAnyPlayerNearby(deployment)) {
                    scheduleNext(PAUSED_CHECK_INTERVAL_TICKS);
                    return;
                }
                int duration = playOnce();
                if (duration <= 0) {
                    duration = 1;
                }
                scheduleNext(duration);
            }, delayTicks);
        }

        private int playOnce() {
            DynamicData data = map.getDynamicData();
            List<DynamicState> states = data.getStates();
            if (states.isEmpty()) {
                return 20;
            }
            if (stateIndex >= states.size()) {
                stateIndex = 0;
            }
            DynamicState state = states.get(stateIndex);
            stateIndex++;
            pasteSchematic(map, deployment, state.getFile());
            return state.getInterval();
        }
    }

    private void pasteSchematic(ParkourMap map, Deployment deployment, String fileName) {
        if (fileName == null || fileName.isBlank() || deployment == null) {
            return;
        }
        Region region = deployment.getRegion();
        if (region == null) {
            plugin.getLogger().warning("Map " + map.getId() + " deployment has no region, cannot paste.");
            return;
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(region.getWorldName());
        if (bukkitWorld == null) {
            return;
        }
        File mapFolder = mapManager.getMapFolder(map);
        File schemFile = new File(mapFolder, fileName);
        if (!schemFile.exists()) {
            plugin.getLogger().warning("Schematic not found: " + schemFile.getAbsolutePath());
            return;
        }

        ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
        if (format == null) {
            plugin.getLogger().warning("Unsupported schematic format: " + fileName);
            return;
        }

        try (FileInputStream fis = new FileInputStream(schemFile); ClipboardReader reader = format.getReader(fis)) {
            Clipboard clipboard = reader.read();
            World weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld)
                    .build()) {
                editSession.setTrackingHistory(false);
                tryDisableSideEffects(editSession);

                Location origin = new Location(bukkitWorld, region.getMinX(), region.getMinY(), region.getMinZ());
                List<BlockChange> changes = collectChanges(clipboard, bukkitWorld, origin);
                ClipboardHolder holder = new ClipboardHolder(clipboard);
                Operation operation = holder.createPaste(editSession)
                        .to(BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()))
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(operation);
                editSession.flushSession();
                playChanges(map, deployment, changes);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to paste schematic " + fileName + ": " + e.getMessage());
        } catch (Throwable t) {
            plugin.getLogger().warning("WorldEdit paste error: " + t.getMessage());
        }
    }

    private List<BlockChange> collectChanges(Clipboard clipboard, org.bukkit.World world, Location origin) {
        List<BlockChange> changes = new ArrayList<>();
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        for (BlockVector3 vec : clipboard.getRegion()) {
            BlockVector3 offset = vec.subtract(min);
            Location loc = origin.clone().add(offset.x(), offset.y(), offset.z());
            BlockData oldData = world.getBlockAt(loc).getBlockData();
            BlockData newData = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(clipboard.getFullBlock(vec));
            if (oldData.getMaterial() != newData.getMaterial()) {
                changes.add(new BlockChange(loc, oldData, newData));
            }
        }
        return changes;
    }

    private void playChanges(ParkourMap map, Deployment deployment, List<BlockChange> changes) {
        boolean particlesEnabled = map == null || map.isParticlesEnabled();
        boolean soundEnabled = map == null || map.isSoundEnabled();
        Map<Material, Location> breakSoundOrigins = soundEnabled ? new HashMap<>() : null;
        Map<Material, Location> placeSoundOrigins = soundEnabled ? new HashMap<>() : null;
        Map<Material, SoundGroup> breakSoundGroups = soundEnabled ? new HashMap<>() : null;
        Map<Material, SoundGroup> placeSoundGroups = soundEnabled ? new HashMap<>() : null;
        org.bukkit.World soundWorld = null;
        for (BlockChange change : changes) {
            Material oldMat = change.oldData.getMaterial();
            Material newMat = change.newData.getMaterial();
            if (soundWorld == null) {
                soundWorld = change.location.getWorld();
            }
            if (!oldMat.isAir()) {
                if (soundEnabled) {
                    breakSoundOrigins.putIfAbsent(oldMat, change.location);
                    breakSoundGroups.putIfAbsent(oldMat, change.oldData.getSoundGroup());
                }
                if (particlesEnabled) {
                    change.location.getWorld().spawnParticle(
                            Particle.BLOCK_CRUMBLE,
                            change.location.clone().add(0.5, 0.5, 0.5),
                            10,
                            0.2,
                            0.2,
                            0.2,
                            0.0,
                            change.oldData
                    );
                }
            }
            if (!newMat.isAir()) {
                if (soundEnabled) {
                    placeSoundOrigins.putIfAbsent(newMat, change.location);
                    placeSoundGroups.putIfAbsent(newMat, change.newData.getSoundGroup());
                }
                if (particlesEnabled) {
                    change.location.getWorld().spawnParticle(
                            Particle.BLOCK,
                            change.location.clone().add(0.5, 0.5, 0.5),
                            10,
                            0.2,
                            0.2,
                            0.2,
                            0.0,
                            change.newData
                    );
                }
            }
        }
        if (soundEnabled) {
            if (soundWorld != null) {
                Region deploymentRegion = deployment == null ? null : deployment.getRegion();
                for (org.bukkit.entity.Player player : soundWorld.getPlayers()) {
                    if (deploymentRegion != null && !deploymentRegion.contains(player.getLocation())) {
                        continue;
                    }
                    Location playerLoc = player.getLocation();
                    for (Map.Entry<Material, Location> entry : breakSoundOrigins.entrySet()) {
                        SoundGroup group = breakSoundGroups.get(entry.getKey());
                        if (group != null) {
                            player.playSound(playerLoc, group.getBreakSound(), 1.0f, 1.0f);
                        }
                    }
                    for (Map.Entry<Material, Location> entry : placeSoundOrigins.entrySet()) {
                        SoundGroup group = placeSoundGroups.get(entry.getKey());
                        if (group != null) {
                            player.playSound(playerLoc, group.getPlaceSound(), 1.0f, 1.0f);
                        }
                    }
                }
            }
        }
    }

    private void tryDisableSideEffects(EditSession editSession) {
        try {
            Class<?> sideEffectSetClass = Class.forName("com.sk89q.worldedit.world.block.SideEffectSet");
            Method noneMethod = sideEffectSetClass.getMethod("none");
            Object noneSet = noneMethod.invoke(null);
            Method setSideEffect = EditSession.class.getMethod("setSideEffectApplier", sideEffectSetClass);
            setSideEffect.invoke(editSession, noneSet);
        } catch (Throwable ignored) {
            // Fallback to default behavior when side effect API is not available.
        }
    }

    private static class BlockChange {
        private final Location location;
        private final BlockData oldData;
        private final BlockData newData;

        private BlockChange(Location location, BlockData oldData, BlockData newData) {
            this.location = location;
            this.oldData = oldData;
            this.newData = newData;
        }
    }
}
