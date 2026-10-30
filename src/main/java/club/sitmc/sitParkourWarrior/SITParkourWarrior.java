package club.sitmc.sitParkourWarrior;

import club.sitmc.sitParkourWarrior.board.BoardData;
import club.sitmc.sitParkourWarrior.board.BoardManager;
import club.sitmc.sitParkourWarrior.board.BoardRenderer;
import club.sitmc.sitParkourWarrior.command.DPCommand;
import club.sitmc.sitParkourWarrior.command.PkwsStartCountdownHints;
import club.sitmc.sitParkourWarrior.config.CountdownScoringConfig;
import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.config.TimingMode;
import club.sitmc.sitParkourWarrior.course.CourseLayoutAnalyzer;
import club.sitmc.sitParkourWarrior.editor.EditSessionManager;
import club.sitmc.sitParkourWarrior.listener.EditControlListener;
import club.sitmc.sitParkourWarrior.listener.EditRegionChangeListener;
import club.sitmc.sitParkourWarrior.listener.EditSessionCleanupListener;
import club.sitmc.sitParkourWarrior.listener.StateListListener;
import club.sitmc.sitParkourWarrior.listener.ItemLockListener;
import club.sitmc.sitParkourWarrior.listener.PkwItemInteractListener;
import club.sitmc.sitParkourWarrior.listener.PlayerDeathListener;
import club.sitmc.sitParkourWarrior.listener.PlayerJoinListener;
import club.sitmc.sitParkourWarrior.listener.PlayerMoveListener;
import club.sitmc.sitParkourWarrior.listener.PlayerQuitListener;
import club.sitmc.sitParkourWarrior.listener.PlayerRespawnListener;
import club.sitmc.sitParkourWarrior.listener.PlayerTeleportListener;
import club.sitmc.sitParkourWarrior.listener.PkwsConfigListener;
import club.sitmc.sitParkourWarrior.listener.PkwsPracticeGuardListener;
import club.sitmc.sitParkourWarrior.listener.PkwsRangeListener;
import club.sitmc.sitParkourWarrior.listener.PkwsWorldLoadListener;
import club.sitmc.sitParkourWarrior.listener.SelectionToolListener;
import club.sitmc.sitParkourWarrior.listener.WorldChangeListener;
import club.sitmc.sitParkourWarrior.map.DynamicService;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsGenerationService;
import club.sitmc.sitParkourWarrior.pkws.PkwsDisconnectStore;
import club.sitmc.sitParkourWarrior.pkws.PkwsRangeConfig;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsTrackStore;
import club.sitmc.sitParkourWarrior.pkws.PkwsTemplateStore;
import club.sitmc.sitParkourWarrior.pkws.PkwsWinsStore;
import club.sitmc.sitParkourWarrior.records.RecordsManager;
import club.sitmc.sitParkourWarrior.session.RunProgress;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.util.ParticleService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SITParkourWarrior extends JavaPlugin {

    private MapManager mapManager;
    private SessionManager sessionManager;
    private DynamicService dynamicService;
    private ParticleService particleService;
    private SelectionManager selectionManager;
    private CourseLayoutAnalyzer courseLayoutAnalyzer;
    private PkwWorldManager pkwWorldManager;
    private RecordsManager recordsManager;
    private CountdownScoringConfig countdownScoring;
    private BoardManager boardManager;
    private VisibilityManager visibilityManager;
    private EditSessionManager editSessionManager;
    private PkwsRangeConfig pkwsRangeConfig;
    private PkwsGenerationService pkwsGenerationService;
    private PkwsTrackStore pkwsTrackStore;
    private PkwsTemplateStore pkwsTemplateStore;
    private PkwsWinsStore pkwsWinsStore;
    private PkwsDisconnectStore pkwsDisconnectStore;
    private PkwsRaceManager pkwsRaceManager;
    private BukkitTask actionBarTask;
    private BukkitTask boardRefreshTask;
    private BukkitTask visibilityTask;
    private BukkitTask editAutosaveTask;
    private static final long EDIT_AUTOSAVE_INTERVAL_TICKS = 20L * 60; // 60 seconds
    private final AtomicBoolean worldInitDone = new AtomicBoolean(false);
    private static final long FALLBACK_DELAY_TICKS = 100L;

    @Override
    public void onEnable() {
        this.mapManager = new MapManager(this);
        this.pkwsRangeConfig = new PkwsRangeConfig(getDataFolder(), getLogger());
        this.pkwsTrackStore = new PkwsTrackStore(getDataFolder(), getLogger());
        this.pkwsTemplateStore = new PkwsTemplateStore(getDataFolder(), getLogger());
        this.pkwsWinsStore = new PkwsWinsStore(getDataFolder(), getLogger());
        this.pkwsDisconnectStore = new PkwsDisconnectStore(getDataFolder(), getLogger());
        this.dynamicService = new DynamicService(this, mapManager);
        this.particleService = new ParticleService(this);
        this.selectionManager = new SelectionManager(mapManager, particleService);
        ItemUtil.init(this);
        this.pkwWorldManager = new PkwWorldManager(getDataFolder());
        this.recordsManager = new RecordsManager(getDataFolder());
        this.countdownScoring = new CountdownScoringConfig(getDataFolder());
        this.boardManager = new BoardManager(this, getDataFolder());
        this.boardManager.init(new NamespacedKey(this, BoardManager.class.getSimpleName()));
        this.courseLayoutAnalyzer = new CourseLayoutAnalyzer(mapManager, pkwWorldManager, getDataFolder());
        this.sessionManager = new SessionManager(this, mapManager, dynamicService, recordsManager, pkwWorldManager, courseLayoutAnalyzer, countdownScoring, null);
        this.pkwsGenerationService = new PkwsGenerationService(this, mapManager,
                pkwsRangeConfig, pkwsTrackStore, dynamicService, sessionManager);
        this.visibilityManager = new VisibilityManager(this, pkwWorldManager, sessionManager);
        this.pkwsRaceManager = new PkwsRaceManager(this, pkwWorldManager, mapManager,
                pkwsTrackStore, pkwsRangeConfig, pkwsWinsStore, visibilityManager,
                dynamicService, sessionManager, pkwsDisconnectStore);
        this.visibilityManager.setPkwsRaceManager(pkwsRaceManager);
        sessionManager.setVisibilityManager(visibilityManager);
        this.editSessionManager = new EditSessionManager(this, mapManager, selectionManager);
        this.particleService.start(selectionManager);
        mapManager.loadAll();
        dynamicService.startAllDeployed();
        pkwsRaceManager.initializeLoadedWorlds();
        applyGamerulesToAllPkwWorlds();
        // 世界依赖初始化改为事件驱动 + 兜底，不在 onEnable 阶段提前执行
        tryRegisterManagedWorldsLoadedListener();
        scheduleWorldInitFallback();

        getServer().getPluginManager().registerEvents(new PlayerMoveListener(sessionManager, mapManager, selectionManager, pkwWorldManager, courseLayoutAnalyzer, visibilityManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(sessionManager, pkwWorldManager, visibilityManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(sessionManager, pkwWorldManager, recordsManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerRespawnListener(sessionManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new SelectionToolListener(selectionManager), this);
        getServer().getPluginManager().registerEvents(new ItemLockListener(pkwWorldManager), this);
        getServer().getPluginManager().registerEvents(new WorldChangeListener(pkwWorldManager, sessionManager, recordsManager, visibilityManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PkwItemInteractListener(sessionManager, mapManager, pkwWorldManager, courseLayoutAnalyzer, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PlayerTeleportListener(sessionManager, pkwWorldManager,
                visibilityManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new PkwsPracticeGuardListener(this), this);
        // PKWS 世界可能在本插件启用之后才加载，那时补一次“重启后起点关闭”的复位。
        getServer().getPluginManager().registerEvents(
                new PkwsWorldLoadListener(pkwWorldManager, pkwsRaceManager), this);
        getServer().getPluginManager().registerEvents(new EditControlListener(editSessionManager, mapManager, this), this);
        getServer().getPluginManager().registerEvents(new EditRegionChangeListener(selectionManager, editSessionManager), this);
        getServer().getPluginManager().registerEvents(new EditSessionCleanupListener(editSessionManager, selectionManager), this);
        // Phase 2 rev 2: state papers are display-only — rename anvil disabled.
        // getServer().getPluginManager().registerEvents(new EditPaperRenameListener(this, editSessionManager, mapManager), this);
        getServer().getPluginManager().registerEvents(new StateListListener(editSessionManager, mapManager), this);
        getServer().getPluginManager().registerEvents(new PkwsConfigListener(mapManager), this);
        getServer().getPluginManager().registerEvents(
                new PkwsRangeListener(this, pkwsRangeConfig, pkwsTemplateStore, mapManager), this);

        DPCommand command = new DPCommand(mapManager, sessionManager, selectionManager, dynamicService,
                courseLayoutAnalyzer, pkwWorldManager, recordsManager, countdownScoring, boardManager,
                editSessionManager, pkwsRangeConfig, pkwsGenerationService,
                pkwsRaceManager, pkwsWinsStore, pkwsTemplateStore);
        // 起点开赛倒计时的秒数可自由指定（默认 10 秒）；这一层只统一
        // /sitpkw pkws start countdown 的提示语与秒数补全，其余命令仍由 DPCommand 处理。
        PkwsStartCountdownHints commandHints = new PkwsStartCountdownHints(command, pkwsRangeConfig);
        if (getCommand("sitpkw") != null) {
            getCommand("sitpkw").setExecutor(commandHints);
            getCommand("sitpkw").setTabCompleter(commandHints);
        } else {
            getLogger().warning("Command sitpkw not found in plugin.yml");
        }

        startActionBarTimer();
        // boardManager.restoreAllEntities() 移至 initWorldDependent()，等世界就绪后再还原
        startBoardRefreshTask();
        visibilityTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            pkwsRaceManager.enforcePkwsRestrictions();
            visibilityManager.scanAndUpdate();
        }, 0L, 5L);
        editAutosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> editSessionManager.autosaveTick(),
                EDIT_AUTOSAVE_INTERVAL_TICKS, EDIT_AUTOSAVE_INTERVAL_TICKS);
    }

    /**
     * Returns whether a world is configured as a PKWS world.
     * This method is intentionally safe for reflection calls from optional plugins.
     */
    public boolean isPkwsWorld(String worldName) {
        try {
            if (worldName == null || worldName.isBlank() || pkwWorldManager == null) {
                return false;
            }
            return pkwWorldManager.isPkwsWorld(worldName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startActionBarTimer() {
        actionBarTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (pkwWorldManager.isPkwsWorld(player.getWorld())) {
                    if (pkwsRaceManager.isRunning(player.getWorld().getName())) {
                        String status = pkwsRaceManager.getActionBarStatus(player);
                        if (!status.isEmpty()) {
                            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                    new TextComponent(status));
                        }
                    }
                    continue;
                }
                if (!pkwWorldManager.isPkwWorld(player.getWorld())) continue;
                RunProgress rp = sessionManager.getRunProgress(player.getUniqueId());
                if (rp == null) continue;

                long elapsed = rp.getElapsedMs();
                String timeStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTimeTenths(elapsed);

                TimingMode mode = pkwWorldManager.getTimingMode(player.getWorld());
                String msg;
                if (mode == TimingMode.COUNTDOWN) {
                    long remaining = sessionManager.getCountdownRemainingMs(player.getUniqueId(), player.getWorld().getName());
                    String remStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTimeTenths(Math.max(0, remaining));
                    msg = "§e倒计时: §f" + remStr + " §e奖牌: §f石" + rp.getStoneCount()
                            + " 铜" + rp.getBronzeCount() + " 银" + rp.getSilverCount() + " 金" + rp.getGoldCount();
                } else {
                    msg = "§e全程用时: §f" + timeStr + "  🥇 " + rp.getMedals();
                }
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
            }
        }, 0L, 5L);
    }

    public static void applyGamerulesToWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.FIRE_DAMAGE, false);
        world.setGameRule(GameRule.FREEZE_DAMAGE, false);
        world.setGameRule(GameRule.COMMAND_BLOCK_OUTPUT, false);
        world.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
        world.setGameRule(GameRule.DO_TILE_DROPS, false);
    }

    private void applyGamerulesToAllPkwWorlds() {
        for (String worldName : pkwWorldManager.getWorlds()) {
            applyGamerulesToWorld(worldName);
        }
        for (String worldName : pkwWorldManager.getPkwsWorlds()) {
            applyGamerulesToWorld(worldName);
        }
    }

    // ---------------------------------------------------------------
    // 世界依赖初始化（事件驱动 + 兜底 + 降级）
    // ---------------------------------------------------------------

    /**
     * 尝试动态监听 Lobby 的 ManagedWorldsLoadedEvent。
     * 不将 Lobby 作为编译依赖，通过 Class.forName 动态获取事件类。
     */
    private void tryRegisterManagedWorldsLoadedListener() {
        try {
            Class<?> eventClass = Class.forName("club.sitmc.sitParkourLobby.event.ManagedWorldsLoadedEvent");
            getLogger().info("检测到 SITParkourLobby，将通过 ManagedWorldsLoadedEvent 等待世界加载完成。");

            Bukkit.getPluginManager().registerEvent(
                    eventClass.asSubclass(org.bukkit.event.Event.class),
                    new org.bukkit.event.Listener() {},
                    EventPriority.NORMAL,
                    (org.bukkit.event.Listener listener, org.bukkit.event.Event event) -> {
                        if (!worldInitDone.get()) {
                            getLogger().info("收到 ManagedWorldsLoadedEvent（来源: Lobby），"
                                    + "开始执行依赖世界的初始化...");
                            initWorldDependent();
                        } else {
                            getLogger().info("[DEBUG] ManagedWorldsLoadedEvent 触发时 worldInitDone 已为 true，跳过。");
                        }
                    },
                    this
            );
        } catch (ClassNotFoundException e) {
            getLogger().warning("未检测到 SITParkourLobby（找不到 ManagedWorldsLoadedEvent），"
                    + "将使用延迟检测方式初始化世界依赖。");
        } catch (Exception e) {
            getLogger().warning("注册 ManagedWorldsLoadedEvent 监听失败: " + e.getMessage()
                    + "，将使用延迟检测方式初始化世界依赖。");
        }
    }

    /**
     * 兜底：onEnable 后延迟检查世界是否就绪，避免事件未触发导致初始化长期缺失。
     */
    private void scheduleWorldInitFallback() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!worldInitDone.get()) {
                checkAndInitWorldDependent();
            }
        }, FALLBACK_DELAY_TICKS);
    }

    /**
     * 检查所有 PKW 世界是否已加载，就绪则执行依赖世界的初始化。
     * 仅检查一次，不再重试（避免重试循环导致实体重复创建）。
     * 若世界未就绪，记录警告，管理员需手动 /sitpkw reload。
     */
    private void checkAndInitWorldDependent() {
        if (worldInitDone.get()) return;

        boolean allReady = true;
        for (String worldName : pkwWorldManager.getWorlds()) {
            if (Bukkit.getWorld(worldName) == null) {
                allReady = false;
                break;
            }
        }

        if (allReady) {
            getLogger().info("所有 PKW 世界已就绪（来源: 兜底检测），执行依赖世界的初始化。");
            initWorldDependent();
        } else {
            getLogger().warning("PKW 世界尚未全部加载，依赖世界的初始化未执行。"
                    + " 请在世界加载完成后手动执行 /sitpkw reload。");
            // 不再重试 —— 重试循环可能导致实体重复创建
        }
    }

    /**
     * 执行所有依赖世界存在的启动初始化。
     * 使用 AtomicBoolean.compareAndSet 实现原子"检查并执行"，保证只执行一次，
     * 即使 ManagedWorldsLoadedEvent 和兜底任务并发也不会重复。
     */
    private void initWorldDependent() {
        if (!worldInitDone.compareAndSet(false, true)) {
            getLogger().warning("[DEBUG] initWorldDependent 被重复调用，已跳过（worldInitDone=true）");
            return;
        }
        getLogger().info("[DEBUG] initWorldDependent 开始执行（调用栈来源: "
                + Thread.currentThread().getStackTrace()[2].getMethodName() + "）");

        // 1. 重算所有 PKW 世界的 course.yml（关卡分类/branch绑定等）
        courseLayoutAnalyzer.recomputeAllPkwWorlds();

        // 2. 对已加载的 PKW 世界应用 gamerule
        applyGamerulesToAllPkwWorlds();

        // 3. 榜牌：清场 + 重建（BoardManager 内部完成完整清场→重建流程）
        boardManager.restoreAllEntities();

        // 4. 安全网：若仍有重复，清理之（仅 remove，绝不 create）
        boardManager.cleanupDuplicates();

        getLogger().info("[DEBUG] initWorldDependent 执行完毕。");
    }

    private void startBoardRefreshTask() {
        boardManager.setOnBoardChanged(() -> refreshAllBoards());
        recordsManager.setOnLeaderboardChanged(this::refreshAllBoards);
        boardRefreshTask = Bukkit.getScheduler().runTaskTimer(this, this::scanAndRefreshBoards, 0L, 10L);
    }

    private boolean boardRefreshNeeded = true;
    private final java.util.Map<String, String> lastNearestPerBoard = new java.util.HashMap<>(); // board key -> player name

    private void refreshAllBoards() {
        boardRefreshNeeded = true;
    }

    private void scanAndRefreshBoards() {
        for (BoardData board : boardManager.getBoards()) {
            var display = boardManager.getEntity(board);
            if (display == null) continue;

            String playersInRangeKey = board.getWorldName() + ":" + board.getTier() + ":" + board.getEntityUuid();
            String nearestName = null;
            String nearestTime = null;
            int nearestRank = -1;
            boolean nearestInTop3 = false;

            // Find nearest player within 10 blocks
            org.bukkit.World world = Bukkit.getWorld(board.getWorldName());
            if (world != null) {
                double bestDist = Double.MAX_VALUE;
                org.bukkit.entity.Player nearest = null;
                for (org.bukkit.entity.Player p : world.getPlayers()) {
                    if (!board.isInRange(p.getLocation())) continue;
                    double d = board.distanceSquared(p.getLocation());
                    if (d < bestDist) { bestDist = d; nearest = p; }
                }
                if (nearest != null) {
                    nearestName = nearest.getName();
                    boolean isCountdown = "countdown".equals(board.getTier());
                    if (isCountdown) {
                        BoardRenderer.PlayerRankInfo info = BoardRenderer.findPlayerRankCountdown(
                                board.getWorldName(), nearestName, recordsManager);
                        if (info != null) {
                            nearestRank = info.rank;
                            nearestTime = info.display;
                            nearestInTop3 = info.rank <= 3;
                        }
                    } else {
                        BoardRenderer.PlayerRankInfo info = BoardRenderer.findPlayerRankCountup(
                                board.getWorldName(), board.getTier(), nearestName, recordsManager);
                        if (info != null) {
                            nearestRank = info.rank;
                            nearestTime = info.display;
                            nearestInTop3 = info.rank <= 3;
                        }
                    }
                }
            }

            String prevNearest = lastNearestPerBoard.get(playersInRangeKey);
            boolean nearestChanged = !String.valueOf(nearestName).equals(String.valueOf(prevNearest));

            if (boardRefreshNeeded || nearestChanged) {
                String text = BoardRenderer.build(board, recordsManager,
                        nearestName, nearestRank, nearestTime, nearestInTop3);
                display.setText(text);
                lastNearestPerBoard.put(playersInRangeKey, nearestName);
            }
        }
        boardRefreshNeeded = false;
    }

    @Override
    public void onDisable() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
        }
        if (visibilityManager != null) {
            visibilityManager.cleanupAll();
        }
        if (boardRefreshTask != null) {
            boardRefreshTask.cancel();
        }
        if (actionBarTask != null) {
            actionBarTask.cancel();
        }
        if (editAutosaveTask != null) {
            editAutosaveTask.cancel();
        }
        if (editSessionManager != null) {
            editSessionManager.shutdown();
        }
        if (pkwsRaceManager != null) {
            pkwsRaceManager.shutdown();
        }
        if (pkwsGenerationService != null) {
            pkwsGenerationService.shutdown();
        }
        if (dynamicService != null) {
            dynamicService.stopAll();
        }
        if (particleService != null) {
            particleService.stopAll();
        }
        if (mapManager != null) {
            mapManager.saveAll();
        }
    }

    // ---------------------------------------------------------------
    // Public query API (for Lobby clock-menu reflection)
    // ---------------------------------------------------------------

    /**
     * 返回所有 PKW 世界的地图列表信息。
     * 每个世界一条记录，包含 id/name/type/world 字段。
     * （author/status 元数据已移交 SITParkourLobby 统一管理，本方法不再返回。）
     * 供 Lobby 钟菜单通过反射调用。
     *
     * @return 地图列表，异常时返回空列表
     */
    public List<Map<String, String>> getParkourMaps() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            for (String worldName : pkwWorldManager.getWorlds()) {
                Map<String, String> entry = new LinkedHashMap<>();
                TimingMode mode = pkwWorldManager.getTimingMode(worldName);
                entry.put("id", worldName);
                entry.put("name", worldName);
                entry.put("type", mode.toConfigString());
                entry.put("world", worldName);
                result.add(entry);
            }
        } catch (Exception ignored) {
            // 内部容错，返回已有结果
        }
        return result;
    }

    /**
     * 返回指定 PKW 世界所有榜单的前 topN 名，按榜名分组。
     * 正计时(COUNTUP)世界返回三个键: standard / advance / expect；
     * 倒计时(COUNTDOWN)世界返回一个键: countdown。
     * 供 Lobby 钟菜单通过反射调用。
     *
     * @param mapId PKW 世界名
     * @param topN  每个榜单返回前几名
     * @return 按榜名分组的排行榜 Map，无记录或异常时返回空 Map
     */
    public Map<String, List<Map<String, String>>> getLeaderboards(String mapId, int topN) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        try {
            if (mapId == null || !pkwWorldManager.isPkwWorld(mapId)) {
                return result;
            }
            if (topN <= 0) topN = 3;
            TimingMode mode = pkwWorldManager.getTimingMode(mapId);
            if (mode == TimingMode.COUNTDOWN) {
                // 倒计时：仅 countdown 一个榜
                result.put("countdown", buildCountdownLeaderboard(mapId, topN));
            } else {
                // 正计时：standard / advance / expect 三个榜
                for (String tier : COUNTUP_TIERS) {
                    result.put(tier, buildCountupLeaderboard(mapId, tier, topN));
                }
            }
        } catch (Exception ignored) {
            // 内部容错，返回已有结果
        }
        return result;
    }

    /**
     * 返回指定玩家在指定 PKW 世界各榜单的名次信息，按榜名分组。
     * 正计时(COUNTUP)世界返回三个键: standard / advance / expect；
     * 倒计时(COUNTDOWN)世界返回一个键: countdown。
     * 某榜无记录则该键对应 null。
     * 供 Lobby 钟菜单通过反射调用。
     *
     * @param mapId    PKW 世界名
     * @param playerId 玩家 UUID
     * @return 按榜名分组的名次 Map，未找到或异常时返回空 Map
     */
    public Map<String, Map<String, String>> getPlayerRanks(String mapId, UUID playerId) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try {
            if (mapId == null || playerId == null || !pkwWorldManager.isPkwWorld(mapId)) {
                return result;
            }
            String uuidStr = playerId.toString();
            TimingMode mode = pkwWorldManager.getTimingMode(mapId);
            if (mode == TimingMode.COUNTDOWN) {
                result.put("countdown", findCountdownPlayerRank(mapId, uuidStr));
            } else {
                for (String tier : COUNTUP_TIERS) {
                    result.put(tier, findCountupPlayerRank(mapId, tier, uuidStr));
                }
            }
        } catch (Exception ignored) {
            // 内部容错，返回已有结果
        }
        return result;
    }

    // ---- helper constants & methods for leaderboard queries ----

    private static final String[] COUNTUP_TIERS = {"standard", "advance", "expect"};

    private List<Map<String, String>> buildCountupLeaderboard(String worldName, String tier, int topN) {
        List<Map<String, String>> list = new ArrayList<>();
        List<RecordsManager.RankEntry> entries = recordsManager.getTop(worldName, tier, topN);
        int rank = 1;
        for (RecordsManager.RankEntry e : entries) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("rank", String.valueOf(rank++));
            entry.put("player", e.playerName);
            entry.put("score", BoardRenderer.formatTime(e.timeMs));
            list.add(entry);
        }
        return list;
    }

    private List<Map<String, String>> buildCountdownLeaderboard(String worldName, int topN) {
        List<Map<String, String>> list = new ArrayList<>();
        List<RecordsManager.CountdownRankEntry> entries = recordsManager.getCountdownTop(worldName, topN);
        int rank = 1;
        for (RecordsManager.CountdownRankEntry e : entries) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("rank", String.valueOf(rank++));
            entry.put("player", e.playerName);
            entry.put("score", e.score + "分");
            list.add(entry);
        }
        return list;
    }

    private Map<String, String> findCountupPlayerRank(String worldName, String tier, String uuidStr) {
        List<RecordsManager.RankEntry> all = recordsManager.getTop(worldName, tier, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            RecordsManager.RankEntry e = all.get(i);
            if (e.playerId.toString().equals(uuidStr)) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("rank", String.valueOf(i + 1));
                entry.put("player", e.playerName);
                entry.put("score", BoardRenderer.formatTime(e.timeMs));
                return entry;
            }
        }
        return null;
    }

    private Map<String, String> findCountdownPlayerRank(String worldName, String uuidStr) {
        List<RecordsManager.CountdownRankEntry> all = recordsManager.getCountdownTop(worldName, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            RecordsManager.CountdownRankEntry e = all.get(i);
            if (e.playerId.toString().equals(uuidStr)) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("rank", String.valueOf(i + 1));
                entry.put("player", e.playerName);
                entry.put("score", e.score + "分");
                return entry;
            }
        }
        return null;
    }
}
