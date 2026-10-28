package club.sitmc.sitParkourWarrior.listener;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.course.CourseLayoutAnalyzer;
import club.sitmc.sitParkourWarrior.map.CourseLinker;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SelectionManager;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.session.ParkourSession;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.session.SessionState;
import club.sitmc.sitParkourWarrior.util.Msg;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class PlayerMoveListener implements Listener {
    private final SessionManager sessionManager;
    private final MapManager mapManager;
    private final SelectionManager selectionManager;
    private final PkwWorldManager pkwWorldManager;
    private final CourseLayoutAnalyzer courseLayoutAnalyzer;
    private final VisibilityManager visibilityManager;
    private final PkwsRaceManager pkwsRaceManager;
    public PlayerMoveListener(SessionManager sessionManager, MapManager mapManager,
                              SelectionManager selectionManager, PkwWorldManager pkwWorldManager,
                              CourseLayoutAnalyzer courseLayoutAnalyzer,
                              VisibilityManager visibilityManager,
                              PkwsRaceManager pkwsRaceManager) {
        this.sessionManager = sessionManager;
        this.mapManager = mapManager;
        this.selectionManager = selectionManager;
        this.pkwWorldManager = pkwWorldManager;
        this.courseLayoutAnalyzer = courseLayoutAnalyzer;
        this.visibilityManager = visibilityManager;
        this.pkwsRaceManager = pkwsRaceManager;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        boolean isPkw = pkwWorldManager.isPkwWorld(to.getWorld());
        Player player = event.getPlayer();
        boolean isPkws = pkwWorldManager.isPkwsWorld(to.getWorld());
        if (isPkws) {
            // Practice mode is never valid in PKWS, including head-only
            // movement events that would otherwise return below.
            clearSpecialMode(player);
        }

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (selectionManager.isEditing(player)) {
            return;
        }

        if (isPkws) {
            // Strict world split: PKWS never enters handlePkwMove or the
            // non-PKW simple-level state machine.
            pkwsRaceManager.handleMove(player, from, to);
            return;
        }

        if (isPkw) {
            handlePkwMove(player, from, to);
        } else {
            handleSimpleMove(player, from, to);
        }
    }

    // ============ GameMode change → a spectator leaves the race in place ============

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (pkwWorldManager.isPkwsWorld(player.getWorld())) {
            // Segment may toggle its marker together with a gamemode change;
            // remove it before any PKWS observer/participant handling.
            clearSpecialMode(player);
        }
        if (event.getNewGameMode() != GameMode.SPECTATOR) return;

        if (pkwsRaceManager != null && pkwWorldManager.isPkwsWorld(player.getWorld())) {
            // A contestant who switches to spectator is removed from the race.
            // If the race continues, the event stays uncancelled so he keeps
            // spectating in place and stays a non-contestant.
            String worldName = player.getWorld().getName();
            boolean raceWasRunning = pkwsRaceManager.isRunning(worldName);
            pkwsRaceManager.onPlayerBecameExempt(player);
            if (raceWasRunning && !pkwsRaceManager.isRunning(worldName)) {
                // The unified no-contest cleanup restores Adventure mode.
                // Cancel this final switch so the event cannot overwrite it.
                event.setCancelled(true);
            }
        }
        // Abandon any active run (full-course timing) — quitRun cleans up
        // RunProgress and removes visibility effects.
        if (sessionManager.getRunProgress(player.getUniqueId()) != null) {
            sessionManager.quitRun(player);
        }
        // Abandon any active session (per-level gameplay).
        if (sessionManager.getSession(player.getUniqueId()) != null) {
            sessionManager.endSession(player, false);
        }
        // Belt-and-suspenders: ensure no invis/glow effects remain.
        visibilityManager.cleanupPlayer(player);
        sessionManager.removeInsideDeployment(player.getUniqueId());
    }

    // ============ Unified PKW exemption check ============

    /**
     * Returns true if the player should be completely excluded from PKW logic
     * and proximity invisibility.  Covers:
     * - Segment special-mode PDC tag (sitsegment:special_mode)
     * - Spectator game mode (any source: /spec, /gamemode spectator, etc.)
     */
    public static boolean isExemptFromPkw(Player player) {
        if (isSpecialMode(player)) return true;
        // Spectator mode
        if (player.getGameMode() == GameMode.SPECTATOR) return true;
        return false;
    }

    /** Special-mode players are excluded from ordinary PKW participation. */
    public static boolean isSpecialMode(Player player) {
        // Segment special-mode PDC marker
        var key = new NamespacedKey("sitsegment", "special_mode");
        Byte v = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (v != null && v == (byte) 1) return true;
        return false;
    }

    /** Removes the Segment practice marker when a player is in a PKWS world. */
    public static void clearSpecialMode(Player player) {
        if (player == null) return;
        player.getPersistentDataContainer().remove(
                new NamespacedKey("sitsegment", "special_mode"));
    }

    // ============ PKW-world full gameplay ============

    private void handlePkwMove(Player player, Location from, Location to) {
        // Segment special mode: quit active run and stay silent
        if (isExemptFromPkw(player)) {
            ParkourSession s = sessionManager.getSession(player.getUniqueId());
            if (s != null || sessionManager.getRunProgress(player.getUniqueId()) != null) {
                sessionManager.quitRun(player);
                sessionManager.endSession(player, false);
            }
            return;
        }

        ParkourSession session = sessionManager.getSession(player.getUniqueId());

        // Death-line check
        if (session != null && to.getBlockY() < session.getDeathLineY()) {
            ParkourMap deathMap = mapManager.getMap(session.getMapId());
            if (deathMap != null && deathMap.getNodeType() == NodeType.FORK) {
                sessionManager.handleForkFallback(player, session);
            } else {
                sessionManager.teleportToCheckpoint(player);
            }
            return;
        }

        // Lava death
        if (session != null && isLava(to)) {
            ParkourMap deathMap = mapManager.getMap(session.getMapId());
            if (deathMap != null && deathMap.getNodeType() == NodeType.FORK) {
                sessionManager.handleForkFallback(player, session);
            } else {
                sessionManager.teleportToCheckpoint(player);
            }
            return;
        }

        // Full-course timing
        checkFullCourseTiming(player, to);

        // Countdown timeout
        checkCountdownTimeout(player, session);

        // BRANCH_END teleport
        if (handleBranchEndTeleport(player, to)) return;

        // No session: auto-start or end-zone completion
        if (session == null) {
            // Bug2修复：检测玩家是否直接处于某个 LEVEL 的终点区域内。
            // 断线重连/切世界回来后 ParkourSession 被清除（只恢复了 RunProgress），
            // 若玩家已在终点区域，auto-start + 立即完成，保证本趟结算不丢失。
            for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(to.getWorld().getName())) {
                if (wd.map.getNodeType() == NodeType.LEVEL && wd.dep.isInEndZone(to)) {
                    if (sessionManager.startSession(player, wd.map, wd.dep)) {
                        sessionManager.setInsideDeployment(player.getUniqueId(), wd.dep.getId());
                        sessionManager.endSession(player, true);
                    }
                    return;
                }
            }

            DeploymentMatch match = findDeploymentByEntryPoint(to);
            if (match != null) {
                String current = sessionManager.getInsideDeployment(player.getUniqueId());
                if (current == null || !current.equals(match.deployment.getId())) {
                    sessionManager.startSession(player, match.map, match.deployment);
                }
                sessionManager.setInsideDeployment(player.getUniqueId(), match.deployment.getId());
            } else {
                sessionManager.removeInsideDeployment(player.getUniqueId());
            }
            return;
        }

        ParkourMap map = mapManager.getMap(session.getMapId());
        if (map == null) { sessionManager.endSession(player, false); return; }
        if (map.getNodeType().isPkwsType()) {
            // A stale/restored session must not turn a PKWS definition into
            // a legacy PKW gameplay session.
            sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }
        Deployment deployment = map.getDeployment(session.getDeploymentId());
        if (deployment == null) { sessionManager.endSession(player, false); return; }

        // Handoff check
        if (map.getNodeType() != NodeType.FORK
                && (session.getState() == SessionState.RUNNING || session.getState() == SessionState.AWAITING_HANDOFF)) {
            if (checkHandoff(player, session, to)) return;
            if (session.getState() == SessionState.AWAITING_HANDOFF) return;
        }

        // FORK runtime
        if (map.getNodeType() == NodeType.FORK) {
            handleForkRuntime(player, session, map, deployment, to);
            return;
        }

        // GLOBAL_START transition: when the player walks from the global start
        // platform into another node's entry point, end the GLOBAL_START session
        // and auto-start the new node.
        if (map.getNodeType() == NodeType.GLOBAL_START) {
            DeploymentMatch match = findDeploymentByEntryPoint(to);
            if (match != null) {
                sessionManager.endSession(player, false);
                sessionManager.startSession(player, match.map, match.deployment);
                sessionManager.setInsideDeployment(player.getUniqueId(), match.deployment.getId());
                return;
            }
            // Still in GLOBAL_START area — no further per-level processing needed.
            return;
        }

        // Per-level region / start-zone / end-zone runtime.
        // Strictly scoped to LEVEL nodes only — GLOBAL_START / GLOBAL_END / BRANCH_END / FORK
        // must never flow through the single-level state machine.
        if (map.getNodeType() == NodeType.LEVEL) {
            Region region = deployment.getRegion();
            boolean wasInside = session.isInsideRegion();
            boolean inside = region != null && region.contains(to);
            if (inside) sessionManager.setInsideDeployment(player.getUniqueId(), deployment.getId());
            else sessionManager.removeInsideDeployment(player.getUniqueId());

            if (!wasInside && inside) {
                session.setInsideStart(false);
                session.setPendingTitleAtStart(true);
                if (!session.isStarted()) {
                    session.startTimer(System.currentTimeMillis());
                }
                session.setInsideRegion(true);
            } else if (wasInside && !inside) {
                if (!session.isCompleted() && session.isStarted()) session.stopTimer(System.currentTimeMillis());
                session.setInsideRegion(false);
                session.setInsideStart(false);
            }

            // Start-zone trigger: edge-triggered + per-session dedup.
            // pendingTitleAtStart is set true on region entry (session create /
            // region re-enter) and set false here on the first start-zone hit.
            // Once false it stays false for the lifetime of this session, so
            // walking out of and back into the start zone cannot retrigger.
            // Death / teleportToCheckpoint sets skipResetAtStartOnce and keeps
            // the session intact — the timer continues and the start zone is
            // permanently silenced for this engagement.
            boolean inStart = deployment.isInStartZone(to);
            if (inStart && !session.isInsideStart() && session.isPendingTitleAtStart()) {
                if (session.isSkipResetAtStartOnce()) {
                    session.setSkipResetAtStartOnce(false);
                    if (!session.isStarted()) {
                        session.startTimer(System.currentTimeMillis());
                        sessionManager.sendLevelTitle(player, map);
                    }
                } else {
                    session.resetTimer();
                    session.startTimer(System.currentTimeMillis());
                    sessionManager.sendLevelTitle(player, map);
                }
                session.setPendingTitleAtStart(false);
                session.setInsideStart(true);
            } else if (!inStart && session.isInsideStart()) {
                session.setInsideStart(false);
            }

            if (deployment.isInEndZone(to)) {
                sessionManager.endSession(player, true);
            }
        }
    }

    // ============ Non-PKW simple single-level state machine ============

    /**
     * Edge-triggered state machine.  The per-player {@code insideDeployment} map
     * acts as the explicit dedup key: a player is "IN" a deployment iff the map
     * contains an entry for that player→deployment.
     *
     * OUT → IN:   Player is OUT (no insideDeployment entry for this deployment)
     *             AND steps into a LEVEL's start zone → session created, timer
     *             starts, title shown, insideDeployment set.
     * IN  → OUT:  Player reaches end zone → complete (show time, clear session
     *             and insideDeployment).
     * IN  → OUT:  Player leaves region via sides/top → silent exit (clear
     *             session and insideDeployment).
     * IN  → IN:   Player falls out region bottom → teleport back to start,
     *             stays IN (session and insideDeployment preserved).
     *
     * The dedup rule: as long as insideDeployment still points to this
     * deployment, re-entering the start zone is a no-op.  Once the player
     * transitions to OUT (end zone or side/top exit), insideDeployment is
     * cleared so the next start-zone step triggers a fresh entry.
     */
    private void handleSimpleMove(Player player, Location from, Location to) {
        if (isExemptFromPkw(player)) {
            ParkourSession s = sessionManager.getSession(player.getUniqueId());
            if (s != null) sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }

        ParkourSession session = sessionManager.getSession(player.getUniqueId());
        String insideId = sessionManager.getInsideDeployment(player.getUniqueId());

        // --- OUT state: try to enter a LEVEL's start zone ---
        if (session == null) {
            for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(to.getWorld().getName())) {
                if (wd.map.getNodeType() != NodeType.LEVEL) continue;
                if (!wd.dep.isInStartZone(to)) continue;

                // Dedup: if insideDeployment still tracks this deployment,
                // the player is logically IN — do not re-enter.
                if (insideId != null && insideId.equals(wd.dep.getId())) {
                    return;
                }

                // Start session (startSession already starts the timer and
                // sets insideRegion for LEVEL nodes in region).
                if (!sessionManager.startSession(player, wd.map, wd.dep)) {
                    return; // startSession failed (e.g. session already exists)
                }
                ParkourSession s = sessionManager.getSession(player.getUniqueId());
                if (s == null) return;

                // Title and dedup marker.
                sessionManager.sendLevelTitle(player, wd.map);
                sessionManager.setInsideDeployment(player.getUniqueId(), wd.dep.getId());
                return;
            }
            // No matching start zone — ensure insideDeployment is clean.
            if (insideId != null) {
                sessionManager.removeInsideDeployment(player.getUniqueId());
            }
            return;
        }

        // --- IN state: player has an active session ---
        ParkourMap map = mapManager.getMap(session.getMapId());
        if (map == null || map.getNodeType() != NodeType.LEVEL) {
            sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }
        Deployment deployment = map.getDeployment(session.getDeploymentId());
        if (deployment == null) {
            sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }

        Region region = deployment.getRegion();
        if (region == null) {
            // Incomplete level — clean up to avoid soft-lock.
            sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }

        // Track which deployment we're in
        boolean inRegion = region.contains(to);
        if (inRegion) {
            sessionManager.setInsideDeployment(player.getUniqueId(), deployment.getId());
        } else {
            sessionManager.removeInsideDeployment(player.getUniqueId());
        }

        // --- IN → OUT: reach end zone (complete) ---
        if (deployment.isInEndZone(to)) {
            if (session.isStarted()) session.stopTimer(System.currentTimeMillis());
            long durationMs = session.getElapsedMs(System.currentTimeMillis());
            String timeStr = club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(durationMs);
            String coloredTitle = map.getDifficulty().getTitleColor() + map.getTitle();
            Msg.send(player, "您已通过 " + coloredTitle + "（用时：" + timeStr + "）。");
            sessionManager.endSession(player, false);
            sessionManager.removeInsideDeployment(player.getUniqueId());
            return;
        }

        // --- IN → IN or IN → OUT: leaving the region ---
        if (!inRegion) {
            int bx = to.getBlockX(), bz = to.getBlockZ();
            boolean inXz = bx >= region.getMinX() && bx <= region.getMaxX()
                    && bz >= region.getMinZ() && bz <= region.getMaxZ();
            if (inXz && to.getBlockY() < region.getMinY()) {
                // Bottom fall → teleport back to start, stay IN.
                // teleportToCheckpoint sets skipResetAtStartOnce and leaves
                // the session intact, so the next onMove will see session≠null
                // and insideDeployment still set → no re-entry, no timer reset.
                sessionManager.teleportToCheckpoint(player);
            } else {
                // Side/top exit → silent OUT.
                sessionManager.endSession(player, false);
                sessionManager.removeInsideDeployment(player.getUniqueId());
            }
        }
    }

    /**
     * Full-course timing: GLOBAL_START start-point and GLOBAL_END end-point
     * detection. Independent of session state — runs on every cross-block move.
     */
    private void checkFullCourseTiming(Player player, Location to) {
        String worldName = to.getWorld().getName();
        club.sitmc.sitParkourWarrior.config.TimingMode mode = pkwWorldManager.getTimingMode(to.getWorld());

        for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(worldName)) {
            NodeType type = wd.map.getNodeType();
            if (type.isPkwsType()) continue;
            if (type != NodeType.GLOBAL_START && type != NodeType.GLOBAL_END) continue;
            if (type == NodeType.GLOBAL_START) {
                if (wd.dep.isInStartZone(to)) {
                    sessionManager.startFullCourse(player.getUniqueId());
                    // Create session for GLOBAL_START fallback safety net
                    // so death-line / lava fallback teleports player back to start.
                    if (sessionManager.getSession(player.getUniqueId()) == null) {
                        sessionManager.startSession(player, wd.map, wd.dep);
                    }
                    return;
                }
            } else {
                if (wd.dep.isInEndZone(to)) {
                    if (sessionManager.getRunProgress(player.getUniqueId()) != null) {
                        String endTier = wd.map.getEndTier().toConfigString();
                        if (mode == club.sitmc.sitParkourWarrior.config.TimingMode.COUNTDOWN) {
                            sessionManager.finishCountdownByEnd(player, worldName, endTier);
                        } else {
                            sessionManager.finishFullCourse(player, wd.map.getId(), wd.dep.getId(), endTier);
                        }
                        sessionManager.endSession(player, false);
                        sessionManager.cleanupPlayerEquipment(player);
                        player.setFallDistance(0f);
                        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                        player.teleport(to.getWorld().getSpawnLocation());
                    } else {
                        Msg.send(player, "你未从全局起点出发，无法结算全程计时。");
                    }
                    return;
                }
            }
        }
    }

    private void checkCountdownTimeout(Player player, ParkourSession session) {
        if (session == null) return;
        String worldName = player.getWorld().getName();
        if (pkwWorldManager.getTimingMode(player.getWorld()) != club.sitmc.sitParkourWarrior.config.TimingMode.COUNTDOWN) return;
        if (sessionManager.getRunProgress(player.getUniqueId()) == null) return;
        if (!sessionManager.isCountdownExpired(player.getUniqueId(), worldName)) return;

        sessionManager.finishCountdownByTimeout(player, worldName);
        sessionManager.endSession(player, false);
        sessionManager.cleanupPlayerEquipment(player);
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.teleport(player.getWorld().getSpawnLocation());
    }

    /**
     * BRANCH_END teleport: if the player is near a BRANCH_END's end point,
     * look up its bound FORK from the course layout, switch the session to
     * that FORK, and teleport the player to the fork-point.
     *
     * @return true if a BRANCH_END teleport was executed
     */
    private boolean handleBranchEndTeleport(Player player, Location to) {
        String worldName = to.getWorld().getName();
        for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(worldName)) {
            if (wd.map.getNodeType() != NodeType.BRANCH_END) continue;
            if (!wd.dep.isInEndZone(to)) continue;

            CourseLayoutAnalyzer.BranchBindingInfo binding = courseLayoutAnalyzer.getBranchBinding(
                    worldName, wd.map.getId(), wd.dep.getId());
            if (binding == null) {
                ParkourSession session = sessionManager.getSession(player.getUniqueId());
                if (session != null) sessionManager.teleportToCheckpoint(player);
                return true;
            }

            ParkourMap forkMap = mapManager.getMap(binding.forkMapId);
            if (forkMap == null || forkMap.getNodeType() != NodeType.FORK) return true;
            Deployment forkDep = forkMap.getDeployment(binding.forkDepId);
            if (forkDep == null) return true;

            sessionManager.handoffToFork(player, forkMap, forkDep, binding.forkPoint);
            return true;
        }
        return false;
    }

    /**
     * Check whether the player has reached any deployed node's entry point
     * (3x3x3 range). First match wins when multiple entry points overlap
     * (deterministic: map iteration order).
     * Entry points: LEVEL→start, FORK→each forkPoint, GLOBAL_END/BRANCH_END→end.
     * GLOBAL_START has no entry point and is skipped.
     * Own deployment is always excluded.
     *
     * @return true if a handoff was executed
     */
    private boolean checkHandoff(Player player, ParkourSession session, Location to) {
        for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(to.getWorld().getName())) {
            if (wd.map.getNodeType().isPkwsType()
                    || wd.map.getNodeType() == NodeType.GLOBAL_START) continue;
            if (wd.map.getId().equalsIgnoreCase(session.getMapId())
                    && wd.dep.getId().equals(session.getDeploymentId())) continue;

            List<Location> entryPoints = getEntryPoints(wd.map, wd.dep);
            for (Location ep : entryPoints) {
                if (isNear(to, ep)) {
                    if (session.getState() == SessionState.RUNNING) {
                        sessionManager.removeInsideDeployment(player.getUniqueId());
                    }
                    sessionManager.handoffToNextDeployment(player, session, wd.map, wd.dep, ep);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Return the entry-point locations for a deployment that can receive handoff.
     * LEVEL → start; FORK → each resolved fork point;
     * BRANCH_END → end (but intercepted by handleBranchEndTeleport before handoff).
     * GLOBAL_START and GLOBAL_END are not handoff targets.
     */
    private List<Location> getEntryPoints(ParkourMap map, Deployment dep) {
        List<Location> points = new ArrayList<>();
        NodeType type = map.getNodeType();
        if (type.isPkwsType() || type == NodeType.GLOBAL_START || type == NodeType.GLOBAL_END) {
            return points;
        }
        Region region = dep.getRegion();
        if (type == NodeType.FORK) {
            points.addAll(CourseLinker.resolveForkBranchPoints(map, dep));
        } else if (type == NodeType.BRANCH_END) {
            Location end = sessionManager.resolveLocationWorld(dep.getEnd(), region);
            if (end != null) {
                points.add(end);
            }
        } else {
            Location start = sessionManager.resolveLocationWorld(dep.getStart(), region);
            if (start != null) {
                points.add(start);
            }
        }
        return points;
    }

    /**
     * FORK runtime: track visited fork points and check handoff out to next LEVEL.
     */
    private void handleForkRuntime(Player player, ParkourSession session, ParkourMap map, Deployment deployment, Location to) {
        Region region = deployment.getRegion();

        // Track visited fork points.
        List<Location> forkPoints = CourseLinker.resolveForkBranchPoints(map, deployment);
        for (Location fp : forkPoints) {
            if (isNear(to, fp) && !containsLocation(session.getVisitedForkPoints(), fp)) {
                session.getVisitedForkPoints().add(fp.clone());
            }
        }

        // Check handoff out: any other node's entry point nearby.
        if (checkHandoff(player, session, to)) {
            return;
        }

        // Region inside/outside tracking.
        boolean inside = region != null && region.contains(to);
        if (!session.isInsideRegion() && inside) {
            session.setInsideRegion(true);
        } else if (session.isInsideRegion() && !inside) {
            session.setInsideRegion(false);
        }
    }

    private boolean isLava(Location loc) {
        Material type = loc.getBlock().getType();
        return type == Material.LAVA;
    }

    private boolean isNear(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!a.getWorld().getName().equals(b.getWorld().getName())) {
            return false;
        }
        int dx = Math.abs(a.getBlockX() - b.getBlockX());
        int dy = Math.abs(a.getBlockY() - b.getBlockY());
        int dz = Math.abs(a.getBlockZ() - b.getBlockZ());
        return dx <= 1 && dy <= 1 && dz <= 1;
    }

    private boolean containsLocation(List<Location> list, Location loc) {
        if (loc.getWorld() == null) {
            return false;
        }
        for (Location l : list) {
            if (l.getWorld() != null
                    && l.getWorld().getName().equals(loc.getWorld().getName())
                    && l.getBlockX() == loc.getBlockX()
                    && l.getBlockY() == loc.getBlockY()
                    && l.getBlockZ() == loc.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find a deployment whose entry point is near the given location.
     * LEVEL → start zone, FORK → each fork point.
     * GLOBAL_START / GLOBAL_END / BRANCH_END are not entry points
     * (GLOBAL_START triggers full-course timing via checkFullCourseTiming;
     *  GLOBAL_END and BRANCH_END are destinations only).
     */
    private DeploymentMatch findDeploymentByEntryPoint(Location location) {
        if (location == null || location.getWorld() == null) return null;
        for (MapManager.WorldDeployment wd : mapManager.getWorldDeployments(location.getWorld().getName())) {
            NodeType type = wd.map.getNodeType();
            if (type.isPkwsType()
                    || type == NodeType.GLOBAL_START || type == NodeType.GLOBAL_END || type == NodeType.BRANCH_END) continue;
            if (type == NodeType.FORK) {
                List<Location> fps = CourseLinker.resolveForkBranchPoints(wd.map, wd.dep);
                for (Location fp : fps) {
                    if (isNear(location, fp)) return new DeploymentMatch(wd.map, wd.dep);
                }
            } else {
                if (wd.dep.isInStartZone(location)) return new DeploymentMatch(wd.map, wd.dep);
            }
        }
        return null;
    }

    private static class DeploymentMatch {
        private final ParkourMap map;
        private final Deployment deployment;

        private DeploymentMatch(ParkourMap map, Deployment deployment) {
            this.map = map;
            this.deployment = deployment;
        }
    }
}
