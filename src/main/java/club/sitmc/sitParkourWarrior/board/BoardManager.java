package club.sitmc.sitParkourWarrior.board;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages leaderboard TextDisplay boards — creation, persistence, lifecycle.
 */
public class BoardManager {

    private static final String PDC_KEY = "pkw_board";

    private final JavaPlugin plugin;
    private final File file;
    private NamespacedKey pdcKey;
    private final List<BoardData> boards = new ArrayList<>();

    /** Set by the main class when recordsManager is available. */
    private Runnable onBoardChanged;

    /** Pending orphan cleanup results, keyed by player UUID. */
    private final Map<UUID, List<Entity>> pendingCleanup = new HashMap<>();

    public BoardManager(JavaPlugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.file = new File(dataFolder, "boards.yml");
    }

    public void init(NamespacedKey pdcKey) {
        this.pdcKey = pdcKey;
        load();
    }

    public void setOnBoardChanged(Runnable callback) {
        this.onBoardChanged = callback;
    }

    public void notifyBoardChanged() {
        if (onBoardChanged != null) onBoardChanged.run();
    }

    // ---- CRUD ----

    public List<BoardData> getBoards() {
        return Collections.unmodifiableList(boards);
    }

    public List<BoardData> getBoardsInWorld(String worldName) {
        List<BoardData> result = new ArrayList<>();
        for (BoardData b : boards) {
            if (b.getWorldName().equals(worldName)) result.add(b);
        }
        return result;
    }

    /** Create a new board, spawn TextDisplay, persist. */
    public BoardData addBoard(String worldName, String tier, Location loc) {
        BoardData board = new BoardData(worldName, tier, loc);
        spawnEntity(board);
        boards.add(board);
        save();
        return board;
    }

    /** Remove the nearest board to a location. */
    public BoardData removeNearest(Location loc) {
        BoardData best = null;
        double bestDist = Double.MAX_VALUE;
        for (BoardData b : boards) {
            if (!b.getWorldName().equals(loc.getWorld().getName())) continue;
            double d = b.distanceSquared(loc);
            if (d < bestDist) { bestDist = d; best = b; }
        }
        if (best != null) {
            removeEntity(best);
            boards.remove(best);
            save();
        }
        return best;
    }

    // ---- Entity management ----

    public void spawnEntity(BoardData board) {
        Location loc = board.toLocation();
        if (loc == null || loc.getWorld() == null) return;

        // 防止 addBoard 命令重复创建：检查该位置是否已有 PDC 标记的 TextDisplay
        // 先确保区块已加载
        ensureChunkLoaded(loc);
        TextDisplay existingByLoc = findExistingBoardEntity(loc);
        if (existingByLoc != null) {
            existingByLoc.getPersistentDataContainer().set(pdcKey, PersistentDataType.BYTE, (byte) 1);
            board.setEntityUuid(existingByLoc.getUniqueId());
            return;
        }

        // 按 UUID 清理可能的旧实体
        if (board.getEntityUuid() != null) {
            Entity existing = Bukkit.getEntity(board.getEntityUuid());
            if (existing != null) existing.remove();
        }

        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        display.setPersistent(true);
        display.setBillboard(TextDisplay.Billboard.CENTER);
        display.setSeeThrough(false);
        display.getPersistentDataContainer().set(pdcKey, PersistentDataType.BYTE, (byte) 1);
        board.setEntityUuid(display.getUniqueId());
    }

    public TextDisplay getEntity(BoardData board) {
        if (board.getEntityUuid() == null) return null;
        Entity entity = Bukkit.getEntity(board.getEntityUuid());
        if (entity instanceof TextDisplay) return (TextDisplay) entity;
        return null;
    }

    /**
     * 移除榜牌实体。先尝试通过 UUID 查找；若区块未加载则加载后重试。
     * @return true 实体已成功移除（或本就不存在），false 无法确定
     */
    private boolean removeEntity(BoardData board) {
        UUID uuid = board.getEntityUuid();
        if (uuid == null) return true; // no entity to remove

        // 1. 尝试在已加载区块中找到实体
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null) {
            entity.remove();
            return true;
        }

