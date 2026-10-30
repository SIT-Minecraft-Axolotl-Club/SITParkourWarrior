package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.DynamicService;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SchematicService;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * PKWS stage-3 extraction and placement service.  All methods are called from
 * the Bukkit main thread; only the scheduler is used to spread WorldEdit
 * operations over multiple ticks.
 */
public final class PkwsGenerationService {
    private static final int SEGMENT_COUNT = 8;
    private static final int LEVELS_PER_SEGMENT = 3;
    private static final int PIECE_COUNT = 33;
    /** Uniform end-point -> next start-point spacing for all 32 joins. */
    private static final int GAP_BLOCKS = 8;

    private final SITParkourWarrior plugin;
    private final MapManager mapManager;
    private final PkwsRangeConfig rangeConfig;
    private final PkwsTrackStore trackStore;
    private final DynamicService dynamicService;
    private final SessionManager sessionManager;
    private final SchematicService schematicService = new SchematicService();
    private final Map<String, PendingPlan> pendingPlans = new HashMap<>();
    private final Map<String, GenerationJob> generationJobs = new HashMap<>();
    private final Set<String> clearWorlds = new HashSet<>();

    public PkwsGenerationService(SITParkourWarrior plugin, MapManager mapManager,
                                 PkwsRangeConfig rangeConfig, PkwsTrackStore trackStore) {
        this(plugin, mapManager, rangeConfig, trackStore, null, null);
    }

    public PkwsGenerationService(SITParkourWarrior plugin, MapManager mapManager,
                                 PkwsRangeConfig rangeConfig, PkwsTrackStore trackStore,
                                 DynamicService dynamicService) {
        this(plugin, mapManager, rangeConfig, trackStore, dynamicService, null);
    }

    public PkwsGenerationService(SITParkourWarrior plugin, MapManager mapManager,
                                 PkwsRangeConfig rangeConfig, PkwsTrackStore trackStore,
                                 DynamicService dynamicService, SessionManager sessionManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.rangeConfig = rangeConfig;
        this.trackStore = trackStore;
        this.dynamicService = dynamicService;
        this.sessionManager = sessionManager;
    }

    /** Starts a new extraction, or consumes the explicit occupancy confirmation. */
    public void generate(Player player, PkwsOrientation orientation, boolean confirm) {
        if (player == null || orientation == null) {
            return;
        }
        String worldName = player.getWorld().getName();
        if (generationJobs.containsKey(worldName) || clearWorlds.contains(worldName)) {
            Msg.send(player, "该世界正在执行 PKWS 操作，请等待当前操作完成。");
            return;
        }
        if (trackStore.containsRecord(worldName)) {
            Msg.send(player, "当前世界已经存在 PKWS 赛道，请先执行 /sitpkw pkws clear confirm。");
            return;
        }

        Plan plan;
        if (confirm) {
            PendingPlan pending = pendingPlans.get(worldName);
            if (pending == null || pending.plan.orientation != orientation) {
                Msg.send(player, "没有与该朝向匹配的待确认 PKWS 生成计划，请先执行不带 confirm 的 generate。"
                        + " 用法: /sitpkw pkws generate " + orientation.name());
                return;
            }
            plan = pending.plan;
            pendingPlans.remove(worldName);
        } else {
            pendingPlans.remove(worldName);
            plan = buildPlan(player, player.getWorld(), orientation);
            if (plan == null) {
                return;
            }
            OccupiedBlock occupied = findFirstOccupied(plan.world, plan.bounds);
            if (occupied != null) {
                pendingPlans.put(worldName, new PendingPlan(plan));
                Msg.send(player, "检测到目标范围内已有非空气方块，暂不生成。");
                Msg.send(player, "首个非空方块: " + occupied.type + " at "
                        + occupied.x + ", " + occupied.y + ", " + occupied.z);
                Msg.send(player, "整体范围: " + plan.bounds);
                Msg.send(player, "确认后执行: /sitpkw pkws generate " + orientation.name() + " confirm");
                return;
            }
        }

        startGeneration(player, plan, false);
    }

