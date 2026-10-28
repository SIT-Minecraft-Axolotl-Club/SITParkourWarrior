package club.sitmc.sitParkourWarrior.command;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import club.sitmc.sitParkourWarrior.board.BoardData;
import club.sitmc.sitParkourWarrior.board.BoardManager;
import club.sitmc.sitParkourWarrior.config.CountdownScoringConfig;
import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.config.TimingMode;
import club.sitmc.sitParkourWarrior.course.CourseLayoutAnalyzer;
import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.records.RecordsManager;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.Difficulty;
import club.sitmc.sitParkourWarrior.map.DynamicData;
import club.sitmc.sitParkourWarrior.map.DynamicService;
import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.EndTier;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import club.sitmc.sitParkourWarrior.map.SchematicService;
import club.sitmc.sitParkourWarrior.pkws.PkwsConfigGui;
import club.sitmc.sitParkourWarrior.pkws.PkwsGenerationService;
import club.sitmc.sitParkourWarrior.pkws.PkwsRangeConfig;
import club.sitmc.sitParkourWarrior.pkws.PkwsRangeGui;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsTimeGui;
import club.sitmc.sitParkourWarrior.pkws.PkwsWinsStore;
import club.sitmc.sitParkourWarrior.pkws.PkwsTemplateStore;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class
DPCommand implements CommandExecutor, TabCompleter {

    private final MapManager mapManager;
    private final SessionManager sessionManager;
    private final SelectionManager selectionManager;
    private final DynamicService dynamicService;
    private final CourseLayoutAnalyzer courseLayoutAnalyzer;
    private final PkwWorldManager pkwWorldManager;
    private final RecordsManager recordsManager;
    private final CountdownScoringConfig countdownScoring;
    private final BoardManager boardManager;
    private final EditSessionManager editSessionManager;
    private final PkwsRangeConfig pkwsRangeConfig;
    private final PkwsGenerationService pkwsGenerationService;
    private final PkwsRaceManager pkwsRaceManager;
    private final PkwsWinsStore pkwsWinsStore;
    private final PkwsTemplateStore pkwsTemplateStore;
    private final SchematicService schematicService = new SchematicService();

    public DPCommand(MapManager mapManager, SessionManager sessionManager, SelectionManager selectionManager,
                     DynamicService dynamicService, CourseLayoutAnalyzer courseLayoutAnalyzer,
                     PkwWorldManager pkwWorldManager, RecordsManager recordsManager,
                     CountdownScoringConfig countdownScoring, BoardManager boardManager,
                     EditSessionManager editSessionManager, PkwsRangeConfig pkwsRangeConfig,
                     PkwsGenerationService pkwsGenerationService,
                     PkwsRaceManager pkwsRaceManager,
                     PkwsWinsStore pkwsWinsStore,
                     PkwsTemplateStore pkwsTemplateStore) {
        this.mapManager = mapManager;
        this.sessionManager = sessionManager;
        this.selectionManager = selectionManager;
        this.dynamicService = dynamicService;
        this.courseLayoutAnalyzer = courseLayoutAnalyzer;
        this.pkwWorldManager = pkwWorldManager;
        this.recordsManager = recordsManager;
        this.countdownScoring = countdownScoring;
        this.boardManager = boardManager;
        this.editSessionManager = editSessionManager;
        this.pkwsRangeConfig = pkwsRangeConfig;
        this.pkwsGenerationService = pkwsGenerationService;
        this.pkwsRaceManager = pkwsRaceManager;
        this.pkwsWinsStore = pkwsWinsStore;
        this.pkwsTemplateStore = pkwsTemplateStore;
    }

    private static final java.util.Set<String> ADMIN_COMMANDS = java.util.Set.of(
            "create", "edit", "exit", "particles", "sound", "pos1", "pos2",
            "setstart", "setend", "title", "subtitle", "difficulty", "dynamic", "deploy",
            "undeploy", "save", "delete", "addforkpoint", "delforkpoint",
            "setendtier", "reload", "pkwworld", "record", "tool", "level", "config", "pkws"
    );

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sender instanceof Player && ADMIN_COMMANDS.contains(sub)
                && !sender.hasPermission("dynamicparkour.admin")) {
            Msg.send(sender, "你没有权限使用该命令。");
            return true;
        }
        switch (sub) {
            case "create":
                return handleCreate(sender, args);
            case "edit":
                return handleEdit(sender, args);
            case "exit":
                return handleExit(sender);
            case "particles":
                return handleParticles(sender, args);
            case "sound":
                return handleSound(sender, args);
            case "pos1":
                return handlePos(sender, true);
            case "pos2":
                return handlePos(sender, false);
            case "setstart":
                return handleSetStart(sender, args);
            case "setend":
                return handleSetEnd(sender, args);
            case "title":
                return handleTitle(sender, args);
            case "subtitle":
                return handleSubtitle(sender, args);
            case "difficulty":
                return handleDifficulty(sender, args);
            case "dynamic":
                return handleDynamic(sender, args);
            case "deploy":
                return handleDeploy(sender, args);
            case "undeploy":
                return handleUndeploy(sender, args);
            case "save":
                return handleSave(sender, args);
            case "delete":
                return handleDelete(sender, args);
            case "addforkpoint":
                return handleAddForkPoint(sender);
            case "delforkpoint":
                return handleDelForkPoint(sender, args);
            case "setendtier":
                return handleSetEndTier(sender, args);
            case "reload":
                return handleReload(sender);
            case "pkwworld":
                return handlePkwWorld(sender, args);
            case "quit":
                return handleQuit(sender);
            case "stats":
                return handleStats(sender, args);
            case "top":
                return handleTop(sender, args);
            case "board":
                return handleBoard(sender, args);
            case "record":
                return handleRecord(sender, args);
            case "tool":
                return handleTool(sender);
            case "level":
                return handleLevel(sender, args);
            case "config":
                return handleConfig(sender);
            case "pkws":
                return handlePkws(sender, args);
            default:
                Msg.send(sender, "未知子命令。输入 /sitpkw 查看用法。");
                return true;
        }
    }

    private void sendUsage(CommandSender sender) {
        Msg.send(sender, "/sitpkw pkws settype <id> <RELAY|START|END>");
        Msg.send(sender, "/sitpkw pkws info <id>");
        Msg.send(sender, "/sitpkw pkws list [RELAY|START|END]");
        Msg.send(sender, "/sitpkw pkws gate <open|close> [id]");
        Msg.send(sender, "/sitpkw pkws range <NS|EW> <段1-8> <位1-3> <最低> <最高>");
        Msg.send(sender, "/sitpkw pkws ranges <NS|EW>");
        Msg.send(sender, "/sitpkw pkws range template <save|load|list|delete> ...");
        Msg.send(sender, "/sitpkw pkws time <NS|EW> <阶段1-8> <秒数>");
        Msg.send(sender, "/sitpkw pkws time edit <NS|EW>");
        Msg.send(sender, "/sitpkw pkws time template <save|load|list|delete> ...");
        Msg.send(sender, "/sitpkw pkws generate <NS|EW> [confirm]");
        Msg.send(sender, "/sitpkw pkws refresh [NS|EW] [world <世界名>] [confirm]");
        Msg.send(sender, "/sitpkw pkws clear [confirm]");
        Msg.send(sender, "/sitpkw pkws trackinfo");
        Msg.send(sender, "/sitpkw pkws world <add|remove|list> [世界名]");
        Msg.send(sender, "/sitpkw pkws start <close|open|countdown> [秒数]");
        Msg.send(sender, "/sitpkw pkws forceopen <NS|EW> <阶段1-8> <秒数>");
        Msg.send(sender, "/sitpkw pkws forceopen list <NS|EW>");
        Msg.send(sender, "/sitpkw pkws wins [玩家]");
        Msg.send(sender, "/sitpkw pkws top");
        Msg.send(sender, "用法:");
        Msg.send(sender, "/sitpkw create <id> [type]  创建关卡并进入编辑,type:level/fork/globalstart/globalend/branchend/pkws_relay/pkws_start/pkws_end,缺省level");
        Msg.send(sender, "/sitpkw edit <id>    进入编辑模式");
        Msg.send(sender, "/sitpkw exit         退出编辑模式");
        Msg.send(sender, "/sitpkw particles <on|off>  设置关卡粒子效果");
        Msg.send(sender, "/sitpkw sound <on|off>      设置关卡破坏音效");
        Msg.send(sender, "/sitpkw pos1|pos2    设置选区角点");
        Msg.send(sender, "/sitpkw save <序列id> 保存当前选区为动态状态");
        Msg.send(sender, "/sitpkw setstart [pos1|pos2]  设置起点（或起点区域的角点）");
        Msg.send(sender, "/sitpkw setend [pos1|pos2]    设置终点（或终点区域的角点）");
        Msg.send(sender, "/sitpkw title <文本>  设置标题");
        Msg.send(sender, "/sitpkw subtitle [文本]  设置关卡副标题（省略参数清空）");
        Msg.send(sender, "/sitpkw difficulty <easy|normal|hard|extreme> 设置难度");
        Msg.send(sender, "/sitpkw level orientation <关卡id> <NS|EW|reset> 设置 PKWS 朝向");
        Msg.send(sender, "/sitpkw level difficulty <关卡id> <1-16|reset> 设置 PKWS 难度");
        Msg.send(sender, "/sitpkw level weight <关卡id> <数值> 设置 PKWS 权重");
        Msg.send(sender, "/sitpkw level info <关卡id> 查看 PKWS 三个参数");
        Msg.send(sender, "/sitpkw config 打开 PKWS 配置总览");
        Msg.send(sender, "/sitpkw dynamic addstate <文件名> [间隔]  添加动态状态");
        Msg.send(sender, "/sitpkw dynamic delstate <序号|文件名>     删除动态状态");
        Msg.send(sender, "/sitpkw dynamic interval <序号> <时间>    修改状态间隔（tick）");
        Msg.send(sender, "/sitpkw dynamic list                   查看动态列表");
        Msg.send(sender, "/sitpkw deploy [id]                    在当前位置放出关卡");
        Msg.send(sender, "/sitpkw undeploy                       在当前位置收回关卡");
        Msg.send(sender, "/sitpkw addforkpoint                   为当前编辑的FORK节点添加分支点");
        Msg.send(sender, "/sitpkw delforkpoint <序号>            删除当前编辑FORK节点的分支点");
        Msg.send(sender, "/sitpkw setendtier <easy|normal|hard>  设置全局终点难度类型");
        Msg.send(sender, "/sitpkw delete <id>                    删除关卡");
        Msg.send(sender, "/sitpkw pkwworld add [世界名] <countup|countdown>  加入 PKW 世界并指定模式");
        Msg.send(sender, "/sitpkw pkwworld setmode [世界名] <countup|countdown>  修改已有 PKW 世界的模式");
        Msg.send(sender, "/sitpkw pkwworld duration [世界名] <秒数>          设定倒计时总时长");
        Msg.send(sender, "/sitpkw pkwworld remove [世界名]                  移出 PKW 世界列表");
        Msg.send(sender, "/sitpkw pkwworld list                             列出所有 PKW 世界");
        Msg.send(sender, "/sitpkw quit                            放弃当前跑酷");
        Msg.send(sender, "/sitpkw stats [玩家名]                  查看正计时三榜成绩计数");
        Msg.send(sender, "/sitpkw top <standard|advance|expect|countdown> [世界名]  查看排行榜（前3+自己）");
        Msg.send(sender, "/sitpkw board add <榜名>                 创建榜牌");
        Msg.send(sender, "/sitpkw board remove                    移除附近榜牌");
        Msg.send(sender, "/sitpkw board list                      列出当前世界榜牌");
        Msg.send(sender, "/sitpkw board cleanup [世界名]          扫描孤儿榜牌实体（需确认后删除）");
        Msg.send(sender, "/sitpkw record delete <世界> <奖牌数|countdown> <玩家名或UUID>  删除一条成绩");
        Msg.send(sender, "/sitpkw reload                         重载配置");
        Msg.send(sender, "/sitpkw tool                           发放动态编辑控制器物品");
    }

    private boolean handleTool(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        boolean granted = editSessionManager.giveEditToolIfAbsent(player);
        Msg.send(sender, granted
                ? "已发放编辑控制器物品：左键切换播放/暂停，右键查看状态列表。"
                : "你已持有编辑控制器物品，未重复发放。");
        return true;
    }

    private boolean handleConfig(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "配置菜单只能由玩家打开。");
            return true;
        }
        PkwsConfigGui.openOverview(player, mapManager);
        return true;
    }

    /** PKWS metadata commands; deliberately independent of editSessionManager. */
    private boolean handleLevel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw level <orientation|difficulty|weight|info> <关卡id> <值>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("orientation") && !action.equals("difficulty")
                && !action.equals("weight") && !action.equals("info")) {
            Msg.send(sender, "无效操作。可选值: orientation、difficulty、weight、info。");
            return true;
        }
        if (args.length < 3) {
            Msg.send(sender, "请提供关卡 id。可用操作: orientation、difficulty、weight、info。");
            return true;
        }

        ParkourMap map = mapManager.getMap(args[2]);
        if (map == null) {
            Msg.send(sender, "关卡不存在: " + args[2]);
            return true;
        }

        if (action.equals("info")) {
            Msg.send(sender, "PKWS 参数 - " + map.getId() + "（" + map.getTitle() + "）");
            Msg.send(sender, "  难度: " + formatPkwsDifficulty(map.getPkwsDifficulty()));
            Msg.send(sender, "  朝向: " + formatPkwsOrientation(map.getPkwsOrientation()));
            Msg.send(sender, "  权重: " + map.getPkwsWeight());
            return true;
        }
        if (args.length < 4) {
            if (action.equals("orientation")) {
                Msg.send(sender, "用法: /sitpkw level orientation <关卡id> <NS|EW|reset>。可选值: NS、EW、reset。");
            } else if (action.equals("difficulty")) {
                Msg.send(sender, "用法: /sitpkw level difficulty <关卡id> <1-16|reset>。可选值: 1-16、reset。");
            } else {
                Msg.send(sender, "用法: /sitpkw level weight <关卡id> <数值>。数值必须是大于等于 0 的整数。");
            }
            return true;
        }

        String value = args[3];
        switch (action) {
            case "orientation": {
                if (value.equalsIgnoreCase("reset")) {
                    map.setPkwsOrientation(null);
                } else {
                    PkwsOrientation orientation = PkwsOrientation.fromString(value);
                    if (orientation == null) {
                        Msg.send(sender, "非法朝向。可选值: NS、EW、reset。");
                        return true;
                    }
                    map.setPkwsOrientation(orientation);
                }
                if (mapManager.saveMap(map)) {
                    Msg.send(sender, "已设置关卡 " + map.getId() + " 的 PKWS 朝向为 "
                            + formatPkwsOrientation(map.getPkwsOrientation()) + "。");
                }
                return true;
            }
            case "difficulty": {
                if (value.equalsIgnoreCase("reset")) {
                    map.setPkwsDifficulty(null);
                } else {
                    int difficulty;
                    try {
                        difficulty = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        Msg.send(sender, "非法难度。可选值: 1-16、reset。");
                        return true;
                    }
                    if (difficulty < 1 || difficulty > 16) {
                        Msg.send(sender, "非法难度。可选值: 1-16、reset。");
                        return true;
                    }
                    map.setPkwsDifficulty(difficulty);
                }
                if (mapManager.saveMap(map)) {
                    Msg.send(sender, "已设置关卡 " + map.getId() + " 的 PKWS 难度为 "
                            + formatPkwsDifficulty(map.getPkwsDifficulty()) + "。");
                }
                return true;
            }
            case "weight": {
                long weight;
                try {
                    weight = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    Msg.send(sender, "非法权重。权重必须是大于等于 0 的整数。");
                    return true;
                }
                if (weight < 0 || weight > Integer.MAX_VALUE) {
                    Msg.send(sender, "非法权重。权重必须是大于等于 0 且不超过 "
                            + Integer.MAX_VALUE + " 的整数。");
                    return true;
                }
                map.setPkwsWeight((int) weight);
                if (mapManager.saveMap(map)) {
                    Msg.send(sender, "已设置关卡 " + map.getId() + " 的 PKWS 权重为 "
                            + map.getPkwsWeight() + "。");
                }
                return true;
            }
            default:
                return true;
        }
    }

    /**
     * PKWS node definition and manual test commands.  These commands do not
     * use the player's edit-session selection; a node id is always explicit.
     */
    private boolean handlePkws(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw pkws <settype|info|list|gate|range|ranges|time|generate|refresh|clear|trackinfo|world|start|forceopen|wins|top>");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "settype":
                if (args.length < 4) {
                    Msg.send(sender, "用法: /sitpkw pkws settype <id> <RELAY|START|END>");
                    return true;
                }
                NodeType newType = NodeType.fromPkwsString(args[3]);
                if (newType == null) {
                    Msg.send(sender, "非法 PKWS 类型。可选值: RELAY、START、END");
                    return true;
                }
                ParkourMap map = mapManager.getMap(args[2]);
                if (map == null) {
                    Msg.send(sender, "关卡不存在: " + args[2]);
                    return true;
                }

                NodeType oldType = map.getNodeType();
                boolean hadDeployments = !map.getDeployments().isEmpty();
                if (oldType != newType) {
                    // If a normal PKW map is converted while deployed, stop
                    // its old dynamic task before the type becomes PKWS.
                    for (Deployment deployment : map.getDeployments()) {
                        dynamicService.stopForDeployment(map, deployment);
                    }
                    map.setNodeType(newType);
                    if (!mapManager.saveMap(map)) {
                        map.setNodeType(oldType);
                        if (!oldType.isPkwsType()) {
                            for (Deployment deployment : map.getDeployments()) {
                                dynamicService.startForDeployment(map, deployment);
                            }
                        }
                        Msg.send(sender, "保存节点类型失败: " + map.getId());
                        return true;
                    }
                    if (!newType.isPkwsType()) {
                        for (Deployment deployment : map.getDeployments()) {
                            dynamicService.startForDeployment(map, deployment);
                        }
                    }
                    // This removes a converted PKWS node from any existing
                    // legacy course.yml section (and restores it on reverse).
                    if (hadDeployments) {
                        courseLayoutAnalyzer.recomputeAllPkwWorlds();
                    }
                }
                Msg.send(sender, "节点 " + map.getId() + " 类型已设置为 " + formatPkwsNodeType(newType));
                return true;

            case "info":
                if (args.length < 3) {
                    Msg.send(sender, "用法: /sitpkw pkws info <id>");
                    return true;
                }
                map = mapManager.getMap(args[2]);
                if (map == null || !map.getNodeType().isPkwsType()) {
                    Msg.send(sender, "不是已定义的 PKWS 节点: " + args[2]);
                    return true;
                }
                Msg.send(sender, "PKWS 节点 " + map.getId() + " (" + map.getTitle() + ")");
                Msg.send(sender, "  类型: " + formatPkwsNodeType(map.getNodeType()));
                Msg.send(sender, "  朝向: " + formatPkwsOrientation(map.getPkwsOrientation()));
                Msg.send(sender, "  区域: " + formatRegion(map.getRegion()));
                Msg.send(sender, "  起点: " + formatPointLocation(map.getStart()));
                Msg.send(sender, "  终点: " + formatPointLocation(map.getEnd()));
                if (map.getNodeType() == NodeType.PKWS_RELAY
                        || map.getNodeType() == NodeType.PKWS_START) {
                    DynamicData data = map.getDynamicData();
                    List<DynamicState> states = data == null ? Collections.emptyList() : data.getStates();
                    String label = map.getNodeType() == NodeType.PKWS_START ? "起点" : "闸门";
                    Msg.send(sender, "  " + label + "状态0(关闭): " + formatStateStatus(map, states, 0, "关闭态"));
                    Msg.send(sender, "  " + label + "状态1(开放): " + formatStateStatus(map, states, 1, "开放态"));
                }
                return true;

            case "list":
                NodeType filter = null;
                if (args.length >= 3 && !args[2].isBlank()) {
                    filter = NodeType.fromPkwsString(args[2]);
                    if (filter == null) {
                        Msg.send(sender, "非法筛选类型。可选值: RELAY、START、END");
                        return true;
                    }
                }
                List<ParkourMap> pkwsMaps = new ArrayList<>();
                for (ParkourMap candidate : mapManager.getMaps().values()) {
                    if (!candidate.getNodeType().isPkwsType()) {
                        continue;
                    }
                    if (filter == null || candidate.getNodeType() == filter) {
                        pkwsMaps.add(candidate);
                    }
                }
                pkwsMaps.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
                Msg.send(sender, "PKWS 节点列表" + (filter == null ? "" : " (" + formatPkwsNodeType(filter) + ")") + ":");
                if (pkwsMaps.isEmpty()) {
                    Msg.send(sender, "  (无)");
                    return true;
                }
                for (ParkourMap candidate : pkwsMaps) {
                    Msg.send(sender, "  " + candidate.getId() + " - "
                            + formatPkwsNodeType(candidate.getNodeType()) + " - 朝向 "
                            + formatPkwsOrientation(candidate.getPkwsOrientation()));
                }
                return true;

            case "world":
                return handlePkwsWorld(sender, args);

            case "start":
                return handlePkwsStart(sender, args);

            case "forceopen":
                return handlePkwsForceOpen(sender, args);

            case "time":
                return handlePkwsTime(sender, args);

            case "wins":
                return handlePkwsWins(sender, args);

            case "top":
                return handlePkwsTop(sender);

            case "range":
                if (args.length >= 3 && args[2].equalsIgnoreCase("template")) {
                    return handlePkwsRangeTemplate(sender, args);
                }
                if (args.length == 7) {
                    PkwsOrientation orientation = PkwsOrientation.fromString(args[2]);
                    if (orientation == null) {
                        Msg.send(sender, "朝向必须是 NS 或 EW。");
                        return true;
                    }
                    int segment;
                    int position;
                    int minimum;
                    int maximum;
                    try {
                        segment = Integer.parseInt(args[3]);
                        position = Integer.parseInt(args[4]);
                        minimum = Integer.parseInt(args[5]);
                        maximum = Integer.parseInt(args[6]);
                    } catch (NumberFormatException ex) {
                        Msg.send(sender, "段、位、最低和最高都必须是整数；范围为段 1-8、位 1-3、难度 1-16。");
                        return true;
                    }
                    if (segment < 1 || segment > PkwsRangeConfig.SEGMENT_COUNT
                            || position < 1 || position > PkwsRangeConfig.POSITION_COUNT
                            || !PkwsRangeConfig.isValidRange(minimum, maximum)) {
                        Msg.send(sender, "非法难度范围：要求段 1-8、位 1-3，且 1 ≤ 最低 ≤ 最高 ≤ 16。");
                        return true;
                    }
                    if (!pkwsRangeConfig.setRange(orientation, segment, position, minimum, maximum)) {
                        Msg.send(sender, "保存 PKWS 难度范围失败。");
                        return true;
                    }
                    Msg.send(sender, "已设置 " + orientation.name() + " 第" + segment + "段第"
                            + position + "关范围为 [" + minimum + "-" + maximum + "]。");
                    return true;
                }
                if (args.length != 6) {
                    Msg.send(sender, "用法: /sitpkw pkws range <段1-8> <位1-3> <最低> <最高>");
                    return true;
                }
                int segment;
                int position;
                int minimum;
                int maximum;
                try {
                    segment = Integer.parseInt(args[2]);
                    position = Integer.parseInt(args[3]);
                    minimum = Integer.parseInt(args[4]);
                    maximum = Integer.parseInt(args[5]);
                } catch (NumberFormatException ex) {
                    Msg.send(sender, "非法难度范围。段为 1-8，位为 1-3，最低和最高必须是整数 1-16。");
                    return true;
                }
                if (segment < 1 || segment > PkwsRangeConfig.SEGMENT_COUNT
                        || position < 1 || position > PkwsRangeConfig.POSITION_COUNT
                        || !PkwsRangeConfig.isValidRange(minimum, maximum)) {
                    Msg.send(sender, "非法难度范围。要求：段 1-8，位 1-3，且 1 ≤ 最低 ≤ 最高 ≤ 16。");
                    return true;
                }
                if (!pkwsRangeConfig.setRange(segment, position, minimum, maximum)) {
                    Msg.send(sender, "保存 PKWS 难度范围失败。");
                    return true;
                }
                Msg.send(sender, "已设置第" + segment + "段第" + position + "关难度范围为 ["
                        + minimum + "-" + maximum + "]。");
                return true;

            case "ranges":
                if (!(sender instanceof Player rangesPlayer)) {
                    Msg.send(sender, "ranges 配置界面只能由玩家打开。");
                    return true;
                }
                if (args.length > 3) {
                    Msg.send(sender, "用法: /sitpkw pkws ranges <NS|EW>");
                    return true;
                }
                PkwsOrientation rangeGuiOrientation = args.length == 3
                        ? PkwsOrientation.fromString(args[2]) : PkwsOrientation.NS;
                if (rangeGuiOrientation == null) {
                    Msg.send(sender, "朝向必须是 NS 或 EW。");
                    return true;
                }
                PkwsRangeGui.open(rangesPlayer, pkwsRangeConfig, pkwsTemplateStore,
                        mapManager, rangeGuiOrientation);
                return true;

            case "refresh":
                PkwsOrientation refreshOrientation = null;
                String requestedWorldName = null;
                boolean refreshConfirm = false;
                for (int index = 2; index < args.length; index++) {
                    String token = args[index];
                    PkwsOrientation parsed = PkwsOrientation.fromString(token);
                    if (parsed != null && refreshOrientation == null) {
                        refreshOrientation = parsed;
                    } else if (token.equalsIgnoreCase("world")
                            && requestedWorldName == null && index + 1 < args.length) {
                        requestedWorldName = args[++index];
                    } else if (token.equalsIgnoreCase("confirm") && !refreshConfirm) {
                        refreshConfirm = true;
                    } else {
                        Msg.send(sender, "用法: /sitpkw pkws refresh [NS|EW] [world <世界名>] [confirm]");
                        return true;
                    }
                }
                World refreshWorld;
                if (requestedWorldName != null) {
                    refreshWorld = Bukkit.getWorld(requestedWorldName);
                } else if (sender instanceof Player refreshPlayer) {
                    refreshWorld = refreshPlayer.getWorld();
                } else if (sender instanceof BlockCommandSender blockSender) {
                    refreshWorld = blockSender.getBlock().getWorld();
                } else {
                    Msg.send(sender, "控制台执行 refresh 必须显式指定 world <世界名>。");
                    return true;
                }
                if (refreshWorld == null) {
                    Msg.send(sender, "目标世界不存在或未加载：" + requestedWorldName);
                    return true;
                }
                if (!refreshConfirm) {
                    Msg.send(sender, "刷新会先抽取新赛道，再清除旧赛道并逐块生成。确认请追加 confirm。");
                    Msg.send(sender, "/sitpkw pkws refresh"
                            + (refreshOrientation == null ? "" : " " + refreshOrientation.name())
                            + " world " + refreshWorld.getName() + " confirm");
                    return true;
                }
                if (pkwsRaceManager.isRunning(refreshWorld.getName())) {
                    Msg.send(sender, "比赛进行中，需先结束比赛后才能刷新赛道。");
                    return true;
                }
                if (!pkwWorldManager.isPkwsWorld(refreshWorld)) {
                    Msg.send(sender, "目标世界未标记为 PKWS 世界。");
                    return true;
                }
                // A pending start countdown must not fire while the track is
                // being cleared/rebuilt, even though its race is not RUNNING yet.
                pkwsRaceManager.cancelStartCountdown(refreshWorld.getName());
                pkwsGenerationService.refresh(sender, refreshWorld, refreshOrientation);
                return true;

            case "generate":
                if (!(sender instanceof Player generationPlayer)) {
                    Msg.send(sender, "PKWS 赛道生成需要由玩家在目标世界执行。");
                    return true;
                }
                if (args.length < 3 || args.length > 4) {
                    Msg.send(sender, "用法: /sitpkw pkws generate <NS|EW> [confirm]");
                    return true;
                }
                PkwsOrientation generationOrientation = PkwsOrientation.fromString(args[2]);
                if (generationOrientation == null) {
                    Msg.send(sender, "非法朝向。可选值：NS、EW。");
                    return true;
                }
                boolean generationConfirm = args.length >= 4 && args[3].equalsIgnoreCase("confirm");
                if (args.length >= 4 && !generationConfirm) {
                    Msg.send(sender, "非法参数。占用确认参数只能是 confirm。");
                    return true;
                }
                if (pkwsRaceManager.isRunning(generationPlayer.getWorld().getName())) {
                    Msg.send(sender, "当前世界正在进行 PKWS 比赛，不能重新生成赛道。");
                    return true;
                }
                if (!pkwWorldManager.isPkwsWorld(generationPlayer.getWorld())) {
                    Msg.send(sender, "当前世界未标记为 PKWS 世界，请先执行 /sitpkw pkws world add <世界名>。");
                    return true;
                }
                pkwsGenerationService.generate(generationPlayer, generationOrientation, generationConfirm);
                return true;

            case "clear":
                if (!(sender instanceof Player clearPlayer)) {
                    Msg.send(sender, "PKWS 赛道清除需要由玩家在目标世界执行。");
                    return true;
                }
                if (args.length > 3) {
                    Msg.send(sender, "非法参数。用法: /sitpkw pkws clear [confirm]");
                    return true;
                }
                if (pkwsRaceManager.isRunning(clearPlayer.getWorld().getName())) {
                    Msg.send(sender, "当前世界正在进行 PKWS 比赛，不能清除赛道。");
                    return true;
                }
                boolean clearConfirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
                if (args.length >= 3 && !clearConfirm) {
                    Msg.send(sender, "非法参数。清除确认参数只能是 confirm。");
                    return true;
                }
                if (clearConfirm) {
                    pkwsRaceManager.cancelStartCountdown(clearPlayer.getWorld().getName());
                }
                pkwsGenerationService.clear(clearPlayer, clearConfirm);
                return true;

            case "trackinfo":
                pkwsGenerationService.trackInfo(sender);
                return true;

            case "gate":
                return handlePkwsGate(sender, args);

            default:
                Msg.send(sender, "未知 PKWS 操作。可选值: settype、info、list、gate、range、ranges、time、generate、refresh、clear、trackinfo、world、start、forceopen、wins、top");
                return true;
        }
    }

    private boolean handlePkwsRangeTemplate(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Msg.send(sender, "用法: /sitpkw pkws range template <save|load|list|delete> ...");
            return true;
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length != 4) {
                Msg.send(sender, "用法: /sitpkw pkws range template list");
                return true;
            }
            Msg.send(sender, "PKWS 难度模板：");
            for (String name : pkwsTemplateStore.listDifficultyTemplates()) {
                Msg.send(sender, "  - " + name);
            }
            return true;
        }
        if (action.equals("save") || action.equals("load")) {
            if (args.length < 6 || args.length > 7) {
                Msg.send(sender, "用法: /sitpkw pkws range template " + action
                        + " <模板名> <NS|EW>" + (action.equals("load") ? " [confirm]" : ""));
                return true;
            }
            String name = args[4];
            PkwsOrientation orientation = PkwsOrientation.fromString(args[5]);
            if (orientation == null) {
                Msg.send(sender, "朝向必须是 NS 或 EW。");
                return true;
            }
            if (action.equals("save")) {
                if (args.length != 6) {
                    Msg.send(sender, "保存模板不需要 confirm；用法: /sitpkw pkws range template save <模板名> <NS|EW>");
                    return true;
                }
                if (!pkwsTemplateStore.saveDifficultyTemplate(name,
                        pkwsRangeConfig.snapshotRanges(orientation))) {
                    Msg.send(sender, "模板名无效或保存难度模板失败。");
                    return true;
                }
                Msg.send(sender, "已将 " + orientation.name() + " 的 24 个范围保存为模板 " + name + "。");
                return true;
            }
            boolean confirm = args.length == 7 && args[6].equalsIgnoreCase("confirm");
            if (args.length == 7 && !confirm) {
                Msg.send(sender, "确认参数只能是 confirm。");
                return true;
            }
            if (!confirm) {
                Msg.send(sender, "加载模板将覆盖 " + orientation.name()
                        + " 的 24 个范围；请再次执行同一命令并追加 confirm。");
                Msg.send(sender, "用法: /sitpkw pkws range template load "
                        + name + " " + orientation.name() + " confirm");
                return true;
            }
            int[] values = pkwsTemplateStore.loadDifficultyTemplate(name);
            if (values == null || !pkwsRangeConfig.applyRanges(orientation, values)) {
                Msg.send(sender, "难度模板不存在、内容损坏或应用失败：" + name);
                return true;
            }
            Msg.send(sender, "已将模板 " + name + " 应用到 " + orientation.name() + "。");
            return true;
        }
        if (action.equals("delete")) {
            if (args.length < 5 || args.length > 6) {
                Msg.send(sender, "用法: /sitpkw pkws range template delete <模板名> [confirm]");
                return true;
            }
            String name = args[4];
            if (PkwsTemplateStore.isDefault(name)) {
                Msg.send(sender, "内置 default 模板不能删除。");
                return true;
            }
            boolean confirm = args.length == 6 && args[5].equalsIgnoreCase("confirm");
            if (args.length == 6 && !confirm) {
                Msg.send(sender, "确认参数只能是 confirm。");
                return true;
            }
            if (!confirm) {
                Msg.send(sender, "删除模板 " + name + " 不可恢复；请再次执行并追加 confirm。");
                return true;
            }
            if (!pkwsTemplateStore.deleteDifficultyTemplate(name)) {
                Msg.send(sender, "难度模板不存在或删除失败：" + name);
                return true;
            }
            Msg.send(sender, "已删除难度模板 " + name + "。");
            return true;
        }
        Msg.send(sender, "未知模板操作，可选 save、load、list、delete。");
        return true;
    }

    private boolean handlePkwsTime(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("template")) {
            return handlePkwsTimeTemplate(sender, args);
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("edit")) {
            if (args.length != 4) {
                Msg.send(sender, "用法: /sitpkw pkws time edit <NS|EW>");
                return true;
            }
            PkwsOrientation orientation = PkwsOrientation.fromString(args[3]);
            if (orientation == null) {
                Msg.send(sender, "朝向必须是 NS 或 EW。");
                return true;
            }
            if (!(sender instanceof Player player)) {
                Msg.send(sender, "阶段时长编辑界面必须由玩家打开。");
                return true;
            }
            PkwsTimeGui.open(player, pkwsRangeConfig, pkwsTemplateStore, mapManager, orientation);
            return true;
        }
        if (args.length == 4 && args[2].equalsIgnoreCase("list")) {
            PkwsOrientation orientation = PkwsOrientation.fromString(args[3]);
            if (orientation == null) {
                Msg.send(sender, "朝向必须是 NS 或 EW。");
                return true;
            }
            Msg.send(sender, "PKWS " + orientation.name() + " 八段独立时长：");
            for (int slot = 1; slot <= PkwsRangeConfig.TIME_CONFIG_COUNT; slot++) {
                Msg.send(sender, "  " + timeSlotName(slot) + ": "
                        + pkwsRangeConfig.getTimeSeconds(orientation, slot) + " 秒");
            }
            return true;
        }
        if (args.length != 5) {
            Msg.send(sender, "用法: /sitpkw pkws time <NS|EW> <1-8> <秒数>"
                    + "；查看用 /sitpkw pkws time list <NS|EW>");
            return true;
        }
        PkwsOrientation orientation = PkwsOrientation.fromString(args[2]);
        int slot = parseTimeSlot(args[3]);
        int seconds;
        try {
            seconds = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "时间必须是大于等于 0 的整数秒。");
            return true;
        }
        if (orientation == null || slot < 1 || !PkwsRangeConfig.isValidTimeSeconds(seconds)) {
            Msg.send(sender, "参数无效：朝向为 NS/EW，阶段为 1-8，秒数不能小于 0。");
            return true;
        }
        if (!pkwsRangeConfig.setTimeSeconds(orientation, slot, seconds)) {
            Msg.send(sender, "保存时间配置失败。");
            return true;
        }
        Msg.send(sender, "已设置 " + orientation.name() + " " + timeSlotName(slot)
                + " 的独立时长为 " + seconds + " 秒；下一局比赛生效。");
        return true;
    }

    private boolean handlePkwsTimeTemplate(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Msg.send(sender, "用法: /sitpkw pkws time template <save|load|list|delete> ...");
            return true;
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length != 4) {
                Msg.send(sender, "用法: /sitpkw pkws time template list");
                return true;
            }
            Msg.send(sender, "PKWS 阶段时长模板：");
            for (String name : pkwsTemplateStore.listTimeTemplates()) {
                Msg.send(sender, "  - " + name);
            }
            return true;
        }
        if (action.equals("save") || action.equals("load")) {
            if (args.length < 6 || args.length > 7) {
                Msg.send(sender, "用法: /sitpkw pkws time template " + action
                        + " <模板名> <NS|EW>" + (action.equals("load") ? " [confirm]" : ""));
                return true;
            }
            String name = args[4];
            PkwsOrientation orientation = PkwsOrientation.fromString(args[5]);
            if (orientation == null) {
                Msg.send(sender, "朝向必须是 NS 或 EW。");
                return true;
            }
            if (action.equals("save")) {
                if (args.length != 6) {
                    Msg.send(sender, "保存模板不需要 confirm；用法: /sitpkw pkws time template save <模板名> <NS|EW>");
                    return true;
                }
                if (!pkwsTemplateStore.saveTimeTemplate(name,
                        pkwsRangeConfig.snapshotTimes(orientation))) {
                    Msg.send(sender, "模板名无效或保存阶段时长模板失败。");
                    return true;
                }
                Msg.send(sender, "已将 " + orientation.name() + " 的 8 项阶段时长保存为模板 "
                        + name + "。");
                return true;
            }
            boolean confirm = args.length == 7 && args[6].equalsIgnoreCase("confirm");
            if (args.length == 7 && !confirm) {
                Msg.send(sender, "确认参数只能是 confirm。");
                return true;
            }
            if (!confirm) {
                Msg.send(sender, "加载时间模板将覆盖 " + orientation.name()
                        + " 的 8 项阶段时长；请再次执行同一命令并追加 confirm。");
                Msg.send(sender, "用法: /sitpkw pkws time template load "
                        + name + " " + orientation.name() + " confirm");
                return true;
            }
            int[] values = pkwsTemplateStore.loadTimeTemplate(name);
            if (values == null || !pkwsRangeConfig.applyTimes(orientation, values)) {
                Msg.send(sender, "时间模板不存在、内容损坏或应用失败：" + name);
                return true;
            }
            Msg.send(sender, "已将时间模板 " + name + " 应用到 " + orientation.name() + "。");
            return true;
        }
        if (action.equals("delete")) {
            if (args.length < 5 || args.length > 6) {
                Msg.send(sender, "用法: /sitpkw pkws time template delete <模板名> [confirm]");
                return true;
            }
            String name = args[4];
            if (PkwsTemplateStore.isDefault(name)) {
                Msg.send(sender, "内置 default 模板不能删除。");
                return true;
            }
            boolean confirm = args.length == 6 && args[5].equalsIgnoreCase("confirm");
            if (args.length == 6 && !confirm) {
                Msg.send(sender, "确认参数只能是 confirm。");
                return true;
            }
            if (!confirm) {
                Msg.send(sender, "删除时间模板 " + name + " 不可恢复；请再次执行并追加 confirm。");
                return true;
            }
            if (!pkwsTemplateStore.deleteTimeTemplate(name)) {
                Msg.send(sender, "时间模板不存在或删除失败：" + name);
                return true;
            }
            Msg.send(sender, "已删除时间模板 " + name + "。");
            return true;
        }
        Msg.send(sender, "未知时间模板操作，可选 save、load、list、delete。");
        return true;
    }

    private int parseTimeSlot(String token) {
        try {
            int slot = Integer.parseInt(token);
            return slot >= 1 && slot <= PkwsRangeConfig.TIME_CONFIG_COUNT ? slot : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String timeSlotName(int slot) {
        return slot == PkwsRangeConfig.FINISH_TIME_SLOT ? "阶段8（终点）" : "阶段" + slot;
    }

    private boolean handlePkwsStart(CommandSender sender, String[] args) {
        World targetWorld;
        if (sender instanceof Player player) {
            targetWorld = player.getWorld();
        } else if (sender instanceof BlockCommandSender blockSender) {
            targetWorld = blockSender.getBlock().getWorld();
        } else {
            Msg.send(sender, "PKWS 起点操作需要由玩家或命令方块在目标世界执行。");
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            Msg.send(sender, "用法: /sitpkw pkws start <close|open|countdown> [秒数]");
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        String worldName = targetWorld.getName();
        if (action.equals("close")) {
            if (args.length != 3) {
                Msg.send(sender, "用法: /sitpkw pkws start close");
                return true;
            }
            Msg.send(sender, pkwsRaceManager.setStartState(worldName, false));
            return true;
        }
        if (action.equals("open")) {
            if (args.length != 3) {
                Msg.send(sender, "用法: /sitpkw pkws start open");
                return true;
            }
            Msg.send(sender, pkwsRaceManager.setStartState(worldName, true));
            return true;
        }
        if (action.equals("countdown")) {
            // 开赛倒计时的秒数与中继闸门等待时间相互独立：默认取 gate-open-delay-seconds
            // （默认 10 秒），管理员可自由指定任意大于等于 1 的整数秒，不再限制在 3 至 5 秒。
            int seconds = pkwsRangeConfig.getStartCountdownSeconds();
            if (args.length == 4) {
                try {
                    seconds = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    Msg.send(sender, "起点开赛倒计时的秒数必须是大于等于 1 的整数。");
                    return true;
                }
                if (!PkwsRangeConfig.isValidStartCountdownSeconds(seconds)) {
                    Msg.send(sender, "起点开赛倒计时的秒数必须是大于等于 1 的整数。");
                    return true;
                }
            }
            Msg.send(sender, pkwsRaceManager.startStartCountdown(worldName, seconds));
            return true;
        }
        Msg.send(sender, "非法起点操作。可选值: close、open、countdown");
        return true;
    }

    private boolean handlePkwsForceOpen(CommandSender sender, String[] args) {
        if (args.length == 4 && args[2].equalsIgnoreCase("list")) {
            PkwsOrientation orientation = PkwsOrientation.fromString(args[3]);
            if (orientation == null) {
                Msg.send(sender, "用法: /sitpkw pkws forceopen list <NS|EW>");
                return true;
            }
            Msg.send(sender, "PKWS " + orientation.name() + " 八段独立时长：");
            for (int slot = 1; slot <= PkwsRangeConfig.TIME_CONFIG_COUNT; slot++) {
                Msg.send(sender, "  " + timeSlotName(slot) + ": "
                        + pkwsRangeConfig.getTimeSeconds(orientation, slot) + " 秒");
            }
            return true;
        }
        if (args.length == 5) {
            return handlePkwsTime(sender, new String[]{
                    "pkws", "time", args[2], args[3], args[4]
            });
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("list")) {
            Msg.send(sender, "PKWS NS 八段独立时长：");
            for (int stage = 1; stage <= 8; stage++) {
                Msg.send(sender, "  " + timeSlotName(stage) + ": "
                        + pkwsRangeConfig.getTimeSeconds(PkwsOrientation.NS, stage) + " 秒");
            }
            return true;
        }
        if (args.length != 4) {
            Msg.send(sender, "用法: /sitpkw pkws forceopen <NS|EW> <1-8> <秒数>，或 forceopen list <NS|EW>。");
            return true;
        }
        Msg.send(sender, "旧的无朝向分钟语法已停用；请使用 forceopen <NS|EW> <1-8> <秒数>。");
        return true;
    }

    private boolean handlePkwsWins(CommandSender sender, String[] args) {
        if (args.length > 3) {
            Msg.send(sender, "用法: /sitpkw pkws wins [玩家]");
            return true;
        }
        UUID playerId;
        String displayName;
        if (args.length == 3) {
            String requested = args[2];
            Player online = Bukkit.getPlayerExact(requested);
            if (online != null) {
                playerId = online.getUniqueId();
                displayName = online.getName();
            } else {
                playerId = pkwsWinsStore.findPlayerByName(requested);
                displayName = requested;
                if (playerId == null) {
                    playerId = Bukkit.getOfflinePlayer(requested).getUniqueId();
                }
            }
        } else if (sender instanceof Player player) {
            playerId = player.getUniqueId();
            displayName = player.getName();
        } else {
            Msg.send(sender, "控制台查询胜场时必须指定玩家名。");
            return true;
        }
        Msg.send(sender, displayName + " 的 PKWS 胜场: " + pkwsWinsStore.getWins(playerId));
        return true;
    }

    private boolean handlePkwsTop(CommandSender sender) {
        List<PkwsWinsStore.WinEntry> entries = pkwsWinsStore.top(10);
        Msg.send(sender, "PKWS 胜场排行（前 10）:");
        if (entries.isEmpty()) {
            Msg.send(sender, "  暂无胜场记录。");
            return true;
        }
        int rank = 1;
        for (PkwsWinsStore.WinEntry entry : entries) {
            Msg.send(sender, "  " + rank++ + ". " + entry.name()
                    + " - " + entry.wins() + " 胜");
        }
        return true;
    }

    private boolean handlePkwsWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Msg.send(sender, "用法: /sitpkw pkws world <add|remove|list> [世界名]");
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (!pkwWorldManager.getPkwsWorlds().isEmpty()) {
                Msg.send(sender, "PKWS 世界列表:");
                for (String worldName : pkwWorldManager.getPkwsWorlds()) {
                    Msg.send(sender, "  - " + worldName
                            + (pkwsRaceManager.isRunning(worldName) ? " [RUNNING]" : " [IDLE]"));
                }
            } else {
                Msg.send(sender, "当前没有 PKWS 世界。");
            }
            return true;
        }

        String worldName;
        if (args.length >= 4) {
            worldName = args[3];
        } else if (sender instanceof Player player) {
            worldName = player.getWorld().getName();
        } else {
            Msg.send(sender, "控制台必须指定世界名: /sitpkw pkws world " + action + " <世界名>");
            return true;
        }
        if (Bukkit.getWorld(worldName) == null) {
            Msg.send(sender, "世界 " + worldName + " 不存在或未加载。");
            return true;
        }

        if (action.equals("add")) {
            if (pkwWorldManager.isPkwWorld(worldName)) {
                Msg.send(sender, "添加失败：" + worldName + " 已经是 PKW 世界，不能同时标记为 PKWS 世界。");
                return true;
            }
            if (pkwWorldManager.isPkwsWorld(worldName)) {
                Msg.send(sender, worldName + " 已经是 PKWS 世界。");
                return true;
            }
            if (!pkwWorldManager.addPkwsWorld(worldName)) {
                Msg.send(sender, "添加 PKWS 世界失败：" + worldName);
                return true;
            }
            SITParkourWarrior.applyGamerulesToWorld(worldName);
            for (Player player : Bukkit.getWorld(worldName).getPlayers()) {
                pkwsRaceManager.onPlayerEnterWorld(player);
            }
            Msg.send(sender, "已将 " + worldName + " 标记为 PKWS 世界。");
            return true;
        }

        if (action.equals("remove")) {
            if (!pkwWorldManager.isPkwsWorld(worldName)) {
                Msg.send(sender, worldName + " 不是 PKWS 世界。");
                return true;
            }
            pkwsRaceManager.onWorldUnmarked(worldName);
            pkwWorldManager.removePkwsWorld(worldName);
            Msg.send(sender, "已移除 " + worldName + " 的 PKWS 世界标记。");
            return true;
        }

        Msg.send(sender, "未知 PKWS world 操作。可选值: add、remove、list");
        return true;
    }

    private boolean handlePkwsGate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "手动闸门测试只能由玩家执行。");
            return true;
        }
        if (args.length < 3) {
            Msg.send(sender, "用法: /sitpkw pkws gate <open|close> [id]");
            return true;
        }
        boolean open;
        if (args[2].equalsIgnoreCase("open")) {
            open = true;
        } else if (args[2].equalsIgnoreCase("close")) {
            open = false;
        } else {
            Msg.send(sender, "非法闸门状态。可选值: open、close");
            return true;
        }

        String requestedId = args.length >= 4 ? args[3] : null;
        if (requestedId != null) {
            ParkourMap requested = mapManager.getMap(requestedId);
            if (requested == null || requested.getNodeType() != NodeType.PKWS_RELAY) {
                Msg.send(sender, "不是中继节点或节点不存在: " + requestedId);
                return true;
            }
        }

        Location playerLocation = player.getLocation();
        Deployment nearestDeployment = null;
        ParkourMap nearestMap = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (ParkourMap candidate : mapManager.getMaps().values()) {
            if (candidate.getNodeType() != NodeType.PKWS_RELAY
                    || (requestedId != null && !candidate.getId().equalsIgnoreCase(requestedId))) {
                continue;
            }
            for (Deployment deployment : candidate.getDeployments()) {
                Region region = deployment.getRegion();
                if (region == null || region.getWorldName() == null || playerLocation.getWorld() == null
                        || !region.getWorldName().equals(playerLocation.getWorld().getName())) {
                    continue;
                }
                double distanceSquared;
                if (region.contains(playerLocation)) {
                    distanceSquared = 0.0;
                } else {
                    double centerX = (region.getMinX() + region.getMaxX()) / 2.0;
                    double centerY = (region.getMinY() + region.getMaxY()) / 2.0;
                    double centerZ = (region.getMinZ() + region.getMaxZ()) / 2.0;
                    double dx = playerLocation.getX() - centerX;
                    double dy = playerLocation.getY() - centerY;
                    double dz = playerLocation.getZ() - centerZ;
                    distanceSquared = dx * dx + dy * dy + dz * dz;
                }
                if ((requestedId != null || distanceSquared <= 48.0 * 48.0)
                        && distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared;
                    nearestDeployment = deployment;
                    nearestMap = candidate;
                }
            }
        }
        if (nearestMap == null || nearestDeployment == null) {
            Msg.send(sender, "附近 48 格内没有已放置的 PKWS 中继节点。可指定节点 id 再试。");
            return true;
        }

        DynamicData data = nearestMap.getDynamicData();
        List<DynamicState> states = data == null ? Collections.emptyList() : data.getStates();
        int stateIndex = open ? 1 : 0;
        if (states.size() <= stateIndex || states.get(stateIndex).getFile() == null
                || states.get(stateIndex).getFile().isBlank()) {
            Msg.send(sender, "中继节点 " + nearestMap.getId() + " 尚未准备闸门状态 " + stateIndex + "。"
                    + "请用现有动态状态编辑工具保存状态0关闭、状态1开放。");
            return true;
        }
        String worldName = nearestDeployment.getRegion().getWorldName();
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Msg.send(sender, "中继节点所在世界未加载: " + worldName);
            return true;
        }
        File schemFile = new File(mapManager.getMapFolder(nearestMap), states.get(stateIndex).getFile());
        boolean pasted = schematicService.pasteSchematic(schemFile,
                new Location(world, nearestDeployment.getRegion().getMinX(),
                        nearestDeployment.getRegion().getMinY(), nearestDeployment.getRegion().getMinZ()));
        if (!pasted) {
            Msg.send(sender, "闸门状态 " + stateIndex + " 粘贴失败，文件不存在或格式无效: " + schemFile.getName());
            return true;
        }
        Msg.send(sender, "已将中继节点 " + nearestMap.getId() + " 切换为状态 " + stateIndex
                + (open ? "（开放）" : "（关闭）") + "。");
        return true;
    }

    private String formatPkwsNodeType(NodeType type) {
        if (type == null) {
            return "未设置";
        }
        switch (type) {
            case PKWS_RELAY:
                return "PKWS_RELAY（中继节点）";
            case PKWS_START:
                return "PKWS_START（全局起点）";
            case PKWS_END:
                return "PKWS_END（全局终点）";
            default:
                return type.toConfigString();
        }
    }

    private String formatRegion(Region region) {
        if (region == null) {
            return "未设置";
        }
        return region.getWorldName() + " [" + region.getMinX() + "," + region.getMinY() + "," + region.getMinZ()
                + " -> " + region.getMaxX() + "," + region.getMaxY() + "," + region.getMaxZ() + "]";
    }

    private String formatPointLocation(PointLocation point) {
        if (point == null) {
            return "未设置";
        }
        return (point.getWorldName() == null ? "?" : point.getWorldName()) + " ("
                + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", point.getX(), point.getY(), point.getZ()) + ")";
    }

    private String formatStateStatus(ParkourMap map, List<DynamicState> states,
                                     int index, String semanticName) {
        if (states.size() <= index || states.get(index).getFile() == null
                || states.get(index).getFile().isBlank()) {
            return "未齐备（" + semanticName + "）";
        }
        DynamicState state = states.get(index);
        File file = new File(mapManager.getMapFolder(map), state.getFile());
        return (file.isFile() ? "已齐备" : "缺少文件") + "（" + state.getFile() + "）";
    }

    private String formatPkwsDifficulty(Integer difficulty) {
        return difficulty == null ? "未设置" : String.valueOf(difficulty);
    }

    private String formatPkwsOrientation(PkwsOrientation orientation) {
        if (orientation == null) {
            return "未设置";
        }
        return orientation == PkwsOrientation.NS ? "NS（南北）" : "EW（东西）";
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw create <id> [type]");
            return true;
        }
        String id = args[1];
        NodeType nodeType = NodeType.LEVEL;
        if (args.length >= 3) {
            nodeType = NodeType.fromString(args[2]);
        }
        ParkourMap map = mapManager.createMap(id);
        if (map == null) {
            Msg.send(sender, "该 id 已存在。");
            return true;
        }
        map.setNodeType(nodeType);
        selectionManager.setEditingMap(player, map);
        mapManager.saveMap(map);
        editSessionManager.startSession(player, map);
        Msg.send(sender, "已创建关卡（" + nodeType.toConfigString() + "）并进入编辑: " + map.getId());
        return true;
    }

    private boolean handleEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw edit <id>");
            return true;
        }
        ParkourMap map = mapManager.getMap(args[1]);
        if (map == null) {
            Msg.send(sender, "关卡不存在。");
            return true;
        }
        selectionManager.setEditingMap(player, map);
        editSessionManager.startSession(player, map);
        Msg.send(sender, "已进入编辑: " + map.getId());
        return true;
    }

    private boolean handleExit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (!selectionManager.isEditing(player)) {
            Msg.send(sender, "当前不在编辑模式。");
            return true;
        }
        editSessionManager.exitSession(player);
        selectionManager.clearEditingMap(player);
        Msg.send(sender, "已退出编辑模式。");
        return true;
    }

    private boolean handleParticles(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw particles <on|off>");
            return true;
        }
        String value = args[1].toLowerCase();
        boolean enabled;
        if (value.equals("on")) {
            enabled = true;
        } else if (value.equals("off")) {
            enabled = false;
        } else {
            Msg.send(sender, "用法: /sitpkw particles <on|off>");
            return true;
        }
        map.setParticlesEnabled(enabled);
        mapManager.saveMap(map);
        Msg.send(sender, enabled ? "已开启关卡粒子效果。" : "已关闭关卡粒子效果。");
        return true;
    }

    private boolean handleSound(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw sound <on|off>");
            return true;
        }
        String value = args[1].toLowerCase();
        boolean enabled;
        if (value.equals("on")) {
            enabled = true;
        } else if (value.equals("off")) {
            enabled = false;
        } else {
            Msg.send(sender, "用法: /sitpkw sound <on|off>");
            return true;
        }
        map.setSoundEnabled(enabled);
        mapManager.saveMap(map);
        Msg.send(sender, enabled ? "已开启关卡破坏音效。" : "已关闭关卡破坏音效。");
        return true;
    }

    private boolean handlePos(CommandSender sender, boolean first) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        selectionManager.setPos(player, first);
        return true;
    }

    private boolean handleSetStart(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        String zone = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (zone) {
            case "pos1":
                map.setStartPos1(PointLocation.fromLocation(player.getLocation()));
                mapManager.saveMap(map);
                Msg.send(sender, "起点区域角点1已设置。");
                return true;
            case "pos2":
                map.setStartPos2(PointLocation.fromLocation(player.getLocation()));
                mapManager.saveMap(map);
                Msg.send(sender, "起点区域角点2已设置。");
                return true;
            default:
                map.setStart(player.getLocation());
                mapManager.saveMap(map);
                Msg.send(sender, "起点已设置" + (args.length >= 2 ? "（未知参数 \"" + args[1] + "\"，已设为起点中心点）" : "。"));
                return true;
        }
    }

    private boolean handleSetEnd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        String zone = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (zone) {
            case "pos1":
                map.setEndPos1(PointLocation.fromLocation(player.getLocation()));
                mapManager.saveMap(map);
                Msg.send(sender, "终点区域角点1已设置。");
                return true;
            case "pos2":
                map.setEndPos2(PointLocation.fromLocation(player.getLocation()));
                mapManager.saveMap(map);
                Msg.send(sender, "终点区域角点2已设置。");
                return true;
            default:
                map.setEnd(player.getLocation());
                mapManager.saveMap(map);
                Msg.send(sender, "终点已设置" + (args.length >= 2 ? "（未知参数 \"" + args[1] + "\"，已设为终点中心点）" : "。"));
                return true;
        }
    }

    private boolean handleTitle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw title <文本>");
            return true;
        }
        String title = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        map.setTitle(title);
        mapManager.saveMap(map);
        Msg.send(sender, "标题已设置。");
        return true;
    }

    private boolean handleSubtitle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            map.setSubtitle(null);
            mapManager.saveMap(map);
            Msg.send(sender, "副标题已清空。");
            return true;
        }
        String subtitle = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        map.setSubtitle(subtitle);
        mapManager.saveMap(map);
        Msg.send(sender, "副标题已设置: " + subtitle);
        return true;
    }

    private boolean handleDifficulty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw difficulty <easy|normal|hard|extreme>");
            return true;
        }
        Difficulty difficulty = Difficulty.fromString(args[1]);
        map.setDifficulty(difficulty);
        mapManager.saveMap(map);
        Msg.send(sender, "难度已设置为 " + difficulty.name().toLowerCase() + "。");
        return true;
    }

    private boolean handleDynamic(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw dynamic <addstate|delstate|interval|list>");
            return true;
        }
        String action = args[1].toLowerCase();
        DynamicData data = map.getDynamicData();
        switch (action) {
            case "addstate":
                if (args.length < 3) {
                    Msg.send(sender, "用法: /sitpkw dynamic addstate <文件名> [间隔]");
                    return true;
                }
                int interval = DynamicState.DEFAULT_INTERVAL_TICKS;
                if (args.length >= 4) {
                    try {
                        interval = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        Msg.send(sender, "无效间隔: " + args[3]);
                        return true;
                    }
                }
                data.getStates().add(new DynamicState(data.nextId(), args[2], null, interval));
                if (data.getStates().size() >= 2) {
                    data.setEnabled(true);
                }
                mapManager.saveMap(map);
                Msg.send(sender, "已添加状态 " + args[2] + "，间隔 " + DynamicState.clampInterval(interval) + "。");
                return true;
            case "delstate":
                if (args.length < 3) {
                    Msg.send(sender, "用法: /sitpkw dynamic delstate <序号|文件名>");
                    return true;
                }
                int index = parseStateIndex(data, args[2]);
                if (index < 0 || index >= data.getStates().size()) {
                    Msg.send(sender, "找不到对应的状态。");
                    return true;
                }
                DynamicState removed = data.getStates().remove(index);
                mapManager.saveMap(map);
                Msg.send(sender, "已删除状态 " + removed.getFile() + "。");
                return true;
            case "interval":
                if (args.length < 4) {
                    Msg.send(sender, "用法: /sitpkw dynamic interval <序号> <时间>");
                    return true;
                }
                int targetIndex;
                try {
                    targetIndex = Integer.parseInt(args[2]) - 1;
                } catch (NumberFormatException ex) {
                    Msg.send(sender, "无效序号: " + args[2]);
                    return true;
                }
                if (targetIndex < 0 || targetIndex >= data.getStates().size()) {
                    Msg.send(sender, "找不到对应的状态。");
                    return true;
                }
                int ticks;
                try {
                    ticks = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    Msg.send(sender, "无效时间: " + args[3]);
                    return true;
                }
                data.getStates().get(targetIndex).setInterval(ticks);
                mapManager.saveMap(map);
                Msg.send(sender, "已更新状态间隔为 " + data.getStates().get(targetIndex).getInterval() + "。");
                return true;
            case "list":
                if (data.getStates().isEmpty()) {
                    Msg.send(sender, "当前没有动态状态。");
                    return true;
                }
                Msg.send(sender, "动态状态列表（序号: 展示名 / 文件名 / 间隔 / id）");
                for (int i = 0; i < data.getStates().size(); i++) {
                    DynamicState s = data.getStates().get(i);
                    Msg.send(sender, (i + 1) + ": " + s.getDisplayName(i + 1) + " / " + s.getFile()
                            + " / " + s.getInterval() + " / id=" + s.getId());
                }
                return true;
            default:
                Msg.send(sender, "未知 dynamic 子命令。");
                return true;
        }
    }

    private int parseStateIndex(DynamicData data, String token) {
        if (data == null || token == null) {
            return -1;
        }
        try {
            int oneBased = Integer.parseInt(token);
            return oneBased - 1;
        } catch (NumberFormatException ex) {
            // ignore
        }
        for (int i = 0; i < data.getStates().size(); i++) {
            if (data.getStates().get(i).getFile().equalsIgnoreCase(token)) {
                return i;
            }
        }
        return -1;
    }

    private boolean handleDeploy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = null;
        if (args.length >= 2) {
            map = mapManager.getMap(args[1]);
        } else {
            map = selectionManager.getEditingMap(player);
        }
        if (map == null) {
            Msg.send(sender, "关卡不存在，请先进入编辑或提供 id。");
            return true;
        }

        NodeType type = map.getNodeType();

        if (type.isPkwsType() && (map.getStart() == null || map.getEnd() == null)) {
            Msg.send(sender, "PKWS 节点必须同时设置起点和终点。");
            return true;
        }
        // ---- type-specific validation ----
        if (map.getRegion() == null) {
            Msg.send(sender, "关卡未设置区域。");
            return true;
        }
        switch (type) {
            case LEVEL:
                if (map.getStart() == null || map.getEnd() == null) {
                    Msg.send(sender, "关卡未设置起点或终点。");
                    return true;
                }
                break;
            case FORK:
                if (map.getForkBranchPoints().isEmpty()) {
                    Msg.send(sender, "岔路口节点需要先设置区域和至少一个岔路点位。");
                    return true;
                }
                break;
            case GLOBAL_START:
                if (map.getStart() == null) {
                    Msg.send(sender, "全局起点未设置起点。");
                    return true;
                }
                for (ParkourMap m : mapManager.getMaps().values()) {
                    if (m == map) continue;
                    if (m.getNodeType() == NodeType.GLOBAL_START && !m.getDeployments().isEmpty()) {
                        Msg.send(sender, "全局起点已存在，无法部署第二个，请先回收已有的全局起点。");
                        return true;
                    }
                }
                break;
            case GLOBAL_END:
            case BRANCH_END:
                if (map.getEnd() == null) {
                    Msg.send(sender, "终点节点未设置终点。");
                    return true;
                }
                break;
            case PKWS_RELAY:
            case PKWS_START:
            case PKWS_END:
                if (!hasSchematicState(map, 0)
                        || (type != NodeType.PKWS_END && !hasSchematicState(map, 1))) {
                    String required = type == NodeType.PKWS_END
                            ? "状态0"
                            : "状态0（关闭/初始）和状态1（开放）";
                    Msg.send(sender, "PKWS 节点必须先用现有动态状态编辑工具准备 " + required + " schematic。");
                    return true;
                }
                break;
            default:
                if (map.getStart() == null || map.getEnd() == null) {
                    Msg.send(sender, "关卡未设置起点或终点。");
                    return true;
                }
                break;
        }

        Location origin = player.getLocation().getBlock().getLocation();
        Region template = map.getRegion();
        int sizeX = template.getMaxX() - template.getMinX();
        int sizeY = template.getMaxY() - template.getMinY();
        int sizeZ = template.getMaxZ() - template.getMinZ();

        // ---- offset reference point by type ----
        PointLocation refPoint;
        switch (type) {
            case FORK:
                refPoint = map.getForkBranchPoints().get(0);
                break;
            case GLOBAL_END:
            case BRANCH_END:
            case PKWS_END:
                refPoint = map.getEnd();
                break;
            default:
                refPoint = map.getStart();
                break;
        }

        int offsetX = origin.getBlockX() - refPoint.getBlockX();
        int offsetY = origin.getBlockY() - refPoint.getBlockY();
        int offsetZ = origin.getBlockZ() - refPoint.getBlockZ();
        int newMinX = template.getMinX() + offsetX;
        int newMinY = template.getMinY() + offsetY;
        int newMinZ = template.getMinZ() + offsetZ;
        int newMaxX = newMinX + sizeX;
        int newMaxY = newMinY + sizeY;
        int newMaxZ = newMinZ + sizeZ;

        Region newRegion = new Region(origin.getWorld().getName(), newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
        String worldName = origin.getWorld().getName();

        // ---- translate coordinates by type ----
        PointLocation newStart = null;
        PointLocation newEnd = null;
        List<PointLocation> newForkPoints = new ArrayList<>();

        switch (type) {
            case LEVEL:
                newStart = translatePoint(map.getStart(), worldName, offsetX, offsetY, offsetZ);
                newEnd = translatePoint(map.getEnd(), worldName, offsetX, offsetY, offsetZ);
                break;
            case FORK:
                for (PointLocation fp : map.getForkBranchPoints()) {
                    newForkPoints.add(translatePoint(fp, worldName, offsetX, offsetY, offsetZ));
                }
                break;
            case GLOBAL_START:
                newStart = translatePoint(map.getStart(), worldName, offsetX, offsetY, offsetZ);
                break;
            case GLOBAL_END:
            case BRANCH_END:
                newEnd = translatePoint(map.getEnd(), worldName, offsetX, offsetY, offsetZ);
                break;
            case PKWS_RELAY:
            case PKWS_START:
            case PKWS_END:
                newStart = translatePoint(map.getStart(), worldName, offsetX, offsetY, offsetZ);
                newEnd = translatePoint(map.getEnd(), worldName, offsetX, offsetY, offsetZ);
                break;
            default:
                newStart = translatePoint(map.getStart(), worldName, offsetX, offsetY, offsetZ);
                newEnd = translatePoint(map.getEnd(), worldName, offsetX, offsetY, offsetZ);
                break;
        }

        // Translate optional custom zone corners (null-safe).
        PointLocation newStartPos1 = translatePointOrNull(map.getStartPos1(), worldName, offsetX, offsetY, offsetZ);
        PointLocation newStartPos2 = translatePointOrNull(map.getStartPos2(), worldName, offsetX, offsetY, offsetZ);
        PointLocation newEndPos1   = translatePointOrNull(map.getEndPos1(),   worldName, offsetX, offsetY, offsetZ);
        PointLocation newEndPos2   = translatePointOrNull(map.getEndPos2(),   worldName, offsetX, offsetY, offsetZ);

        // ---- emerald marker: only for types that have an end ----
        if (newEnd != null) {
            Location endLoc = newEnd.toLocation();
            if (endLoc != null && endLoc.getWorld() != null) {
                endLoc.clone().subtract(0, 1, 0).getBlock().setType(Material.EMERALD_BLOCK);
            }
        }

        Deployment deployment = new Deployment(UUID.randomUUID().toString(), newRegion, newStart, newEnd,
                newForkPoints, newStartPos1, newStartPos2, newEndPos1, newEndPos2);
        map.addDeployment(deployment);

        // Schematic paste (region-based, works for all types).
        File mapFolder = mapManager.getMapFolder(map);
        int initialStateIndex = type == NodeType.PKWS_START ? 1 : 0;
        String initialState = map.getDynamicData().getStates().size() <= initialStateIndex
                ? null : map.getDynamicData().getStates().get(initialStateIndex).getFile();
        if (initialState != null) {
            File schemFile = new File(mapFolder, initialState);
            if (schemFile.exists()) {
                schematicService.pasteSchematic(schemFile, new Location(origin.getWorld(), newMinX, newMinY, newMinZ));
            }
        }
        dynamicService.startForDeployment(map, deployment);

        mapManager.saveMap(map);
        if (!type.isPkwsType() && pkwWorldManager.isPkwWorld(worldName)) {
            courseLayoutAnalyzer.recomputeWorld(worldName);
        }
        Msg.send(sender, "已放出关卡: " + map.getId());
        return true;
    }

    private boolean hasSchematicState(ParkourMap map, int stateIndex) {
        if (map == null || stateIndex < 0 || map.getDynamicData() == null
                || map.getDynamicData().getStates().size() <= stateIndex) {
            return false;
        }
        DynamicState state = map.getDynamicData().getStates().get(stateIndex);
        if (state == null || state.getFile() == null || state.getFile().isBlank()) {
            return false;
        }
        return new File(mapManager.getMapFolder(map), state.getFile()).isFile();
    }

    private PointLocation translatePoint(PointLocation template, String worldName, int offsetX, int offsetY, int offsetZ) {
        return new PointLocation(worldName,
                template.getX() + offsetX,
                template.getY() + offsetY,
                template.getZ() + offsetZ,
                template.getYaw(),
                template.getPitch());
    }

    private PointLocation translatePointOrNull(PointLocation template, String worldName, int offsetX, int offsetY, int offsetZ) {
        if (template == null) return null;
        return translatePoint(template, worldName, offsetX, offsetY, offsetZ);
    }

    private boolean handleUndeploy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = null;
        if (args.length >= 2) {
            map = mapManager.getMap(args[1]);
        }
        Deployment deployment = null;
        if (map != null) {
            deployment = map.findDeploymentByLocation(player.getLocation());
        } else {
            for (ParkourMap m : mapManager.getMaps().values()) {
                Deployment found = m.findDeploymentByLocation(player.getLocation());
                if (found != null) {
                    map = m;
                    deployment = found;
                    break;
                }
            }
        }
        if (map == null || deployment == null) {
            Msg.send(sender, "当前位置不在任何关卡的放置区域内。");
            return true;
        }
        sessionManager.endSessionsForDeployment(map.getId(), deployment.getId());
        dynamicService.stopForDeployment(map, deployment);
        schematicService.clearRegion(deployment.getRegion());
        map.removeDeployment(deployment.getId());
        mapManager.saveMap(map);
        String depWorld = deployment.getRegion().getWorldName();
        if (!map.getNodeType().isPkwsType() && pkwWorldManager.isPkwWorld(depWorld)) {
            courseLayoutAnalyzer.recomputeWorld(depWorld);
        }
        Msg.send(sender, "已收回关卡: " + map.getId());
        return true;
    }

    private boolean handleSave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw save <序列id>");
            return true;
        }
        int seqId;
        try {
            seqId = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "序列 id 必须是数字。");
            return true;
        }
        selectionManager.saveSelection(player, seqId);
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw delete <id>");
            return true;
        }
        ParkourMap map = mapManager.getMap(args[1]);
        if (map == null) {
            Msg.send(sender, "关卡不存在。");
            return true;
        }
        sessionManager.endSessionsForMap(map.getId());
        mapManager.deleteMap(map.getId());
        Msg.send(sender, "已删除关卡: " + map.getId());
        return true;
    }

    private boolean handleAddForkPoint(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (map.getNodeType() != NodeType.FORK) {
            Msg.send(sender, "只有 FORK 节点可以添加分支点。");
            return true;
        }
        Location loc = player.getLocation();
        map.getForkBranchPoints().add(PointLocation.fromLocation(loc));
        mapManager.saveMap(map);
        Msg.send(sender, "已添加分支点 #" + map.getForkBranchPoints().size() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
        return true;
    }

    private boolean handleDelForkPoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (map.getNodeType() != NodeType.FORK) {
            Msg.send(sender, "只有 FORK 节点可以删除分支点。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw delforkpoint <序号>");
            return true;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "序号必须是数字。");
            return true;
        }
        if (index < 1 || index > map.getForkBranchPoints().size()) {
            Msg.send(sender, "序号超出范围（1-" + map.getForkBranchPoints().size() + "）。");
            return true;
        }
        PointLocation removed = map.getForkBranchPoints().remove(index - 1);
        mapManager.saveMap(map);
        Msg.send(sender, "已删除分支点 #" + index + " (" + removed.getBlockX() + ", " + removed.getBlockY() + ", " + removed.getBlockZ() + ")");
        return true;
    }

    private boolean handleSetEndTier(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        ParkourMap map = selectionManager.getEditingMap(player);
        if (map == null) {
            Msg.send(sender, "请先 /sitpkw create <id> 或 /sitpkw edit <id>。");
            return true;
        }
        if (map.getNodeType() != NodeType.GLOBAL_END) {
            Msg.send(sender, "该命令仅适用于全局终点(globalend)类型节点。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw setendtier <easy|normal|hard>");
            return true;
        }
        EndTier tier = EndTier.fromString(args[1]);
        map.setEndTier(tier);
        mapManager.saveMap(map);
        Msg.send(sender, "全局终点难度类型已设置为 " + tier.toConfigString() + "。");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        sessionManager.endAll();
        dynamicService.stopAll();
        mapManager.loadAll();
        dynamicService.startAllDeployed();
        countdownScoring.load();
        recordsManager.reload();
        pkwsRangeConfig.reload();
        courseLayoutAnalyzer.recomputeAllPkwWorlds();
        Msg.send(sender, "已重载关卡配置。");
        return true;
    }

    private boolean handlePkwWorld(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw pkwworld <add|setmode|duration|remove|list|threshold> [世界名] [...]");
            return true;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "add":
                return handlePkwWorldAdd(sender, args);
            case "setmode":
                return handlePkwWorldSetMode(sender, args);
            case "duration":
                return handlePkwWorldDuration(sender, args);
            case "remove":
                return handlePkwWorldRemove(sender, args);
            case "list":
                return handlePkwWorldList(sender);
            case "threshold":
                return handlePkwWorldThreshold(sender, args);
            default:
                Msg.send(sender, "用法: /sitpkw pkwworld <add|setmode|duration|remove|list|threshold> [世界名] [...]");
                return true;
        }
    }

    /**
     * Parse [世界名] <值> from args. Returns {worldName, value}.
     * If value is omitted (only one arg after action), worldName defaults
     * to the player's current world and the single arg is the value.
     */
    private String[] resolveWorldAndValue(CommandSender sender, String[] args, int actionIdx) {
        String worldName;
        String value;
        if (args.length > actionIdx + 1) {
            worldName = args[actionIdx];
            value = args[actionIdx + 1];
        } else if (args.length > actionIdx && sender instanceof Player) {
            worldName = ((Player) sender).getWorld().getName();
            value = args[actionIdx];
        } else {
            return null;
        }
        return new String[]{worldName, value};
    }

    private boolean handlePkwWorldAdd(CommandSender sender, String[] args) {
        String[] wv = resolveWorldAndValue(sender, args, 2);
        if (wv == null) {
            Msg.send(sender, "用法: /sitpkw pkwworld add [世界名] <countup|countdown>");
            return true;
        }
        String worldName = wv[0];
        TimingMode mode = TimingMode.fromString(wv[1]);
        if (Bukkit.getWorld(worldName) == null) {
            Msg.send(sender, "世界 " + worldName + " 不存在或未加载。");
            return true;
        }
        if (pkwWorldManager.isPkwsWorld(worldName)) {
            Msg.send(sender, "添加失败：" + worldName + " 已经是 PKWS 世界，不能同时标记为 PKW 世界。");
            return true;
        }
        boolean added = pkwWorldManager.add(worldName, mode);
        if (added) {
            SITParkourWarrior.applyGamerulesToWorld(worldName);
            courseLayoutAnalyzer.recomputeWorld(worldName);
        }
        Msg.send(sender, added
                ? "已将 " + worldName + " 加入 PKW 世界列表（" + mode.toConfigString() + "）。"
                : worldName + " 已在 PKW 世界列表中。");
        return true;
    }

    private boolean handlePkwWorldSetMode(CommandSender sender, String[] args) {
        String[] wv = resolveWorldAndValue(sender, args, 2);
        if (wv == null) {
            Msg.send(sender, "用法: /sitpkw pkwworld setmode [世界名] <countup|countdown>");
            return true;
        }
        String worldName = wv[0];
        TimingMode mode = TimingMode.fromString(wv[1]);
        if (!pkwWorldManager.isPkwWorld(worldName)) {
            Msg.send(sender, worldName + " 不是 PKW 世界。");
            return true;
        }
        pkwWorldManager.setMode(worldName, mode);
        Msg.send(sender, "已将 " + worldName + " 的模式改为 " + mode.toConfigString() + "。");
        return true;
    }

    private boolean handlePkwWorldDuration(CommandSender sender, String[] args) {
        String[] wv = resolveWorldAndValue(sender, args, 2);
        if (wv == null) {
            Msg.send(sender, "用法: /sitpkw pkwworld duration [世界名] <秒数>");
            return true;
        }
        String worldName = wv[0];
        if (!pkwWorldManager.isPkwWorld(worldName)) {
            Msg.send(sender, worldName + " 不是 PKW 世界。");
            return true;
        }
        if (pkwWorldManager.getTimingMode(worldName) == TimingMode.COUNTUP) {
            Msg.send(sender, "注意：" + worldName + " 是正计时模式，设定的时长暂不生效。");
        }
        int sec;
        try {
            sec = Integer.parseInt(wv[1]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "无效秒数: " + wv[1]);
            return true;
        }
        if (sec <= 0) {
            Msg.send(sender, "秒数必须为正整数。");
            return true;
        }
        pkwWorldManager.setCountdownDuration(worldName, sec);
        Msg.send(sender, "已将 " + worldName + " 的倒计时时长设为 " + sec + " 秒。");
        return true;
    }

    private boolean handlePkwWorldRemove(CommandSender sender, String[] args) {
        String worldName;
        if (args.length >= 3) {
            worldName = args[2];
        } else if (sender instanceof Player) {
            worldName = ((Player) sender).getWorld().getName();
        } else {
            Msg.send(sender, "控制台必须指定世界名: /sitpkw pkwworld remove <世界名>");
            return true;
        }
        boolean removed = pkwWorldManager.remove(worldName);
        if (removed) {
            courseLayoutAnalyzer.removeWorld(worldName);
        }
        Msg.send(sender, removed ? "已将 " + worldName + " 移出 PKW 世界列表。" : worldName + " 不在 PKW 世界列表中。");
        return true;
    }

    private boolean handlePkwWorldList(CommandSender sender) {
        java.util.Set<String> worlds = pkwWorldManager.getWorlds();
        if (worlds.isEmpty()) {
            Msg.send(sender, "当前没有 PKW 世界。");
        } else {
            Msg.send(sender, "PKW 世界列表:");
            for (String w : worlds) {
                TimingMode mode = pkwWorldManager.getTimingMode(w);
                int dur = pkwWorldManager.getCountdownDuration(w);
                String extra = mode == TimingMode.COUNTDOWN && dur > 0 ? "（倒计时 " + dur + " 秒）" : "";
                Msg.send(sender, "  - " + w + " [" + mode.toConfigString() + "]" + extra);
            }
        }
        return true;
    }

    private boolean handlePkwWorldThreshold(CommandSender sender, String[] args) {
        if (args.length != 5) {
            Msg.send(sender, "用法: /sitpkw pkwworld threshold <世界名> <standard|advance|expect> <奖牌数>");
            return true;
        }
        String worldName = args[2];
        String tier = args[3].toLowerCase(Locale.ROOT);
        if (!pkwWorldManager.isPkwWorld(worldName)
                || pkwWorldManager.getTimingMode(worldName) != TimingMode.COUNTUP) {
            Msg.send(sender, "世界 " + worldName + " 不支持正计时排行榜门槛配置。");
            return true;
        }
        if (!tier.equals("standard") && !tier.equals("advance") && !tier.equals("expect")) {
            Msg.send(sender, "榜单类型必须为 standard、advance 或 expect。");
            return true;
        }
        int threshold;
        try {
            if (!args[4].matches("\\d+")) throw new NumberFormatException();
            threshold = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            Msg.send(sender, "奖牌数必须是非负整数，原有门槛未改变。");
            return true;
        }
        recordsManager.setCountupThreshold(worldName, tier, threshold);
        Msg.send(sender, "已将 " + worldName + " 的 " + tier + " 榜最低奖牌数设为 "
                + threshold + "，并完成历史成绩重审。");
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        String targetName;
        if (args.length >= 2) {
            targetName = args[1];
        } else if (sender instanceof Player player) {
            targetName = player.getName();
        } else {
            Msg.send(sender, "控制台必须指定玩家名: /sitpkw stats <玩家名>");
            return true;
        }

        // Try UUID first if online
        String lookup = targetName;
        org.bukkit.entity.Player online = Bukkit.getPlayer(targetName);
        if (online != null) {
            lookup = online.getUniqueId().toString();
        }

        int[] counts = recordsManager.countPlayerStats(lookup);
        Msg.send(sender, "玩家 " + targetName + " 的正计时成绩:");
        Msg.send(sender, "  标准榜(Standard): " + counts[0]);
        Msg.send(sender, "  进阶榜(Advance): " + counts[1]);
        Msg.send(sender, "  卓越榜(Expect): " + counts[2]);
        return true;
    }

    private boolean handleQuit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (sessionManager.getRunProgress(player.getUniqueId()) == null) {
            Msg.send(sender, "你没有进行中的跑酷。");
            return true;
        }
        sessionManager.quitRun(player);
        if (pkwsRaceManager.isParticipant(player.getUniqueId())) {
            pkwsRaceManager.abandon(player);
        }
        Msg.send(player, "已放弃当前跑酷。");
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw top <standard|advance|expect|countdown> [世界名]");
            return true;
        }
        String tier = args[1].toLowerCase();
        if (!tier.equals("standard") && !tier.equals("advance") && !tier.equals("expect") && !tier.equals("countdown")) {
            Msg.send(sender, "榜名须为 standard、advance、expect 或 countdown。");
            return true;
        }
        String worldName;
        if (args.length >= 3) {
            worldName = args[2];
        } else if (sender instanceof Player player) {
            worldName = player.getWorld().getName();
        } else {
            Msg.send(sender, "控制台必须指定世界名: /sitpkw top <standard|advance|expect|countdown> <世界名>");
            return true;
        }
        if (!pkwWorldManager.isPkwWorld(worldName)) {
            Msg.send(sender, worldName + " 不是 PKW 世界。");
            return true;
        }

        // 查询者自身名称（控制台则无自身成绩行）
        String selfName = sender instanceof Player player ? player.getName() : null;

        if (tier.equals("countdown")) {
            java.util.List<club.sitmc.sitParkourWarrior.records.RecordsManager.CountdownRankEntry> all =
                    recordsManager.getCountdownTop(worldName, Integer.MAX_VALUE);
            if (all.isEmpty()) {
                Msg.send(sender, "§6--- " + worldName + " countdown 榜 ---");
                Msg.send(sender, "暂无记录。");
                return true;
            }
            Msg.send(sender, "§6--- " + worldName + " countdown 榜 (前3) ---");
            int topLimit = Math.min(all.size(), 3);
            for (int i = 0; i < topLimit; i++) {
                var e = all.get(i);
                String time = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(e.timeMs);
                String endTierLabel = e.endTier != null ? endTierDisplay(e.endTier) : "超时";
                Msg.send(sender, (i + 1) + ". " + e.playerName + " - " + e.score + "分"
                        + " 石×" + e.stone + " 铜×" + e.bronze + " 银×" + e.silver + " 金×" + e.gold
                        + " " + endTierLabel + " " + time);
            }
            // 自身成绩（如果不在前3中，或在前3末尾也单独标出）
            if (selfName != null) {
                int selfRank = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).playerName.equals(selfName)) {
                        selfRank = i + 1;
                        break;
                    }
                }
                if (selfRank > 0) {
                    Msg.send(sender, "§8----------------");
                    var e = all.get(selfRank - 1);
                    String time = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(e.timeMs);
                    String endTierLabel = e.endTier != null ? endTierDisplay(e.endTier) : "超时";
                    Msg.send(sender, "§e" + selfRank + ". " + e.playerName + " - " + e.score + "分"
                            + " 石×" + e.stone + " 铜×" + e.bronze + " 银×" + e.silver + " 金×" + e.gold
                            + " " + endTierLabel + " " + time);
                } else {
                    Msg.send(sender, "§7你暂无记录。");
                }
            }
        } else {
            java.util.List<club.sitmc.sitParkourWarrior.records.RecordsManager.RankEntry> all =
                    recordsManager.getTop(worldName, tier, Integer.MAX_VALUE);
            if (all.isEmpty()) {
                Msg.send(sender, "§6--- " + worldName + " " + tier + " 榜 ---");
                Msg.send(sender, "暂无记录。");
                return true;
            }
            Msg.send(sender, "§6--- " + worldName + " " + tier + " 榜 (前3) ---");
            int topLimit = Math.min(all.size(), 3);
            for (int i = 0; i < topLimit; i++) {
                var e = all.get(i);
                String time = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(e.timeMs);
                Msg.send(sender, (i + 1) + ". " + e.playerName + " - " + time + " (" + e.medals + "牌)");
            }
            // 自身成绩
            if (selfName != null) {
                int selfRank = -1;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).playerName.equals(selfName)) {
                        selfRank = i + 1;
                        break;
                    }
                }
                if (selfRank > 0) {
                    Msg.send(sender, "§8----------------");
                    var e = all.get(selfRank - 1);
                    String time = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(e.timeMs);
                    Msg.send(sender, "§e" + selfRank + ". " + e.playerName + " - " + time + " (" + e.medals + "牌)");
                } else {
                    Msg.send(sender, "§7你暂无记录。");
                }
            }
        }
        return true;
    }

    private boolean handleBoard(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "只能由玩家执行。");
            return true;
        }
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw board <add|remove|list>");
            return true;
        }
        String worldName = player.getWorld().getName();
        if (!pkwWorldManager.isPkwWorld(worldName)) {
            Msg.send(sender, "榜牌只能在 PKW 世界使用。");
            return true;
        }
        TimingMode mode = pkwWorldManager.getTimingMode(worldName);

        switch (args[1].toLowerCase()) {
            case "add": {
                if (args.length < 3) {
                    Msg.send(sender, "用法: /sitpkw board add <榜名>");
                    return true;
                }
                String tier = args[2].toLowerCase();
                if (mode == TimingMode.COUNTUP && tier.equals("countdown")) {
                    Msg.send(sender, "正计时世界不支持倒计时榜。可用: standard, advance, expect");
                    return true;
                }
                if (mode == TimingMode.COUNTDOWN && !tier.equals("countdown")) {
                    Msg.send(sender, "倒计时世界只支持 countdown 榜。");
                    return true;
                }
                if (!tier.equals("standard") && !tier.equals("advance") && !tier.equals("expect") && !tier.equals("countdown")) {
                    Msg.send(sender, "无效榜名。可用: standard, advance, expect, countdown");
                    return true;
                }
                BoardData board = boardManager.addBoard(worldName, tier, player.getLocation());
                boardManager.notifyBoardChanged();
                Msg.send(sender, "已创建" + board.tierDisplayName() + "。");
                return true;
            }
            case "remove": {
                BoardData removed = boardManager.removeNearest(player.getLocation());
                if (removed != null) {
                    boardManager.notifyBoardChanged();
                    Msg.send(sender, "已移除" + removed.tierDisplayName() + "。");
                } else {
                    Msg.send(sender, "附近没有榜牌。");
                }
                return true;
            }
            case "list": {
                java.util.List<BoardData> list = boardManager.getBoardsInWorld(worldName);
                if (list.isEmpty()) {
                    Msg.send(sender, "当前世界没有榜牌。");
                } else {
                    Msg.send(sender, "当前世界榜牌:");
                    for (BoardData b : list) {
                        Msg.send(sender, "  - " + b.tierDisplayName()
                                + " (" + (int) b.getX() + ", " + (int) b.getY() + ", " + (int) b.getZ() + ")");
                    }
                }
                return true;
            }
            case "cleanup":
                return handleBoardCleanup(sender, player, args);
            default:
                Msg.send(sender, "用法: /sitpkw board <add|remove|list|cleanup>");
                return true;
        }
    }

    private boolean handleBoardCleanup(CommandSender sender, Player player, String[] args) {
        // /sitpkw board cleanup [世界名]      → 扫描孤儿
        // /sitpkw board cleanup confirm       → 确认删除
        // /sitpkw board cleanup cancel        → 取消操作

        // 如果 args[2] 是 confirm/cancel 则走对应流程；否则视为世界名（或默认当前世界）
        boolean isConfirm = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        boolean isCancel = args.length >= 3 && args[2].equalsIgnoreCase("cancel");

        if (isConfirm) {
            java.util.List<Entity> pending = boardManager.getPendingCleanup(player.getUniqueId());
            if (pending == null || pending.isEmpty()) {
                Msg.send(sender, "§c没有待处理的清理操作。请先运行 /sitpkw board cleanup [世界名]。");
                return true;
            }

            // 转为 TextDisplay 列表
            java.util.List<TextDisplay> toRemove = new ArrayList<>();
            for (Entity e : pending) {
                if (e instanceof TextDisplay && e.isValid()) {
                    toRemove.add((TextDisplay) e);
                }
            }

            int removed = boardManager.cleanupOrphans(toRemove);
            boardManager.clearPendingCleanup(player.getUniqueId());
            Msg.send(sender, "§a已清理 " + removed + " 个孤儿实体。");
            boardManager.notifyBoardChanged();
            return true;
        }

        if (isCancel) {
            boardManager.clearPendingCleanup(player.getUniqueId());
            Msg.send(sender, "§e已取消清理操作。");
            return true;
        }

        // ===== scan: 世界名为 args[2] 或默认当前世界 =====
        String targetWorld;
        if (args.length >= 3) {
            targetWorld = args[2];
        } else {
            targetWorld = player.getWorld().getName();
        }

        BoardManager.OrphanScanResult result = boardManager.scanOrphans(targetWorld);
        if (result.totalPdcEntities == 0) {
            Msg.send(sender, "世界 " + targetWorld + " 中未找到任何带本插件 PDC 标记的 TextDisplay 实体。");
            return true;
        }

        Msg.send(sender, "§6===== 世界 " + targetWorld + " 榜牌实体扫描 =====");
        Msg.send(sender, "  PDC 标记实体总数: " + result.totalPdcEntities);
        Msg.send(sender, "  有效榜牌实体: " + result.validEntities);
        Msg.send(sender, "  孤儿实体(无 boards.yml 记录): " + result.orphans.size());

        if (result.orphans.isEmpty()) {
            Msg.send(sender, "§a未发现孤儿实体，无需清理。");
            boardManager.clearPendingCleanup(player.getUniqueId());
            return true;
        }

        Msg.send(sender, "§e----- 孤儿实体列表 -----");
        for (TextDisplay td : result.orphans) {
            org.bukkit.Location loc = td.getLocation();
            Msg.send(sender, "  §cUUID: " + td.getUniqueId()
                    + "  坐标: (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
        }

        // 存到 pending
        boardManager.setPendingCleanup(player.getUniqueId(), result.orphans);

        Msg.send(sender, "§e-----");
        Msg.send(sender, "§6输入 /sitpkw board cleanup confirm 确认删除以上所有孤儿实体。");
        Msg.send(sender, "§6输入 /sitpkw board cleanup cancel 取消操作。");
        Msg.send(sender, "§7提示: 仅扫描当前已加载区块。如果孤儿可能在未加载区块，请走到相应区域后再运行扫描。");
        return true;
    }

    private boolean handleRecord(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Msg.send(sender, "用法: /sitpkw record delete <世界名> <奖牌数|countdown> <玩家名或UUID>");
            return true;
        }
        if (!args[1].equalsIgnoreCase("delete")) {
            Msg.send(sender, "用法: /sitpkw record delete <世界名> <奖牌数|countdown> <玩家名或UUID>");
            return true;
        }
        if (args.length < 5) {
            Msg.send(sender, "用法: /sitpkw record delete <世界名> <奖牌数|countdown> <玩家名或UUID>");
            return true;
        }
        String worldName = args[2];
        String recordType = args[3].toLowerCase(Locale.ROOT);
        String playerId = args[4];

        String deletedName;
        if (recordType.equals("countdown")) {
            // Keep the existing countdown command available; it has no medal-count raw view.
            deletedName = recordsManager.deleteRecord(worldName, "countdown", playerId);
        } else {
            int medals;
            try {
                medals = Integer.parseInt(recordType);
            } catch (NumberFormatException ex) {
                Msg.send(sender, "无效奖牌数。请输入非负整数；倒计时记录使用 countdown。");
                return true;
            }
            if (medals < 0) {
                Msg.send(sender, "无效奖牌数。请输入非负整数。");
                return true;
            }
            deletedName = recordsManager.deleteCountupRecord(worldName, medals, playerId);
        }
        if (deletedName != null) {
            if (recordType.equals("countdown")) boardManager.notifyBoardChanged();
            Msg.send(sender, "已删除 [" + worldName + "] [" + recordType + "] 玩家 " + deletedName + " 的成绩。");
        } else {
            Msg.send(sender, "未找到 [" + worldName + "] [" + recordType + "] 中玩家 " + playerId + " 的记录。");
        }
        return true;
    }

    private static String endTierDisplay(String tier) {
        if (tier == null) return "超时";
        switch (tier) {
            case "easy":   return "简单";
            case "normal": return "普通";
            case "hard":   return "困难";
            default:       return tier;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            var allCmds = new java.util.ArrayList<>(Arrays.asList("create", "edit", "exit", "particles", "sound", "pos1", "pos2", "setstart", "setend", "title", "difficulty", "dynamic", "deploy", "undeploy", "addforkpoint", "delforkpoint", "setendtier", "save", "delete", "pkwworld", "quit", "top", "board", "record", "stats", "reload", "tool", "level", "config", "pkws"));
            if (sender instanceof Player && !sender.hasPermission("dynamicparkour.admin")) {
                allCmds.removeIf(ADMIN_COMMANDS::contains);
            }
            return filter(args[0], allCmds);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("level")) {
            return filter(args[1], Arrays.asList("orientation", "difficulty", "weight", "info"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pkws")) {
            return filter(args[1], Arrays.asList("settype", "info", "list", "gate",
                    "range", "ranges", "time", "generate", "refresh", "clear", "trackinfo", "world",
                    "start", "forceopen", "wins", "top"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pkws")) {
            if (args[1].equalsIgnoreCase("forceopen")) {
                return filter(args[2], Arrays.asList("NS", "EW", "list"));
            }
            if (args[1].equalsIgnoreCase("range")
                    || args[1].equalsIgnoreCase("ranges")
                    || args[1].equalsIgnoreCase("generate")) {
                    return filter(args[2], Arrays.asList("NS", "EW", "template", "confirm"));
            }
            if (args[1].equalsIgnoreCase("refresh")) {
                return filter(args[2], Arrays.asList("NS", "EW", "world", "confirm"));
            }
            if (args[1].equalsIgnoreCase("time")) {
                return filter(args[2], Arrays.asList("NS", "EW", "list", "edit", "template"));
            }
            if (args[1].equalsIgnoreCase("world")) {
                return filter(args[2], Arrays.asList("add", "remove", "list"));
            }
            if (args[1].equalsIgnoreCase("start")) {
                return filter(args[2], Arrays.asList("close", "open", "countdown"));
            }
            if (args[1].equalsIgnoreCase("wins")) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
                names.addAll(pkwsWinsStore.getKnownNames());
                return filter(args[2], names);
            }
            if (args[1].equalsIgnoreCase("range")) {
                List<String> segments = new ArrayList<>();
                for (int i = 1; i <= PkwsRangeConfig.SEGMENT_COUNT; i++) segments.add(String.valueOf(i));
                return filter(args[2], segments);
            }
            if (args[1].equalsIgnoreCase("generate")) {
                return filter(args[2], Arrays.asList("NS", "EW"));
            }
            if (args[1].equalsIgnoreCase("clear")) {
                return filter(args[2], Arrays.asList("confirm"));
            }
            if (args[1].equalsIgnoreCase("settype") || args[1].equalsIgnoreCase("info")) {
                return filter(args[2], getMapIdSuggestions());
            }
            if (args[1].equalsIgnoreCase("list")) {
                return filter(args[2], Arrays.asList("RELAY", "START", "END"));
            }
            if (args[1].equalsIgnoreCase("gate")) {
                return filter(args[2], Arrays.asList("open", "close"));
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && args[2].equalsIgnoreCase("template")) {
            return filter(args[3], Arrays.asList("save", "load", "list", "delete"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("template")) {
            return filter(args[3], Arrays.asList("save", "load", "list", "delete"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("edit")) {
            return filter(args[3], Arrays.asList("NS", "EW"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("list")) {
            return filter(args[3], Arrays.asList("NS", "EW"));
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("template")
                && (args[3].equalsIgnoreCase("save") || args[3].equalsIgnoreCase("load"))) {
            return filter(args[5], Arrays.asList("NS", "EW"));
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("template")
                && args[3].equalsIgnoreCase("delete")) {
            return filter(args[5], Arrays.asList("confirm"));
        }
        if (args.length == 7 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("template")
                && args[3].equalsIgnoreCase("load")) {
            return filter(args[6], Arrays.asList("confirm"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && PkwsOrientation.fromString(args[2]) != null) {
            return filter(args[3], Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("forceopen")
                && args[2].equalsIgnoreCase("list")) {
            return filter(args[3], Arrays.asList("NS", "EW"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("forceopen")
                && PkwsOrientation.fromString(args[2]) != null) {
            return filter(args[3], Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"));
        }
        if (args.length >= 4 && args.length <= 7
                && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("refresh")) {
            if (args[args.length - 2].equalsIgnoreCase("world")) {
                List<String> worlds = new ArrayList<>();
                for (World world : Bukkit.getWorlds()) worlds.add(world.getName());
                return filter(args[args.length - 1], worlds);
            }
            return filter(args[args.length - 1], Arrays.asList("NS", "EW", "world", "confirm"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && PkwsOrientation.fromString(args[2]) != null) {
            List<String> segments = new ArrayList<>();
            for (int i = 1; i <= PkwsRangeConfig.SEGMENT_COUNT; i++) {
                segments.add(String.valueOf(i));
            }
            return filter(args[3], segments);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")) {
            List<String> positions = new ArrayList<>();
            for (int i = 1; i <= PkwsRangeConfig.POSITION_COUNT; i++) positions.add(String.valueOf(i));
            return filter(args[3], positions);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("world")
                && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) {
            List<String> worlds = new ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) worlds.add(world.getName());
            return filter(args[3], worlds);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("generate")) {
            return filter(args[3], Arrays.asList("confirm"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("start")
                && args[2].equalsIgnoreCase("countdown")) {
            // 秒数可自由指定，因此只提示当前配置的默认秒数。
            return filter(args[3], List.of(
                    String.valueOf(pkwsRangeConfig.getStartCountdownSeconds())));
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && PkwsOrientation.fromString(args[2]) != null) {
            List<String> positions = new ArrayList<>();
            for (int i = 1; i <= PkwsRangeConfig.POSITION_COUNT; i++) {
                positions.add(String.valueOf(i));
            }
            return filter(args[4], positions);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("pkws")
                && (args[1].equalsIgnoreCase("time")
                || args[1].equalsIgnoreCase("forceopen"))
                && PkwsOrientation.fromString(args[2]) != null) {
            List<String> seconds = new ArrayList<>();
            for (int value : new int[]{0, 60, 180, 300, 600, 900, 1200, 1800}) {
                seconds.add(String.valueOf(value));
            }
            return filter(args[4], seconds);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 16; i++) values.add(String.valueOf(i));
            return filter(args[4], values);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && args[2].equalsIgnoreCase("template")
                && (args[3].equalsIgnoreCase("save") || args[3].equalsIgnoreCase("load"))) {
            return filter(args[5], Arrays.asList("NS", "EW"));
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("time")
                && args[2].equalsIgnoreCase("template")
                && (args[3].equalsIgnoreCase("save") || args[3].equalsIgnoreCase("load"))) {
            return filter(args[5], Arrays.asList("NS", "EW"));
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && PkwsOrientation.fromString(args[2]) != null) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 16; i++) values.add(String.valueOf(i));
            return filter(args[5], values);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 16; i++) values.add(String.valueOf(i));
            return filter(args[5], values);
        }
        if (args.length == 7 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("range")
                && PkwsOrientation.fromString(args[2]) != null) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 16; i++) values.add(String.valueOf(i));
            return filter(args[6], values);
        }
        if (args.length == 7 && args[0].equalsIgnoreCase("pkws")
                && (args[1].equalsIgnoreCase("range") || args[1].equalsIgnoreCase("time"))
                && args[2].equalsIgnoreCase("template")
                && args[3].equalsIgnoreCase("load")) {
            return filter(args[6], Arrays.asList("confirm"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("settype")) {
            return filter(args[3], Arrays.asList("RELAY", "START", "END"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pkws")
                && args[1].equalsIgnoreCase("gate")) {
            return filter(args[3], getMapIdSuggestions());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("level")
                && (args[1].equalsIgnoreCase("orientation")
                || args[1].equalsIgnoreCase("difficulty")
                || args[1].equalsIgnoreCase("weight")
                || args[1].equalsIgnoreCase("info"))) {
            return filter(args[2], getMapIdSuggestions());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("level")
                && args[1].equalsIgnoreCase("orientation")) {
            return filter(args[3], Arrays.asList("NS", "EW", "reset"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("level")
                && args[1].equalsIgnoreCase("difficulty")) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= 16; i++) values.add(String.valueOf(i));
            values.add("reset");
            return filter(args[3], values);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return filter(args[2], Arrays.asList("level", "fork", "globalstart", "globalend", "branchend", "pkws_relay", "pkws_start", "pkws_end"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setendtier")) {
            return filter(args[1], Arrays.asList("easy", "normal", "hard"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("difficulty")) {
            return filter(args[1], Arrays.asList("easy", "normal", "hard", "extreme"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dynamic")) {
            return filter(args[1], Arrays.asList("addstate", "delstate", "interval", "list"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setstart") || args[0].equalsIgnoreCase("setend"))) {
            return filter(args[1], Arrays.asList("pos1", "pos2"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("particles") || args[0].equalsIgnoreCase("sound"))) {
            return filter(args[1], Arrays.asList("on", "off"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("deploy")
                || args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("delete"))) {
            return filter(args[1], new ArrayList<>(mapManager.getMaps().keySet()));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pkwworld")) {
            return filter(args[1], Arrays.asList("add", "setmode", "duration", "remove", "list", "threshold"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            return filter(args[1], Arrays.asList("standard", "advance", "expect", "countdown"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            List<String> names = new ArrayList<>();
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(args[1], names);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("board")) {
            return filter(args[1], Arrays.asList("add", "remove", "list", "cleanup"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("record")) {
            return filter(args[1], Arrays.asList("delete"));
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("record")) {
            if (args.length == 3) {
                return filter(args[2], new ArrayList<>(recordsManager.getRecordedWorldNames()));
            }
            if (args.length == 4) {
                List<String> choices = new ArrayList<>(recordsManager.getCountupMedalCounts(args[2]));
                choices.add("countdown");
                return filter(args[3], choices);
            }
            if (args.length == 5 && args.length >= 4) {
                String worldName = args[2];
                String recordType = args[3].toLowerCase(Locale.ROOT);
                if (recordType.equals("countdown")) {
                    return filter(args[4], recordsManager.getPlayerNames(worldName, "countdown"));
                }
                try {
                    Integer.parseInt(recordType);
                    // Raw count-up records may be present without being on any view.
                    return filter(args[4], recordsManager.getCountupPlayerNames(worldName, Integer.parseInt(recordType)));
                } catch (NumberFormatException ignored) {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("board") && args[1].equalsIgnoreCase("add")
                && sender instanceof Player player) {
            TimingMode mode = pkwWorldManager.getTimingMode(player.getWorld());
            if (mode == TimingMode.COUNTDOWN) {
                return filter(args[2], Arrays.asList("countdown"));
            }
            return filter(args[2], Arrays.asList("standard", "advance", "expect"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("board") && args[1].equalsIgnoreCase("cleanup")) {
            List<String> opts = new ArrayList<>();
            opts.add("confirm");
            opts.add("cancel");
            for (org.bukkit.World w : Bukkit.getWorlds()) opts.add(w.getName());
            return filter(args[2], opts);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("top")) {
            List<String> opts = new ArrayList<>();
            for (String w : pkwWorldManager.getWorlds()) opts.add(w);
            return filter(args[2], opts);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("pkwworld")) {
            String sub = args[1].toLowerCase();
            int modePos = sub.equals("add") || sub.equals("setmode") ? 3 : (sub.equals("duration") || sub.equals("remove") ? -1 : -1);
            if (sub.equals("duration")) {
                // arg[2] could be world or value, arg[3] could be value
                if (args.length == 3) {
                    List<String> opts = new ArrayList<>();
                    for (org.bukkit.World w : Bukkit.getWorlds()) opts.add(w.getName());
                    return filter(args[2], opts);
                }
                return Collections.emptyList();
            }
            if (sub.equals("threshold")) {
                if (args.length == 3) {
                    return filter(args[2], new ArrayList<>(pkwWorldManager.getWorlds()));
                }
                if (args.length == 4) {
                    return filter(args[3], Arrays.asList("standard", "advance", "expect"));
                }
                return Collections.emptyList();
            }
            if ((sub.equals("add") || sub.equals("setmode")) && args.length == 3) {
                // Could be world name or mode value
                List<String> opts = new ArrayList<>();
                opts.addAll(Arrays.asList("countup", "countdown"));
                for (org.bukkit.World w : Bukkit.getWorlds()) opts.add(w.getName());
                return filter(args[2], opts);
            }
            if ((sub.equals("add") || sub.equals("setmode")) && args.length == 4) {
                return filter(args[3], Arrays.asList("countup", "countdown"));
            }
            if (sub.equals("remove") && args.length == 3) {
                List<String> opts = new ArrayList<>();
                for (org.bukkit.World w : Bukkit.getWorlds()) opts.add(w.getName());
                return filter(args[2], opts);
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delforkpoint") && sender instanceof Player player) {
            ParkourMap map = selectionManager.getEditingMap(player);
            if (map != null) {
                List<String> indices = new ArrayList<>();
                for (int i = 1; i <= map.getForkBranchPoints().size(); i++) {
                    indices.add(String.valueOf(i));
                }
                return filter(args[1], indices);
            }
        }
        return Collections.emptyList();
    }

    private List<String> getMapIdSuggestions() {
        List<String> ids = new ArrayList<>();
        for (ParkourMap map : mapManager.getMaps().values()) {
            ids.add(map.getId());
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private List<String> filter(String token, List<String> options) {
        if (token == null || token.isBlank()) {
            return options;
        }
        String lower = token.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}


