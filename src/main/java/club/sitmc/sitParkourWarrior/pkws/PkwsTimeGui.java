package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.util.Msg;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Administrator GUI for the eight PKWS stage durations. */
public final class PkwsTimeGui {
    public static final int SIZE = 27;
    public static final int RESET_SLOT = 18;
    public static final int SAVE_TEMPLATE_SLOT = 20;
    public static final int SWITCH_ORIENTATION_SLOT = 22;
    public static final int LOAD_TEMPLATE_SLOT = 24;
    public static final int CLOSE_SLOT = 26;

    private static final int TEMPLATE_LIST_SIZE = 54;
    private static final int TEMPLATE_RETURN_SLOT = 53;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;
    private static final Map<UUID, SavePrompt> SAVE_PROMPTS = new ConcurrentHashMap<>();

    private PkwsTimeGui() {
    }

    public static void open(Player player, PkwsRangeConfig ranges,
                            PkwsTemplateStore templates, MapManager mapManager,
                            PkwsOrientation orientation) {
        if (player == null || ranges == null || templates == null || orientation == null) {
            return;
        }
        Inventory inventory = Bukkit.createInventory(new Holder(orientation), SIZE,
                Msg.color("&8PKWS 阶段时长 — " + orientation.name()));
        render(inventory, ranges, orientation);
        player.openInventory(inventory);
    }

