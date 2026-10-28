package club.sitmc.sitParkourWarrior.editor;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SchematicService;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every player's dynamic-level {@link EditSession}: play/pause state
 * machine, dirty tracking, autosave, state switching, interval tuning, and
 * the temporary in-world "edit copy" generated at the player's feet.
 * <p>
 * Core rule: all data mutation (dirty region edits, state switches, interval
 * changes, autosave) only ever happens while a session is paused. Playback
 * pastes into the session's temporary edit region directly and is independent
 * of the runtime {@code DynamicService}/{@code DynamicTask} deployment engine.
 */
public class EditSessionManager {
    private final SITParkourWarrior plugin;
    private final MapManager mapManager;
    private final SelectionManager selectionManager;
    private final SchematicService schematicService = new SchematicService();
    private final Map<UUID, EditSession> sessions = new HashMap<>();

    public EditSessionManager(SITParkourWarrior plugin, MapManager mapManager, SelectionManager selectionManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.selectionManager = selectionManager;
    }

    public EditSession getSession(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    /**
     * Enters (or switches into) an edit session for the given map.
     * <p>
     * Like {@code /sitpkw deploy}, this generates a temporary working copy at the
     * player's current position instead of sending them to the map's original
     * template location:
     * <ul>
     *   <li>Map has a region and states: the first state's schematic is pasted
     *       into the temp copy.</li>
     *   <li>Map has a region but no states yet: the temp copy is only outlined
     *       (pos1/pos2 + particle boundary), nothing is pasted, so the player can
     *       build from scratch.</li>
     *   <li>Map has no region at all (brand new): nothing is generated; the
     *       player selects manually as before.</li>
     * </ul>
     * If the player already has a session on a different map it is exited first
     * (save-if-dirty + temp region cleanup + reclaim items) so state never leaks
     * across maps.
     */
    public void startSession(Player player, ParkourMap map) {
        if (player == null || map == null) return;
        EditSession existing = sessions.get(player.getUniqueId());
        if (existing != null) {
            if (existing.getMapId().equalsIgnoreCase(map.getId())) {
                // Re-entering the same map while still editing it: clean up
                // legacy papers, top up tool, keep the existing temp region.
                cleanupLegacyPapers(player);
                giveEditToolIfAbsent(player);
                refreshToolName(player, existing);
                return;
            }
            exitSession(player);
        }

        List<DynamicState> states = map.getDynamicData().getStates();
        DynamicState initial = states.isEmpty() ? null : states.get(0);

        Region editRegion = computeEditRegion(map, player.getLocation());
        if (editRegion != null) {
            // ---- Pre-check: region must be empty before we touch a single block ----
            String blockError = checkRegionOccupied(player.getWorld(), editRegion);
            if (blockError != null) {
                Msg.send(player, blockError);
                return;   // <-- HARD abort: zero side effects, no session, no blocks, no tool
            }
        }

        EditSession session = new EditSession(map.getId(), initial);

        if (editRegion != null) {
            session.setEditRegion(editRegion);
            boolean pasted = initial != null && pasteIntoRegion(map, editRegion, initial.getFile());
            selectionManager.restoreRegionSelection(player, editRegion);
            String contentNote;
            if (initial == null) {
                contentNote = "（空白，等待搭建）";
            } else if (pasted) {
                contentNote = "（已加载当前状态）";
            } else {
                contentNote = "（当前状态的 schem 文件缺失，未能加载，区域为空）";
            }
            Msg.send(player, "已在脚下生成临时编辑区" + contentNote + "，尺寸与关卡模板一致。");
        } else if (map.getRegion() == null) {
            Msg.send(player, "该关卡尚无边界，请用 pos1/pos2 手动选区后 /sitpkw save 建立第一个状态。");
        }

        sessions.put(player.getUniqueId(), session);
        cleanupLegacyPapers(player);
        giveEditToolIfAbsent(player);
        refreshToolName(player, session);
    }

    /**
     * Leaves the edit session, in order: (1) stop any running preview playback,
     * (2) flush pending edits if dirty, (3) persist the map's full data
     * unconditionally, (4) reclaim the controller tool and all state papers,
     * (5) wipe the temporary edit-region copy back to air — always after saving,
     * never before, so nothing unsaved is ever lost.
     */
    public void exitSession(Player player) {
        if (player == null) return;
        EditSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;

        // 1. Stop playback.
        stopPreview(session);
        session.setPlaying(false);

        ParkourMap map = mapManager.getMap(session.getMapId());
        if (map != null) {
            // 2. Flush pending edits (no-op if not dirty; playing sessions never accrue dirty).
            saveIfDirty(player, map, session);
            // 3. Persist the map's full data (state list / names / intervals / order) unconditionally.
            mapManager.saveMap(map);
        }

        // 4. Clean up legacy papers + reclaim controller tool.
        cleanupLegacyPapers(player);
        reclaimItems(player);

        // 5. Clear the temporary edit-region copy — must run after saving.
        if (session.getEditRegion() != null) {
            schematicService.clearRegion(session.getEditRegion());
        }

        Msg.send(player, "已保存并清理编辑区。");
    }

    /** Called on quit / world change: same handling as an explicit /sitpkw exit. */
    public void handlePlayerLeaving(Player player) {
        exitSession(player);
    }

    /** Server shutdown: flush every dirty paused session and wipe temp regions, no item reclaim needed. */
    public void shutdown() {
        for (EditSession session : sessions.values()) {
            stopPreview(session);
            ParkourMap map = mapManager.getMap(session.getMapId());
            if (map != null && !session.isPlaying() && session.isDirty() && session.getCurrentState() != null) {
                Region region = session.getEditRegion();
                if (region != null) {
                    File schemFile = new File(mapManager.getMapFolder(map), session.getCurrentState().getFile());
                    if (schematicService.saveRegionToSchem(region, schemFile)) {
                        mapManager.saveMap(map);
                    }
                }
            }
            if (session.getEditRegion() != null) {
                schematicService.clearRegion(session.getEditRegion());
            }
        }
        sessions.clear();
    }

    // ---- temp edit-region placement (mirrors DPCommand.handleDeploy's anchor rules) ----

    /**
     * Resolves the same reference point {@code handleDeploy} uses to translate a
     * map's template onto a new location: the FORK node's first branch point,
     * the end point for END-type nodes, otherwise the start point. Unlike deploy
     * (which requires it to be set), this may return null if that point hasn't
     * been placed yet — the caller falls back to anchoring on the player's feet.
     */
    private PointLocation resolveAnchor(ParkourMap map) {
        NodeType type = map.getNodeType();
        switch (type) {
            case FORK:
                List<PointLocation> points = map.getForkBranchPoints();
                return points.isEmpty() ? null : points.get(0);
            case GLOBAL_END:
            case BRANCH_END:
            case PKWS_END:
                return map.getEnd();
            default:
                return map.getStart();
        }
    }

    /**
     * Computes the temp edit region: same size as {@code map.getRegion()} always
     * (guaranteeing exported schematics stay deploy-compatible), positioned by
     * translating the template so its anchor point lands on the player's current
     * block (same offset math as handleDeploy). If the map has no anchor point
     * set yet (e.g. start/end not placed), the region's min corner is simply
     * placed at the player's feet instead.
     */
    private Region computeEditRegion(ParkourMap map, Location playerLoc) {
        Region template = map.getRegion();
        if (template == null || playerLoc == null || playerLoc.getWorld() == null) {
            return null;
        }
        Location origin = playerLoc.getBlock().getLocation();
        int sizeX = template.getMaxX() - template.getMinX();
        int sizeY = template.getMaxY() - template.getMinY();
        int sizeZ = template.getMaxZ() - template.getMinZ();

        PointLocation refPoint = resolveAnchor(map);
        int newMinX, newMinY, newMinZ;
        if (refPoint != null) {
            newMinX = template.getMinX() + (origin.getBlockX() - refPoint.getBlockX());
            newMinY = template.getMinY() + (origin.getBlockY() - refPoint.getBlockY());
            newMinZ = template.getMinZ() + (origin.getBlockZ() - refPoint.getBlockZ());
        } else {
            newMinX = origin.getBlockX();
            newMinY = origin.getBlockY();
            newMinZ = origin.getBlockZ();
        }
        return new Region(origin.getWorld().getName(), newMinX, newMinY, newMinZ,
                newMinX + sizeX, newMinY + sizeY, newMinZ + sizeZ);
    }

    /**
     * Validates that the edit region is clear before we generate anything.
     * <p>
     * Rules:
     * <ul>
     *   <li>Y must be within the world's height limits ({@code getMinHeight()} to
     *       {@code getMaxHeight() - 1}) for every vertical slice.</li>
     *   <li>Every block must be one of AIR, CAVE_AIR, or VOID_AIR. Everything
     *       else — grass, flowers, water, lava, any replaceable block — counts
     *       as non-empty.</li>
     * </ul>
     *
     * @return {@code null} if the region is completely empty and within Y bounds;
     *         otherwise a human-readable error message suitable for
     *         {@link Msg#send(Player, String)} that includes the first blocked
     *         coordinate and both region corners
     */
    private String checkRegionOccupied(World world, Region region) {
        if (world == null) {
            return "目标世界未加载，无法检测占用。";
        }

        int worldMinY = world.getMinHeight();
        // getMaxHeight() is the exclusive upper bound.
        int worldMaxY = world.getMaxHeight() - 1;

        // ---- Y-bounds check ----
        if (region.getMinY() < worldMinY || region.getMaxY() > worldMaxY) {
            return Msg.color("&c无法生成编辑区：关卡区域超出世界高度限制。")
                    + "\n" + Msg.color("&7区域Y范围: &f" + region.getMinY() + " ~ " + region.getMaxY())
                    + "\n" + Msg.color("&7世界Y限制: &f" + worldMinY + " ~ " + worldMaxY)
                    + "\n" + Msg.color("&7区域角点: &f("
                    + region.getMinX() + ", " + region.getMinY() + ", " + region.getMinZ() + ") &7→ &f("
                    + region.getMaxX() + ", " + region.getMaxY() + ", " + region.getMaxZ() + ")");
        }

        // ---- Block occupancy scan ----
        for (int x = region.getMinX(); x <= region.getMaxX(); x++) {
            for (int y = region.getMinY(); y <= region.getMaxY(); y++) {
                for (int z = region.getMinZ(); z <= region.getMaxZ(); z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
                        return Msg.color("&c该区域已有方块，本次编辑已取消。请移动到空旷位置后重试。")
                                + "\n" + Msg.color("&7首个非空方块: &f" + type.name()
                                + " &7@ (&f" + x + ", " + y + ", " + z + "&7)")
                                + "\n" + Msg.color("&7占用范围角点: &f("
                                + region.getMinX() + ", " + region.getMinY() + ", " + region.getMinZ() + ") &7→ &f("
                                + region.getMaxX() + ", " + region.getMaxY() + ", " + region.getMaxZ() + ")");
                    }
                }
            }
        }

        return null; // All clear.
    }

