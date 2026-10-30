package club.sitmc.sitParkourWarrior.editor;

import club.sitmc.sitParkourWarrior.map.DynamicData;
import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 rev 2: State-list chest GUI for the dynamic-level editor.
 * <p>
 * Opened by right-clicking the controller tool while paused. Supports:
 * <ul>
 *   <li><b>Left / Right click</b>: both act as "select &amp; drag" —
 *       pickup/swap/place papers within the GUI for reordering.
 *       Strictly confined to the top inventory.</li>
 *   <li><b>Middle click</b>: duplicate the clicked state (copy schematic,
 *       insert new state after the source, persist and refresh GUI).</li>
 *   <li><b>Delete slot</b>: bottom-right corner holds a red barrier icon.
 *       Place a state paper into that slot to delete the corresponding
 *       state (file + model + persist).</li>
 * </ul>
 * When the GUI closes, the slot order is read and the state list is
 * reordered accordingly and persisted. File renumbering is NOT performed
 * (deferred to a later phase).
 * <p>
 * State identity always uses the stable {@link DynamicState#getId() id},
 * never file names or positions. PDC tags on paper items carry this id.
 */
public final class StateListGui {

    private StateListGui() {}

    /** Identifies our state-list GUI in event handlers. */
    public static final class StateListHolder implements InventoryHolder {
        private final String mapId;
        boolean skipSaveOnClose;

        public StateListHolder(String mapId) {
            this.mapId = mapId;
        }

        public String getMapId() {
            return mapId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    // ---------------------------------------------------------------
    //  Delete-slot constants
    // ---------------------------------------------------------------

    private static final Material DELETE_MATERIAL = Material.BARRIER;
    private static final String DELETE_NAME = Msg.color("&c&l删除状态");
    private static final List<String> DELETE_LORE = Arrays.asList(
            Msg.color("&7将状态纸拖放到此槽位"),
            Msg.color("&7即可删除该状态"),
            Msg.color("&c⚠ 此操作不可逆，文件将被删除")
    );

    /** Number of fixed non-paper slots (currently 1: the delete slot). */
    private static final int FIXED_SLOTS = 1;

    // ---------------------------------------------------------------
    //  Open
    // ---------------------------------------------------------------

    /**
     * Opens the state-list GUI. Slot layout: slots {@code 0 .. size-2} hold
     * state papers in playback order; slot {@code size-1} is the fixed delete
     * slot.
     */
    public static void open(Player player, ParkourMap map, EditSessionManager esm) {
        List<DynamicState> states = map.getDynamicData().getStates();
        // Compute rows: enough for all papers + 1 delete icon.
        int neededSlots = states.size() + FIXED_SLOTS;
        int rows = Math.min(6, Math.max(1, (neededSlots + 8) / 9));
        int size = rows * 9;
        int deleteSlot = size - 1;

        StateListHolder holder = new StateListHolder(map.getId());
        Inventory inv = Bukkit.createInventory(holder, size,
                Msg.color("&8动态状态列表 — " + map.getId()));

        // Fill paper slots.
        for (int i = 0; i < states.size() && i < deleteSlot; i++) {
            inv.setItem(i, createGuiPaper(map.getId(), states.get(i), i + 1));
        }

        // Place delete icon in the bottom-right.
        inv.setItem(deleteSlot, createDeleteIcon());

        if (states.size() > deleteSlot) {
            Msg.send(player, "状态数量超过GUI容量，仅显示前" + deleteSlot + "个。");
        }

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    //  Item factories
    // ---------------------------------------------------------------

    private static ItemStack createGuiPaper(String mapId, DynamicState state, int position) {
        ItemStack item = ItemUtil.createStatePaper(mapId, state, position);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(Arrays.asList(
                    Msg.color("&7序号: &f" + position),
                    Msg.color("&7间隔: &f" + state.getInterval() + " tick"),
                    Msg.color("&7点击拖动排序 | 中键复制"),
                    Msg.color("&bShift+左键: 加载此状态进行编辑")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createDeleteIcon() {
        ItemStack item = new ItemStack(DELETE_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(DELETE_NAME);
        meta.setLore(DELETE_LORE);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isDeleteIcon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getType() == DELETE_MATERIAL
                && DELETE_NAME.equals(item.getItemMeta().getDisplayName());
    }

    // ---------------------------------------------------------------
    //  Rebuild GUI
    // ---------------------------------------------------------------

    private static void refreshGui(Player player, ParkourMap map) {
        Inventory topInv = player.getOpenInventory().getTopInventory();
        if (!(topInv.getHolder() instanceof StateListHolder)) return;

        List<DynamicState> states = map.getDynamicData().getStates();
        int deleteSlot = topInv.getSize() - 1;

        topInv.clear();
        for (int i = 0; i < states.size() && i < deleteSlot; i++) {
            topInv.setItem(i, createGuiPaper(map.getId(), states.get(i), i + 1));
        }
        topInv.setItem(deleteSlot, createDeleteIcon());
    }

    /**
     * Opens a fresh GUI with a resized inventory (used after duplicate pushes
     * the count past the current row limit, or after deletion shrinks below a
     * row boundary).
     */
    private static void reopen(Player player, ParkourMap map, EditSessionManager esm,
                               StateListHolder oldHolder) {
        oldHolder.skipSaveOnClose = true;
        player.closeInventory();
        JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("SITParkourWarrior");
        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> open(player, map, esm));
        }
    }

    // ---------------------------------------------------------------
    //  Click handler
    // ---------------------------------------------------------------

    /**
     * Processes a click inside our state-list GUI.
     * <ul>
     *   <li>Click outside the top inventory → cancel.</li>
     *   <li>Left / Right click in the top inventory → allow
     *       (natural pickup/swap/place for reorder).</li>
     *   <li>Middle click on a state paper → duplicate.</li>
     *   <li>Click on the delete slot while cursor holds a state paper
     *       → delete that state.</li>
     *   <li>Click on the delete slot with empty cursor → cancel
     *       (delete icon is not movable).</li>
     *   <li>All other click types → cancel.</li>
     * </ul>
     */
    public static void handleClick(InventoryClickEvent event, EditSessionManager esm,
                                   MapManager mapManager) {
        StateListHolder holder = (StateListHolder) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        String mapId = holder.getMapId();

        Inventory topInv = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();
        boolean isTopInv = clickedInv != null && clickedInv.equals(topInv);

        // ---- Clicks outside the top inventory → always cancel ----
        if (!isTopInv) {
            event.setCancelled(true);
            return;
        }

        ClickType click = event.getClick();
        ItemStack current = event.getCurrentItem();   // item in the clicked slot
        ItemStack cursor  = event.getCursor();         // item on the cursor

        int slot = event.getSlot();
        int deleteSlot = topInv.getSize() - 1;

        // ---- Delete-slot detection ----
        // Case A: cursor carries a state paper and clicks on the delete slot.
        // Case B: cursor is empty, player clicks on the delete icon — block pickup.
        if (slot == deleteSlot) {
            event.setCancelled(true);

            // Check if cursor holds a state paper → delete action.
            if (cursor != null && ItemUtil.isEditPaper(cursor)) {
                handleDelete(player, cursor, mapId, mapManager, esm);
                return;
            }
            // Otherwise (empty cursor clicking delete icon): deny pickup.
            return;
        }

        // ---- Shift+LeftClick: load state into edit area ----
        if (click == ClickType.SHIFT_LEFT) {
            event.setCancelled(true);
            if (current != null && ItemUtil.isEditPaper(current)) {
                handleSwitchState(player, current, holder, esm, mapManager);
            }
            return;
        }

        // ---- Left click / Right click: both allow drag-reorder ----
        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            // Do NOT cancel. Minecraft handles pickup/swap/place natively.
            // Movement out of the GUI is blocked by the !isTopInv guard above.
            return;
        }

        // ---- Middle click (or creative pick-block): duplicate ----
        if (click == ClickType.MIDDLE || click == ClickType.CREATIVE) {
            event.setCancelled(true);
            if (current != null && ItemUtil.isEditPaper(current)) {
                handleDuplicate(player, current, holder, mapId, mapManager, esm);
            }
            return;
        }

        // ---- All other click types (shift, double, number keys, drop, etc.) ----
        event.setCancelled(true);
    }

    // ---------------------------------------------------------------
    //  Duplicate
    // ---------------------------------------------------------------

    private static void handleDuplicate(Player player, ItemStack paper,
                                        StateListHolder holder, String mapId,
                                        MapManager mapManager, EditSessionManager esm) {
        Integer sourceStateId = ItemUtil.getPaperStateId(paper);
        if (sourceStateId == null) return;

        ParkourMap map = mapManager.getMap(mapId);
        if (map == null) return;

        DynamicData data = map.getDynamicData();
        DynamicState source = data.findById(sourceStateId);
        if (source == null) {
            Msg.send(player, "该状态已被删除，无法复制。");
            return;
        }

        List<DynamicState> states = data.getStates();
        int sourceIdx = -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).getId() == sourceStateId) { sourceIdx = i; break; }
        }
        if (sourceIdx == -1) return;

        // Copy schematic file.
        File mapFolder = mapManager.getMapFolder(map);
        File sourceFile = new File(mapFolder, source.getFile());
        if (!sourceFile.exists()) {
            Msg.send(player, "源状态文件缺失（" + source.getFile() + "），无法复制。");
            return;
        }

        int newId = data.nextId();
        String tempFileName = "new_" + UUID.randomUUID().toString().replace("-", "") + ".schem";
        File targetFile = new File(mapFolder, tempFileName);
        try {
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Msg.send(player, "复制原理图文件失败: " + e.getMessage());
            return;
        }

        String sourceName = source.getName();
        String copyName = (sourceName != null && !sourceName.isBlank())
                ? sourceName + " 副本" : null;

        DynamicState copy = new DynamicState(newId, tempFileName, copyName, source.getInterval());
        states.add(sourceIdx + 1, copy);
        mapManager.saveMap(map);

        // Check if we need to resize.
        Inventory topInv = player.getOpenInventory().getTopInventory();
        int deleteSlot = topInv.getSize() - 1;
        if (states.size() > deleteSlot) {
            reopen(player, map, esm, holder);
        } else {
            refreshGui(player, map);
        }

        Msg.send(player, "已复制状态: " + copy.getDisplayName(states.size()));
    }

    // ---------------------------------------------------------------
    //  Switch (Shift+LeftClick)
    // ---------------------------------------------------------------

    private static void handleSwitchState(Player player, ItemStack paper,
                                          StateListHolder holder, EditSessionManager esm,
                                          MapManager mapManager) {
        Integer stateId = ItemUtil.getPaperStateId(paper);
        String paperMapId = ItemUtil.getPaperMapId(paper);
        if (stateId == null || paperMapId == null) return;

        if (esm.switchToState(player, paperMapId, stateId)) {
            // Success — close the GUI so the player can see the loaded state.
            holder.skipSaveOnClose = true;
            player.closeInventory();
        }
    }

    // ---------------------------------------------------------------
    //  Delete
    // ---------------------------------------------------------------

    private static void handleDelete(Player player, ItemStack cursorPaper,
                                     String mapId, MapManager mapManager,
                                     EditSessionManager esm) {
        Integer stateId = ItemUtil.getPaperStateId(cursorPaper);
        String paperMapId = ItemUtil.getPaperMapId(cursorPaper);
        if (stateId == null || paperMapId == null || !mapId.equalsIgnoreCase(paperMapId)) {
            Msg.send(player, "该纸张不属于当前关卡，无法删除。");
            return;
        }

        ParkourMap map = mapManager.getMap(mapId);
        if (map == null) return;

        DynamicData data = map.getDynamicData();
        DynamicState target = data.findById(stateId);
        if (target == null) {
            Msg.send(player, "该状态已被删除，无需再次操作。");
            player.setItemOnCursor(null); // consume the stale paper
            return;
        }

        // Protection: refuse to delete the last remaining state.
        if (data.getStates().size() <= 1) {
            Msg.send(player, "无法删除：至少需要保留1个状态。若要清空整个关卡请使用 /sitpkw delete。");
            player.setItemOnCursor(null); // put paper back so it's not lost
            // Re-add the paper to the first GUI slot so the player can grab it.
            Inventory topInv = player.getOpenInventory().getTopInventory();
            topInv.setItem(0, cursorPaper);
            return;
        }

        // Delete the schematic file from disk.
        File schemFile = new File(mapManager.getMapFolder(map), target.getFile());
        if (schemFile.exists()) {
            if (!schemFile.delete()) {
                Msg.send(player, "删除原理图文件失败（" + target.getFile()
                        + "），请手动清理。状态已从列表移除。");
            }
        }

        // Remove from the state list.
        data.getStates().remove(target);
        mapManager.saveMap(map);

        // Consume the paper from cursor.
        player.setItemOnCursor(null);

        // Refresh GUI. If state count now fits in a smaller inventory, resize.
        Inventory topInv = player.getOpenInventory().getTopInventory();
        int currentDeleteSlot = topInv.getSize() - 1;
        int neededSlots = data.getStates().size() + FIXED_SLOTS;
        int newRows = Math.min(6, Math.max(1, (neededSlots + 8) / 9));
        int newSize = newRows * 9;

        if (newSize != topInv.getSize()) {
            StateListHolder holder = (StateListHolder) topInv.getHolder();
            reopen(player, map, esm, holder);
        } else {
            refreshGui(player, map);
        }

        // Refresh player inventory papers (remove any stale paper for the
        // deleted state, update positions for remaining).
        refreshPlayerPapers(player, map, data);

        // Warn if dynamic switching would not be active with < 2 states.
        if (data.getStates().size() < 2) {
            Msg.send(player, "注意：状态数已少于2，动态关卡将不会自动切换。"
                    + "添加至少2个状态后自动恢复。");
        }

        Msg.send(player, "已删除状态: " + target.getDisplayName(0));
    }

    // ---------------------------------------------------------------
    //  Close handler (save order only, NO file renumbering)
    // ---------------------------------------------------------------

    /**
     * Handles GUI close: cursor cleanup, read slot order, reorder the state
     * list, persist. File names are NOT renumbered (deferred to later phase).
     */
    public static void handleClose(InventoryCloseEvent event, EditSessionManager esm,
                                   MapManager mapManager) {
        StateListHolder holder = (StateListHolder) event.getInventory().getHolder();
        if (holder.skipSaveOnClose) return;

        Player player = (Player) event.getPlayer();
        String mapId = holder.getMapId();

        EditSession session = esm.getSession(player);
        if (session == null || !session.getMapId().equalsIgnoreCase(mapId)) return;
        if (session.isPlaying()) return;

        ParkourMap map = mapManager.getMap(mapId);
        if (map == null) return;

        Inventory inv = event.getInventory();
        DynamicData data = map.getDynamicData();
        List<DynamicState> oldStates = new ArrayList<>(data.getStates());

        // ---- cursor cleanup ----
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir() && ItemUtil.isEditPaper(cursor)) {
            boolean placed = false;
            int deleteSlot = inv.getSize() - 1;
            for (int i = 0; i < inv.getSize(); i++) {
                if (i == deleteSlot) continue; // never use the delete slot
                ItemStack slotItem = inv.getItem(i);
                if (slotItem == null || slotItem.getType().isAir()) {
                    inv.setItem(i, cursor);
                    placed = true;
                    break;
                }
            }
            if (placed) {
                player.setItemOnCursor(null);
            }
        }

        // ---- read order from GUI slots (skip the delete slot) ----
        List<Integer> newOrderIds = new ArrayList<>();
        int deleteSlot = inv.getSize() - 1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == deleteSlot) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || !ItemUtil.isEditPaper(item)) continue;
            Integer stateId = ItemUtil.getPaperStateId(item);
            String paperMapId = ItemUtil.getPaperMapId(item);
            if (stateId != null && mapId.equalsIgnoreCase(paperMapId)) {
                newOrderIds.add(stateId);
            }
        }

        // ---- validate ----
        if (newOrderIds.size() != oldStates.size()) {
            Msg.send(player, "状态数量与GUI内纸的数量不一致（"
                    + oldStates.size() + " vs " + newOrderIds.size()
                    + "），跳过保存。请重新打开GUI。");
            mapManager.saveMap(map);
            return;
        }

        // ---- check if order changed ----
        boolean changed = false;
        for (int i = 0; i < newOrderIds.size(); i++) {
            if (newOrderIds.get(i) != oldStates.get(i).getId()) {
                changed = true;
                break;
            }
        }

        if (!changed) {
            mapManager.saveMap(map);
            return;
        }

        // ---- build new ordered list ----
        List<DynamicState> newStates = new ArrayList<>();
        for (int id : newOrderIds) {
            DynamicState state = data.findById(id);
            if (state == null) {
                Msg.send(player, "内部错误：找不到状态 id=" + id + "，重排序中止。");
                return;
            }
            newStates.add(state);
        }

        // ---- apply new order (file names unchanged) ----
        data.getStates().clear();
        data.getStates().addAll(newStates);
        mapManager.saveMap(map);

        // ---- refresh ----
        refreshPlayerPapers(player, map, data);
        esm.refreshToolName(player, session);

        Msg.send(player, "状态顺序已保存。");
    }

    // ---------------------------------------------------------------
    //  Refresh helpers
    // ---------------------------------------------------------------

    /** Refreshes all state papers in the player's inventory after reorder or delete. */
    private static void refreshPlayerPapers(Player player, ParkourMap map, DynamicData data) {
        PlayerInventory inv = player.getInventory();
        List<DynamicState> states = data.getStates();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || !ItemUtil.isEditPaper(item)) continue;
            String paperMapId = ItemUtil.getPaperMapId(item);
            if (!map.getId().equalsIgnoreCase(paperMapId)) continue;

            Integer stateId = ItemUtil.getPaperStateId(item);
            if (stateId == null) continue;

            DynamicState state = data.findById(stateId);
            if (state == null) {
                // State was deleted — remove the stale paper.
                inv.setItem(i, null);
                continue;
            }

            int newPos = 1;
            for (DynamicState s : states) {
                if (s.getId() == stateId) break;
                newPos++;
            }
            ItemUtil.refreshStatePaperMeta(item, state, newPos);
        }
    }
}
