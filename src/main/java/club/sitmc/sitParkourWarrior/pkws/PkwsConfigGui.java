package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Administrator-only PKWS metadata overview and editor.
 *
 * <p>This GUI intentionally has no relationship with the Lobby menu or the
 * dynamic-level editor.  A category holder keeps a snapshot of the displayed
 * order.  Metadata changes are persisted immediately, but normal clicks only
 * re-render that snapshot; the save button creates a fresh snapshot and thus
 * performs the requested reclassification/reordering.</p>
 */
public final class PkwsConfigGui {

    public static final int UNCONFIGURED = 0;

    private static final int OVERVIEW_SIZE = 27;
    private static final int OVERVIEW_UNCONFIGURED_SLOT = 4;
    private static final List<Integer> OVERVIEW_DIFFICULTY_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16, 17,
            19, 20, 21, 22, 23, 24, 25, 26
    );

    private static final int SAVE_SLOT = 45;
    private static final int PAGE_SLOT = 49;
    private static final int RETURN_SLOT = 53;
    private static final List<Integer> LEFT_SLOTS = List.of(
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30,
            36, 37, 38, 39
    );
    private static final List<Integer> RIGHT_SLOTS = List.of(
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44
    );
    private static final List<Integer> FLAT_SLOTS = createFlatSlots();
    private static final Comparator<ParkourMap> WEIGHT_ORDER = Comparator
            .comparingInt(ParkourMap::getPkwsWeight)
            .reversed()
            .thenComparing(PkwsConfigGui::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ParkourMap::getId, String.CASE_INSENSITIVE_ORDER);

    private PkwsConfigGui() {
    }

    public static void openOverview(Player player, MapManager mapManager) {
        Inventory inventory = Bukkit.createInventory(new OverviewHolder(), OVERVIEW_SIZE,
                Msg.color("&8PKWS 配置总览"));
        fillWithPane(inventory, Material.GRAY_STAINED_GLASS_PANE);

        List<ParkourMap> maps = getLevelMaps(mapManager);
        for (int difficulty = 1; difficulty <= 16; difficulty++) {
            int count = countForCategory(maps, difficulty);
            inventory.setItem(OVERVIEW_DIFFICULTY_SLOTS.get(difficulty - 1),
                    createCategoryItem(difficulty, count));
        }
        inventory.setItem(OVERVIEW_UNCONFIGURED_SLOT,
                createCategoryItem(UNCONFIGURED, countForCategory(maps, UNCONFIGURED)));
        player.openInventory(inventory);
    }

    public static void openCategory(Player player, MapManager mapManager, int category, int page) {
        CategoryHolder holder = createHolder(mapManager, category, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Msg.color("&8PKWS 配置 - " + categoryName(category)
                        + " &7(" + (holder.getPage() + 1) + "/" + holder.getTotalPages() + ")"));
        renderCategory(inventory, holder, mapManager);
        player.openInventory(inventory);
    }

    public static void handleClick(InventoryClickEvent event, MapManager mapManager) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }

        if (event.getInventory().getHolder() instanceof OverviewHolder) {
            handleOverviewClick(player, event.getRawSlot(), mapManager);
            return;
        }
        if (event.getInventory().getHolder() instanceof CategoryHolder holder) {
            handleCategoryClick(player, event.getRawSlot(), event.getClick(), holder, mapManager);
        }
    }

    public static void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        event.setCancelled(true);
    }

    private static void handleOverviewClick(Player player, int slot, MapManager mapManager) {
        int category = OVERVIEW_DIFFICULTY_SLOTS.indexOf(slot);
        if (category >= 0) {
            openCategory(player, mapManager, category + 1, 0);
        } else if (slot == OVERVIEW_UNCONFIGURED_SLOT) {
            openCategory(player, mapManager, UNCONFIGURED, 0);
        }
    }

    private static void handleCategoryClick(Player player, int slot, ClickType click,
                                             CategoryHolder holder, MapManager mapManager) {
        if (slot == SAVE_SLOT) {
            openCategory(player, mapManager, holder.getCategory(), holder.getPage());
            Msg.send(player, "已保存并按当前参数重排。");
            return;
        }
        if (slot == PAGE_SLOT) {
            holder.nextPage();
            renderCategory(player.getOpenInventory().getTopInventory(), holder, mapManager);
            return;
        }
        if (slot == RETURN_SLOT) {
            openOverview(player, mapManager);
            return;
        }

        String mapId = holder.getMapIdAt(slot);
        if (mapId == null) {
            return;
        }
        ParkourMap map = mapManager.getMap(mapId);
        if (map == null) {
            return;
        }

        boolean changed = false;
        if (click == ClickType.LEFT) {
            if (map.getPkwsWeight() < Integer.MAX_VALUE) {
                map.setPkwsWeight(map.getPkwsWeight() + 1);
                changed = true;
            }
        } else if (click == ClickType.RIGHT) {
            if (map.getPkwsWeight() > 0) {
                map.setPkwsWeight(map.getPkwsWeight() - 1);
                changed = true;
            }
        } else if (click == ClickType.SHIFT_LEFT) {
            Integer difficulty = map.getPkwsDifficulty();
            int next = difficulty == null ? 1 : Math.min(16, difficulty + 1);
            if (!Integer.valueOf(next).equals(difficulty)) {
                map.setPkwsDifficulty(next);
                changed = true;
            }
        } else if (click == ClickType.SHIFT_RIGHT) {
            Integer difficulty = map.getPkwsDifficulty();
            if (difficulty != null && difficulty > 1) {
                map.setPkwsDifficulty(difficulty - 1);
                changed = true;
            }
        } else if (click == ClickType.MIDDLE) {
            map.setPkwsOrientation(nextOrientation(map.getPkwsOrientation()));
            changed = true;
        }

        if (changed) {
            mapManager.saveMap(map);
        }
        // Re-render from the holder's stable order.  Classification and
        // sorting are deliberately deferred until SAVE_SLOT is pressed.
        renderCategory(player.getOpenInventory().getTopInventory(), holder, mapManager);
    }

    private static PkwsOrientation nextOrientation(PkwsOrientation orientation) {
        if (orientation == null) {
            return PkwsOrientation.NS;
        }
        return orientation == PkwsOrientation.NS ? PkwsOrientation.EW : PkwsOrientation.NS;
    }

    private static CategoryHolder createHolder(MapManager mapManager, int category, int page) {
        List<ParkourMap> maps = getLevelMaps(mapManager);
        if (category == UNCONFIGURED) {
            List<String> flat = maps.stream()
                    .filter(map -> isUnconfigured(map))
                    .sorted(WEIGHT_ORDER)
                    .map(ParkourMap::getId)
                    .toList();
            CategoryHolder holder = new CategoryHolder(category, page, List.of(), List.of(), flat);
            holder.clampPage();
            return holder;
        }

        List<String> ns = maps.stream()
                .filter(map -> map.getPkwsDifficulty() != null
                        && map.getPkwsDifficulty() == category
                        && map.getPkwsOrientation() == PkwsOrientation.NS)
                .sorted(WEIGHT_ORDER)
                .map(ParkourMap::getId)
                .toList();
        List<String> ew = maps.stream()
                .filter(map -> map.getPkwsDifficulty() != null
                        && map.getPkwsDifficulty() == category
                        && map.getPkwsOrientation() == PkwsOrientation.EW)
                .sorted(WEIGHT_ORDER)
                .map(ParkourMap::getId)
                .toList();
        CategoryHolder holder = new CategoryHolder(category, page, ns, ew, List.of());
        holder.clampPage();
        return holder;
    }

    private static void renderCategory(Inventory inventory, CategoryHolder holder, MapManager mapManager) {
        inventory.clear();
        fillWithPane(inventory, Material.GRAY_STAINED_GLASS_PANE);

        if (holder.getCategory() == UNCONFIGURED) {
            int start = holder.getPage() * FLAT_SLOTS.size();
            for (int i = 0; i < FLAT_SLOTS.size(); i++) {
                int index = start + i;
                if (index >= holder.getFlatMapIds().size()) break;
                ParkourMap map = mapManager.getMap(holder.getFlatMapIds().get(index));
                if (map != null) {
                    inventory.setItem(FLAT_SLOTS.get(i), createMapIcon(map));
                }
            }
        } else {
            int start = holder.getPage() * LEFT_SLOTS.size();
            for (int i = 0; i < LEFT_SLOTS.size(); i++) {
                int index = start + i;
                if (index < holder.getLeftMapIds().size()) {
                    ParkourMap map = mapManager.getMap(holder.getLeftMapIds().get(index));
                    if (map != null) inventory.setItem(LEFT_SLOTS.get(i), createMapIcon(map));
                }
                if (index < holder.getRightMapIds().size()) {
                    ParkourMap map = mapManager.getMap(holder.getRightMapIds().get(index));
                    if (map != null) inventory.setItem(RIGHT_SLOTS.get(i), createMapIcon(map));
                }
            }
            for (int slot : List.of(4, 13, 22, 31, 40)) {
                inventory.setItem(slot, createPane(Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        inventory.setItem(SAVE_SLOT, createButton(Material.EMERALD, "&a&l保存并重排",
                "&7重新分类并按权重排序"));
        inventory.setItem(PAGE_SLOT, createButton(Material.CLOCK, "&e&l切换页面",
                "&7当前页面: &f" + (holder.getPage() + 1) + "/" + holder.getTotalPages()));
        inventory.setItem(RETURN_SLOT, createButton(Material.ARROW, "&b&l返回",
                "&7返回 PKWS 难度总览"));
    }

    private static ItemStack createCategoryItem(int category, int count) {
        Material material = category == UNCONFIGURED ? Material.BARRIER : Material.BOOK;
        String name = category == UNCONFIGURED ? "&c&l未配置" : "&b&l难度 " + category;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        meta.setLore(List.of(
                Msg.color("&7关卡数量: &f" + count),
                Msg.color("&e点击进入详情")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createMapIcon(ParkourMap map) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color("&e" + displayName(map)));
        meta.setLore(List.of(
                Msg.color("&7ID: &f" + map.getId()),
                Msg.color("&7难度: &f" + formatDifficulty(map.getPkwsDifficulty())),
                Msg.color("&7朝向: &f" + formatOrientation(map.getPkwsOrientation())),
                Msg.color("&7权重: &f" + map.getPkwsWeight()),
                Msg.color("&8左键: 权重 +1 | 右键: 权重 -1"),
                Msg.color("&8Shift 左/右键: 难度 +1/-1"),
                Msg.color("&8中键: 未设置 → NS → EW → NS"),
                Msg.color("&7所有改动立即保存；保存并重排后移动分类")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createButton(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        meta.setLore(List.of(Msg.color(lore)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private static void fillWithPane(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, createPane(material));
        }
    }

    private static List<ParkourMap> getLevelMaps(MapManager mapManager) {
        List<ParkourMap> maps = new ArrayList<>();
        for (ParkourMap map : mapManager.getMaps().values()) {
            if (map.getNodeType() == NodeType.LEVEL) {
                maps.add(map);
            }
        }
        return maps;
    }

    private static int countForCategory(List<ParkourMap> maps, int category) {
        int count = 0;
        for (ParkourMap map : maps) {
            if (category == UNCONFIGURED ? isUnconfigured(map) : isInCategory(map, category)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isInCategory(ParkourMap map, int category) {
        return map.getPkwsDifficulty() != null
                && map.getPkwsDifficulty() == category
                && map.getPkwsOrientation() != null;
    }

    private static boolean isUnconfigured(ParkourMap map) {
        return map.getPkwsDifficulty() == null || map.getPkwsOrientation() == null;
    }

    private static String categoryName(int category) {
        return category == UNCONFIGURED ? "未配置" : "难度 " + category;
    }

    private static String displayName(ParkourMap map) {
        return map.getTitle() == null || map.getTitle().isBlank() ? map.getId() : map.getTitle();
    }

    private static String formatDifficulty(Integer difficulty) {
        return difficulty == null ? "未设置" : String.valueOf(difficulty);
    }

    private static String formatOrientation(PkwsOrientation orientation) {
        if (orientation == null) return "未设置";
        return orientation == PkwsOrientation.NS ? "NS（南北）" : "EW（东西）";
    }

    private static List<Integer> createFlatSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 45; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    public static final class OverviewHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static final class CategoryHolder implements InventoryHolder {
        private final int category;
        private int page;
        private final List<String> leftMapIds;
        private final List<String> rightMapIds;
        private final List<String> flatMapIds;

        private CategoryHolder(int category, int page, List<String> leftMapIds,
                               List<String> rightMapIds, List<String> flatMapIds) {
            this.category = category;
            this.page = Math.max(0, page);
            this.leftMapIds = List.copyOf(leftMapIds);
            this.rightMapIds = List.copyOf(rightMapIds);
            this.flatMapIds = List.copyOf(flatMapIds);
        }

        public int getCategory() {
            return category;
        }

        public int getPage() {
            return page;
        }

        public List<String> getLeftMapIds() {
            return leftMapIds;
        }

        public List<String> getRightMapIds() {
            return rightMapIds;
        }

        public List<String> getFlatMapIds() {
            return flatMapIds;
        }

        public int getTotalPages() {
            int count;
            if (category == UNCONFIGURED) {
                count = flatMapIds.size();
                return Math.max(1, (count + FLAT_SLOTS.size() - 1) / FLAT_SLOTS.size());
            }
            int sideCount = Math.max(leftMapIds.size(), rightMapIds.size());
            return Math.max(1, (sideCount + LEFT_SLOTS.size() - 1) / LEFT_SLOTS.size());
        }

        public void clampPage() {
            page = Math.min(page, getTotalPages() - 1);
        }

        public void nextPage() {
            page = (page + 1) % getTotalPages();
        }

        public String getMapIdAt(int slot) {
            if (category == UNCONFIGURED) {
                int local = FLAT_SLOTS.indexOf(slot);
                if (local < 0) return null;
                int index = page * FLAT_SLOTS.size() + local;
                return index < flatMapIds.size() ? flatMapIds.get(index) : null;
            }
            int local = LEFT_SLOTS.indexOf(slot);
            if (local >= 0) {
                int index = page * LEFT_SLOTS.size() + local;
                return index < leftMapIds.size() ? leftMapIds.get(index) : null;
            }
            local = RIGHT_SLOTS.indexOf(slot);
            if (local >= 0) {
                int index = page * RIGHT_SLOTS.size() + local;
                return index < rightMapIds.size() ? rightMapIds.get(index) : null;
            }
            return null;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
