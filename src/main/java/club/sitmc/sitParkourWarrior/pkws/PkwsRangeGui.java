package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.MapManager;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Administrator GUI for the fixed 8 x 3 PKWS difficulty ranges. */
public final class PkwsRangeGui {
    public static final int SIZE = 54;
    public static final int RESET_SLOT = 45;
    public static final int SAVE_TEMPLATE_SLOT = 46;
    public static final int SWITCH_ORIENTATION_SLOT = 47;
    public static final int LOAD_TEMPLATE_SLOT = 48;
    public static final int RETURN_SLOT = 53;

    private static final int TEMPLATE_LIST_SIZE = 54;
    private static final int TEMPLATE_RETURN_SLOT = 53;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;
    private static final Map<UUID, SavePrompt> SAVE_PROMPTS = new ConcurrentHashMap<>();

    private PkwsRangeGui() {
    }

    public static void open(Player player, PkwsRangeConfig ranges, MapManager mapManager) {
        open(player, ranges, new PkwsTemplateStore(resolveDataFolder(player),
                Bukkit.getLogger()), mapManager, PkwsOrientation.NS);
    }

    /** Opens the requested orientation; the title always carries the current orientation. */
    public static void open(Player player, PkwsRangeConfig ranges,
                            PkwsTemplateStore templates, MapManager mapManager,
                            PkwsOrientation orientation) {
        if (player == null || ranges == null || templates == null || orientation == null) {
            return;
        }
        Inventory inventory = Bukkit.createInventory(new Holder(orientation), SIZE,
                Msg.color("&8PKWS 难度范围 - " + orientation.name()));
        render(inventory, ranges, orientation, templates);
        player.openInventory(inventory);
    }