    /**
     * Refreshes a track as one transaction from the administrator's point of
     * view: extraction is completed first, then the old track is cleared, and
     * only then are the new pieces pasted one per tick.
     */
    public void refresh(CommandSender sender, World world,
                        PkwsOrientation requestedOrientation) {
        if (sender == null || world == null) {
            return;
        }
        String worldName = world.getName();
        if (generationJobs.containsKey(worldName) || clearWorlds.contains(worldName)) {
            Msg.send(sender, "该世界正在执行 PKWS 操作，请等待当前操作完成。");
            return;
        }

        PkwsTrack currentTrack = trackStore.load(worldName);
        PkwsOrientation currentOrientation = currentTrack == null
                ? null : currentTrack.getOrientation();
        PkwsOrientation orientation = requestedOrientation != null
                ? requestedOrientation
                : currentOrientation == null ? PkwsOrientation.NS : currentOrientation;

        Plan plan;
        PkwsTrack oldTrack;
        plan = buildPlan(sender, world, orientation);
        if (plan == null) {
            return;
        }
        broadcastWorld(world, "PKWS 赛道正在刷新，请暂时不要进入赛道。");
        oldTrack = currentTrack;

        if (oldTrack == null) {
            startGeneration(sender, plan, true);
            return;
        }
        unregisterTrackDeployments(oldTrack.getPieces());
        clearWorlds.add(worldName);
        scheduleRefreshClear(sender, worldName, oldTrack, plan, 0);
    }

    /** Starts the explicit, two-step clear operation for the current world. */
    public void clear(Player player, boolean confirm) {
        if (player == null) {
            return;
        }
        String worldName = player.getWorld().getName();
        if (generationJobs.containsKey(worldName) || clearWorlds.contains(worldName)) {
            Msg.send(player, "该世界正在执行 PKWS 操作，请等待当前操作完成。");
            return;
        }
        PkwsTrack track = trackStore.load(worldName);
        if (track == null) {
            Msg.send(player, "当前世界没有可清除的 PKWS 赛道记录。");
            return;
        }
        if (!confirm) {
            Msg.send(player, "即将按赛道记录清除当前世界的 33 个区域。");
            Msg.send(player, "赛道范围: " + boundsOf(track.getPieces()));
            Msg.send(player, "确认后执行: /sitpkw pkws clear confirm");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Msg.send(player, "当前世界未加载，无法清除 PKWS 赛道。");
            return;
        }
        // Generated pieces are ordinary MapManager deployments at runtime.
        // Stop/remove them before clearing blocks so dynamic tasks cannot
        // paste another level state into a region being deleted.
        unregisterTrackDeployments(track.getPieces());
        clearWorlds.add(worldName);
        scheduleClear(player, worldName, track, 0);
    }

