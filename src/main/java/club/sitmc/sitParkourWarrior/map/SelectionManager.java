package club.sitmc.sitParkourWarrior.map;

import club.sitmc.sitParkourWarrior.util.Msg;
import club.sitmc.sitParkourWarrior.util.ParticleService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SelectionManager {
    private final MapManager mapManager;
    private final ParticleService particleService;
    private final SchematicService schematicService = new SchematicService();
    private final Map<UUID, String> editingMap = new HashMap<>();
    private final Map<UUID, Location> pos1Cache = new HashMap<>();
    private final Map<UUID, Location> pos2Cache = new HashMap<>();

    public SelectionManager(MapManager mapManager, ParticleService particleService) {
        this.mapManager = mapManager;
        this.particleService = particleService;
    }

    public void setEditingMap(Player player, ParkourMap map) {
        if (player == null || map == null) {
            return;
        }
        editingMap.put(player.getUniqueId(), map.getId());
    }

    public void clearEditingMap(Player player) {
        if (player == null) {
            return;
        }
        editingMap.remove(player.getUniqueId());
        pos1Cache.remove(player.getUniqueId());
        pos2Cache.remove(player.getUniqueId());
    }

    public boolean isEditing(Player player) {
        if (player == null) {
            return false;
        }
        return editingMap.containsKey(player.getUniqueId());
    }

    public ParkourMap getEditingMap(Player player) {
        if (player == null) {
            return null;
        }
        String id = editingMap.get(player.getUniqueId());
        if (id == null) {
            return null;
        }
        return mapManager.getMap(id);
    }

    public boolean setPos(Player player, boolean first) {
        return setPos(player, first, player == null ? null : player.getLocation());
    }

    public boolean setPos(Player player, boolean first, Location location) {
        if (player == null) {
            return false;
        }
        ParkourMap map = getEditingMap(player);
        if (map == null) {
            Msg.send(player, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return false;
        }
        Location loc = location == null ? player.getLocation() : location;
        if (loc != null && loc.getWorld() != null) {
            loc = loc.getBlock().getLocation();
        }
        if (first) {
            pos1Cache.put(player.getUniqueId(), loc);
            Msg.send(player, "已设置 pos1。");
        } else {
            pos2Cache.put(player.getUniqueId(), loc);
            Msg.send(player, "已设置 pos2。");
        }
        return true;
    }

    /**
     * Restores a map's already-saved template region into the player's pos1/pos2
     * cache (e.g. on entering edit mode), so the existing particle boundary display
     * and dynamic-state tooling immediately reflect it without a manual reselect.
     * No-ops silently if the region's world isn't loaded.
     */
    public void restoreRegionSelection(Player player, Region region) {
        if (player == null || region == null) {
            return;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(region.getWorldName());
        if (world == null) {
            return;
        }
        pos1Cache.put(player.getUniqueId(), new Location(world, region.getMinX(), region.getMinY(), region.getMinZ()));
        pos2Cache.put(player.getUniqueId(), new Location(world, region.getMaxX(), region.getMaxY(), region.getMaxZ()));
    }

    public Region getSelectionRegion(Player player) {
        if (player == null) {
            return null;
        }
        Location p1 = pos1Cache.get(player.getUniqueId());
        Location p2 = pos2Cache.get(player.getUniqueId());
        if (p1 == null || p2 == null) {
            return null;
        }
        if (p1.getWorld() == null || p2.getWorld() == null || !p1.getWorld().getName().equals(p2.getWorld().getName())) {
            return null;
        }
        int minX = Math.min(p1.getBlockX(), p2.getBlockX());
        int minY = Math.min(p1.getBlockY(), p2.getBlockY());
        int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        int maxY = Math.max(p1.getBlockY(), p2.getBlockY());
        int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        return new Region(p1.getWorld().getName(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean saveSelection(Player player, int seqId) {
        ParkourMap map = getEditingMap(player);
        if (map == null) {
            Msg.send(player, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return false;
        }
        Region region = getSelectionRegion(player);
        if (region == null) {
            Msg.send(player, "请先用线工具设置 pos1 和 pos2。");
            return false;
        }
        if (mapManager.isRegionTooLarge(region)) {
            Msg.send(player, "区域过大，已拒绝保存。");
            return false;
        }
        map.setRegion(region);
        String fileName = "state_" + seqId + ".schem";
        File mapFolder = mapManager.getMapFolder(map);
        File targetFile = new File(mapFolder, fileName);
        boolean saved = schematicService.saveRegionToSchem(region, targetFile);
        if (!saved) {
            Msg.send(player, "保存结构失败，请检查 WorldEdit。");
            return false;
        }
        DynamicData data = map.getDynamicData();
        upsertState(data, seqId, fileName);
        mapManager.saveMap(map);
        Msg.send(player, "选区已保存，序列 id: " + seqId);
        return true;
    }

    /**
     * Legacy /sitpkw save &lt;seqId&gt; entry point: overwrites the schematic file of the
     * state already identified by {@code seqId} in place (position, name and interval
     * untouched), or appends a brand-new state with that id to the end of the list.
     */
    private void upsertState(DynamicData data, int seqId, String fileName) {
        DynamicState existing = data.findById(seqId);
        if (existing != null) {
            existing.setFile(fileName);
            return;
        }
        data.getStates().add(new DynamicState(seqId, fileName, null, DynamicState.DEFAULT_INTERVAL_TICKS));
    }
}