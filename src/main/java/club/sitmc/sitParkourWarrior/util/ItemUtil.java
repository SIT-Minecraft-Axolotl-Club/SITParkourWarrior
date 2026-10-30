package club.sitmc.sitParkourWarrior.util;

import club.sitmc.sitParkourWarrior.map.DynamicState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;

/**
 * PDC-based creation and identification of PKW interactive items.
 */
public final class ItemUtil {

    public static final String KEY_FORK_RETURN = "pkw_fork_return";
    public static final String KEY_QUIT = "pkw_quit";
    public static final String KEY_SETTLE = "pkws_settle";
    public static final String KEY_EDIT_TOOL = "pkw_edit_tool";
    public static final String KEY_EDIT_PAPER_MAP = "pkw_edit_paper_map";
    public static final String KEY_EDIT_PAPER_STATE = "pkw_edit_paper_state";

    /** Material for the play/pause controller tool. Avoids CLOCK (used by another plugin). */
    public static final Material EDIT_TOOL_MATERIAL = Material.COMPARATOR;

    private static NamespacedKey forkReturnKey;
    private static NamespacedKey quitKey;
    private static NamespacedKey settleKey;
    private static NamespacedKey editToolKey;
    private static NamespacedKey editPaperMapKey;
    private static NamespacedKey editPaperStateKey;

    private ItemUtil() {}

    public static void init(JavaPlugin plugin) {
        forkReturnKey = new NamespacedKey(plugin, KEY_FORK_RETURN);
        quitKey = new NamespacedKey(plugin, KEY_QUIT);
        settleKey = new NamespacedKey(plugin, KEY_SETTLE);
        editToolKey = new NamespacedKey(plugin, KEY_EDIT_TOOL);
        editPaperMapKey = new NamespacedKey(plugin, KEY_EDIT_PAPER_MAP);
        editPaperStateKey = new NamespacedKey(plugin, KEY_EDIT_PAPER_STATE);
    }

    public static ItemStack createForkReturnItem() {
        ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a返回岔路口");
        meta.setLore(Collections.singletonList("§7返回最近岔路口"));
        meta.getPersistentDataContainer().set(forkReturnKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack createQuitItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§c放弃跑酷");
        meta.setLore(Collections.singletonList("§7放弃当前跑酷"));
        meta.getPersistentDataContainer().set(quitKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isForkReturnItem(ItemStack item) {
        return hasPdc(item, forkReturnKey);
    }

    public static boolean isQuitItem(ItemStack item) {
        return hasPdc(item, quitKey);
    }

    public static ItemStack createSettlementItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§aPKWS 结算");
        meta.setLore(Collections.singletonList("§7右键结束比赛"));
        meta.getPersistentDataContainer().set(settleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isSettlementItem(ItemStack item) {
        return hasPdc(item, settleKey);
    }

    public static boolean isPkwItem(ItemStack item) {
        return isForkReturnItem(item) || isQuitItem(item) || isSettlementItem(item);
    }

    private static boolean hasPdc(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---- dynamic-level edit controller tool ----

    public static ItemStack createEditTool() {
        ItemStack item = new ItemStack(EDIT_TOOL_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color("&b[PKW编辑] &7暂无状态"));
        meta.setLore(Arrays.asList(
                Msg.color("&7左键：播放/暂停"),
                Msg.color("&7右键：打开状态列表")
        ));
        meta.getPersistentDataContainer().set(editToolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isEditTool(ItemStack item) {
        return hasPdc(item, editToolKey);
    }

    /** Rewrites the tool's display name in place (used to reflect current-state / playing status). */
    public static void updateEditToolName(ItemStack item, String displayName) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color(displayName));
        item.setItemMeta(meta);
    }

    // ---- dynamic-level state paper ----

    public static ItemStack createStatePaper(String mapId, DynamicState state, int oneBasedPosition) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        applyStatePaperMeta(meta, state, oneBasedPosition);
        meta.getPersistentDataContainer().set(editPaperMapKey, PersistentDataType.STRING, mapId.toLowerCase());
        meta.getPersistentDataContainer().set(editPaperStateKey, PersistentDataType.INTEGER, state.getId());
        item.setItemMeta(meta);
        return item;
    }

    /** Refreshes an existing paper's name/lore after the underlying state changed (e.g. interval adjusted). */
    public static void refreshStatePaperMeta(ItemStack item, DynamicState state, int oneBasedPosition) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        applyStatePaperMeta(meta, state, oneBasedPosition);
        item.setItemMeta(meta);
    }

    private static void applyStatePaperMeta(ItemMeta meta, DynamicState state, int oneBasedPosition) {
        meta.setDisplayName(Msg.color("&e" + state.getDisplayName(oneBasedPosition)));
        meta.setLore(Arrays.asList(
                Msg.color("&7序号: &f" + oneBasedPosition),
                Msg.color("&7间隔: &f" + state.getInterval() + " tick"),
                Msg.color("&7状态管理操作请使用状态列表GUI"),
                Msg.color("&7（手持控制器右键打开）")
        ));
    }

    public static boolean isEditPaper(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        // Must check PersistentDataType.STRING here, not the BYTE-only hasPdc()
        // helper: the map-id key is written as STRING in createStatePaper(), and
        // PersistentDataContainer#has() matches on type as well as key, so a BYTE
        // check against a STRING-typed key always silently returns false.
        return item.getItemMeta().getPersistentDataContainer().has(editPaperMapKey, PersistentDataType.STRING);
    }

    public static String getPaperMapId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(editPaperMapKey, PersistentDataType.STRING);
    }

    public static Integer getPaperStateId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(editPaperStateKey, PersistentDataType.INTEGER);
    }

    public static boolean isEditItem(ItemStack item) {
        return isEditTool(item) || isEditPaper(item);
    }
}