        // 2. 区块可能未加载 —— 加载后再试
        Location loc = board.toLocation();
        if (loc != null && loc.getWorld() != null) {
            World world = loc.getWorld();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                // 主动加载区块，确保实体可被访问
                world.loadChunk(cx, cz);
                entity = Bukkit.getEntity(uuid);
                if (entity != null) {
                    entity.remove();
                    return true;
                }
            }
        }

        // 3. 彻底找不到 —— 实体可能已被手动移除或世界已卸载
        plugin.getLogger().warning("[removeEntity] 找不到实体 " + uuid
                + "（board: world=" + board.getWorldName()
                + " tier=" + board.getTier()
                + " pos=" + (int) board.getX() + "," + (int) board.getY() + "," + (int) board.getZ()
                + "），可能已被手动移除。");
        return false;
    }

    /**
     * 启动时恢复榜单实体。策略：对 boards.yml 中每条记录，先尝试保全已有实体，
     * 仅在没有实体时才新建。不执行盲目批量删除，避免误删其他插件的 TextDisplay。
     */
    public void restoreAllEntities() {
        // ===== Step 1: 对每条 boards.yml 记录，确保有且仅有一个实体 =====
        int reused = 0;
        int spawned = 0;
        int skipped = 0;
        int adopted = 0;

        for (BoardData board : boards) {
            Location loc = board.toLocation();
            if (loc == null || loc.getWorld() == null) {
                plugin.getLogger().warning("[重建] 跳过 board（世界未加载）: world="
                        + board.getWorldName() + " tier=" + board.getTier());
                skipped++;
                continue;
            }

            // 确保区块已加载
            ensureChunkLoaded(loc);

            // 优先按 UUID 查找 —— 实体仍存活则复用
            if (board.getEntityUuid() != null) {
                Entity existingByUuid = Bukkit.getEntity(board.getEntityUuid());
                if (existingByUuid instanceof TextDisplay
                        && existingByUuid.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE)) {
                    // 实体存续且 PDC 完整，复用之
                    reused++;
                    continue;
                }
            }

            // UUID 找不到或实体无效 —— 在相同位置查找 PDC 实体
            TextDisplay existingByLoc = findExistingBoardEntity(loc);
            if (existingByLoc != null) {
                // "收养"该位置上的现有实体
                existingByLoc.getPersistentDataContainer().set(pdcKey, PersistentDataType.BYTE, (byte) 1);
                board.setEntityUuid(existingByLoc.getUniqueId());
                adopted++;
                continue;
            }

            // 完全没有实体 —— 创建新的
            TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
            display.setPersistent(true);
            display.setBillboard(TextDisplay.Billboard.CENTER);
            display.setSeeThrough(false);
            display.getPersistentDataContainer().set(pdcKey, PersistentDataType.BYTE, (byte) 1);
            board.setEntityUuid(display.getUniqueId());
            spawned++;
        }

        StringBuilder log = new StringBuilder("[重建] ");
        log.append("复用 ").append(reused).append("，收养 ").append(adopted)
                .append("，新建 ").append(spawned);
        if (skipped > 0) log.append("，跳过 ").append(skipped).append("（世界未加载）");
        log.append("。");
        plugin.getLogger().info(log.toString());

        // ===== Step 2: 清理每个位置的重复实体（安全网） =====
        cleanupDuplicates();

        // ===== Step 3: 保存（有新建或收养则更新 UUID） =====
        if (spawned > 0 || adopted > 0) {
            save();
        }
    }

    // ---- 孤儿实体扫描与清理（供 cleanup 命令调用） ----

    /**
     * 检查实体是否为有效的"有 boards.yml 记录"的榜牌实体。
     */
    private boolean isValidBoardEntity(Entity entity) {
        if (!(entity instanceof TextDisplay)) return false;
        if (!entity.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE)) return false;
        UUID entityUuid = entity.getUniqueId();
        for (BoardData b : boards) {
            if (entityUuid.equals(b.getEntityUuid())) return true;
        }
        return false;
    }

    /**
     * 扫描孤儿实体信息。
     */
    public static final class OrphanScanResult {
        /** 孤儿实体列表（PDC 标记存在但 UUID 不在任何 boards.yml 记录中） */
        public final List<TextDisplay> orphans;
        /** 该世界所有带 PDC 标记的 TextDisplay 总数 */
        public final int totalPdcEntities;
        /** 有效榜牌实体数（UUID 与 boards.yml 匹配） */
        public final int validEntities;

        OrphanScanResult(List<TextDisplay> orphans, int totalPdcEntities, int validEntities) {
            this.orphans = orphans;
            this.totalPdcEntities = totalPdcEntities;
            this.validEntities = validEntities;
        }
    }

    /**
     * 扫描指定世界中所有带本插件 PDC 标记的 TextDisplay 实体，
     * 找出其中 UUID 不在 boards.yml 记录中的孤儿。
     * 仅扫描当前已加载区块中的实体 —— 如果孤儿可能在未加载区块，
     * 请走到附近触发区块加载后重新扫描。
     */
    public OrphanScanResult scanOrphans(String worldName) {
        World world = Bukkit.getWorld(worldName);
        List<TextDisplay> orphans = new ArrayList<>();
        int totalPdc = 0;
        int valid = 0;

        if (world == null) {
            return new OrphanScanResult(orphans, 0, 0);
        }

        // 收集所有 boards.yml 中记录的有效 UUID
        Set<UUID> validUuids = new HashSet<>();
        for (BoardData b : boards) {
            if (b.getWorldName().equals(worldName) && b.getEntityUuid() != null) {
                validUuids.add(b.getEntityUuid());
            }
        }

        // 遍历已加载区块中的所有实体
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof TextDisplay)) continue;
            if (!entity.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE)) continue;

            totalPdc++;
            if (validUuids.contains(entity.getUniqueId())) {
                valid++;
            } else {
                orphans.add((TextDisplay) entity);
            }
        }

        return new OrphanScanResult(orphans, totalPdc, valid);
    }

    /**
     * 删除指定孤儿实体列表。仅删除那些确实是 PDC 标记 + 不在 boards.yml 中的实体。
     * @return 实际删除的实体数量
     */
    public int cleanupOrphans(List<TextDisplay> orphans) {
        int removed = 0;
        for (TextDisplay td : orphans) {
            if (!td.isValid()) continue; // already removed
            if (!td.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE)) continue;
            // 二次确认：仍不在 boards.yml 记录中
            if (isValidBoardEntity(td)) continue; // 已被更新为有效榜牌，跳过
            td.remove();
            removed++;
        }
        return removed;
    }

    // ---- Pending cleanup 管理 ----

    public void setPendingCleanup(UUID playerUuid, List<TextDisplay> orphans) {
        // 包装为 List&lt;Entity&gt; 存储
        List<Entity> list = new ArrayList<>(orphans);
        pendingCleanup.put(playerUuid, list);
    }

    public List<Entity> getPendingCleanup(UUID playerUuid) {
        return pendingCleanup.get(playerUuid);
    }

    public void clearPendingCleanup(UUID playerUuid) {
        pendingCleanup.remove(playerUuid);
    }

    // ---- 位置+PDC 辅助方法（供 spawnEntity / cleanupDuplicates 使用） ----

    /**
     * 确保指定位置的区块已加载（用于后续实体操作）。
     */
    private void ensureChunkLoaded(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        World world = loc.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            world.loadChunk(cx, cz);
        }
    }

    /**
     * 在目标位置查找带本插件 PDC 标记的 TextDisplay（最多返回一个）。
     * 用于 addBoard 命令防重复创建。请调用前确保区块已加载。
     */
    private TextDisplay findExistingBoardEntity(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;
        Collection<Entity> nearby = world.getNearbyEntities(loc, 1.0, 1.0, 1.0,
                e -> e instanceof TextDisplay
                        && e.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE));
        for (Entity e : nearby) {
            if (e.getLocation().getBlockX() == loc.getBlockX()
                    && e.getLocation().getBlockY() == loc.getBlockY()
                    && e.getLocation().getBlockZ() == loc.getBlockZ()) {
                return (TextDisplay) e;
            }
        }
        return null;
    }

    /**
     * 查找目标位置所有带本插件 PDC 标记的 TextDisplay（用于重复检测和清理）。
     * 请调用前确保区块已加载。
     */
    private List<TextDisplay> findAllBoardEntities(Location loc) {
        List<TextDisplay> result = new ArrayList<>();
        World world = loc.getWorld();
        if (world == null) return result;
        Collection<Entity> nearby = world.getNearbyEntities(loc, 1.0, 1.0, 1.0,
                e -> e instanceof TextDisplay
                        && e.getPersistentDataContainer().has(pdcKey, PersistentDataType.BYTE));
        for (Entity e : nearby) {
            if (e.getLocation().getBlockX() == loc.getBlockX()
                    && e.getLocation().getBlockY() == loc.getBlockY()
                    && e.getLocation().getBlockZ() == loc.getBlockZ()) {
                result.add((TextDisplay) e);
            }
        }
        return result;
    }

    /**
     * 安全网：清理重复的榜牌实体。仅删除多余实体，绝不创建任何实体。
     * 只认本插件专属 PDC 标记（{@link #pdcKey}），不碰其他插件的 TextDisplay。
     * 先确保区块已加载，避免因区块未加载而漏检重复体。
     */
    public void cleanupDuplicates() {
        for (BoardData board : boards) {
            Location loc = board.toLocation();
            if (loc == null || loc.getWorld() == null) continue;

            // 确保区块已加载，否则 getNearbyEntities 可能漏检
            ensureChunkLoaded(loc);

            List<TextDisplay> found = findAllBoardEntities(loc);
            if (found.size() <= 1) continue;

            // 优先保留 UUID 匹配的，否则保留第一个
            TextDisplay keeper = null;
            if (board.getEntityUuid() != null) {
                for (TextDisplay td : found) {
                    if (td.getUniqueId().equals(board.getEntityUuid())) {
                        keeper = td;
                        break;
                    }
                }
            }
            if (keeper == null) {
                keeper = found.get(0);
                board.setEntityUuid(keeper.getUniqueId());
            }

            // 仅移除多余的（绝不在此方法中创建实体）
            for (TextDisplay td : found) {
                if (td != keeper) {
                    td.remove();
                    plugin.getLogger().warning("[清理重复] world=" + board.getWorldName()
                            + " tier=" + board.getTier()
                            + " removed_uuid=" + td.getUniqueId());
                }
            }
            if (found.size() > 1) {
                save();
            }
        }
    }

    // ---- Persistence ----

    private void load() {
        boards.clear();
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = config.getConfigurationSection("boards");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String worldName = sec.getString(key + ".world");
            String tier = sec.getString(key + ".tier");
            double x = sec.getDouble(key + ".x");
            double y = sec.getDouble(key + ".y");
            double z = sec.getDouble(key + ".z");
            String uuidStr = sec.getString(key + ".entity_uuid");
            UUID uuid = uuidStr != null ? UUID.fromString(uuidStr) : null;
            if (worldName != null && tier != null) {
                boards.add(new BoardData(worldName, tier, x, y, z, uuid));
            }
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (int i = 0; i < boards.size(); i++) {
            BoardData b = boards.get(i);
            String base = "boards." + i;
            config.set(base + ".world", b.getWorldName());
            config.set(base + ".tier", b.getTier());
            config.set(base + ".x", b.getX());
            config.set(base + ".y", b.getY());
            config.set(base + ".z", b.getZ());
            if (b.getEntityUuid() != null) {
                config.set(base + ".entity_uuid", b.getEntityUuid().toString());
            }
        }
        try { config.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Failed to save boards.yml: " + e.getMessage()); }
    }
}