    /** Prints the persisted track without changing it. */
    public void trackInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Msg.send(sender, "trackinfo 需要由玩家在目标世界执行。");
            return;
        }
        PkwsTrack track = trackStore.load(player.getWorld().getName());
        if (track == null) {
            Msg.send(sender, "当前世界没有 PKWS 赛道记录。");
            return;
        }
        Msg.send(sender, "PKWS 赛道: 世界=" + track.getWorldName()
                + "，朝向=" + track.getOrientation().name()
                + "，生成时间=" + track.getGeneratedAt());
        for (int index = 0; index < track.getPieces().size(); index++) {
            PkwsTrack.Piece piece = track.getPieces().get(index);
            String segment = piece.getType() == PkwsTrack.PieceType.LEVEL
                    ? "第" + piece.getSegment() + "段第" + piece.getPosition() + "关"
                    : piece.getType().name();
            String details = segment + " -> " + piece.getSourceId() + " (" + piece.getSourceTitle() + ")";
            if (piece.getType() == PkwsTrack.PieceType.LEVEL) {
                details += " 难度=" + (piece.getDifficulty() == null ? "未设置" : piece.getDifficulty())
                        + " 权重=" + piece.getWeight();
            }
            if (piece.getType() == PkwsTrack.PieceType.RELAY) {
                details += " 闸门=" + (piece.getGateState() == null ? "未知" : piece.getGateState());
            }
            if (piece.getType() == PkwsTrack.PieceType.START) {
                details += " 起点状态=" + (piece.getGateState() == null ? "未知" : piece.getGateState());
            }
            details += " 区域=" + formatRegion(piece.getRegion())
                    + " 起点=" + formatPoint(piece.getStart())
                    + " 终点=" + formatPoint(piece.getEnd());
            Msg.send(sender, String.format("  %02d/33 %s", index + 1, details));
        }
    }

    /** Drops transient plans/jobs during plugin shutdown. */
    public void shutdown() {
        pendingPlans.clear();
        generationJobs.clear();
        clearWorlds.clear();
    }

    private Plan buildPlan(CommandSender sender, World world,
                           PkwsOrientation orientation) {
        String worldName = world.getName();
        List<ParkourMap> levelPool = new ArrayList<>();
        List<ParkourMap> relayPool = new ArrayList<>();
        List<ParkourMap> startPool = new ArrayList<>();
        List<ParkourMap> endPool = new ArrayList<>();

        for (ParkourMap map : mapManager.getMaps().values()) {
            if (map.getPkwsOrientation() != orientation) {
                continue;
            }
            if (map.getNodeType() == NodeType.LEVEL
                    && map.getPkwsDifficulty() != null
                    && map.getPkwsWeight() > 0
                    && isPlaceable(map, 0)) {
                levelPool.add(map);
            }
            if (map.getNodeType() == NodeType.PKWS_RELAY
                    && isPlaceable(map, 0) && isPlaceable(map, 1)) {
                relayPool.add(map);
            }
            if (map.getNodeType() == NodeType.PKWS_START
                    && isPlaceable(map, 0) && isPlaceable(map, 1)) {
                startPool.add(map);
            }
            if (map.getNodeType() == NodeType.PKWS_END && isPlaceable(map, 0)) {
                endPool.add(map);
            }
        }

        if (relayPool.isEmpty()) {
            Msg.send(sender, "生成中止：没有朝向 " + orientation.name()
                    + " 的可用 PKWS_RELAY 中继节点（需要区域、起点、终点以及状态0关闭、状态1开放两个 schematic）。");
            return null;
        }
        if (startPool.isEmpty()) {
            Msg.send(sender, "生成中止：没有朝向 " + orientation.name()
                    + " 的可用 PKWS_START 全局起点（需要区域、起点、终点以及状态0关闭、状态1开放两个 schematic）。");
            return null;
        }
        if (endPool.isEmpty()) {
            Msg.send(sender, "生成中止：没有朝向 " + orientation.name()
                    + " 的可用 PKWS_END 全局终点（需要区域、起点、终点和状态0 schematic）。");
            return null;
        }

        List<PreparedPiece> pieces = new ArrayList<>(PIECE_COUNT);
        Set<String> usedLevelTitles = new HashSet<>();
        ParkourMap start = randomItem(startPool);
        int targetX = world.getSpawnLocation().getBlockX();
        int targetY = world.getSpawnLocation().getBlockY();
        int targetZ = world.getSpawnLocation().getBlockZ();

        PreparedPiece startPiece = preparePiece(start, PkwsTrack.PieceType.START,
                0, 0, worldName, targetX, targetY, targetZ, 0);
        if (startPiece == null) {
            Msg.send(sender, "生成中止：全局起点素材不完整。");
            return null;
        }
        pieces.add(startPiece);
        targetX = startPiece.nextX(orientation);
        targetY = startPiece.nextY();
        targetZ = startPiece.nextZ(orientation);

        for (int segment = 1; segment <= SEGMENT_COUNT; segment++) {
            for (int position = 1; position <= LEVELS_PER_SEGMENT; position++) {
                PkwsRangeConfig.DifficultyRange range = rangeConfig.getRange(orientation, segment, position);
                List<ParkourMap> candidates = new ArrayList<>();
                for (ParkourMap map : levelPool) {
                    String title = normalizedTitle(map);
                    if (!usedLevelTitles.contains(title)
                            && map.getPkwsDifficulty() >= range.min()
                            && map.getPkwsDifficulty() <= range.max()) {
                        candidates.add(map);
                    }
                }
                if (candidates.isEmpty()) {
                    Msg.send(sender, "生成中止：第" + segment + "段第" + position + "关抽取失败，难度范围 "
                            + range + "，当前该区间内可用关卡数为 0。可能原因：权重为0、难度未设置、"
                            + "朝向不匹配、关卡素材未准备或本次已经抽中过。");
                    return null;
                }
                ParkourMap level = weightedRandom(candidates);
                usedLevelTitles.add(normalizedTitle(level));
                PreparedPiece prepared = preparePiece(level, PkwsTrack.PieceType.LEVEL,
                        segment, position, worldName, targetX, targetY, targetZ, 0);
                if (prepared == null) {
                    Msg.send(sender, "生成中止：第" + segment + "段第" + position
                            + "关的区域、起点、终点或状态0 schematic 不完整。");
                    return null;
                }
                pieces.add(prepared);
                targetX = prepared.nextX(orientation);
                targetY = prepared.nextY();
                targetZ = prepared.nextZ(orientation);
            }

            if (segment <= 7) {
                ParkourMap relay = randomItem(relayPool);
                PreparedPiece prepared = preparePiece(relay, PkwsTrack.PieceType.RELAY,
                        segment, 4, worldName, targetX, targetY, targetZ, 0);
                if (prepared == null) {
                    Msg.send(sender, "生成中止：第" + segment + "段后的中继节点素材不完整。");
                    return null;
                }
                pieces.add(prepared);
                targetX = prepared.nextX(orientation);
                targetY = prepared.nextY();
                targetZ = prepared.nextZ(orientation);
            }
        }

        ParkourMap end = randomItem(endPool);
        PreparedPiece endPiece = preparePiece(end, PkwsTrack.PieceType.END,
                8, 4, worldName, targetX, targetY, targetZ, 0);
        if (endPiece == null) {
            Msg.send(sender, "生成中止：全局终点素材不完整。");
            return null;
        }
        pieces.add(endPiece);

        if (pieces.size() != PIECE_COUNT) {
            Msg.send(sender, "生成中止：内部赛道块数量异常，预期 33 块，实际 " + pieces.size() + " 块。");
            return null;
        }
        Bounds bounds = boundsOfPrepared(pieces);
        if (bounds.minY < world.getMinHeight() || bounds.maxY > world.getMaxHeight() - 1) {
            Msg.send(sender, "生成中止：赛道 Y 范围越界，计划范围为 " + bounds.minY + "~" + bounds.maxY
                    + "，世界允许范围为 " + world.getMinHeight() + "~" + (world.getMaxHeight() - 1) + "。");
            return null;
        }
        return new Plan(world, orientation, pieces, bounds);
    }

    private String normalizedTitle(ParkourMap map) {
        String title = map == null ? null : map.getTitle();
        return title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private PreparedPiece preparePiece(ParkourMap map, PkwsTrack.PieceType type,
                                       int segment, int position, String worldName,
                                       int targetX, int targetY, int targetZ,
                                       int stateIndex) {
        if (!isPlaceable(map, stateIndex)) {
            return null;
        }
        Region template = map.getRegion();
        PointLocation sourceStart = map.getStart();
        PointLocation sourceEnd = map.getEnd();
        int offsetX = targetX - sourceStart.getBlockX();
        int offsetY = targetY - sourceStart.getBlockY();
        int offsetZ = targetZ - sourceStart.getBlockZ();
        Region region = new Region(worldName,
                template.getMinX() + offsetX, template.getMinY() + offsetY, template.getMinZ() + offsetZ,
                template.getMaxX() + offsetX, template.getMaxY() + offsetY, template.getMaxZ() + offsetZ);
        PointLocation start = translate(sourceStart, worldName, offsetX, offsetY, offsetZ);
        PointLocation end = translate(sourceEnd, worldName, offsetX, offsetY, offsetZ);
        DynamicState state = map.getDynamicData().getStates().get(stateIndex);
        File schematic = new File(mapManager.getMapFolder(map), state.getFile());
        if (!schematic.isFile()) {
            return null;
        }
        String gateState = type == PkwsTrack.PieceType.RELAY
                || type == PkwsTrack.PieceType.START ? "CLOSED" : null;
        String deploymentId = "pkws-" + UUID.randomUUID();
        PkwsTrack.Piece record = new PkwsTrack.Piece(type, segment, position, map.getId(), map.getTitle(),
                map.getPkwsDifficulty(), map.getPkwsWeight(), region, start, end, gateState,
                deploymentId);
        return new PreparedPiece(record, schematic);
    }

    private boolean isPlaceable(ParkourMap map, int stateIndex) {
        if (map == null || map.getRegion() == null || map.getStart() == null || map.getEnd() == null
                || map.getDynamicData() == null || map.getDynamicData().getStates().size() <= stateIndex) {
            return false;
        }
        DynamicState state = map.getDynamicData().getStates().get(stateIndex);
        if (state.getFile() == null || state.getFile().isBlank()) {
            return false;
        }
        return new File(mapManager.getMapFolder(map), state.getFile()).isFile();
    }

    private void startGeneration(CommandSender sender, Plan plan, boolean quietProgress) {
        String worldName = plan.world.getName();
        GenerationJob job = new GenerationJob(sender, plan, quietProgress);
        generationJobs.put(worldName, job);
        Msg.send(sender, "PKWS 抽取完成，开始逐块生成，朝向 " + plan.orientation.name() + "。"
                + " 起点锚定在世界出生点，间隔规则为终点到下一起点 8 格。");
        scheduleGenerationStep(job);
    }

    private void broadcastWorld(World world, String message) {
        if (world == null || message == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            Msg.send(player, message);
        }
    }

    private void scheduleGenerationStep(GenerationJob job) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (generationJobs.get(job.plan.world.getName()) != job) {
                return;
            }
            if (job.index >= job.plan.pieces.size()) {
                finishGeneration(job);
                return;
            }
            PreparedPiece piece = job.plan.pieces.get(job.index);
            if (!ensureChunks(job.plan.world, piece.record.getRegion())) {
                failGeneration(job, "第" + (job.index + 1) + "/33 块加载目标区块失败。");
                return;
            }
            if (!schematicService.pasteSchematic(piece.schematic,
                    new Location(job.plan.world, piece.record.getRegion().getMinX(),
                            piece.record.getRegion().getMinY(), piece.record.getRegion().getMinZ()))) {
                failGeneration(job, "第" + (job.index + 1) + "/33 块粘贴失败：" + piece.record.getSourceId());
                return;
            }
            if (!registerDeployment(piece.record)) {
                failGeneration(job, "第" + (job.index + 1) + "/33 块运行时部署登记失败："
                        + piece.record.getSourceId());
                return;
            }
            job.index++;
            if (!job.quietProgress) {
                Msg.send(job.sender, "PKWS 生成进度：第 " + job.index + "/33 块（"
                        + piece.record.getType().name() + " / " + piece.record.getSourceId() + "）。");
            }
            if (job.index >= job.plan.pieces.size()) {
                finishGeneration(job);
            } else {
                scheduleGenerationStep(job);
            }
        }, 1L);
    }

    private void finishGeneration(GenerationJob job) {
        List<PkwsTrack.Piece> records = new ArrayList<>();
        for (PreparedPiece piece : job.plan.pieces) {
            records.add(piece.record);
        }
        PkwsTrack track = new PkwsTrack(job.plan.world.getName(), job.plan.orientation,
                Instant.now().toString(), records);
        generationJobs.remove(job.plan.world.getName());
        persistGeneratedDeployments(job.plan.pieces);
        if (!trackStore.save(track)) {
            unregisterTrackDeployments(records);
            Msg.send(job.sender, "PKWS 方块已生成，但赛道记录保存失败，请立即备份并检查 pkws-track.yml。");
            return;
        }
        if (job.quietProgress) {
            broadcastWorld(job.plan.world, "PKWS 赛道刷新完成，现在可以进入赛道了。");
        }
        Msg.send(job.sender, "PKWS 赛道生成完成，已保存 33 块记录。可用 /sitpkw pkws trackinfo 查看。");
    }

    private void failGeneration(GenerationJob job, String message) {
        generationJobs.remove(job.plan.world.getName());
        unregisterTrackDeployments(job.plan.pieces.stream()
                .map(piece -> piece.record)
                .toList());
        Msg.send(job.sender, "PKWS 生成中止：" + message
                + " 已生成的方块不会自动清除，请检查现场后按记录处理。");
    }

    /**
     * Register a generated piece using the same MapManager deployment model as
     * /sitpkw deploy.  The owning ParkourMap supplies the source id and node
     * type; the deployment carries the translated runtime region and points.
     */
    private boolean registerDeployment(PkwsTrack.Piece piece) {
        if (piece == null || piece.getDeploymentId() == null
                || piece.getDeploymentId().isBlank()) {
            return false;
        }
        ParkourMap sourceMap = mapManager.getMap(piece.getSourceId());
        if (sourceMap == null || piece.getRegion() == null
                || piece.getStart() == null || piece.getEnd() == null) {
            return false;
        }
        Deployment existing = sourceMap.getDeployment(piece.getDeploymentId());
        if (existing == null) {
            // preparePiece anchors the generated copy at the source map's
            // start point, exactly like the normal deploy command.  Use the
            // same anchor for custom start/end AABBs; region-min offsets are
            // wrong when a template point is not its region's minimum corner.
            PointLocation sourceStart = sourceMap.getStart();
            int offsetX = sourceStart == null ? 0
                    : piece.getStart().getBlockX() - sourceStart.getBlockX();
            int offsetY = sourceStart == null ? 0
                    : piece.getStart().getBlockY() - sourceStart.getBlockY();
            int offsetZ = sourceStart == null ? 0
                    : piece.getStart().getBlockZ() - sourceStart.getBlockZ();
            String worldName = piece.getRegion().getWorldName();
            PointLocation startPos1 = translatePointOrNull(sourceMap.getStartPos1(),
                    worldName, offsetX, offsetY, offsetZ);
            PointLocation startPos2 = translatePointOrNull(sourceMap.getStartPos2(),
                    worldName, offsetX, offsetY, offsetZ);
            PointLocation endPos1 = translatePointOrNull(sourceMap.getEndPos1(),
                    worldName, offsetX, offsetY, offsetZ);
            PointLocation endPos2 = translatePointOrNull(sourceMap.getEndPos2(),
                    worldName, offsetX, offsetY, offsetZ);
            existing = new Deployment(piece.getDeploymentId(), piece.getRegion(),
                    piece.getStart(), piece.getEnd(), List.of(),
                    startPos1, startPos2, endPos1, endPos2);
            sourceMap.addDeployment(existing);
        }
        mapManager.rebuildWorldIndex();
        if (dynamicService != null) {
            // DynamicService explicitly rejects PKWS_START/PKWS_RELAY/PKWS_END;
            // ordinary PKWS LEVEL pieces are intentionally allowed through.
            dynamicService.startForDeployment(sourceMap, existing);
        }
        return true;
    }

    /** Persist generated deployments and retry dynamic startup after saveMap
     * auto-enables maps that contain at least two dynamic states. */
    private void persistGeneratedDeployments(List<PreparedPiece> pieces) {
        Set<String> savedMapIds = new HashSet<>();
        for (PreparedPiece prepared : pieces) {
            ParkourMap map = mapManager.getMap(prepared.record.getSourceId());
            if (map == null) {
                continue;
            }
            String key = map.getId().toLowerCase(java.util.Locale.ROOT);
            if (savedMapIds.add(key) && !mapManager.saveMap(map)) {
                plugin.getLogger().warning("[PKWS] 无法保存生成块部署登记：" + map.getId());
            }
        }
        if (dynamicService != null) {
            for (PreparedPiece prepared : pieces) {
                ParkourMap map = mapManager.getMap(prepared.record.getSourceId());
                if (map == null) {
                    continue;
                }
                Deployment deployment = map.getDeployment(prepared.record.getDeploymentId());
                if (deployment != null) {
                    dynamicService.startForDeployment(map, deployment);
                }
            }
        }
    }

    /** Remove only generated deployments belonging to this track. */
    private void unregisterTrackDeployments(List<PkwsTrack.Piece> pieces) {
        Set<String> changedMapIds = new HashSet<>();
        for (PkwsTrack.Piece piece : pieces) {
            if (piece == null) {
                continue;
            }

            DeploymentOwner owner = findTrackDeployment(piece);
            if (owner == null) {
                continue;
            }

            ParkourMap map = owner.map;
            Deployment deployment = owner.deployment;
            String deploymentId = deployment.getId();
            if (sessionManager != null && deploymentId != null) {
                sessionManager.endSessionsForDeployment(map.getId(), deploymentId);
            }
            if (dynamicService != null) {
                dynamicService.stopForDeployment(map, deployment);
            }
            if (map.removeDeployment(deploymentId)) {
                changedMapIds.add(map.getId().toLowerCase(java.util.Locale.ROOT));
            }
        }
        mapManager.rebuildWorldIndex();
        for (String mapId : changedMapIds) {
            ParkourMap map = mapManager.getMap(mapId);
            if (map != null && !mapManager.saveMap(map)) {
                plugin.getLogger().warning("[PKWS] 无法保存清除后的部署登记：" + map.getId());
            }
        }
    }

    private DeploymentOwner findTrackDeployment(PkwsTrack.Piece piece) {
        String deploymentId = piece.getDeploymentId();
        if (deploymentId != null && !deploymentId.isBlank()) {
            ParkourMap map = mapManager.getMap(piece.getSourceId());
            if (map == null) {
                return null;
            }
            Deployment deployment = map.getDeployment(deploymentId);
            return deployment == null ? null : new DeploymentOwner(map, deployment);
        }

        // Older track records have no deployment id. Generated deployments
        // use the exact region stored in the track, so use it to find the
        // matching runtime registration while leaving other deployments alone.
        Region recordedRegion = piece.getRegion();
        if (recordedRegion == null) {
            return null;
        }
        ParkourMap sourceMap = mapManager.getMap(piece.getSourceId());
        DeploymentOwner found = findDeploymentInMap(sourceMap, recordedRegion);
        if (found != null) {
            return found;
        }
        for (ParkourMap map : mapManager.getMaps().values()) {
            if (map == sourceMap) {
                continue;
            }
            found = findDeploymentInMap(map, recordedRegion);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private DeploymentOwner findDeploymentInMap(ParkourMap map, Region recordedRegion) {
        if (map == null) {
            return null;
        }
        for (Deployment deployment : map.getDeployments()) {
            if (sameRegion(recordedRegion, deployment.getRegion())) {
                return new DeploymentOwner(map, deployment);
            }
        }
        return null;
    }

    private boolean sameRegion(Region first, Region second) {
        return first != null && second != null
                && java.util.Objects.equals(first.getWorldName(), second.getWorldName())
                && first.getMinX() == second.getMinX()
                && first.getMinY() == second.getMinY()
                && first.getMinZ() == second.getMinZ()
                && first.getMaxX() == second.getMaxX()
                && first.getMaxY() == second.getMaxY()
                && first.getMaxZ() == second.getMaxZ();
    }

    private static final class DeploymentOwner {
        private final ParkourMap map;
        private final Deployment deployment;

        private DeploymentOwner(ParkourMap map, Deployment deployment) {
            this.map = map;
            this.deployment = deployment;
        }
    }

    private void scheduleClear(Player player, String worldName, PkwsTrack track, int index) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!clearWorlds.contains(worldName)) {
                return;
            }
            if (index >= track.getPieces().size()) {
                clearWorlds.remove(worldName);
                if (trackStore.remove(worldName)) {
                    Msg.send(player, "PKWS 赛道清除完成，赛道记录已删除。");
                } else {
                    Msg.send(player, "PKWS 方块已清除，但赛道记录删除失败，请检查 pkws-track.yml。");
                }
                return;
            }
            PkwsTrack.Piece piece = track.getPieces().get(index);
            World world = Bukkit.getWorld(worldName);
            if (world == null || !ensureChunks(world, piece.getRegion())
                    || !schematicService.clearRegion(piece.getRegion())) {
                clearWorlds.remove(worldName);
                Msg.send(player, "PKWS 清除中止：第" + (index + 1) + "/33 块清除失败，记录保留。");
                return;
            }
            Msg.send(player, "PKWS 清除进度：第 " + (index + 1) + "/33 块。");
            scheduleClear(player, worldName, track, index + 1);
        }, 1L);
    }

    private void scheduleRefreshClear(CommandSender sender, String worldName, PkwsTrack oldTrack,
                                       Plan plan, int index) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!clearWorlds.contains(worldName)) {
                return;
            }
            if (index >= oldTrack.getPieces().size()) {
                trackStore.remove(worldName);
                Msg.send(sender, "旧 PKWS 赛道清除完成，下一 tick 开始逐块放置新赛道（朝向 "
                        + plan.orientation.name() + "）。");
                // Keep the refresh lock for one extra tick so the old-track
                // clear has fully completed before the new paste job starts.
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!clearWorlds.remove(worldName)) {
                        return;
                    }
                    startGeneration(sender, plan, true);
                }, 1L);
                return;
            }
            PkwsTrack.Piece piece = oldTrack.getPieces().get(index);
            World world = Bukkit.getWorld(worldName);
            if (world == null || !ensureChunks(world, piece.getRegion())
                    || !schematicService.clearRegion(piece.getRegion())) {
                clearWorlds.remove(worldName);
                Msg.send(sender, "刷新中止：第" + (index + 1)
                        + "/33 块旧赛道清除失败；旧赛道记录保留，未放置新赛道。");
                return;
            }
            scheduleRefreshClear(sender, worldName, oldTrack, plan, index + 1);
        }, 1L);
    }

    private OccupiedBlock findFirstOccupied(World world, Bounds bounds) {
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (!isAir(material)) {
                        return new OccupiedBlock(x, y, z, material.name());
                    }
                }
            }
        }
        return null;
    }

    private boolean ensureChunks(World world, Region region) {
        int minChunkX = region.getMinX() >> 4;
        int maxChunkX = region.getMaxX() >> 4;
        int minChunkZ = region.getMinZ() >> 4;
        int maxChunkZ = region.getMaxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.getChunkAt(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private ParkourMap weightedRandom(List<ParkourMap> candidates) {
        long total = 0L;
        for (ParkourMap map : candidates) {
            total += map.getPkwsWeight();
        }
        long target = ThreadLocalRandom.current().nextLong(total);
        for (ParkourMap map : candidates) {
            target -= map.getPkwsWeight();
            if (target < 0) {
                return map;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private <T> T randomItem(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private PointLocation translate(PointLocation point, String worldName,
                                    int offsetX, int offsetY, int offsetZ) {
        return new PointLocation(worldName, point.getX() + offsetX, point.getY() + offsetY,
                point.getZ() + offsetZ, point.getYaw(), point.getPitch());
    }

    private PointLocation translatePointOrNull(PointLocation point, String worldName,
                                               int offsetX, int offsetY, int offsetZ) {
        return point == null ? null : translate(point, worldName, offsetX, offsetY, offsetZ);
    }

    private Bounds boundsOfPrepared(List<PreparedPiece> pieces) {
        Bounds bounds = null;
        for (PreparedPiece piece : pieces) {
            bounds = bounds == null ? new Bounds(piece.record.getRegion()) : bounds.include(piece.record.getRegion());
        }
        return bounds;
    }

    private Bounds boundsOf(List<PkwsTrack.Piece> pieces) {
        Bounds bounds = null;
        for (PkwsTrack.Piece piece : pieces) {
            if (piece != null && piece.getRegion() != null) {
                bounds = bounds == null ? new Bounds(piece.getRegion()) : bounds.include(piece.getRegion());
            }
        }
        return bounds == null ? new Bounds(0, 0, 0, 0, 0, 0) : bounds;
    }

    private String formatRegion(Region region) {
        if (region == null) {
            return "未设置";
        }
        return region.getWorldName() + " [" + region.getMinX() + "," + region.getMinY() + ","
                + region.getMinZ() + " -> " + region.getMaxX() + "," + region.getMaxY() + ","
                + region.getMaxZ() + "]";
    }

    private String formatPoint(PointLocation point) {
        if (point == null) {
            return "未设置";
        }
        return String.format(java.util.Locale.ROOT, "(%1$.2f,%2$.2f,%3$.2f)",
                point.getX(), point.getY(), point.getZ());
    }

    private static final class Plan {
        private final World world;
        private final PkwsOrientation orientation;
        private final List<PreparedPiece> pieces;
        private final Bounds bounds;

        private Plan(World world, PkwsOrientation orientation, List<PreparedPiece> pieces, Bounds bounds) {
            this.world = world;
            this.orientation = orientation;
            this.pieces = pieces;
            this.bounds = bounds;
        }
    }

    private static final class PendingPlan {
        private final Plan plan;

        private PendingPlan(Plan plan) {
            this.plan = plan;
        }
    }

    private static final class GenerationJob {
        private final CommandSender sender;
        private final Plan plan;
        private final boolean quietProgress;
        private int index;

        private GenerationJob(CommandSender sender, Plan plan, boolean quietProgress) {
            this.sender = sender;
            this.plan = plan;
            this.quietProgress = quietProgress;
        }
    }

    private static final class PreparedPiece {
        private final PkwsTrack.Piece record;
        private final File schematic;

        private PreparedPiece(PkwsTrack.Piece record, File schematic) {
            this.record = record;
            this.schematic = schematic;
        }

        private int nextX(PkwsOrientation orientation) {
            return record.getEnd().getBlockX() + (orientation == PkwsOrientation.EW ? -GAP_BLOCKS : 0);
        }

        private int nextY() {
            return record.getEnd().getBlockY();
        }

        private int nextZ(PkwsOrientation orientation) {
            return record.getEnd().getBlockZ() + (orientation == PkwsOrientation.NS ? GAP_BLOCKS : 0);
        }
    }

    private static final class OccupiedBlock {
        private final int x;
        private final int y;
        private final int z;
        private final String type;

        private OccupiedBlock(int x, int y, int z, String type) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
        }
    }

    private static final class Bounds {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;

        private Bounds(Region region) {
            this(region.getMinX(), region.getMinY(), region.getMinZ(),
                    region.getMaxX(), region.getMaxY(), region.getMaxZ());
        }

        private Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        private Bounds include(Region region) {
            return new Bounds(Math.min(minX, region.getMinX()), Math.min(minY, region.getMinY()),
                    Math.min(minZ, region.getMinZ()), Math.max(maxX, region.getMaxX()),
                    Math.max(maxY, region.getMaxY()), Math.max(maxZ, region.getMaxZ()));
        }

        @Override
        public String toString() {
            return "[" + minX + "," + minY + "," + minZ + " -> "
                    + maxX + "," + maxY + "," + maxZ + "]";
        }
    }
}