    public static void handleClick(InventoryClickEvent event, PkwsRangeConfig ranges,
                                   PkwsTemplateStore templates, MapManager mapManager) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }

        Object holder = top.getHolder();
        if (holder instanceof Holder rangeHolder) {
            handleRangeClick(player, event, rangeHolder, ranges, templates, mapManager);
        } else if (holder instanceof TemplateListHolder listHolder) {
            handleTemplateListClick(player, event, listHolder, ranges, templates, mapManager);
        } else if (holder instanceof TemplateConfirmHolder confirmHolder) {
            handleTemplateConfirmClick(player, event, confirmHolder, ranges, templates, mapManager);
        }
    }

    /** Compatibility overload for integrations that have not adopted templates yet. */
    public static void handleClick(InventoryClickEvent event, PkwsRangeConfig ranges,
                                   MapManager mapManager) {
        handleClick(event, ranges,
                new PkwsTemplateStore(resolveDataFolder(event.getWhoClicked()), Bukkit.getLogger()),
                mapManager);
    }

    public static void handleChat(AsyncPlayerChatEvent event, JavaPlugin plugin,
                                  PkwsRangeConfig ranges, PkwsTemplateStore templates,
                                  MapManager mapManager) {
        SavePrompt prompt = SAVE_PROMPTS.remove(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String name = event.getMessage().trim();
        if (name.equalsIgnoreCase("cancel")) {
            Msg.send(event.getPlayer(), "已取消保存模板。");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean saved = templates.saveDifficultyTemplate(name,
                    ranges.snapshotRanges(prompt.orientation));
            if (saved) {
                Msg.send(event.getPlayer(), "已将 " + prompt.orientation.name()
                        + " 难度范围保存为模板 " + name + "。");
            } else {
                Msg.send(event.getPlayer(), "模板名无效或保存失败（名称不能为空、不能超过 64 个字符，且不能使用 default）。");
            }
            open(event.getPlayer(), ranges, templates, mapManager, prompt.orientation);
        });
    }

    public static void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        event.setCancelled(true);
    }

    private static void handleRangeClick(Player player, InventoryClickEvent event,
                                         Holder holder, PkwsRangeConfig ranges,
                                         PkwsTemplateStore templates, MapManager mapManager) {
        PkwsOrientation orientation = holder.orientation;
        int slot = event.getRawSlot();
        if (slot == RESET_SLOT) {
            if (ranges.resetDefaults(orientation)) {
                Msg.send(player, "已恢复 " + orientation.name() + " 难度默认曲线。");
                render(event.getView().getTopInventory(), ranges, orientation, templates);
            } else {
                Msg.send(player, "保存默认难度曲线失败。");
            }
            return;
        }
        if (slot == SAVE_TEMPLATE_SLOT) {
            SAVE_PROMPTS.put(player.getUniqueId(), new SavePrompt(orientation));
            player.closeInventory();
            Msg.send(player, "请在聊天输入模板名（输入 cancel 取消）。当前朝向：" + orientation.name());
            return;
        }
        if (slot == SWITCH_ORIENTATION_SLOT) {
            open(player, ranges, templates, mapManager, opposite(orientation));
            return;
        }
        if (slot == LOAD_TEMPLATE_SLOT) {
            openTemplateList(player, ranges, templates, mapManager, orientation);
            return;
        }
        if (slot == RETURN_SLOT) {
            PkwsConfigGui.openOverview(player, mapManager);
            return;
        }

        int row = slot / 9;
        int column = slot % 9;
        if (row < 0 || row >= 3 || column < 0 || column >= 8) {
            return;
        }
        int segment = column + 1;
        int position = row + 1;
        PkwsRangeConfig.DifficultyRange current = ranges.getRange(orientation, segment, position);
        int nextMin = current.min();
        int nextMax = current.max();
        if (event.getClick() == ClickType.LEFT) {
            nextMin++;
        } else if (event.getClick() == ClickType.RIGHT) {
            nextMin--;
        } else if (event.getClick() == ClickType.SHIFT_LEFT) {
            nextMax++;
        } else if (event.getClick() == ClickType.SHIFT_RIGHT) {
            nextMax--;
        } else {
            return;
        }

        if (!PkwsRangeConfig.isValidRange(nextMin, nextMax)) {
            Msg.send(player, "已拒绝：难度范围必须满足 1 ≤ 最低 ≤ 最高 ≤ 16。当前为 " + current);
            return;
        }
        if (!ranges.setRange(orientation, segment, position, nextMin, nextMax)) {
            Msg.send(player, "保存第 " + segment + " 段第 " + position + " 关范围失败。");
            return;
        }
        render(event.getView().getTopInventory(), ranges, orientation, templates);
    }

    private static void openTemplateList(Player player, PkwsRangeConfig ranges,
                                         PkwsTemplateStore templates, MapManager mapManager,
                                         PkwsOrientation orientation) {
        Inventory inventory = Bukkit.createInventory(new TemplateListHolder(orientation),
                TEMPLATE_LIST_SIZE, Msg.color("&8加载难度模板 - " + orientation.name()));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        List<String> names = templates.listDifficultyTemplates();
        for (int index = 0; index < names.size() && index < TEMPLATE_RETURN_SLOT; index++) {
            inventory.setItem(index, templateItem(names.get(index),
                    PkwsTemplateStore.isDefault(names.get(index))));
        }
        inventory.setItem(TEMPLATE_RETURN_SLOT, button(Material.ARROW, "&b&l返回范围配置",
                "&7返回 " + orientation.name() + " 难度范围"));
        player.openInventory(inventory);
    }

    private static void handleTemplateListClick(Player player, InventoryClickEvent event,
                                                TemplateListHolder holder,
                                                PkwsRangeConfig ranges,
                                                PkwsTemplateStore templates,
                                                MapManager mapManager) {
        int slot = event.getRawSlot();
        if (slot == TEMPLATE_RETURN_SLOT) {
            open(player, ranges, templates, mapManager, holder.orientation);
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()
                || clicked.getItemMeta().getDisplayName() == null) {
            return;
        }
        String name = clicked.getItemMeta().getDisplayName()
                .replace("§e", "").replace("§a", "").trim();
        if (name.isBlank()) {
            return;
        }
        Inventory inventory = Bukkit.createInventory(
                new TemplateConfirmHolder(holder.orientation, name), 27,
                Msg.color("&8确认加载模板 - " + holder.orientation.name()));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(CONFIRM_SLOT, button(Material.LIME_WOOL, "&a&l确认加载",
                "&7覆盖当前 " + holder.orientation.name() + " 的 24 个范围"));
        inventory.setItem(CANCEL_SLOT, button(Material.RED_WOOL, "&c&l取消",
                "&7返回模板列表"));
        player.openInventory(inventory);
    }

    private static void handleTemplateConfirmClick(Player player, InventoryClickEvent event,
                                                   TemplateConfirmHolder holder,
                                                   PkwsRangeConfig ranges,
                                                   PkwsTemplateStore templates,
                                                   MapManager mapManager) {
        if (event.getRawSlot() == CANCEL_SLOT) {
            openTemplateList(player, ranges, templates, mapManager, holder.orientation);
            return;
        }
        if (event.getRawSlot() != CONFIRM_SLOT) {
            return;
        }
        int[] values = templates.loadDifficultyTemplate(holder.name);
        if (values == null || !ranges.applyRanges(holder.orientation, values)) {
            Msg.send(player, "模板不存在、内容损坏或应用失败：" + holder.name);
            openTemplateList(player, ranges, templates, mapManager, holder.orientation);
            return;
        }
        Msg.send(player, "已将模板 " + holder.name + " 应用到 " + holder.orientation.name() + "。");
        open(player, ranges, templates, mapManager, holder.orientation);
    }

    private static void render(Inventory inventory, PkwsRangeConfig ranges,
                               PkwsOrientation orientation, PkwsTemplateStore templates) {
        inventory.clear();
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        for (int position = 1; position <= PkwsRangeConfig.POSITION_COUNT; position++) {
            for (int segment = 1; segment <= PkwsRangeConfig.SEGMENT_COUNT; segment++) {
                int slot = (position - 1) * 9 + (segment - 1);
                PkwsRangeConfig.DifficultyRange range = ranges.getRange(orientation, segment, position);
                inventory.setItem(slot, rangeItem(segment, position, range));
            }
        }
        inventory.setItem(RESET_SLOT, button(Material.REDSTONE, "&c&l恢复默认",
                "&7恢复当前朝向的 24 个范围"));
        inventory.setItem(SAVE_TEMPLATE_SLOT, button(Material.WRITABLE_BOOK, "&e&l保存为模板",
                "&7保存当前 " + orientation.name() + " 的范围"));
        inventory.setItem(SWITCH_ORIENTATION_SLOT, button(Material.COMPASS, "&b&l切换朝向",
                "&7切换到 " + opposite(orientation).name()));
        inventory.setItem(LOAD_TEMPLATE_SLOT, button(Material.CHEST, "&a&l加载模板",
                "&7列出模板并确认覆盖当前配置"));
        inventory.setItem(RETURN_SLOT, button(Material.ARROW, "&b&l返回",
                "&7返回 PKWS 配置总览"));
    }

    private static ItemStack rangeItem(int segment, int position,
                                       PkwsRangeConfig.DifficultyRange range) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color("&e第" + segment + "段第" + position + "关"));
        meta.setLore(List.of(
                Msg.color("&7当前范围: &f[" + range.min() + "-" + range.max() + "]"),
                Msg.color("&a左键: 最低难度 +1 | 右键: 最低难度 -1"),
                Msg.color("&bShift 左键: 最高难度 +1 | Shift 右键: 最高难度 -1"),
                Msg.color("&8改动立即保存；越界或最低高于最高会拒绝")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack templateItem(String name, boolean builtIn) {
        ItemStack item = new ItemStack(builtIn ? Material.NETHER_STAR : Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color((builtIn ? "&a" : "&e") + name));
        meta.setLore(List.of(Msg.color(builtIn ? "&7内置默认曲线" : "&7点击后确认覆盖当前配置")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        meta.setLore(List.of(Msg.color(lore)));
        item.setItemMeta(meta);
        return item;
    }

    private static void fill(Inventory inventory, Material material) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(" ");
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane);
        }
    }

    private static PkwsOrientation opposite(PkwsOrientation orientation) {
        return orientation == PkwsOrientation.NS ? PkwsOrientation.EW : PkwsOrientation.NS;
    }

    private static File resolveDataFolder(org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player player) {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager()
                    .getPlugin("SITParkourWarrior");
            if (plugin != null) {
                return plugin.getDataFolder();
            }
            return new File(player.getWorld().getName());
        }
        return new File(".");
    }

    private static final class SavePrompt {
        private final PkwsOrientation orientation;

        private SavePrompt(PkwsOrientation orientation) {
            this.orientation = orientation;
        }
    }

    public static final class Holder implements InventoryHolder {
        private final PkwsOrientation orientation;

        private Holder(PkwsOrientation orientation) {
            this.orientation = orientation;
        }

        public PkwsOrientation getOrientation() {
            return orientation;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static final class TemplateListHolder implements InventoryHolder {
        private final PkwsOrientation orientation;

        private TemplateListHolder(PkwsOrientation orientation) {
            this.orientation = orientation;
        }

        public PkwsOrientation getOrientation() {
            return orientation;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static final class TemplateConfirmHolder implements InventoryHolder {
        private final PkwsOrientation orientation;
        private final String name;

        private TemplateConfirmHolder(PkwsOrientation orientation, String name) {
            this.orientation = orientation;
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