    // ---- dirty tracking ----

    /** Marks the current session dirty; a no-op while playing (playback never dirties). */
    public void markDirty(Player player) {
        EditSession session = getSession(player);
        if (session == null || session.isPlaying()) return;
        session.setDirty(true);
    }

    // ---- autosave (60s fallback tick, see SITParkourWarrior) ----

    public void autosaveTick() {
        for (Map.Entry<UUID, EditSession> entry : sessions.entrySet()) {
            EditSession session = entry.getValue();
            if (session.isPlaying() || !session.isDirty()) continue;
            ParkourMap map = mapManager.getMap(session.getMapId());
            if (map == null) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            saveIfDirty(player, map, session);
        }
    }

    private void saveIfDirty(Player player, ParkourMap map, EditSession session) {
        if (!session.isDirty() || session.getCurrentState() == null) {
            return;
        }
        Region region = session.getEditRegion();
        if (region == null) {
            return;
        }
        File schemFile = new File(mapManager.getMapFolder(map), session.getCurrentState().getFile());
        boolean ok = schematicService.saveRegionToSchem(region, schemFile);
        if (ok) {
            session.setDirty(false);
            mapManager.saveMap(map);
            if (player != null) {
                Msg.send(player, "已保存当前状态: " + session.getCurrentState().getDisplayName(positionOf(map, session.getCurrentState())));
            }
        } else if (player != null) {
            Msg.send(player, "保存失败，请检查区域与 WorldEdit。");
        }
    }