    public static void handleClick(InventoryClickEvent event, JavaPlugin plugin,
                                   PkwsRangeConfig ranges, PkwsTemplateStore templates,
                                   MapManager mapManager) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }

        Object holder = top.getHolder();
        if (holder instanceof Holder timeHolder) {
            handleTimeClick(player, event, timeHolder, ranges, templates, mapManager);
        } else if (holder instanceof TemplateListHolder listHolder) {
            handleTemplateListClick(player, event, listHolder, ranges, templates, mapManager);
        } else if (holder instanceof TemplateConfirmHolder confirmHolder) {
            handleTemplateConfirmClick(player, event, confirmHolder, ranges, templates, mapManager);
        }
    }

    public static void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        event.setCancelled(true);
    }

    public static void handleChat(AsyncChatEvent event, JavaPlugin plugin,
                                  PkwsRangeConfig ranges, PkwsTemplateStore templates,
                                  MapManager mapManager) {
        SavePrompt prompt = SAVE_PROMPTS.remove(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String name = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (name.equalsIgnoreCase("cancel")) {
                Msg.send(player, "已取消保存时间模板。");
                if (player.isOnline()) {
                    open(player, ranges, templates, mapManager, prompt.orientation);
                }
                return;
            }
            boolean saved = templates.saveTimeTemplate(name, ranges.snapshotTimes(prompt.orientation));
            if (saved) {
                Msg.send(player, "已将 " + prompt.orientation.name() + " 的 8 项时长保存为模板 "
                        + name + "。");
            } else {
                Msg.send(player, "模板名无效或保存失败；名称不能空、不能超过 64 个字符，也不能使用 default。");
            }
            if (player.isOnline()) {
                open(player, ranges, templates, mapManager, prompt.orientation);
            }
        });
    }

    private static void handleTimeClick(Player player, InventoryClickEvent event,
                                        Holder holder, PkwsRangeConfig ranges,
                                        PkwsTemplateStore templates, MapManager mapManager) {
        PkwsOrientation orientation = holder.orientation;
        int slot = event.getRawSlot();
        if (slot >= 9 && slot <= 16) {
            int stage = slot - 8;
            int seconds = ranges.getTimeSeconds(orientation, stage);
            int delta;
            if (event.getClick() == ClickType.LEFT) {
                delta = 5;
            } else if (event.getClick() == ClickType.RIGHT) {
                delta = -5;
            } else if (event.getClick() == ClickType.SHIFT_LEFT) {
                delta = 30;
            } else if (event.getClick() == ClickType.SHIFT_RIGHT) {
                delta = -30;
            } else {
                return;
            }
            int next = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) seconds + delta));
            if (!ranges.setTimeSeconds(orientation, stage, next)) {
                Msg.send(player, "保存第 " + stage + " 阶段时长失败。");
                return;
            }
            render(event.getView().getTopInventory(), ranges, orientation);
            return;
        }

        if (slot == RESET_SLOT) {
            if (!ranges.resetTimeDefaults(orientation)) {
                Msg.send(player, "恢复默认阶段时长失败。");
                return;
            }
            render(event.getView().getTopInventory(), ranges, orientation);
        } else if (slot == SAVE_TEMPLATE_SLOT) {
            SAVE_PROMPTS.put(player.getUniqueId(), new SavePrompt(orientation));
            player.closeInventory();
            Msg.send(player, "请输入模板名；输入 cancel 取消保存。");
        } else if (slot == SWITCH_ORIENTATION_SLOT) {
            open(player, ranges, templates, mapManager, opposite(orientation));
        } else if (slot == LOAD_TEMPLATE_SLOT) {
            openTemplateList(player, ranges, templates, mapManager, orientation);
        } else if (slot == CLOSE_SLOT) {
            player.closeInventory();
        }
    }

    private static void openTemplateList(Player player, PkwsRangeConfig ranges,
                                         PkwsTemplateStore templates, MapManager mapManager,
                                         PkwsOrientation orientation) {
        List<String> names = templates.listTimeTemplates();
        Inventory inventory = Bukkit.createInventory(new TemplateListHolder(orientation, names),
                TEMPLATE_LIST_SIZE, Msg.color("&8加载时间模板 — " + orientation.name()));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        for (int index = 0; index < names.size() && index < TEMPLATE_RETURN_SLOT; index++) {
            inventory.setItem(index, templateItem(names.get(index)));
        }
        inventory.setItem(TEMPLATE_RETURN_SLOT, button(Material.ARROW, "&b&l返回时间配置",
                "&7返回 " + orientation.name() + " 阶段时长界面"));
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
        if (slot < 0 || slot >= holder.names.size() || slot >= TEMPLATE_RETURN_SLOT) {
            return;
        }
        String name = holder.names.get(slot);
        Inventory inventory = Bukkit.createInventory(
                new TemplateConfirmHolder(holder.orientation, name), 27,
                Msg.color("&8确认加载时间模板 — " + holder.orientation.name()));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(CONFIRM_SLOT, button(Material.LIME_WOOL, "&a&l确认加载",
                "&7覆盖当前 " + holder.orientation.name() + " 的 8 项阶段时长"));
        inventory.setItem(CANCEL_SLOT, button(Material.RED_WOOL, "&c&l取消",
                "&7返回时间模板列表"));
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
        int[] values = templates.loadTimeTemplate(holder.name);
        if (values == null || !ranges.applyTimes(holder.orientation, values)) {
            Msg.send(player, "时间模板不存在、内容损坏或应用失败：" + holder.name);
            openTemplateList(player, ranges, templates, mapManager, holder.orientation);
            return;
        }
        Msg.send(player, "已将时间模板 " + holder.name + " 应用到 "
                + holder.orientation.name() + "。");
        open(player, ranges, templates, mapManager, holder.orientation);
    }

    private static void render(Inventory inventory, PkwsRangeConfig ranges,
                               PkwsOrientation orientation) {
        inventory.clear();
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        for (int stage = 1; stage <= PkwsRangeConfig.TIME_CONFIG_COUNT; stage++) {
            inventory.setItem(8 + stage, stageItem(stage, ranges.getTimeSeconds(orientation, stage)));
        }
        inventory.setItem(RESET_SLOT, button(Material.REDSTONE, "&c&l恢复默认",
                "&7恢复当前 " + orientation.name() + " 的 8 项阶段时长"));
        inventory.setItem(SAVE_TEMPLATE_SLOT, button(Material.WRITABLE_BOOK, "&e&l保存为模板",
                "&7保存当前 " + orientation.name() + " 的 8 项时长", "&7输入 cancel 取消；同名模板会被覆盖"));
        inventory.setItem(SWITCH_ORIENTATION_SLOT, button(Material.COMPASS, "&b&l切换朝向",
                "&7切换到 " + opposite(orientation).name()));
        inventory.setItem(LOAD_TEMPLATE_SLOT, button(Material.CHEST, "&a&l加载时间模板",
                "&7选择模板后确认覆盖当前配置"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "&c&l关闭",
                "&7关闭时间配置界面"));
    }

    private static ItemStack stageItem(int stage, int seconds) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color("&e第" + stage + "阶段"));
        meta.setLore(List.of(
                Msg.color("&7当前时长: &f" + seconds + " 秒"),
                Msg.color("&a左键: +5 秒 | 右键: -5 秒"),
                Msg.color("&bShift 左键: +30 秒 | Shift 右键: -30 秒")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack templateItem(String name) {
        ItemStack item = new ItemStack(PkwsTemplateStore.isDefault(name)
                ? Material.NETHER_STAR : Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color("&e" + name));
        meta.setLore(List.of(Msg.color("&7点击后确认覆盖当前朝向的 8 项阶段时长")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        List<String> lines = new ArrayList<>(lore.length);
        for (String line : lore) {
            lines.add(Msg.color(line));
        }
        meta.setLore(lines);
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

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public static final class TemplateListHolder implements InventoryHolder {
        private final PkwsOrientation orientation;
        private final List<String> names;

        private TemplateListHolder(PkwsOrientation orientation, List<String> names) {
            this.orientation = orientation;
            this.names = List.copyOf(names);
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
