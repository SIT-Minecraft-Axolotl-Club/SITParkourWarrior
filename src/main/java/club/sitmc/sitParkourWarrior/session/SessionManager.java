package club.sitmc.sitParkourWarrior.session;

import club.sitmc.sitParkourWarrior.SITParkourWarrior;
import club.sitmc.sitParkourWarrior.config.CountdownScoringConfig;
import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.config.TimingMode;
import club.sitmc.sitParkourWarrior.course.CourseLayoutAnalyzer;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.DynamicService;
import club.sitmc.sitParkourWarrior.map.EndTier;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.records.RecordsManager;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import club.sitmc.sitParkourWarrior.util.Msg;
import org.bukkit.Bukkit;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SessionManager {
    private final SITParkourWarrior plugin;
    private final MapManager mapManager;
    private final DynamicService dynamicService;
    private final RecordsManager recordsManager;
    private final PkwWorldManager pkwWorldManager;
    private final CourseLayoutAnalyzer courseLayoutAnalyzer;
    private final CountdownScoringConfig countdownScoring;
    private VisibilityManager visibilityManager;
    private final Map<UUID, ParkourSession> sessions = new HashMap<>();
    private final Map<UUID, RunProgress> runProgresses = new HashMap<>();
    /** Tracks which deployment a player is currently inside (for auto-start dedup). */
    private final Map<UUID, String> insideDeployments = new HashMap<>();
    /** Tracks player UUIDs currently undergoing an internal (plugin-initiated) teleport.
     * Used by PlayerTeleportListener to distinguish internal course mechanics from
     * external teleports that should trigger a leave-save. */
    private final Set<UUID> internalTeleports = new HashSet<>();

    public SessionManager(SITParkourWarrior plugin, MapManager mapManager, DynamicService dynamicService,
                          RecordsManager recordsManager, PkwWorldManager pkwWorldManager,
                          CourseLayoutAnalyzer courseLayoutAnalyzer, CountdownScoringConfig countdownScoring,
                          VisibilityManager visibilityManager) {
        this.plugin = plugin;
        this.mapManager = mapManager;
        this.dynamicService = dynamicService;
        this.recordsManager = recordsManager;
        this.pkwWorldManager = pkwWorldManager;
        this.courseLayoutAnalyzer = courseLayoutAnalyzer;
        this.countdownScoring = countdownScoring;
        this.visibilityManager = visibilityManager;
    }

    /** Set after construction to resolve circular dependency with VisibilityManager. */
    public void setVisibilityManager(VisibilityManager vm) {
        this.visibilityManager = vm;
    }

    public ParkourSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean isPlaying(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public boolean startSession(Player player, ParkourMap map, Deployment deployment) {
        if (player == null || map == null || deployment == null) {
            return false;
        }
        // PKWS nodes are definitions for a later PKWS runner, not playable
        // PKW course nodes.  Keep them out of sessions/records entirely.
        if (map.getNodeType().isPkwsType()) {
            return false;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            Msg.send(player, "你已经在游玩中，先退出当前关卡。");
            return false;
        }

        Region region = deployment.getRegion();
        if (region == null) {
            Msg.send(player, "关卡配置不完整（需要区域）。");
            return false;
        }

        NodeType nodeType = map.getNodeType();
        Location checkpoint = null;

        switch (nodeType) {
            case LEVEL: {
                Location start = resolveLocationWorld(deployment.getStart(), region);
                Location end = resolveLocationWorld(deployment.getEnd(), region);
                if (start == null || end == null) {
                    Msg.send(player, "关卡配置不完整（需要起点、终点）。");
                    return false;
                }
                checkpoint = start.clone();
                break;
            }
            case GLOBAL_START: {
                Location gsStart = resolveLocationWorld(deployment.getStart(), region);
                if (gsStart == null) {
                    Msg.send(player, "关卡配置不完整（需要起点）。");
                    return false;
                }
                checkpoint = gsStart.clone();
                break;
            }
            case FORK: {
                Location best = null;
                double bestDist = Double.MAX_VALUE;
                for (PointLocation fp : deployment.getForkBranchPoints()) {
                    Location loc = resolveLocationWorld(fp, region);
                    if (loc == null) continue;
                    double d = player.getLocation().distance(loc);
                    if (d < bestDist) { bestDist = d; best = loc; }
                }
                if (best == null) {
                    Msg.send(player, "关卡配置不完整（需要岔路点位）。");
                    return false;
                }
                checkpoint = best.clone();
                break;
            }
            default:
                Msg.send(player, "此节点类型无法直接开始。");
                return false;
        }

        ParkourSession session = new ParkourSession(player.getUniqueId(), map.getId(), deployment.getId(), System.currentTimeMillis());
        session.setDeathLineY(region.getMinY());
        session.setCheckpoint(checkpoint);
        sessions.put(player.getUniqueId(), session);
        dynamicService.onPlayerJoin(map, deployment);

        if (nodeType == NodeType.LEVEL && region.contains(player.getLocation())) {
            session.startTimer(System.currentTimeMillis());
            session.setInsideRegion(true);
            session.setInsideStart(false);
            session.setPendingTitleAtStart(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ParkourSession current = sessions.get(player.getUniqueId());
            if (current == null) {
                return;
            }
            if (nodeType == NodeType.LEVEL && region.contains(player.getLocation())) {
                current.setInsideRegion(true);
                current.setInsideStart(false);
                current.setPendingTitleAtStart(true);
            } else if (nodeType == NodeType.GLOBAL_START && deployment.isInStartZone(player.getLocation())) {
                current.setInsideStart(false);
                current.setPendingTitleAtStart(true);
                sendLevelTitle(player, map);
            }
        });
        return true;
    }

    public void endSession(Player player, boolean completed) {
        if (player == null) {
            return;
        }
        if (completed) {
            // Completion: keep session alive, transition to waiting-for-handoff state.
            ParkourSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            if (session.isStarted()) {
                session.stopTimer(System.currentTimeMillis());
            }
            // Medal for LEVEL completion (COUNTUP or COUNTDOWN)
            ParkourMap map = mapManager.getMap(session.getMapId());
            if (map != null && map.getNodeType() == NodeType.LEVEL) {
                TimingMode mode = pkwWorldManager.getTimingMode(player.getWorld());
                if (mode == TimingMode.COUNTUP) {
                    claimLevelMedal(player, session.getMapId(), session.getDeploymentId());
                } else if (mode == TimingMode.COUNTDOWN) {
                    claimCountdownMedal(player, session.getMapId(), session.getDeploymentId());
                }
            }
            long durationMs = session.getElapsedMs(System.currentTimeMillis());
            // Shared PKW/PKWS presentation: one completion message containing
            // the measured duration, followed by the standard effects.
            sendLevelCompletion(player, map, durationMs);
            session.setCompleted(true);
            session.setState(SessionState.AWAITING_HANDOFF);
            // Keep deathLineY unchanged (stay at completed node's region minY).
            // Set checkpoint to this level's end.
            Deployment deployment = getDeployment(map, session.getDeploymentId());
            if (deployment != null) {
                Location end = resolveLocationWorld(deployment.getEnd(), deployment.getRegion());
                if (end != null) {
                    session.setCheckpoint(end.clone());
                }
            }
            cleanupPlayerEquipment(player);
            return;
        }

        // Non-completion: fully remove session.
        ParkourSession session = sessions.remove(player.getUniqueId());
        insideDeployments.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        ParkourMap map = mapManager.getMap(session.getMapId());
        Deployment deployment = getDeployment(map, session.getDeploymentId());
        if (map != null && deployment != null) {
            dynamicService.onPlayerLeave(map, deployment);
        }
        Msg.send(player, "已结束关卡。");
        session.resetTimer();
    }

    // ---- Full-course timing (GLOBAL_START → GLOBAL_END) ----

    public void startFullCourse(UUID playerId) {
        if (runProgresses.containsKey(playerId)) return;
        runProgresses.put(playerId, new RunProgress());
    }

    public RunProgress getRunProgress(UUID playerId) {
        return runProgresses.get(playerId);
    }

    public void removeRunProgress(UUID playerId) {
        runProgresses.remove(playerId);
    }

    public void restoreRunProgress(UUID playerId, RunProgress rp) {
        runProgresses.put(playerId, rp);
    }

    // ---- Inside-deployment tracking (for auto-start dedup) ----

    public String getInsideDeployment(UUID playerId) {
        return insideDeployments.get(playerId);
    }

    public void setInsideDeployment(UUID playerId, String deploymentId) {
        insideDeployments.put(playerId, deploymentId);
    }

    public void removeInsideDeployment(UUID playerId) {
        insideDeployments.remove(playerId);
    }

    public java.util.Set<java.util.UUID> getActiveRunPlayerIds() {
        return java.util.Collections.unmodifiableSet(runProgresses.keySet());
    }

    public void clearAllRunProgresses() {
        runProgresses.clear();
    }

    /**
     * Award a medal for completing a LEVEL in COUNTUP mode.
     * Only main and branch levels count (+1 each). Final and unclassified
     * levels do not award medals (final bonus comes from GLOBAL_END endTier).
     */
    public boolean claimLevelMedal(Player player, String mapId, String deploymentId) {
        if (player == null) return false;
        if (pkwWorldManager.getTimingMode(player.getWorld()) != TimingMode.COUNTUP) return false;
        RunProgress rp = runProgresses.get(player.getUniqueId());
        if (rp == null) return false;

        // Only main and branch levels award medals
        String worldName = player.getWorld().getName();
        CourseLayoutAnalyzer.LevelRoleInfo role = courseLayoutAnalyzer.getLevelRole(worldName, mapId, deploymentId);
        if (role == null || (!"main".equals(role.role) && !"branch".equals(role.role))) {
            return false;
        }
        return rp.claimLevel(mapId, deploymentId);
    }

    /**
     * Settle full-course timing and archive the result.
     * For COUNTUP worlds: determine tier, save to records.
     */
    public boolean finishFullCourse(Player player, String globalEndMapId, String globalEndDepId, String endTierStr) {
        RunProgress progress = runProgresses.remove(player.getUniqueId());
        if (progress == null) return false;

        progress.pause();
        long elapsedMs = progress.getElapsedMs();
        String timeStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(elapsedMs);

        // Award end-tier medals
        EndTier tier = EndTier.fromString(endTierStr);
        int bonus;
        switch (tier) {
            case HARD:   bonus = 3; break;
            case NORMAL: bonus = 2; break;
            default:     bonus = 1; break;
        }
        progress.addEndTierMedals(bonus);
        int totalMedals = progress.getMedals();

        String worldName = player.getWorld().getName();
        java.util.List<String> eligibleTiers = new java.util.ArrayList<>();
        for (String candidate : new String[]{"expect", "advance", "standard"}) {
            if (totalMedals >= recordsManager.getCountupThreshold(worldName, candidate)) {
                eligibleTiers.add(candidate);
            }
        }

        // Tier display string
        String tierDisplay = String.join("/", eligibleTiers);

        visibilityManager.cleanupPlayer(player);
        // Archive to records (COUNTUP only) — each tier independently
        boolean isPb = false;
        if (pkwWorldManager.getTimingMode(player.getWorld()) == TimingMode.COUNTUP) {
            isPb = recordsManager.saveCountupRecord(worldName, player.getUniqueId(),
                    player.getName(), elapsedMs, totalMedals);
            recordsManager.clearActiveRun(worldName, player.getUniqueId());
        }

        playCompletionEffects(player);

        // Single merged completion message
        String completionMsg = "已完成跑酷！总用时：" + timeStr + "，奖牌数：" + totalMedals
                + "（" + tierDisplay + "）";
        if (isPb) {
            completionMsg += "（新个人最佳！）";
        }
        Msg.send(player, completionMsg);

        return true;
    }

    /**
     * Abandon the current run without archiving.
     */
    public void quitRun(Player player) {
        RunProgress rp = runProgresses.remove(player.getUniqueId());
        if (rp == null) return;
        visibilityManager.cleanupPlayer(player);
        if (pkwWorldManager.isPkwWorld(player.getWorld())) {
            recordsManager.clearActiveRun(player.getWorld().getName(), player.getUniqueId());
        }
    }

    // ---- Countdown-specific ----

    /**
     * Award a categorized medal for COUNTDOWN LEVEL completion.
     * Medal type is determined from course.yml role.
     */
    public String claimCountdownMedal(Player player, String mapId, String deploymentId) {
        if (player == null) return null;
        if (pkwWorldManager.getTimingMode(player.getWorld()) != TimingMode.COUNTDOWN) return null;
        RunProgress rp = runProgresses.get(player.getUniqueId());
        if (rp == null) return null;

        // Query role from CourseLayoutAnalyzer
        String worldName = player.getWorld().getName();
        CourseLayoutAnalyzer.LevelRoleInfo roleInfo = courseLayoutAnalyzer.getLevelRole(worldName, mapId, deploymentId);
        String medalType = roleToMedalType(roleInfo);
        if (medalType == null) return null;

        return rp.claimLevelByType(mapId, deploymentId, medalType);
    }

    private static String endTierDisplay(String tier) {
        if (tier == null) return "未到终点";
        switch (tier) {
            case "easy":   return "简单";
            case "normal": return "普通";
            case "hard":   return "困难";
            default:       return tier;
        }
    }

    private String roleToMedalType(CourseLayoutAnalyzer.LevelRoleInfo info) {
        if (info == null) return null;
        if ("main".equals(info.role)) return "stone";
        if ("branch".equals(info.role)) {
            switch (info.order) {
                case 1: return "bronze";
                case 2: return "silver";
                case 3: return "gold";
                default: return "gold"; // 3+ → gold cap
            }
        }
        return null; // final / unclassified don't award
    }

    /** Get remaining time in ms for COUNTDOWN action bar display. */
    public long getCountdownRemainingMs(UUID playerId, String worldName) {
        RunProgress rp = runProgresses.get(playerId);
        if (rp == null) return -1;
        int totalSec = pkwWorldManager.getCountdownDuration(worldName);
        long totalMs = totalSec * 1000L;
        return totalMs - rp.getElapsedMs();
    }

    /** Check if countdown has reached zero. */
    public boolean isCountdownExpired(UUID playerId, String worldName) {
        return getCountdownRemainingMs(playerId, worldName) <= 0;
    }

    /** Settle countdown run via reaching GLOBAL_END. */
    public void finishCountdownByEnd(Player player, String worldName, String endTier) {
        RunProgress rp = runProgresses.remove(player.getUniqueId());
        if (rp == null) return;
        rp.pause();
        settleCountdown(player, worldName, endTier, rp);
    }

    /** Settle countdown run via timeout. */
    public void finishCountdownByTimeout(Player player, String worldName) {
        RunProgress rp = runProgresses.remove(player.getUniqueId());
        if (rp == null) return;
        rp.pause();
        settleCountdown(player, worldName, null, rp);
    }

    private void settleCountdown(Player player, String worldName, String endTier, RunProgress rp) {
        int stone = rp.getStoneCount();
        int bronze = rp.getBronzeCount();
        int silver = rp.getSilverCount();
        int gold = rp.getGoldCount();

        int stoneScore = countdownScoring.getStoneScore(stone);
        int bronzeScore = countdownScoring.getBronzeScore(bronze);
        int silverScore = countdownScoring.getSilverScore(silver);
        int goldScore = countdownScoring.getGoldScore(gold);

        double multiplier = endTier != null ? countdownScoring.getEndTierMultiplier(endTier) : 0.0;
        int totalScore = (int) Math.round((stoneScore + bronzeScore + silverScore + goldScore) * (1.0 + multiplier));

        long elapsedMs = rp.getElapsedMs();
        String timeStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(elapsedMs);

        String endTierLabel = endTierDisplay(endTier);
        String endMsg = endTier != null ? endTierLabel + "终点 完成！" : "时间到！";
        Msg.send(player, endMsg + " 用时：" + timeStr
                + " 石×" + stone + " 铜×" + bronze + " 银×" + silver + " 金×" + gold
                + "，总分 " + totalScore);

        playCompletionEffects(player);

        boolean isPb = recordsManager.saveCountdownRecord(worldName,
                player.getUniqueId(), player.getName(), totalScore,
                stone, bronze, silver, gold, elapsedMs, endTier);
        visibilityManager.cleanupPlayer(player);
        recordsManager.clearActiveRun(worldName, player.getUniqueId());
        if (isPb) {
            Msg.send(player, "新个人最佳！");
        }
    }

    // ---- Session lifecycle ----

    /**
     * Save the active run (RunProgress + ParkourSession snapshot) to records
     * and clean up in-memory state. Called when a player quits, changes world
     * away from PKW, or is externally teleported away.
     * <p>
     * After this call, both RunProgress and ParkourSession are removed from memory.
     * The session snapshot is persisted so it can be restored on rejoin.
     */
    public void saveAndClearActiveRun(Player player) {
        String worldName = player.getWorld().getName();
        RunProgress rp = runProgresses.get(player.getUniqueId());
        ParkourSession session = sessions.get(player.getUniqueId());

        if (rp != null || session != null) {
            long rpElapsed = 0;
            int rpMedals = 0;
            int stone = 0, bronze = 0, silver = 0, gold = 0;
            java.util.Map<String, String> claimed = java.util.Collections.emptyMap();

            if (rp != null) {
                rp.pause();
                rpElapsed = rp.getElapsedMs();
                rpMedals = rp.getMedals();
                stone = rp.getStoneCount();
                bronze = rp.getBronzeCount();
                silver = rp.getSilverCount();
                gold = rp.getGoldCount();
                claimed = rp.getClaimedLevelsWithTypes();
            }

            // Extract session snapshot
            String sessMapId = null, sessDepId = null;
            long sessElapsed = 0;
            boolean sessStarted = false, sessCompleted = false, sessInsideRegion = false;
            String sessState = null;
            double sessDeathLineY = 0;
            String cpWorld = null;
            double cpX = 0, cpY = 0, cpZ = 0;
            float cpYaw = 0, cpPitch = 0;
            String visitedForkSerial = null, forkFallbackSerial = null;

            if (session != null) {
                sessMapId = session.getMapId();
                sessDepId = session.getDeploymentId();
                sessElapsed = session.getElapsedMs(System.currentTimeMillis());
                sessStarted = session.isStarted();
                sessCompleted = session.isCompleted();
                sessInsideRegion = session.isInsideRegion();
                sessState = session.getState().name();
                sessDeathLineY = session.getDeathLineY();
                org.bukkit.Location cp = session.getCheckpoint();
                if (cp != null && cp.getWorld() != null) {
                    cpWorld = cp.getWorld().getName();
                    cpX = cp.getX(); cpY = cp.getY(); cpZ = cp.getZ();
                    cpYaw = cp.getYaw(); cpPitch = cp.getPitch();
                }
                visitedForkSerial = club.sitmc.sitParkourWarrior.records.RecordsManager.serializeLocationList(
                        session.getVisitedForkPoints());
                forkFallbackSerial = club.sitmc.sitParkourWarrior.records.RecordsManager.serializeLocation(
                        session.getInitialForkFallback());
            }

            org.bukkit.Location loc = player.getLocation();
            recordsManager.saveActiveRunFull(worldName,
                    player.getUniqueId(), player.getName(),
                    rpElapsed, rpMedals, stone, bronze, silver, gold, claimed,
                    loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                    loc.getYaw(), loc.getPitch(),
                    sessMapId, sessDepId, sessElapsed, sessStarted,
                    sessCompleted, sessInsideRegion, sessState, sessDeathLineY,
                    cpWorld, cpX, cpY, cpZ, cpYaw, cpPitch,
                    visitedForkSerial, forkFallbackSerial,
                    rp != null);

            if (rp != null) {
                runProgresses.remove(player.getUniqueId());
            }
            insideDeployments.remove(player.getUniqueId());
        }

        if (session != null) {
            endSession(player, false);
        }
    }

    /**
     * Restore a ParkourSession from saved run data after reconnect / world change.
     * Reconstructs the in-memory ParkourSession and returns the deployment ID
     * for insideDeployment tracking.
     *
     * @return the deployment ID if the session was inside its region, or null
     */
    public String restoreSessionFromSaved(Player player, club.sitmc.sitParkourWarrior.records.RecordsManager.SavedRunData saved) {
        if (!saved.hasSessionState()) return null;
        ParkourMap savedMap = mapManager.getMap(saved.sessionMapId);
        if (savedMap == null || savedMap.getNodeType().isPkwsType()) {
            return null;
        }

        ParkourSession restored = new ParkourSession(
                player.getUniqueId(), saved.sessionMapId, saved.sessionDeploymentId,
                System.currentTimeMillis());

        restored.setElapsedMsDirect(saved.sessionElapsedMs);
        if (saved.sessionStarted) {
            restored.startTimer(System.currentTimeMillis());
        }
        restored.setCompleted(saved.sessionCompleted);
        restored.setInsideRegion(saved.sessionInsideRegion);
        restored.setDeathLineY(saved.sessionDeathLineY);

        if (saved.sessionState != null && !saved.sessionState.isEmpty()) {
            try {
                restored.setState(SessionState.valueOf(saved.sessionState));
            } catch (IllegalArgumentException ignored) {
                restored.setState(SessionState.RUNNING);
            }
        }

        // Restore checkpoint
        if (saved.hasCheckpoint()) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(saved.checkpointWorld);
            if (w != null) {
                restored.setCheckpoint(new org.bukkit.Location(w,
                        saved.checkpointX, saved.checkpointY, saved.checkpointZ,
                        saved.checkpointYaw, saved.checkpointPitch));
            }
        } else {
            // Fallback: resolve checkpoint from map deployment config
            ParkourMap map = mapManager.getMap(saved.sessionMapId);
            Deployment dep = getDeployment(map, saved.sessionDeploymentId);
            if (map != null && dep != null) {
                org.bukkit.Location cp = resolveLocationWorld(dep.getStart(), dep.getRegion());
                if (cp != null) {
                    restored.setCheckpoint(cp.clone());
                }
            }
        }

        // Restore FORK state
        if (saved.visitedForkPointsSerial != null) {
            java.util.List<org.bukkit.Location> visited = club.sitmc.sitParkourWarrior.records.RecordsManager
                    .deserializeLocationList(saved.visitedForkPointsSerial);
            restored.getVisitedForkPoints().addAll(visited);
        }
        if (saved.initialForkFallbackSerial != null) {
            org.bukkit.Location fallback = club.sitmc.sitParkourWarrior.records.RecordsManager
                    .deserializeLocation(saved.initialForkFallbackSerial);
            restored.setInitialForkFallback(fallback);
        }

        // Restore dynamic tracking
        ParkourMap map = mapManager.getMap(saved.sessionMapId);
        Deployment dep = getDeployment(map, saved.sessionDeploymentId);
        if (map != null && dep != null) {
            dynamicService.onPlayerJoin(map, dep);
        }

        sessions.put(player.getUniqueId(), restored);

        // Restore inside-deployment tracking
        if (saved.sessionInsideRegion) {
            insideDeployments.put(player.getUniqueId(), saved.sessionDeploymentId);
        }

        return saved.sessionInsideRegion ? saved.sessionDeploymentId : null;
    }

    // ---- Original session lifecycle methods ----

    public void endSessionsForMap(String mapId) {
        if (mapId == null) {
            return;
        }
        for (UUID playerId : new java.util.ArrayList<>(sessions.keySet())) {
            ParkourSession session = sessions.get(playerId);
            if (session != null && session.getMapId().equalsIgnoreCase(mapId)) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    endSession(player, false);
                } else {
                    sessions.remove(playerId);
                    insideDeployments.remove(playerId);
                }
            }
        }
    }

    public void endSessionsForDeployment(String mapId, String deploymentId) {
        if (mapId == null || deploymentId == null) {
            return;
        }
        for (UUID playerId : new java.util.ArrayList<>(sessions.keySet())) {
            ParkourSession session = sessions.get(playerId);
            if (session != null && session.getMapId().equalsIgnoreCase(mapId)
                    && deploymentId.equals(session.getDeploymentId())) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    endSession(player, false);
                } else {
                    sessions.remove(playerId);
                    insideDeployments.remove(playerId);
                }
            }
        }
    }

    public void endAll() {
        for (UUID playerId : new java.util.ArrayList<>(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                endSession(player, false);
            } else {
                sessions.remove(playerId);
            }
        }
        for (UUID id : new java.util.ArrayList<>(runProgresses.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && pkwWorldManager.isPkwWorld(p.getWorld())) {
                recordsManager.clearActiveRun(p.getWorld().getName(), id);
            }
        }
        runProgresses.clear();
        insideDeployments.clear();
    }

    public void teleportToStart(Player player) {
        ParkourSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        ParkourMap map = mapManager.getMap(session.getMapId());
        Deployment deployment = getDeployment(map, session.getDeploymentId());
        if (deployment == null) {
            return;
        }
        Location start = resolveLocationWorld(deployment.getStart(), deployment.getRegion());
        if (start != null) {
            session.setInsideRegion(true);
            session.setInsideStart(false);
            session.setSkipResetAtStartOnce(true);
            teleportToStartLocation(player, start);
        }
    }

    public void teleportToStartLocation(Player player, Location start) {
        if (player == null || start == null || start.getWorld() == null) {
            return;
        }
        cleanupPlayerEquipment(player);
        player.setFallDistance(0f);
        player.setVelocity(new Vector(0, 0, 0));
        internalTeleports.add(player.getUniqueId());
        try {
            player.teleport(start);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    /** Returns true if the given player is currently undergoing a plugin-internal teleport.
     * Internal teleports (death respawn, checkpoint, handoff, fork fallback, etc.) should
     * NOT trigger leave-save logic in PlayerTeleportListener. */
    public boolean isInternalTeleport(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    /** Mark the start of a plugin-internal teleport. Call before {@code player.teleport()}
     * for any plugin-initiated teleport that should not trigger leave-save logic.
     * Always pair with {@link #endInternalTeleport} in a {@code try/finally} block. */
    public void beginInternalTeleport(UUID playerId) {
        internalTeleports.add(playerId);
    }

    /** Mark the end of a plugin-internal teleport. Call in a {@code finally} block
     * after the teleport completes. */
    public void endInternalTeleport(UUID playerId) {
        internalTeleports.remove(playerId);
    }

    public void cleanupPlayerEquipment(Player player) {
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.getInventory().setBoots(null);
    }

    private void playCompletionEffects(Player player) {
        // 升级叮声
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        Location center = player.getLocation();
        // 环形粒子（不详试炼刷怪笼激活粒子）
        for (int i = 0; i < 12; i++) {
            double angle = 2 * Math.PI * i / 12;
            double x = Math.cos(angle) * 1.5;
            double z = Math.sin(angle) * 1.5;
            player.getWorld().spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS,
                    center.clone().add(x, 0.5, z), 1, 0, 0, 0, 0);
        }
        // 向上柱状粒子
        for (int i = 0; i < 8; i++) {
            player.getWorld().spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS,
                    center.clone().add(0, i * 0.4, 0), 1, 0, 0, 0, 0);
        }
    }

    /**
     * Shared single-level completion presentation for legacy PKW and PKWS.
     * Medal/record changes deliberately remain in the PKW caller; PKWS only
     * reuses the elapsed-time format, message and completion effects.
     */
    public void sendLevelCompletion(Player player, ParkourMap map, long durationMs) {
        if (player == null) {
            return;
        }
        String timeStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(durationMs);
        if (map != null) {
            String coloredTitle = map.getDifficulty().getTitleColor() + map.getTitle();
            Msg.send(player, "您已通过 " + coloredTitle + "（用时：" + timeStr + "）。");
        } else {
            Msg.send(player, "您已通过关卡（用时：" + timeStr + "）。");
        }
        playCompletionEffects(player);
    }

    /** Reuse the standard per-level completion effects for PKWS sub-levels
     * without creating a legacy PKW session or awarding legacy medals. */
    public void playLevelCompletionEffects(Player player) {
        if (player != null) {
            playCompletionEffects(player);
        }
    }

    /**
     * 发送关卡标题：将 title 移到较小的 subtitle 行显示，
     * 原来的 subtitle 延迟 2 秒（40 tick）后出现。
     */
    public void sendLevelTitle(Player player, ParkourMap map) {
        if (player == null || map == null) return;
        String coloredTitle = map.getDifficulty().getTitleColor() + map.getTitle();
        String sub = map.getSubtitle();
        // 主标题置空，关卡名放在副标题行（字号更小）
        player.sendTitle("", coloredTitle, 10, 70, 10);
        // 进入关卡音效
        player.playSound(player.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.0f, 1.0f);
        // 原副标题延迟 2s 出现
        if (sub != null && !sub.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendTitle("", map.getDifficulty().getTitleColor() + sub, 0, 50, 10);
                }
            }, 40L);
        }
    }

    /**
     * Teleport player to current checkpoint. Sets skipResetAtStartOnce so
     * reappearing at a start area does not reset the timer.
     */
    public void teleportToCheckpoint(Player player) {
        ParkourSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Location checkpoint = session.getCheckpoint();
        if (checkpoint == null) {
            return;
        }
        session.setSkipResetAtStartOnce(true);
        session.setInsideRegion(true);
        session.setInsideStart(false);
        teleportToStartLocation(player, checkpoint);
    }

    /**
     * Transition the session to the next deployment when the player enters
     * its entry point (handoff). Updates deathLine, checkpoint, and resets
     * timer state for LEVEL nodes.
     *
     * @param nextMap        the next deployment's ParkourMap
     * @param nextDeployment the next deployment
     * @param entryPoint     the entry-point location the player reached
     */
    public void handoffToNextDeployment(Player player, ParkourSession session,
                                        ParkourMap nextMap, Deployment nextDeployment,
                                        Location entryPoint) {
        if (player == null || session == null || nextMap == null || nextDeployment == null
                || entryPoint == null || nextMap.getNodeType().isPkwsType()) {
            return;
        }
        // Update session to point to the new deployment.
        ParkourSession updated = new ParkourSession(
                player.getUniqueId(),
                nextMap.getId(),
                nextDeployment.getId(),
                System.currentTimeMillis());
        updated.setCheckpoint(entryPoint.clone());
        Region nextRegion = nextDeployment.getRegion();
        if (nextRegion != null) {
            updated.setDeathLineY(nextRegion.getMinY());
        }
        sessions.put(player.getUniqueId(), updated);
        dynamicService.onPlayerJoin(nextMap, nextDeployment);

        if (nextMap.getNodeType() == NodeType.FORK) {
            // Record the fork point entered and store previous-level end as initial fallback.
            updated.getVisitedForkPoints().add(entryPoint.clone());
            updated.setInitialForkFallback(session.getCheckpoint());
            // Track last visited FORK for return-compass item
            RunProgress rp = runProgresses.get(player.getUniqueId());
            if (rp != null) {
                rp.setLastFork(nextMap.getId(), nextDeployment.getId(), entryPoint);
            }
        } else if (nextMap.getNodeType() == NodeType.LEVEL && nextRegion != null
                && nextRegion.contains(player.getLocation())) {
            updated.startTimer(System.currentTimeMillis());
            updated.setInsideRegion(true);
            updated.setPendingTitleAtStart(true);
        }
    }

    /**
     * BRANCH_END teleport: switch the player's session to the bound FORK
     * deployment and teleport them to the fork-point location. If the player
     * has no current session a fresh one is created.
     */
    public void handoffToFork(Player player, ParkourMap forkMap, Deployment forkDeployment, Location forkPoint) {
        if (player == null || forkMap == null || forkDeployment == null || forkPoint == null
                || forkMap.getNodeType().isPkwsType()) {
            return;
        }
        ParkourSession old = sessions.get(player.getUniqueId());
        ParkourSession updated = new ParkourSession(
                player.getUniqueId(),
                forkMap.getId(),
                forkDeployment.getId(),
                System.currentTimeMillis());
        updated.setCheckpoint(forkPoint.clone());
        Region forkRegion = forkDeployment.getRegion();
        if (forkRegion != null) {
            updated.setDeathLineY(forkRegion.getMinY());
        }
        updated.getVisitedForkPoints().add(forkPoint.clone());
        if (old != null) {
            updated.setInitialForkFallback(old.getCheckpoint());
        }
        sessions.put(player.getUniqueId(), updated);
        dynamicService.onPlayerJoin(forkMap, forkDeployment);

        // Track last visited FORK for return-compass item
        RunProgress rp = runProgresses.get(player.getUniqueId());
        if (rp != null) {
            rp.setLastFork(forkMap.getId(), forkDeployment.getId(), forkPoint);
        }

        updated.setSkipResetAtStartOnce(true);
        updated.setInsideRegion(true);
        updated.setInsideStart(false);
        teleportToStartLocation(player, forkPoint.clone());
    }

    /**
     * FORK death fallback: teleport to nearest visited fork point, or initial
     * fallback (previous-level end) if no fork points have been visited yet.
     */
    public void handleForkFallback(Player player, ParkourSession session) {
        List<Location> visited = session.getVisitedForkPoints();
        if (!visited.isEmpty()) {
            Location nearest = findNearest(player.getLocation(), visited);
            if (nearest != null) {
                session.setSkipResetAtStartOnce(true);
                session.setInsideRegion(true);
                session.setInsideStart(false);
                teleportToStartLocation(player, nearest.clone());
            }
        } else {
            Location fallback = session.getInitialForkFallback();
            if (fallback != null) {
                session.setSkipResetAtStartOnce(true);
                session.setInsideRegion(true);
                session.setInsideStart(false);
                teleportToStartLocation(player, fallback.clone());
            }
        }
    }

    private Location findNearest(Location from, List<Location> candidates) {
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (Location loc : candidates) {
            if (loc.getWorld() == null || from.getWorld() == null) {
                continue;
            }
            if (!loc.getWorld().getName().equals(from.getWorld().getName())) {
                continue;
            }
            double d = from.distance(loc);
            if (d < bestDist) {
                bestDist = d;
                best = loc;
            }
        }
        return best;
    }

    private Deployment getDeployment(ParkourMap map, String deploymentId) {
        if (map == null || deploymentId == null) {
            return null;
        }
        return map.getDeployment(deploymentId);
    }

    public Location resolveLocationWorld(PointLocation point, Region region) {
        if (point == null) {
            return null;
        }
        String worldName = point.hasWorld() ? point.getWorldName() : (region != null ? region.getWorldName() : null);
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, point.getX(), point.getY(), point.getZ(), point.getYaw(), point.getPitch());
    }
}