    // ---- play / pause ----

    public void togglePlayPause(Player player) {
        EditSession session = getSession(player);
        if (session == null) {
            Msg.send(player, "请先使用 /sitpkw edit <id> 进入编辑模式。");
            return;
        }
        if (session.isPlaying()) {
            pause(player, session);
        } else {
            play(player, session);
        }
    }

    private void play(Player player, EditSession session) {
        ParkourMap map = mapManager.getMap(session.getMapId());
        if (map == null) return;
        List<DynamicState> states = map.getDynamicData().getStates();
        if (states.isEmpty()) {
            Msg.send(player, "当前没有任何状态，无法播放。");
            return;
        }
        Region region = session.getEditRegion();
        if (region == null) {
            Msg.send(player, "该关卡尚未生成编辑区域。");
            return;
        }
        // Playback overwrites the edit region in place, so flush pending edits first.
        saveIfDirty(player, map, session);
        session.setPlaying(true);
        // Resume the cycle starting at whichever state the player was paused on,
        // rather than always restarting from the first state.
        int startIdx = session.getCurrentState() != null
                ? Math.max(0, positionOf(map, session.getCurrentState()) - 1)
                : 0;
        session.setPreviewIndex(startIdx - 1);
        refreshToolName(player, session);
        Msg.send(player, "开始播放预览，左键控制器物品可暂停。");
        schedulePreviewStep(player, session, map, region, 1L);
    }

    private void schedulePreviewStep(Player player, EditSession session, ParkourMap map, Region region, long delayTicks) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!session.isPlaying()) return;
            List<DynamicState> states = map.getDynamicData().getStates();
            if (states.isEmpty()) {
                pause(player, session);
                return;
            }
            int idx = session.getPreviewIndex() + 1;
            if (idx >= states.size()) {
                idx = 0;
            }
            DynamicState state = states.get(idx);
            pasteIntoRegion(map, region, state.getFile());
            session.setPreviewIndex(idx);
            schedulePreviewStep(player, session, map, region, Math.max(1, state.getInterval()));
        }, delayTicks);
        session.setPreviewTask(task);
    }

    private void pause(Player player, EditSession session) {
        stopPreview(session);
        session.setPlaying(false);
        ParkourMap map = mapManager.getMap(session.getMapId());
        if (map != null) {
            List<DynamicState> states = map.getDynamicData().getStates();
            int lastIdx = session.getPreviewIndex();
            if (lastIdx >= 0 && lastIdx < states.size()) {
                session.setCurrentState(states.get(lastIdx));
            }
        }
        Msg.send(player, "已暂停播放，当前编辑归属于该状态。");
        refreshToolName(player, session);
    }

    private void stopPreview(EditSession session) {
        BukkitTask task = session.getPreviewTask();
        if (task != null) {
            task.cancel();
            session.setPreviewTask(null);
        }
    }

    private boolean pasteIntoRegion(ParkourMap map, Region region, String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) return false;
        File schemFile = new File(mapManager.getMapFolder(map), fileName);
        Location origin = new Location(world, region.getMinX(), region.getMinY(), region.getMinZ());
        return schematicService.pasteSchematic(schemFile, origin);
    }

    /**
     * Switches the edit session to a different state, saving any pending edits
     * first. Intended for GUI-based state switching (Shift+LeftClick).
     *
     * @return true if the switch succeeded
     */
    public boolean switchToState(Player player, String mapId, int stateId) {
        EditSession session = getSession(player);
        if (session == null) {
            Msg.send(player, "请先使用 /sitpkw edit <id> 进入编辑模式。");
            return false;
        }
        if (session.isPlaying()) {
            Msg.send(player, "播放中，请先暂停。");
            return false;
        }
        if (!session.getMapId().equalsIgnoreCase(mapId)) {
            Msg.send(player, "该状态不属于当前编辑的关卡。");
            return false;
        }
        ParkourMap map = mapManager.getMap(mapId);
        if (map == null) return false;
        DynamicState target = map.getDynamicData().findById(stateId);
        if (target == null) {
            Msg.send(player, "该状态已被删除。");
            return false;
        }
        // If already on this state, nothing to do.
        if (session.getCurrentState() != null && session.getCurrentState().getId() == target.getId()) {
            return true;
        }
        return switchState(player, map, session, target);
    }

    /** Internal: save-if-dirty, paste the target's schematic, update session. */
    private boolean switchState(Player player, ParkourMap map, EditSession session, DynamicState target) {
        saveIfDirty(player, map, session);
        Region region = session.getEditRegion();
        if (region != null) {
            pasteIntoRegion(map, region, target.getFile());
        }
        session.setCurrentState(target);
        session.setDirty(false);
        refreshToolName(player, session);
        Msg.send(player, "已切换到状态: " + target.getDisplayName(positionOf(map, target)));
        return true;
    }

    // ---- items ----

    /**
     * Grants the controller tool if the player doesn't already have one.
     * Used both when entering edit mode and by the manual /sitpkw tool command,
     * so entry and the manual top-up command never hand out duplicates.
     *
     * @return true if a tool was actually granted, false if they already had one.
     */
    public boolean giveEditToolIfAbsent(Player player) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (item != null && ItemUtil.isEditTool(item)) {
                return false;
            }
        }
        inv.addItem(ItemUtil.createEditTool());
        return true;
    }

    /** Reclaims the controller tool only. State papers are no longer issued so there's nothing to reclaim. */
    private void reclaimItems(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (ItemUtil.isEditTool(item)) {
                inv.setItem(i, null);
            }
        }
    }

    /**
     * Scans the player's entire inventory and removes any state paper matching
     * {@link ItemUtil#isEditPaper}. Called on edit entry, edit exit, world
     * change, and quit so legacy papers from an earlier version are swept.
     */
    public void cleanupLegacyPapers(Player player) {
        PlayerInventory inv = player.getInventory();
        boolean removedAny = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (ItemUtil.isEditPaper(item)) {
                inv.setItem(i, null);
                removedAny = true;
            }
        }
        if (removedAny) {
            Msg.send(player, "已清理背包中的旧版状态纸。");
        }
    }

    public void refreshToolName(Player player, EditSession session) {
        String name;
        if (session.isPlaying()) {
            name = "&c[PKW编辑] &f播放中...";
        } else if (session.getCurrentState() == null) {
            name = "&b[PKW编辑] &7暂无状态";
        } else {
            ParkourMap map = mapManager.getMap(session.getMapId());
            int position = map != null ? positionOf(map, session.getCurrentState()) : 1;
            name = "&b[PKW编辑] &f" + session.getCurrentState().getDisplayName(position);
        }
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (ItemUtil.isEditTool(item)) {
                ItemUtil.updateEditToolName(item, name);
            }
        }
    }

    /** 1-based position of a state within its map's ordered list, looked up by stable id. */
    public int positionOf(ParkourMap map, DynamicState state) {
        List<DynamicState> states = map.getDynamicData().getStates();
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).getId() == state.getId()) {
                return i + 1;
            }
        }
        return 1;
    }
}
