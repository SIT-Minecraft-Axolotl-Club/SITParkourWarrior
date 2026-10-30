package club.sitmc.sitParkourWarrior.pkws;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.listener.PlayerMoveListener;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.DynamicData;
import club.sitmc.sitParkourWarrior.map.DynamicState;
import club.sitmc.sitParkourWarrior.map.DynamicService;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PkwsOrientation;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import club.sitmc.sitParkourWarrior.map.SchematicService;
import club.sitmc.sitParkourWarrior.session.ParkourSession;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import club.sitmc.sitParkourWarrior.session.SessionState;
import club.sitmc.sitParkourWarrior.util.ItemUtil;
import club.sitmc.sitParkourWarrior.util.Msg;
import club.sitmc.sitParkourWarrior.visibility.VisibilityManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-scoped PKWS race state.  PKWS deliberately does not use the legacy
 * PKW SessionManager: its participants, lives and relay arrivals belong to
 * the generated track and to one particular world race.
 */
public final class PkwsRaceManager {
    private static final int RELAY_COUNT = 7;
    private static final int INITIAL_LIVES = 3;
    /** Stage-time instants (seconds still remaining) that warn a contestant
     * who has not reached the current relay node yet about the life penalty. */
    private static final Set<Integer> LIFE_WARNING_SECONDS = Set.of(15, 5);

    public enum State {
        IDLE,
        RUNNING
    }

    private final JavaPlugin plugin;
    private final PkwWorldManager worldManager;
    private final MapManager mapManager;
    private final PkwsTrackStore trackStore;
    private final PkwsRangeConfig rangeConfig;
    private final PkwsWinsStore winsStore;
    private final VisibilityManager visibilityManager;
    private final DynamicService dynamicService;
    private final SessionManager sessionManager;
    private final PkwsDisconnectStore disconnectStore;
    private final SchematicService schematicService = new SchematicService();
    private final Map<String, Race> races = new HashMap<>();
    private final Map<UUID, String> participantWorlds = new HashMap<>();
    private final Map<String, Countdown> countdowns = new HashMap<>();
    /** Offline participants that must receive the normal post-race return on
     * their next PKWS entry. This is in-memory only; race state is not
     * restored across a server restart. */
    private final Map<String, Set<UUID>> postRaceReturnPlayers = new HashMap<>();
    /** Players currently observing a running race from inside the world. They
     * fly in adventure mode, stay permanently invisible and are hidden from
     * every other player. This is in-memory only and is retracted when the
     * race ends or the observer leaves the world. */
    private final Map<UUID, String> watcherWorlds = new HashMap<>();

    public PkwsRaceManager(JavaPlugin plugin, PkwWorldManager worldManager,
                           MapManager mapManager, PkwsTrackStore trackStore,
                           PkwsRangeConfig rangeConfig, PkwsWinsStore winsStore,
                           VisibilityManager visibilityManager) {
        this(plugin, worldManager, mapManager, trackStore, rangeConfig, winsStore,
                visibilityManager, null, null, null);
    }

    public PkwsRaceManager(JavaPlugin plugin, PkwWorldManager worldManager,
                           MapManager mapManager, PkwsTrackStore trackStore,
                           PkwsRangeConfig rangeConfig, PkwsWinsStore winsStore,
                           VisibilityManager visibilityManager,
                           DynamicService dynamicService,
                           SessionManager sessionManager) {
        this(plugin, worldManager, mapManager, trackStore, rangeConfig, winsStore,
                visibilityManager, dynamicService, sessionManager, null);
    }

    public PkwsRaceManager(JavaPlugin plugin, PkwWorldManager worldManager,
                           MapManager mapManager, PkwsTrackStore trackStore,
                           PkwsRangeConfig rangeConfig, PkwsWinsStore winsStore,
                           VisibilityManager visibilityManager,
                           DynamicService dynamicService,
                           SessionManager sessionManager,
                           PkwsDisconnectStore disconnectStore) {
        this.plugin = plugin;
        this.worldManager = worldManager;
        this.mapManager = mapManager;
        this.trackStore = trackStore;
        this.rangeConfig = rangeConfig;
        this.winsStore = winsStore;
        this.visibilityManager = visibilityManager;
        this.dynamicService = dynamicService;
        this.sessionManager = sessionManager;
        this.disconnectStore = disconnectStore;
    }

    /**
     * Restarts are intentionally safe: a race is not persisted, so an old
     * track is returned to relay-closed/start-closed idle state before players
     * are admitted to the world.
     */
    public void initializeLoadedWorlds() {
        // A race is not persisted across a server restart.  Any disconnect
        // snapshots left by the previous process therefore cannot be valid.
        if (disconnectStore != null) {
            disconnectStore.clearAll();
        }
        for (String worldName : worldManager.getPkwsWorlds()) {
            Race race = races.computeIfAbsent(worldName, Race::new);
            PkwsTrack track = trackStore.load(worldName);
            if (track != null) {
                race.track = track;
                resetTrackForIdle(track);
                race.track = null;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            // A reload/restart is also a race reset. Restore every player
            // uniformly before idle entry gives normal PKWS lobby items.
            restoreWorldPlayers(world, null);
            for (Player player : world.getPlayers()) {
                onPlayerEnterWorld(player);
            }
        }
    }

    /** Handles the only movement path used by a marked PKWS world. */
    public void handleMove(Player player, Location from, Location to) {
        if (player == null || to == null || to.getWorld() == null
                || !worldManager.isPkwsWorld(to.getWorld())) {
            return;
        }

        String worldName = to.getWorld().getName();
        Race race = races.computeIfAbsent(worldName, Race::new);
        if (race.state == State.RUNNING) {
            checkForceOpen(race, System.currentTimeMillis());
            if (race.state != State.RUNNING) {
                return;
            }
        }

        // Practice mode is not supported in PKWS.  Clear a stale Segment
        // marker before applying the ordinary PKWS lobby/race rules.
        PlayerMoveListener.clearSpecialMode(player);

        if (race.state == State.IDLE) {
            PkwsTrack track = loadTrack(race, player, false);
            if (track == null || track.getPieces().isEmpty()) {
                return;
            }
            PkwsTrack.Piece start = track.getPieces().get(0);
            if (!isStartOpen(start)) {
                // The closed schematic is the physical barrier. Keep the
                // logical barrier as a fallback when the model does not
                // completely block movement.
                if (isInsideAndLeavingPiece(start, from, to)) {
                    teleportToPieceStart(player, start);
                    return;
                }
                // Stepping on a closed start is the player-facing way to
                // request the start-opening countdown. It uses the configured
                // start countdown (10 seconds by default), which is a value
                // independent of the relay gate wait. The per-world guard
                // prevents continued contact with the gate from restarting
                // the countdown. Unlike the old behavior, merely entering the
                // start range is not teleported back; the player is returned
                // only after leaving that range.
                if (!countdowns.containsKey(worldName)
                        && !isInPieceEndZone(start, from)
                        && isInPieceEndZone(start, to)) {
                    Msg.send(player, startStartCountdown(worldName,
                            rangeConfig.getStartCountdownSeconds()));
                }
                return;
            }
            if (!isInPieceEndZone(start, from) && isInPieceEndZone(start, to)) {
                startRace(race, player);
            }
            return;
        }

        if (PlayerMoveListener.isExemptFromPkw(player)) {
            // A player who selected spectator mode keeps it and observes in
            // place; everybody else observes as an invisible flying
            // adventurer.
            makeWatcher(player);
            return;
        }

        Participant participant = race.participants.get(player.getUniqueId());
        if (participant == null) {
            // Movement is also an entry fallback for same-world teleports or
            // integrations that do not emit a world-change callback.
            makeWatcher(player);
            return;
        }
        participant.lastLocation = to.clone();

        PkwsTrack.Piece currentPiece = currentPiece(race, participant);
        if (currentPiece != null && currentPiece.getRegion() != null
                && to.getBlockY() < currentPiece.getRegion().getMinY()) {
            suppressLevelStartTriggerAfterFall(participant, currentPiece);
            teleportToCurrentStart(player, participant, currentPiece);
            participant.lastLocation = player.getLocation().clone();
            return;
        }
        if (to.getBlock().getType() == Material.LAVA) {
            if (currentPiece != null) {
                suppressLevelStartTriggerAfterFall(participant, currentPiece);
                teleportToCurrentStart(player, participant, currentPiece);
                participant.lastLocation = player.getLocation().clone();
            }
            return;
        }

        if (enforceClosedRelay(race, player, from, to)) {
            return;
        }

        handleLevelProgress(race, player, participant, from, to);
        if (!race.participants.containsKey(player.getUniqueId())) {
            return;
        }
        recordRelayArrivals(race, player, participant, to);
        if (!race.participants.containsKey(player.getUniqueId())) {
            return;
        }

        PkwsTrack.Piece end = race.track.getPieces()
                .get(race.track.getPieces().size() - 1);
        if (!isInPieceEndZone(end, from) && isInPieceEndZone(end, to)) {
            finishRace(race, player.getUniqueId(),
                    "第一名：" + player.getName() + " 到达全局终点，本局结束。", null);
        }
    }

    /** Applies entry behavior after a world change, join or marker addition. */
    public void onPlayerEnterWorld(Player player) {
        if (player == null || player.getWorld() == null
                || !worldManager.isPkwsWorld(player.getWorld())) {
            return;
        }
        // A teleport/world-change listener or another plugin can apply a
        // gamemode after the entry callback.  Re-check on the next tick so a
        // non-participant cannot remain an ordinary player while a race is
        // running.
        schedulePlayerEntryCheck(player);
        // PKWS never permits Segment practice mode.  Clear the marker before
        // deciding whether this is a reconnecting participant or an observer.
        PlayerMoveListener.clearSpecialMode(player);
        Race race = races.computeIfAbsent(player.getWorld().getName(), Race::new);
        if (race.state == State.RUNNING) {
            if (restoreParticipantIfPossible(race, player)) {
                return;
            }
            removePostRaceReturnMarker(race.worldName, player.getUniqueId());
            makeWatcher(player);
        } else {
            // An idle world never keeps observer flight or hiding.
            clearWatcherState(player);
            Set<UUID> pendingReturns = postRaceReturnPlayers.get(race.worldName);
            boolean returnedAfterFinishedRace = pendingReturns != null
                    && pendingReturns.remove(player.getUniqueId());
            if (pendingReturns != null && pendingReturns.isEmpty()) {
                postRaceReturnPlayers.remove(race.worldName);
            }
            if (disconnectStore != null) {
                disconnectStore.remove(player.getWorld().getName(), player.getUniqueId());
            }
            player.setGameMode(GameMode.ADVENTURE);
            removePkwsItems(player);
            cleanupPkwsEffects(player);
            if (returnedAfterFinishedRace) {
                teleportToSpawn(player);
            } else {
                giveQuitItem(player);
            }
        }
    }

    /** Schedules a final running-race entry guard after world/teleport events. */
    public void schedulePlayerEntryCheck(Player player) {
        if (player == null || plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> enforceRunningWorldEntry(player));
    }

    /**
     * Periodic PKWS-only guard.  Segment can write its practice marker from a
     * command without producing a movement/world-change event, so clear it
     * for every player currently inside a PKWS world as well.
     */
    public void enforcePkwsRestrictions() {
        for (String worldName : worldManager.getPkwsWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            Race race = races.get(worldName);
            for (Player player : new ArrayList<>(world.getPlayers())) {
                PlayerMoveListener.clearSpecialMode(player);
                if (race == null || race.state != State.RUNNING || race.finishing) {
                    continue;
                }
                Participant participant = race.participants.get(player.getUniqueId());
                if (participant == null || participant.eliminated || !participant.online) {
                    makeWatcher(player);
                }
            }
        }
    }

    private void enforceRunningWorldEntry(Player player) {
        if (player == null || !player.isOnline() || player.getWorld() == null
                || !worldManager.isPkwsWorld(player.getWorld())) {
            return;
        }
        PlayerMoveListener.clearSpecialMode(player);
        Race race = races.get(player.getWorld().getName());
        if (race == null || race.state != State.RUNNING || race.finishing) {
            return;
        }
        Participant participant = race.participants.get(player.getUniqueId());
        if (participant == null || participant.eliminated || !participant.online) {
            makeWatcher(player);
        }
    }

    /**
     * Re-applies observer state after a respawn.  A player who is not part of
     * the running race (for example an eliminated contestant) keeps observing
     * in adventure flight, so the flight permission and invisibility lost on
     * death are restored.
     *
     * @return true when the player was handled as an in-place observer
     */
    public boolean reapplyObserverState(Player player) {
        if (player == null || player.getWorld() == null
                || !worldManager.isPkwsWorld(player.getWorld())) {
            return false;
        }
        Race race = races.get(player.getWorld().getName());
        if (race == null || race.state != State.RUNNING || race.finishing
                || race.participants.containsKey(player.getUniqueId())) {
            return false;
        }
        makeWatcher(player);
        return true;
    }

    /**
     * Saves a participant snapshot when leaving the source PKWS world. A
     * running race keeps the participant in its population while marking it
     * offline, so gates do not wait for the disconnected player.
     */
    public void onPlayerLeaveWorld(Player player, String sourceWorldName) {
        Location departure = player == null || player.getLocation() == null
                ? null : player.getLocation().clone();
        if (departure != null && departure.getWorld() != null
                && !sourceWorldName.equals(departure.getWorld().getName())) {
            departure = null;
        }
        onPlayerLeaveWorld(player, sourceWorldName, departure);
    }

    /** Called before/after a player leaves a PKWS world. */
    public void onPlayerLeaveWorld(Player player, String sourceWorldName,
                                   Location departureLocation) {
        if (player == null || sourceWorldName == null
                || !worldManager.isPkwsWorld(sourceWorldName)) {
            return;
        }
        boolean observer = isRaceObserver(player, sourceWorldName);
        boolean countdownActive = countdowns.containsKey(sourceWorldName);
        // Observer flight, invisibility and hiding never survive leaving the
        // PKWS world.
        clearWatcherState(player);
        Race race = races.get(sourceWorldName);
        if (race != null && race.state == State.RUNNING) {
            clearActionBar(player);
        }
        if (observer && ((race != null && race.state == State.RUNNING)
                || countdownActive)) {
            player.resetTitle();
        }
        if (race != null && race.state == State.RUNNING) {
            Participant participant = race.participants.get(player.getUniqueId());
            if (participant != null) {
                boolean stillInSourceWorld = player.getWorld() != null
                        && sourceWorldName.equals(player.getWorld().getName());
                if (!stillInSourceWorld) {
                    // A world-change callback can be the first notification
                    // for a cross-world transfer. Treat it as a voluntary
                    // leave rather than saving a reconnect snapshot.
                    race.participants.remove(player.getUniqueId());
                    if (disconnectStore != null) {
                        disconnectStore.remove(sourceWorldName, player.getUniqueId());
                    }
                    participantWorlds.remove(player.getUniqueId());
                    cleanupPkwsEffects(player);
                    removePkwsItems(player);
                    Msg.send(player, "已退出 PKWS 比赛。");
                    afterParticipantRemoved(race, player.getUniqueId());
                    if (race.state == State.IDLE) {
                        scheduleReturnToWorldSpawn(player, sourceWorldName);
                    }
                    return;
                }
                if (participant.online) {
                    participant.online = false;
                    Location savedLocation = departureLocation;
                    if (savedLocation == null || savedLocation.getWorld() == null
                            || !sourceWorldName.equals(savedLocation.getWorld().getName())) {
                        savedLocation = participant.lastLocation;
                    }
                    if (savedLocation == null && player.getLocation() != null
                            && player.getLocation().getWorld() != null
                            && sourceWorldName.equals(player.getLocation().getWorld().getName())) {
                        savedLocation = player.getLocation();
                    }
                    participant.lastLocation = savedLocation == null
                            ? null : savedLocation.clone();
                    saveDisconnectedParticipant(race, participant, false);
                    cleanupPkwsEffects(player);
                    removePkwsItems(player);
                    checkRelayReadiness(race);
                    // Losing an online contestant can make another online
                    // contestant the last survivor.  The disconnected record
                    // remains recoverable but must not keep a stale winner.
                    checkLastSurvivor(race);
                    // If this was the last online contestant, there is nobody
                    // left who can continue or win the running race. End it
                    // now; the disconnected snapshot marker makes the player
                    // return to the world spawn when they rejoin.
                    if (aliveOnlineCount(race) == 0) {
                        finishRace(race, null,
                                "所有在线参赛者已退出游戏，比赛结束。", null);
                    }
                }
                return;
            }
            cleanupPkwsEffects(player);
            removePkwsItems(player);
            return;
        }
        if (disconnectStore != null) {
            disconnectStore.remove(sourceWorldName, player.getUniqueId());
        }
        cleanupPkwsEffects(player);
        removePkwsItems(player);
        player.setGameMode(GameMode.ADVENTURE);
        visibilityManager.cleanupPlayer(player);
        participantWorlds.remove(player.getUniqueId());
    }

    private boolean restoreParticipantIfPossible(Race race, Player player) {
        UUID playerId = player.getUniqueId();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            // A player who observes in spectator mode is never re-admitted as
            // a contestant, and the plugin never rewrites his game mode. The
            // reconnect record is kept for a later return to adventure mode.
            return false;
        }
        PkwsDisconnectStore.Snapshot snapshot = disconnectStore == null
                ? null : disconnectStore.load(race.worldName, playerId);
        if (snapshot != null) {
            boolean valid = race.matchId != null && race.matchId.equals(snapshot.getMatchId())
                    && !snapshot.isEliminated() && snapshot.getLives() > 0;
            if (!valid) {
                disconnectStore.remove(race.worldName, playerId);
                return false;
            }
            Participant participant = race.participants.get(playerId);
            if (participant == null) {
                participant = new Participant(snapshot.getPlayerName());
                race.participants.put(playerId, participant);
            }
            restoreParticipantSnapshot(race, player, playerId, participant, snapshot);
            if (disconnectStore != null) {
                disconnectStore.remove(race.worldName, playerId);
            }
            activateRestoredParticipant(race, player, participant, snapshotLocation(snapshot));
            checkRelayReadiness(race);
            return true;
        }

        Participant participant = race.participants.get(playerId);
        if (participant == null || participant.eliminated) {
            return false;
        }
        participant.online = true;
        participant.lastLocation = player.getLocation().clone();
        if (participant.levelSession != null && participant.pausedLevelTimer
                && !participant.levelSession.isStarted()) {
            participant.levelSession.setElapsedMsDirect(participant.pausedLevelElapsedMs);
            participant.levelSession.startTimer(System.currentTimeMillis());
            participant.levelSession.setInsideStart(participant.pausedInsideLevelStart);
        }
        clearPausedLevelState(participant);
        participantWorlds.put(playerId, race.worldName);
        activateRestoredParticipant(race, player, participant,
                participant.lastLocation == null ? null : participant.lastLocation.clone());
        checkRelayReadiness(race);
        return true;
    }

    private void restoreParticipantSnapshot(Race race, Player player, UUID playerId,
                                            Participant participant,
                                            PkwsDisconnectStore.Snapshot snapshot) {
        participant.pieceIndex = clampPieceIndex(race.track, snapshot.getPieceIndex());
        participant.activeLevelPieceIndex = snapshot.getActiveLevelPieceIndex();
        if (race.track == null || participant.activeLevelPieceIndex < 0
                || participant.activeLevelPieceIndex >= race.track.getPieces().size()) {
            participant.activeLevelPieceIndex = -1;
        }
        participant.lives = Math.max(0, snapshot.getLives());
        participant.eliminated = snapshot.isEliminated();
        participant.online = false;
        participant.lastLocation = snapshotLocation(snapshot);
        participant.levelSession = null;
        participant.pausedLevelTimer = false;
        participant.pausedLevelElapsedMs = 0L;
        participant.pausedInsideLevelStart = false;
        participant.checkpoint = snapshotCheckpointLocation(snapshot);

        int unfinishedLevelPieceIndex = -1;
        if (!snapshot.isLevelCompleted() && race.track != null
                && participant.activeLevelPieceIndex >= 0
                && participant.activeLevelPieceIndex < race.track.getPieces().size()) {
            PkwsTrack.Piece activeLevel = race.track.getPieces()
                    .get(participant.activeLevelPieceIndex);
            if (activeLevel.getType() == PkwsTrack.PieceType.LEVEL) {
                unfinishedLevelPieceIndex = participant.activeLevelPieceIndex;
                participant.pieceIndex = Math.min(participant.pieceIndex,
                        unfinishedLevelPieceIndex);
                participant.checkpoint = null;
                Location levelStart = resolvePieceCheckpoint(player, activeLevel, false);
                if (levelStart != null) {
                    participant.checkpoint = levelStart.clone();
                }
            }
        }

        if (participant.activeLevelPieceIndex >= 0 && race.track != null
                && participant.activeLevelPieceIndex < race.track.getPieces().size()) {
            PkwsTrack.Piece piece = race.track.getPieces().get(participant.activeLevelPieceIndex);
            if (piece.getType() == PkwsTrack.PieceType.LEVEL) {
                ParkourMap map = mapManager.getMap(piece.getSourceId());
                if (map != null) {
                    String deploymentId = piece.getDeploymentId() == null ? piece.getSourceId()
                            : piece.getDeploymentId();
                    ParkourSession levelSession = new ParkourSession(playerId, map.getId(),
                            deploymentId, System.currentTimeMillis());
                    levelSession.setElapsedMsDirect(snapshot.getLevelElapsedMs());
                    levelSession.setDeathLineY(piece.getRegion() == null
                            ? 0.0 : piece.getRegion().getMinY());
                    if (snapshot.isLevelTimerRunning() && !snapshot.isLevelCompleted()) {
                        levelSession.startTimer(System.currentTimeMillis());
                    }
                    levelSession.setCompleted(snapshot.isLevelCompleted());
                    levelSession.setState(snapshot.isLevelCompleted()
                            ? club.sitmc.sitParkourWarrior.session.SessionState.AWAITING_HANDOFF
                            : club.sitmc.sitParkourWarrior.session.SessionState.RUNNING);
                    levelSession.setInsideRegion(snapshot.isLevelInsideRegion());
                    levelSession.setInsideStart(snapshot.isInsideLevelStart());
                    levelSession.setPendingTitleAtStart(snapshot.isLevelPendingTitleAtStart());
                    if (participant.checkpoint == null) {
                        participant.checkpoint = resolvePieceCheckpoint(player, piece,
                                snapshot.isLevelCompleted());
                    }
                    levelSession.setCheckpoint(participant.checkpoint == null
                            ? null : participant.checkpoint.clone());
                    participant.levelSession = levelSession;
                    participant.pausedLevelTimer = snapshot.isLevelTimerRunning()
                            && !snapshot.isLevelCompleted();
                    participant.pausedLevelElapsedMs = snapshot.getLevelElapsedMs();
                    participant.pausedInsideLevelStart = snapshot.isInsideLevelStart();
                    ensurePkwsDynamic(map, piece);
                } else {
                    participant.activeLevelPieceIndex = -1;
                }
            } else {
                participant.activeLevelPieceIndex = -1;
            }
        }
        if (participant.checkpoint == null && race.track != null
                && !race.track.getPieces().isEmpty()) {
            PkwsTrack.Piece piece = race.track.getPieces().get(
                    clampPieceIndex(race.track, participant.pieceIndex));
            participant.checkpoint = resolvePieceCheckpoint(player, piece, false);
        }

        // Replace any stale in-memory memberships before rebuilding from the
        // snapshot, which may have survived while the player was offline.
        for (RelayProgress relay : race.relays) {
            relay.arrived.remove(playerId);
            relay.arrivalOrder.removeIf(playerId::equals);
        }
        for (Integer pieceIndex : snapshot.getArrivedRelayPieceIndices()) {
            if (pieceIndex == null || pieceIndex > participant.pieceIndex) {
                continue;
            }
            for (RelayProgress relay : race.relays) {
                if (relay.pieceIndex != pieceIndex) {
                    continue;
                }
                if (relay.arrived.add(playerId) && !relay.arrivalOrder.contains(playerId)) {
                    relay.arrivalOrder.add(playerId);
                }
                break;
            }
        }
    }

    private void activateRestoredParticipant(Race race, Player player,
                                             Participant participant, Location location) {
        participant.online = true;
        clearPausedLevelState(participant);
        participantWorlds.put(player.getUniqueId(), race.worldName);
        // A restored contestant is a racer again: observer state is retracted
        // before the race equipment is rebuilt.
        clearWatcherState(player);
        player.setGameMode(GameMode.ADVENTURE);
        cleanupPkwsEffects(player);
        if (location != null && location.getWorld() != null
                && race.worldName.equals(location.getWorld().getName())) {
            player.setFallDistance(0f);
            player.teleport(location);
        }
        // Rebuild PKWS-owned hotbar items after the reconnect inventory has
        // been cleaned.  Quit/logout cleanup removes these items, and an
        // inventory saved by the server can otherwise leave an item in an
        // arbitrary slot or make the old presence check skip slot 7.
        checkLastSurvivor(race);
        restoreParticipantItems(race, player);
    }

    private int clampPieceIndex(PkwsTrack track, int index) {
        if (track == null || track.getPieces().isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(index, track.getPieces().size() - 1));
    }

    private Location snapshotLocation(PkwsDisconnectStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.getLocationWorld() == null
                || snapshot.getLocationWorld().isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(snapshot.getLocationWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, snapshot.getLocationX(), snapshot.getLocationY(),
                snapshot.getLocationZ(), snapshot.getLocationYaw(), snapshot.getLocationPitch());
    }

    private Location snapshotCheckpointLocation(PkwsDisconnectStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.getCheckpointWorld() == null
                || snapshot.getCheckpointWorld().isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(snapshot.getCheckpointWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, snapshot.getCheckpointX(), snapshot.getCheckpointY(),
                snapshot.getCheckpointZ(), snapshot.getCheckpointYaw(), snapshot.getCheckpointPitch());
    }

    private void saveDisconnectedParticipant(Race race, Participant participant,
                                              boolean eliminated) {
        if (disconnectStore == null || race == null || participant == null) {
            return;
        }
        long elapsedMs = 0L;
        boolean timerRunning = false;
        boolean insideStart = false;
        boolean levelCompleted = false;
        boolean levelInsideRegion = false;
        boolean levelPendingTitleAtStart = false;
        Location checkpoint = participant.checkpoint == null
                ? null : participant.checkpoint.clone();
        if (participant.levelSession != null) {
            levelCompleted = participant.levelSession.isCompleted();
            levelInsideRegion = participant.levelSession.isInsideRegion();
            levelPendingTitleAtStart = participant.levelSession.isPendingTitleAtStart();
            if (checkpoint == null && participant.levelSession.getCheckpoint() != null) {
                checkpoint = participant.levelSession.getCheckpoint().clone();
            }
            boolean currentlyRunning = participant.levelSession.isStarted();
            if (currentlyRunning || !participant.pausedLevelTimer) {
                timerRunning = currentlyRunning;
                elapsedMs = participant.levelSession.getElapsedMs(System.currentTimeMillis());
                insideStart = participant.levelSession.isInsideStart();
            } else {
                // A later gate opening can save the same offline player again.
                // Preserve the original disconnect snapshot instead of turning
                // a paused running timer into a permanently stopped timer.
                timerRunning = true;
                elapsedMs = participant.pausedLevelElapsedMs;
                insideStart = participant.pausedInsideLevelStart;
            }
            if (currentlyRunning) {
                participant.levelSession.stopTimer(System.currentTimeMillis());
            }
            participant.pausedLevelTimer = timerRunning;
            participant.pausedLevelElapsedMs = elapsedMs;
            participant.pausedInsideLevelStart = insideStart;
        }
        Location location = participant.lastLocation;
        List<Integer> arrived = arrivedRelayPieceIndices(race, participant);
        disconnectStore.save(PkwsDisconnectStore.create(
                race.worldName, findPlayerId(race, participant), race.matchId,
                participant.lastKnownName, Math.max(0, participant.lives), eliminated,
                participant.pieceIndex, participant.activeLevelPieceIndex, elapsedMs,
                timerRunning, insideStart, levelCompleted, levelInsideRegion,
                levelPendingTitleAtStart,
                checkpoint == null || checkpoint.getWorld() == null
                        ? null : checkpoint.getWorld().getName(),
                checkpoint == null ? 0.0 : checkpoint.getX(),
                checkpoint == null ? 0.0 : checkpoint.getY(),
                checkpoint == null ? 0.0 : checkpoint.getZ(),
                checkpoint == null ? 0.0f : checkpoint.getYaw(),
                checkpoint == null ? 0.0f : checkpoint.getPitch(),
                location == null || location.getWorld() == null ? null : location.getWorld().getName(),
                location == null ? 0.0 : location.getX(),
                location == null ? 0.0 : location.getY(),
                location == null ? 0.0 : location.getZ(),
                location == null ? 0.0f : location.getYaw(),
                location == null ? 0.0f : location.getPitch(), arrived));
    }

    private UUID findPlayerId(Race race, Participant participant) {
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            if (entry.getValue() == participant) {
                return entry.getKey();
            }
        }
        return null;
    }

    private List<Integer> arrivedRelayPieceIndices(Race race, Participant participant) {
        UUID playerId = findPlayerId(race, participant);
        if (playerId == null) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (RelayProgress relay : race.relays) {
            if (relay.arrived.contains(playerId)) {
                result.add(relay.pieceIndex);
            }
        }
        return result;
    }

    private void saveAllOfflineSnapshots(Race race) {
        if (race == null || disconnectStore == null) {
            return;
        }
        for (Participant participant : race.participants.values()) {
            if (!participant.online && !participant.eliminated) {
                saveDisconnectedParticipant(race, participant, false);
            }
        }
    }

    // ============ In-place observation (non-participants) ============

    /**
     * Keeps a player who must not take part in the running race inside the
     * world as an observer instead of forcing him into spectator mode.
     * Observers fly in adventure mode, stay permanently invisible and are
     * hidden from every other player (including other observers).
     *
     * <p>A player who selected spectator mode on his own keeps that game mode;
     * the plugin only cleans his race items, hides him and never rewrites his
     * game mode.</p>
     */
    private void makeWatcher(Player player) {
        if (player == null || !player.isOnline() || player.getWorld() == null) {
            return;
        }
        String worldName = player.getWorld().getName();
        if (!worldManager.isPkwsWorld(worldName)) {
            return;
        }
        boolean newlyBecameWatcher = !worldName.equals(watcherWorlds.get(player.getUniqueId()));
        watcherWorlds.put(player.getUniqueId(), worldName);
        if (newlyBecameWatcher) {
            // Race items and temporary contestant effects are never part of
            // observation mode.
            removePkwsItems(player);
            cleanupPkwsEffects(player);
        }
        // Observers never carry a glowing outline.
        player.removePotionEffect(PotionEffectType.GLOWING);
        if (player.getGameMode() != GameMode.SPECTATOR) {
            if (player.getGameMode() != GameMode.ADVENTURE) {
                player.setGameMode(GameMode.ADVENTURE);
            }
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                        PotionEffect.INFINITE_DURATION, 0, false, false, false));
            }
        }
        hideWatcher(player, newlyBecameWatcher);
    }

    /**
     * Retracts observer flight, invisibility and hiding for one player. Safe to
     * call for players that never observed, and for offline players.
     */
    private void clearWatcherState(Player player) {
        if (player == null) {
            return;
        }
        watcherWorlds.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.GLOWING);
        showWatcher(player);
    }

    /** Hides one observer from every other online player. */
    private void hideWatcher(Player watcher, boolean force) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(watcher.getUniqueId())) {
                continue;
            }
            // The force pass covers the transition itself; later passes only
            // repair viewers that hid, joined or came back into range.
            if (force || viewer.canSee(watcher)) {
                viewer.hidePlayer(plugin, watcher);
            }
        }
    }

    /** Makes one observer visible again for every other online player. */
    private void showWatcher(Player watcher) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(watcher.getUniqueId())) {
                continue;
            }
            viewer.showPlayer(plugin, watcher);
        }
    }

    private void cleanupPkwsEffects(Player player) {
        if (player == null) {
            return;
        }
        if (visibilityManager != null) {
            visibilityManager.cleanupPlayer(player);
        }
        if (sessionManager != null) {
            // PKWS only uses temporary effects/equipment; this shared PKW
            // cleanup component also removes any future PKWS potion additions.
            sessionManager.cleanupPlayerEquipment(player);
        } else {
            new ArrayList<>(player.getActivePotionEffects()).forEach(
                    effect -> player.removePotionEffect(effect.getType()));
            player.getInventory().setBoots(null);
        }
    }

    /** Called when an external command changes a PKWS participant to spectator. */
    public void onPlayerBecameExempt(Player player) {
        if (player == null || player.getWorld() == null
                || !worldManager.isPkwsWorld(player.getWorld())) {
            return;
        }
        Race race = races.get(player.getWorld().getName());
        if (race == null || race.state != State.RUNNING
                || !race.participants.containsKey(player.getUniqueId())) {
            return;
        }
        PlayerMoveListener.clearSpecialMode(player);
        removeParticipant(race, player.getUniqueId(), false);
        // The plugin deliberately leaves the new game mode alone: the player
        // keeps spectating in place and never becomes a contestant again.
        Msg.send(player, "已退出 PKWS 比赛。");
        // The player is still in the PKWS world. If this was the last
        // participant, the normal idle reset must still return them to spawn.
        afterParticipantRemoved(race, null);
    }

    /** Implements the PKWS quit item. */
    public void abandon(Player player) {
        if (player == null || player.getWorld() == null
                || !worldManager.isPkwsWorld(player.getWorld())) {
            return;
        }
        String worldName = player.getWorld().getName();
        Race race = races.get(worldName);
        if (race != null && race.state == State.RUNNING
                && race.participants.remove(player.getUniqueId()) != null) {
            if (disconnectStore != null) {
                disconnectStore.remove(worldName, player.getUniqueId());
            }
            participantWorlds.remove(player.getUniqueId());
            // A voluntary quit while the race is still active keeps the player
            // in the world as an observer. Returning him to the lobby would
            // let him walk back into the race as an ordinary participant, so
            // the world spawn is deliberately not used either.
            makeWatcher(player);
            player.sendTitle("§c已放弃", "", 5, 40, 10);
            Msg.send(player, "已退出 PKWS 比赛。");
            afterParticipantRemoved(race, player.getUniqueId());
            return;
        }
        removePkwsItems(player);
        cleanupPkwsEffects(player);
        clearWatcherState(player);
        player.setGameMode(GameMode.ADVENTURE);
        teleportToSpawn(player);
    }

    /** The last-survivor settlement item calls this method. */
    public void settleByItem(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        Race race = races.get(player.getWorld().getName());
        if (race == null || race.state != State.RUNNING
                || !player.getUniqueId().equals(race.pendingWinner)
                || !isOnlineAliveParticipant(race, player.getUniqueId(),
                race.participants.get(player.getUniqueId()))) {
            Msg.send(player, "当前无法结算比赛。");
            return;
        }
        finishRace(race, player.getUniqueId(),
                "第一名：" + player.getName() + " 使用结算物品结束本局。", null);
    }

    public boolean isRunning(String worldName) {
        Race race = races.get(worldName);
        return race != null && race.state == State.RUNNING;
    }

    /**
     * Returns a player to the finished race world after an in-flight
     * cross-world teleport has completed.
     */
    public void scheduleReturnToWorldSpawn(Player player, String worldName) {
        if (player == null || worldName == null || worldName.isBlank()
                || !worldManager.isPkwsWorld(worldName)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null || !player.isOnline()) {
                return;
            }
            restorePlayer(player, world, true);
        });
    }

    public boolean isParticipant(UUID playerId) {
        return playerId != null && participantWorlds.containsKey(playerId);
    }

    public Set<UUID> getParticipantIds() {
        return Collections.unmodifiableSet(new HashSet<>(participantWorlds.keySet()));
    }

    public long getElapsedMillis(String worldName) {
        Race race = races.get(worldName);
        if (race == null || race.state != State.RUNNING) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - race.startedAt);
    }

    public int getRemainingLives(UUID playerId) {
        Race race = raceForParticipant(playerId);
        Participant participant = race == null ? null : race.participants.get(playerId);
        return participant == null ? -1 : participant.lives;
    }

    /** Builds the complete PKWS action-bar display for one participant. */
    public String getActionBarStatus(UUID playerId) {
        Race race = raceForParticipant(playerId);
        Participant participant = race == null ? null : race.participants.get(playerId);
        if (race == null || participant == null) {
            return "";
        }
        int stage = actionBarStage(race, participant);
        int targetStage = Math.max(1, Math.min(PkwsRangeConfig.SEGMENT_COUNT,
                race.currentStage));
        RelayProgress targetRelay = targetStage <= RELAY_COUNT
                ? relayForStage(race, targetStage) : null;
        int arrived = targetRelay == null ? 0 : countArrived(race, targetRelay);
        return "阶段" + stage + " | 剩余时间:" + stageRemainingSeconds(race)
                + " 人数:" + arrived + "/" + aliveOnlineCount(race)
                + " | 生命：" + participant.lives;
    }

    /** Builds the race-world action bar for either a contestant or an observer. */
    public String getActionBarStatus(Player player) {
        if (player == null || player.getWorld() == null) {
            return "";
        }
        Race race = races.get(player.getWorld().getName());
        if (race == null || race.state != State.RUNNING) {
            return "";
        }
        UUID playerId = player.getUniqueId();
        Participant participant = race.participants.get(playerId);
        if (isOnlineAliveParticipant(race, playerId, participant)) {
            return getActionBarStatus(playerId);
        }
        int stage = Math.max(1, Math.min(PkwsRangeConfig.SEGMENT_COUNT,
                race.currentStage));
        RelayProgress targetRelay = stage <= RELAY_COUNT
                ? relayForStage(race, stage) : null;
        int arrived = targetRelay == null ? 0 : countArrived(race, targetRelay);
        return "§c阶段" + stage + " | 剩余时间:" + stageRemainingSeconds(race)
                + " 人数:" + arrived + "/" + aliveOnlineCount(race);
    }

    private int actionBarStage(Race race, Participant participant) {
        PkwsTrack.Piece piece = currentPiece(race, participant);
        if (piece == null) {
            return 1;
        }
        return Math.max(1, Math.min(PkwsRangeConfig.SEGMENT_COUNT, piece.getSegment()));
    }

    private long stageRemainingSeconds(Race race) {
        if (race == null || race.stageDeadlineAt <= 0L) {
            return 0L;
        }
        RelayProgress relay = race.currentStage <= RELAY_COUNT
                ? relayForStage(race, race.currentStage) : null;
        long target = relay != null && relay.pendingOpen
                ? relay.openAt : race.stageDeadlineAt;
        return Math.max(0L, (target - System.currentTimeMillis() + 999L) / 1000L);
    }

    private RelayProgress relayForStage(Race race, int stage) {
        if (race == null) {
            return null;
        }
        for (RelayProgress relay : race.relays) {
            if (relay.nodeNumber == stage) {
                return relay;
            }
        }
        return null;
    }

    public int getCurrentPieceIndex(UUID playerId) {
        Race race = raceForParticipant(playerId);
        Participant participant = race == null ? null : race.participants.get(playerId);
        return participant == null ? -1 : participant.pieceIndex;
    }

    public PkwsTrack.Piece getCurrentPiece(UUID playerId) {
        Race race = raceForParticipant(playerId);
        Participant participant = race == null ? null : race.participants.get(playerId);
        return participant == null ? null : currentPiece(race, participant);
    }

    /** Returns a safe copy of a participant's last valid PKWS checkpoint. */
    public Location getRespawnCheckpoint(UUID playerId) {
        Race race = raceForParticipant(playerId);
        Participant participant = race == null ? null : race.participants.get(playerId);
        Location checkpoint = participant == null ? null : participant.checkpoint;
        if (race == null || checkpoint == null || checkpoint.getWorld() == null
                || !race.worldName.equals(checkpoint.getWorld().getName())) {
            return null;
        }
        return checkpoint.clone();
    }

    /**
     * Sets the current generated start structure to state 0/1. Commands are
     * intentionally world-scoped and are rejected while a race is running.
     */
    public String setStartState(String worldName, boolean open) {
        if (worldName == null || !worldManager.isPkwsWorld(worldName)) {
            return "当前世界不是 PKWS 世界。";
        }
        Race race = races.computeIfAbsent(worldName, Race::new);
        if (race.state == State.RUNNING) {
            return "比赛进行中，不能切换起点状态。";
        }
        if (open) {
            // Opening follows the same configurable start countdown as the
            // player-triggered path (independent of the relay gate wait).
            return startStartCountdown(worldName, rangeConfig.getStartCountdownSeconds());
        }
        cancelCountdown(worldName);
        PkwsTrack track = trackStore.load(worldName);
        if (track == null) {
            return "当前世界没有有效的 PKWS 赛道记录。";
        }
        PkwsTrack.Piece start = findStart(track);
        if (start == null) {
            return "赛道记录中没有全局起点。";
        }
        if (!pasteNodeState(track, start, 0)) {
            return "起点状态 schematic 不完整或粘贴失败。";
        }
        trackStore.save(track);
        race.track = track;
        return "全局起点已关闭，倒计时已取消。";
    }

    /** Starts a main-thread, per-world countdown and closes the start first. */
    public String startStartCountdown(String worldName, int seconds) {
        if (worldName == null || !worldManager.isPkwsWorld(worldName)) {
            return "当前世界不是 PKWS 世界。";
        }
        if (!PkwsRangeConfig.isValidStartCountdownSeconds(seconds)) {
            return "起点开赛倒计时秒数必须是大于等于 1 的整数。";
        }
        Race race = races.computeIfAbsent(worldName, Race::new);
        if (race.state == State.RUNNING) {
            return "比赛进行中，不能启动开赛倒计时。";
        }
        PkwsTrack track = trackStore.load(worldName);
        if (track == null) {
            return "当前世界没有有效的 PKWS 赛道记录。";
        }
        PkwsTrack.Piece start = findStart(track);
        if (start == null) {
            return "赛道记录中没有全局起点。";
        }
        cancelCountdown(worldName);
        if (!pasteNodeState(track, start, 0)) {
            return "起点关闭态 schematic 不完整或粘贴失败。";
        }
        trackStore.save(track);
        race.track = track;
        Countdown countdown = new Countdown(worldName, track, seconds);
        countdowns.put(worldName, countdown);
        showCountdown(countdown);
        countdown.task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickCountdown(countdown), 20L, 20L);
        return "开赛倒计时已开始，共 " + seconds + " 秒。";
    }

    /** Cancels an administrative countdown before a generated track is cleared. */
    public void cancelStartCountdown(String worldName) {
        if (worldName != null) {
            cancelCountdown(worldName);
        }
    }

    public void shutdown() {
        for (Countdown countdown : new ArrayList<>(countdowns.values())) {
            cancelCountdown(countdown.worldName);
        }
        for (Race race : new ArrayList<>(races.values())) {
            cancelForceOpenTask(race);
            World world = Bukkit.getWorld(race.worldName);
            if (world != null) {
                restoreWorldPlayers(world, null);
            }
            if (race.state == State.RUNNING && race.track != null) {
                resetTrackForIdle(race.track);
            }
        }
        if (disconnectStore != null) {
            disconnectStore.clearAll();
        }
        // Plugin disable must not leave observer flight, invisibility or
        // hiding behind for players that are still online.
        for (UUID watcherId : new ArrayList<>(watcherWorlds.keySet())) {
            Player watcher = Bukkit.getPlayer(watcherId);
            if (watcher != null) {
                clearWatcherState(watcher);
            }
        }
        watcherWorlds.clear();
        races.clear();
        participantWorlds.clear();
        postRaceReturnPlayers.clear();
    }

    /** Clears a world-scoped race before its PKWS marker is removed. */
    public void onWorldUnmarked(String worldName) {
        cancelCountdown(worldName);
        Race race = races.get(worldName);
        if (race != null && race.state == State.RUNNING) {
            finishRace(race, null, "世界标记已移除，比赛结束。", null);
        } else {
            PkwsTrack track = trackStore.load(worldName);
            if (track != null) {
                resetTrackForIdle(track);
            }
        }
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            restoreWorldPlayers(world, null);
        }
        if (disconnectStore != null) {
            disconnectStore.clearWorld(worldName);
        }
        postRaceReturnPlayers.remove(worldName);
        participantWorlds.entrySet().removeIf(entry -> worldName.equals(entry.getValue()));
        watcherWorlds.entrySet().removeIf(entry -> worldName.equals(entry.getValue()));
        races.remove(worldName);
    }

    private void startRace(Race race, Player trigger) {
        startRace(race, trigger == null ? null : trigger.getWorld(), trigger);
    }

    private void startRace(Race race, World world, Player trigger) {
        if (race == null || world == null || race.state != State.IDLE) {
            return;
        }
        PkwsTrack track = loadTrack(race, trigger, true);
        if (track == null || track.getPieces().isEmpty()) {
            return;
        }

        race.track = track;
        race.state = State.RUNNING;
        race.matchId = UUID.randomUUID().toString();
        race.startedAt = System.currentTimeMillis();
        PkwsOrientation orientation = track.getOrientation() == null
                ? PkwsOrientation.NS : track.getOrientation();
        race.orientation = orientation;
        race.stageDurations = rangeConfig.snapshotTimes(orientation);
        race.currentStage = 1;
        race.stageStartedAt = race.startedAt;
        race.stageDeadlineAt = deadlineAfter(race.stageStartedAt,
                stageDuration(race, 1));
        race.warningSecondsSent.clear();
        race.finishing = false;
        race.population = 0;
        race.pendingWinner = null;
        race.participants.clear();
        race.relays.clear();
        if (disconnectStore != null) {
            disconnectStore.clearWorld(race.worldName);
        }

        for (Player player : world.getPlayers()) {
            PlayerMoveListener.clearSpecialMode(player);
            if (player.getGameMode() == GameMode.SPECTATOR) {
                makeWatcher(player);
                continue;
            }
            Participant participant = new Participant(player.getName());
            participant.pieceIndex = findReachedPiece(track, player.getLocation());
            PkwsTrack.Piece initialPiece = track.getPieces().get(
                    clampPieceIndex(track, participant.pieceIndex));
            participant.checkpoint = resolvePieceCheckpoint(player, initialPiece, false);
            participant.online = true;
            participant.lastLocation = player.getLocation().clone();
            race.participants.put(player.getUniqueId(), participant);
            participantWorlds.put(player.getUniqueId(), world.getName());
            // A player who is present when the race starts becomes a
            // contestant again, so any earlier observation state is retracted.
            clearWatcherState(player);
            player.setGameMode(GameMode.ADVENTURE);
            giveQuitItem(player);
        }

        race.population = race.participants.size();
        if (race.population == 0) {
            // Keep the existing empty-world guard for administrative starts.
            race.state = State.IDLE;
            race.matchId = null;
            race.startedAt = 0L;
            race.orientation = null;
            race.stageDurations = null;
            race.currentStage = 0;
            race.stageStartedAt = 0L;
            race.stageDeadlineAt = 0L;
            return;
        }
        race.singlePlayer = race.population == 1;
        postRaceReturnPlayers.remove(race.worldName);
        initializeRelays(race);

        // The trigger could only enter an open start zone. Reassert the
        // persisted open state and make relay state deterministic for this run.
        PkwsTrack.Piece start = findStart(track);
        if (start != null) {
            pasteNodeState(track, start, 1);
        }
        // Single-player races use exactly the same closed gates, stage limits
        // and life settlement as multiplayer races.
        setAllRelayStates(race, false);
        scheduleForceOpenTask(race);

        if (trigger != null) {
            broadcast(world, "第1阶段起点闸门已开放（玩家通过起点），阶段1计时开始。");
        }

        for (Player player : world.getPlayers()) {
            if (race.participants.containsKey(player.getUniqueId())) {
                Msg.send(player, "PKWS 已开始，参赛人数: " + race.population
                        + "，每人 3 条命。");
            }
        }
    }

    private void initializeRelays(Race race) {
        int node = 0;
        for (int index = 0; index < race.track.getPieces().size(); index++) {
            PkwsTrack.Piece piece = race.track.getPieces().get(index);
            if (piece.getType() != PkwsTrack.PieceType.RELAY) {
                continue;
            }
            node++;
            RelayProgress progress = new RelayProgress(node, index, piece);
            progress.open = "OPEN".equalsIgnoreCase(piece.getGateState());
            race.relays.add(progress);
        }
    }

    private PkwsTrack loadTrack(Race race, Player player, boolean notify) {
        if (race.state == State.RUNNING && race.track != null) {
            return race.track;
        }
        PkwsTrack track = trackStore.load(race.worldName);
        if (track == null && notify && player != null) {
            race.track = null;
            Msg.send(player, "赛道不存在，请联系管理员。");
        }
        // IDLE reloads the persisted record so clear -> regenerate cannot
        // leave a stale in-memory track from a previous generation.
        race.track = track;
        return race.track;
    }

    /**
     * PKWS ordinary LEVEL runtime. START/RELAY/END are deliberately not
     * handled here: their race-specific logic remains in the methods above.
     * The track index is the handoff source of truth; a completed level keeps
     * its per-player session in AWAITING_HANDOFF until the next LEVEL starts.
     */
    private void handleLevelProgress(Race race, Player player, Participant participant,
                                     Location from, Location to) {
        PkwsTrack.Piece active = activeLevelPiece(race, participant);
        if (active == null) {
            int nextIndex = findNextLevelIndex(race.track, participant.pieceIndex);
            if (nextIndex < 0) {
                return;
            }
            if (!canEnterLevel(race, participant, nextIndex)) {
                return;
            }
            PkwsTrack.Piece next = race.track.getPieces().get(nextIndex);
            boolean enteredStart = enteredPieceStartZone(next, from, to);
            boolean waitingAtStart = participant.levelSession != null
                    && participant.levelSession.isCompleted()
                    && isInPieceStartZone(next, to);
            if (enteredStart || waitingAtStart) {
                participant.pieceIndex = nextIndex;
                participant.activeLevelPieceIndex = nextIndex;
                startPkwsLevel(player, participant, next, true);
            }
            return;
        }

        // Complete before the region-exit branch, matching PKW's end-zone
        // precedence when the configured end point touches a region edge.
        if (enteredPieceEndZone(active, from, to)) {
            completePkwsLevelWithTimer(player, participant, active);
            return;
        }

        ParkourSession levelSession = participant.levelSession;
        boolean wasInsideRegion = levelSession != null && levelSession.isInsideRegion();
        boolean insideRegion = active.getRegion() != null && active.getRegion().contains(to);
        if (!wasInsideRegion && insideRegion) {
            // Match PKW: a side/top exit pauses the current level session;
            // re-entering the region arms one fresh start-zone transition.
            levelSession.setInsideRegion(true);
            levelSession.setInsideStart(false);
            levelSession.setPendingTitleAtStart(true);
            if (!levelSession.isStarted()) {
                levelSession.startTimer(System.currentTimeMillis());
            }
        } else if (wasInsideRegion && !insideRegion) {
            // Bottom falls are intercepted before this method. Side/top exits
            // keep the session, but stop the timer until the player returns.
            if (levelSession.isStarted()) {
                levelSession.stopTimer(System.currentTimeMillis());
            }
            levelSession.setInsideRegion(false);
            levelSession.setInsideStart(false);
        }

        if (!insideRegion) {
            return;
        }

        boolean fromInStart = isInPieceStartZone(active, from);
        boolean toInStart = isInPieceStartZone(active, to);
        if (!fromInStart) {
            levelSession.setInsideStart(false);
        }
        if (enteredPieceStartZone(active, from, to)
                && !levelSession.isInsideStart()
                && levelSession.isPendingTitleAtStart()) {
            // Re-entering the start zone after a region exit is a new PKW
            // attempt: reset the per-level timer and show the title once.
            if (levelSession.isSkipResetAtStartOnce()) {
                levelSession.setSkipResetAtStartOnce(false);
                if (!levelSession.isStarted()) {
                    levelSession.startTimer(System.currentTimeMillis());
                }
                startPkwsLevel(player, participant, active, false);
            } else {
                levelSession.resetTimer();
                levelSession.startTimer(System.currentTimeMillis());
                startPkwsLevel(player, participant, active, false);
            }
            levelSession.setPendingTitleAtStart(false);
            levelSession.setInsideStart(true);
        } else if (!toInStart && levelSession != null) {
            levelSession.setInsideStart(false);
        }
    }

    private PkwsTrack.Piece activeLevelPiece(Race race, Participant participant) {
        if (participant == null || participant.levelSession == null
                || participant.levelSession.isCompleted()
                || race == null || race.track == null) {
            return null;
        }
        int index = participant.activeLevelPieceIndex;
        if (index < 0 || index >= race.track.getPieces().size()) {
            return null;
        }
        PkwsTrack.Piece piece = race.track.getPieces().get(index);
        return piece.getType() == PkwsTrack.PieceType.LEVEL ? piece : null;
    }

    private void clearActiveLevel(Participant participant) {
        if (participant == null) {
            return;
        }
        if (participant.levelSession != null) {
            participant.levelSession.resetTimer();
        }
        participant.levelSession = null;
        participant.activeLevelPieceIndex = -1;
        clearPausedLevelState(participant);
    }

    private void clearPausedLevelState(Participant participant) {
        if (participant == null) {
            return;
        }
        participant.pausedLevelTimer = false;
        participant.pausedLevelElapsedMs = 0L;
        participant.pausedInsideLevelStart = false;
    }

    /**
     * A fall respawn is an internal return to the current level's checkpoint,
     * not a new physical OUT -> IN entry.  Mark the start zone as already
     * occupied so the teleport-generated movement event cannot replay the
     * title or alter the timer.  A real step out clears this flag in the normal
     * edge-triggered path, allowing a later re-entry to trigger again.
     */
    private void suppressLevelStartTriggerAfterFall(Participant participant,
                                                    PkwsTrack.Piece piece) {
        if (participant == null || piece == null
                || participant.activeLevelPieceIndex < 0
                || participant.activeLevelPieceIndex != participant.pieceIndex
                || participant.levelSession == null) {
            return;
        }
        participant.levelSession.setInsideStart(true);
    }

    private void removePostRaceReturnMarker(String worldName, UUID playerId) {
        Set<UUID> pending = postRaceReturnPlayers.get(worldName);
        if (pending == null) {
            return;
        }
        pending.remove(playerId);
        if (pending.isEmpty()) {
            postRaceReturnPlayers.remove(worldName);
        }
    }

    private void ensurePkwsDynamic(ParkourMap map, PkwsTrack.Piece piece) {
        if (dynamicService == null || map == null || piece == null
                || piece.getDeploymentId() == null) {
            return;
        }
        Deployment deployment = map.getDeployment(piece.getDeploymentId());
        if (deployment != null) {
            // START/RELAY/END are rejected by DynamicService; LEVEL is shared
            // with the ordinary PKW dynamic task implementation.
            dynamicService.onPlayerJoin(map, deployment);
        }
    }

    private int findNextLevelIndex(PkwsTrack track, int currentIndex) {
        if (track == null || track.getPieces().isEmpty()) {
            return -1;
        }
        int first = Math.max(0, currentIndex);
        if (first < track.getPieces().size()
                && track.getPieces().get(first).getType() == PkwsTrack.PieceType.LEVEL) {
            return first;
        }
        for (int index = first + 1; index < track.getPieces().size(); index++) {
            if (track.getPieces().get(index).getType() == PkwsTrack.PieceType.LEVEL) {
                return index;
            }
        }
        return -1;
    }

    private boolean canEnterLevel(Race race, Participant participant, int nextIndex) {
        if (race == null || race.track == null || participant == null) {
            return false;
        }
        int first = Math.max(0, participant.pieceIndex);
        for (int index = first; index < nextIndex; index++) {
            PkwsTrack.Piece piece = race.track.getPieces().get(index);
            if (piece.getType() != PkwsTrack.PieceType.RELAY) {
                continue;
            }
            RelayProgress relay = relayAt(race, index);
            if (relay != null && !relay.open) {
                return false;
            }
        }
        return true;
    }

    private RelayProgress relayAt(Race race, int pieceIndex) {
        if (race == null) {
            return null;
        }
        for (RelayProgress relay : race.relays) {
            if (relay.pieceIndex == pieceIndex) {
                return relay;
            }
        }
        return null;
    }

    private boolean enteredPieceStartZone(PkwsTrack.Piece piece,
                                          Location from, Location to) {
        return !isInPieceStartZone(piece, from) && isInPieceStartZone(piece, to);
    }

    private boolean enteredPieceEndZone(PkwsTrack.Piece piece,
                                        Location from, Location to) {
        return !isInPieceEndZone(piece, from) && isInPieceEndZone(piece, to);
    }

    private boolean isInsideAndLeavingPiece(PkwsTrack.Piece piece,
                                             Location from, Location to) {
        Region region = piece == null ? null : piece.getRegion();
        return region != null && region.contains(from) && !region.contains(to);
    }

    private void startPkwsLevel(Player player, Participant participant,
                                PkwsTrack.Piece piece, boolean fresh) {
        ParkourMap map = mapManager.getMap(piece.getSourceId());
        if (map == null) {
            return;
        }
        if (fresh) {
            if (participant.levelSession != null) {
                participant.levelSession.resetTimer();
            }
            clearPausedLevelState(participant);
            long now = System.currentTimeMillis();
            String deploymentId = piece.getDeploymentId() == null ? piece.getSourceId()
                    : piece.getDeploymentId();
            ParkourSession levelSession = new ParkourSession(player.getUniqueId(),
                    map.getId(), deploymentId, now);
            levelSession.setDeathLineY(piece.getRegion() == null
                    ? 0.0 : piece.getRegion().getMinY());
            Location start = resolvePieceCheckpoint(player, piece, false);
            levelSession.setCheckpoint(start == null ? null : start.clone());
            levelSession.setInsideRegion(true);
            levelSession.setInsideStart(true);
            levelSession.setPendingTitleAtStart(false);
            levelSession.setCompleted(false);
            levelSession.setState(SessionState.RUNNING);
            levelSession.startTimer(now);
            participant.levelSession = levelSession;
            participant.checkpoint = start == null ? null : start.clone();
        }
        ensurePkwsDynamic(map, piece);
        if (sessionManager != null) {
            sessionManager.sendLevelTitle(player, map);
        } else {
            sendLevelTitleFallback(player, map);
        }
    }

    private void completePkwsLevelWithTimer(Player player, Participant participant,
                                            PkwsTrack.Piece piece) {
        int completedIndex = participant.activeLevelPieceIndex;
        ParkourMap map = mapManager.getMap(piece.getSourceId());
        ParkourSession levelSession = participant.levelSession;
        long durationMs = 0L;
        if (levelSession != null) {
            if (levelSession.isStarted()) {
                levelSession.stopTimer(System.currentTimeMillis());
            }
            durationMs = levelSession.getElapsedMs(System.currentTimeMillis());
        }
        if (sessionManager != null) {
            sessionManager.sendLevelCompletion(player, map, durationMs);
        } else if (map != null) {
            Msg.send(player, "已通过 " + map.getTitle() + "（用时："
                    + club.sitmc.sitParkourWarrior.board.BoardRenderer.formatTime(durationMs) + "）。");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                    1.0f, 1.0f);
        }
        Location end = resolvePieceCheckpoint(player, piece, true);
        if (levelSession != null) {
            levelSession.setCompleted(true);
            levelSession.setState(SessionState.AWAITING_HANDOFF);
            levelSession.setInsideRegion(true);
            levelSession.setInsideStart(false);
            levelSession.setPendingTitleAtStart(false);
            levelSession.setSkipResetAtStartOnce(false);
            if (end != null) {
                levelSession.setCheckpoint(end.clone());
            }
        }
        if (end != null) {
            participant.checkpoint = end.clone();
        }
        // Match PKW's completion state while keeping the PKWS participant,
        // race-wide clock and relay progression alive for the next piece.
        cleanupPkwsEffects(player);
        clearPausedLevelState(participant);
        participant.pieceIndex = Math.max(participant.pieceIndex, completedIndex + 1);
    }

    /** Compatibility wrapper retained for callers from older integrations. */
    private void completePkwsLevel(Player player, Participant participant,
                                   PkwsTrack.Piece piece) {
        completePkwsLevelWithTimer(player, participant, piece);
        /*
        int completedIndex = participant.activeLevelPieceIndex;
        ParkourMap map = mapManager.getMap(piece.getSourceId());
        if (map != null) {
            String coloredTitle = map.getDifficulty().getTitleColor() + map.getTitle();
            Msg.send(player, "您已通过 " + coloredTitle + "。");
            if (sessionManager != null) {
                sessionManager.playLevelCompletionEffects(player);
            } else {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                        1.0f, 1.0f);
            }
        }
        // Clear only this player's sub-level state. The shared dynamic
        // deployment must remain alive for other racers and future players.
        clearActiveLevel(participant);
        int nextIndex = completedIndex + 1;
        participant.pieceIndex = Math.max(participant.pieceIndex, nextIndex);
        */
    }

    private void sendLevelTitleFallback(Player player, ParkourMap map) {
        String coloredTitle = map.getDifficulty().getTitleColor() + map.getTitle();
        player.sendTitle("", coloredTitle, 10, 70, 10);
        player.playSound(player.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM,
                1.0f, 1.0f);
        String subtitle = map.getSubtitle();
        if (subtitle != null && !subtitle.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendTitle("", map.getDifficulty().getTitleColor() + subtitle,
                            0, 50, 10);
                }
            }, 40L);
        }
    }

    private void recordRelayArrivals(Race race, Player player,
                                     Participant participant, Location location) {
        RelayProgress relay = relayForStage(race, race.currentStage);
        if (relay == null || relay.arrived.contains(player.getUniqueId())
                || !hasCompletedRelayApproach(race, participant, relay)
                || !isInPieceStartZone(relay.piece, location)) {
            return;
        }
        relay.arrived.add(player.getUniqueId());
        relay.arrivalOrder.add(player.getUniqueId());
        Location relayStart = resolvePieceCheckpoint(player, relay.piece, false);
        if (relayStart != null) {
            participant.checkpoint = relayStart;
        }
        if (!relay.open && !relay.pendingOpen && allAliveArrived(race, relay)) {
            triggerRelayOpen(race, relay, OpenReason.ALL_ARRIVED);
        }
    }

    private boolean hasCompletedRelayApproach(Race race, Participant participant,
                                              RelayProgress relay) {
        if (race == null || race.track == null || participant == null || relay == null
                || participant.pieceIndex < relay.pieceIndex) {
            return false;
        }
        int previousLevelIndex = -1;
        for (int index = relay.pieceIndex - 1; index >= 0; index--) {
            PkwsTrack.Piece piece = race.track.getPieces().get(index);
            if (piece.getType() == PkwsTrack.PieceType.RELAY
                    || piece.getType() == PkwsTrack.PieceType.START) {
                break;
            }
            if (piece.getType() == PkwsTrack.PieceType.LEVEL) {
                previousLevelIndex = index;
                break;
            }
        }
        return previousLevelIndex >= 0
                && participant.activeLevelPieceIndex == previousLevelIndex
                && participant.levelSession != null
                && participant.levelSession.isCompleted();
    }

    private boolean enforceClosedRelay(Race race, Player player,
                                       Location from, Location to) {
        if (race == null || player == null) {
            return false;
        }
        RelayProgress relay = relayForStage(race, race.currentStage);
        if (relay == null || relay.open
                || !relay.arrived.contains(player.getUniqueId())) {
            return false;
        }
        Region region = relay.piece.getRegion();
        if (region == null || !region.contains(from) || region.contains(to)) {
            return false;
        }
        teleportToRelayStart(player, relay);
        return true;
    }

    private void teleportToRelayStart(Player player, RelayProgress relay) {
        teleportToPieceStart(player, relay.piece);
    }

    private void teleportToPieceStart(Player player, PkwsTrack.Piece piece) {
        Location start = piece.getStart() == null
                ? null : piece.getStart().toLocation();
        if (start == null) {
            Region region = piece.getRegion();
            start = new Location(player.getWorld(), region.getMinX(),
                    region.getMinY(), region.getMinZ());
        }
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.teleport(start);
    }

    private boolean allAliveArrived(Race race, RelayProgress relay) {
        boolean hasOnlineAlive = false;
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            UUID playerId = entry.getKey();
            Participant participant = entry.getValue();
            if (!isOnlineAliveParticipant(race, playerId, participant)) {
                // Disconnected players deliberately do not hold a gate.
                continue;
            }
            hasOnlineAlive = true;
            if (!relay.arrived.contains(playerId)) {
                return false;
            }
        }
        return hasOnlineAlive;
    }

    private void checkRelayReadiness(Race race) {
        if (race.state != State.RUNNING || race.finishing) {
            return;
        }
        if (race.currentStage > RELAY_COUNT) {
            return;
        }
        RelayProgress relay = relayForStage(race, race.currentStage);
        if (relay != null && !relay.open && !relay.pendingOpen
                && allAliveArrived(race, relay)) {
            triggerRelayOpen(race, relay, OpenReason.ALL_ARRIVED);
        }
    }

    private void scheduleForceOpenTask(Race race) {
        cancelForceOpenTask(race);
        race.forceOpenTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> checkForceOpen(race, System.currentTimeMillis()), 1L, 20L);
    }

    private void checkForceOpen(Race race, long now) {
        if (race.state != State.RUNNING || race.finishing) {
            cancelForceOpenTask(race);
            return;
        }
        if (race.currentStage == PkwsRangeConfig.FINISH_TIME_SLOT
                && now >= race.stageDeadlineAt) {
            finishRace(race, null, "阶段8终点时限已到，无人到达终点，本局无胜者。", null);
            return;
        }
        RelayProgress relay = race.currentStage <= RELAY_COUNT
                ? relayForStage(race, race.currentStage) : null;
        if (relay == null || relay.open) {
            return;
        }
        if (!relay.pendingOpen && now >= race.stageDeadlineAt) {
            triggerRelayOpen(race, relay, OpenReason.TIME_UP);
        }
        if (relay.pendingOpen) {
            sendRelayOpenCountdown(race, relay, now);
            if (now >= relay.openAt) {
                openRelay(race, relay);
            }
        } else {
            sendLifeWarnings(race, relay, now);
        }
    }

    private void cancelForceOpenTask(Race race) {
        if (race.forceOpenTask != null) {
            race.forceOpenTask.cancel();
            race.forceOpenTask = null;
        }
    }

    private void triggerRelayOpen(Race race, RelayProgress relay, OpenReason reason) {
        if (race.state != State.RUNNING || race.finishing || relay.open
                || relay.pendingOpen || relay.nodeNumber != race.currentStage) {
            return;
        }
        relay.pendingOpen = true;
        relay.openReason = reason;
        relay.openCountdownSecondsSent.clear();
        // The relay gate keeps its own 3..5 second wait; it is deliberately
        // independent of the start-opening countdown.
        relay.openAt = deadlineAfter(System.currentTimeMillis(),
                rangeConfig.getRelayGateOpenDelaySeconds());
    }

    private void sendRelayOpenCountdown(Race race, RelayProgress relay, long now) {
        int remaining = (int) Math.max(0L,
                (relay.openAt - now + 999L) / 1000L);
        if (remaining < 1 || remaining > 3) {
            return;
        }
        World world = Bukkit.getWorld(race.worldName);
        if (world == null) {
            return;
        }
        if (!relay.openCountdownSecondsSent.add(remaining)) {
            return;
        }
        for (Player player : world.getPlayers()) {
            playCountdownSound(player, remaining);
        }
    }

    private void sendLifeWarnings(Race race, RelayProgress relay, long now) {
        int remaining = (int) Math.max(0L,
                (race.stageDeadlineAt - now + 999L) / 1000L);
        if (!LIFE_WARNING_SECONDS.contains(remaining)
                || !race.warningSecondsSent.add(remaining)) {
            return;
        }
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            Participant participant = entry.getValue();
            if (!isOnlineAliveParticipant(race, entry.getKey(), participant)
                    || relay.arrived.contains(entry.getKey())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                        1.0f, remaining == 5 ? 1.0f : 0.8f);
                Msg.send(player, "警告：距离第" + (relay.nodeNumber + 1)
                        + "阶段闸门触发仅剩 " + remaining
                        + " 秒，未及时到达节点将扣除 1 条生命！");
            }
        }
    }

    private void openRelay(Race race, RelayProgress relay) {
        if (race.state != State.RUNNING || race.finishing || relay.open
                || !relay.pendingOpen || relay.nodeNumber != race.currentStage) {
            return;
        }
        if (!pasteNodeState(race.track, relay.piece, 1)) {
            World world = Bukkit.getWorld(race.worldName);
            if (world != null) {
                broadcast(world, "第" + relay.nodeNumber
                        + "个节点开放失败，请联系管理员。");
            }
            relay.openAt = deadlineAfter(System.currentTimeMillis(), 1);
            return;
        }
        relay.open = true;
        relay.pendingOpen = false;
        relay.piece.setGateState("OPEN");
        long openedAt = System.currentTimeMillis();
        trackStore.save(race.track);

        World world = Bukkit.getWorld(race.worldName);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN,
                        2.0f, 1.0f);
            }
            broadcast(world, "第" + (relay.nodeNumber + 1) + "阶段闸门已开放（"
                    + relay.openReason.display + "）。");
        }
        applyLifeLosses(race, relay);
        saveAllOfflineSnapshots(race);
        if (race.participants.isEmpty()) {
            resetAfterNoParticipants(race, null);
        } else if (aliveOnlineCount(race) == 0) {
            finishRace(race, null, "本局无人可继续比赛，比赛结束。", null);
        } else {
            checkLastSurvivor(race);
            startNextStage(race, relay.nodeNumber + 1, openedAt);
            checkRelayReadiness(race);
        }
    }

    private void startNextStage(Race race, int stage, long openedAt) {
        race.currentStage = Math.max(1, Math.min(PkwsRangeConfig.SEGMENT_COUNT, stage));
        race.stageStartedAt = openedAt;
        race.stageDeadlineAt = deadlineAfter(openedAt,
                stageDuration(race, race.currentStage));
        race.warningSecondsSent.clear();
    }

    private int stageDuration(Race race, int stage) {
        if (race != null && race.stageDurations != null
                && stage >= 1 && stage <= race.stageDurations.length) {
            return race.stageDurations[stage - 1];
        }
        PkwsOrientation orientation = race == null || race.orientation == null
                ? PkwsOrientation.NS : race.orientation;
        return rangeConfig.getTimeSeconds(orientation, stage);
    }

    private long deadlineAfter(long startedAt, int seconds) {
        long millis = Math.max(0L, (long) seconds) * 1000L;
        return startedAt > Long.MAX_VALUE - millis ? Long.MAX_VALUE : startedAt + millis;
    }

    private void applyLifeLosses(Race race, RelayProgress relay) {
        List<UUID> victims = new ArrayList<>();
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            Participant participant = entry.getValue();
            // Offline participants are deliberately treated as not arrived at
            // the settlement instant, even if they touched the node earlier.
            if (!participant.eliminated && participant.lives > 0
                    && (!participant.online || !relay.arrived.contains(entry.getKey()))) {
                victims.add(entry.getKey());
            }
        }
        for (UUID playerId : victims) {
            Participant participant = race.participants.get(playerId);
            if (participant == null || participant.lives <= 0) {
                continue;
            }
            participant.lives--;
            Player player = Bukkit.getPlayer(playerId);
            String name = player != null ? player.getName()
                    : (participant.lastKnownName == null ? playerId.toString()
                    : participant.lastKnownName);
            if (player != null) {
                player.sendTitle("§c生命 -1", "§f剩余 " + participant.lives + " 条命",
                        5, 30, 10);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                        1.0f, 0.7f);
            }
            World world = Bukkit.getWorld(race.worldName);
            if (world != null) {
                broadcast(world, "玩家 " + name + " 未在阶段" + relay.nodeNumber
                        + "时限内到达节点，生命 -1，剩余 " + participant.lives + " 条。"
                        + (participant.lives == 0 ? "已淘汰。" : ""));
            }
            if (participant.lives == 0) {
                eliminate(race, playerId, name, false);
            } else if (!participant.online) {
                saveDisconnectedParticipant(race, participant, false);
            }
        }
    }

    private void eliminate(Race race, UUID playerId, String name, boolean announce) {
        Participant participant = race.participants.get(playerId);
        if (participant == null) {
            return;
        }
        participant.eliminated = true;
        participant.lives = 0;
        if (!participant.online || Bukkit.getPlayer(playerId) == null) {
            saveDisconnectedParticipant(race, participant, true);
            postRaceReturnPlayers.computeIfAbsent(race.worldName, ignored -> new HashSet<>())
                    .add(playerId);
        } else if (disconnectStore != null) {
            disconnectStore.remove(race.worldName, playerId);
        }
        race.participants.remove(playerId);
        participantWorlds.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            // An eliminated contestant keeps watching the race from inside the
            // world: flying, invisible and hidden, but no longer racing.
            makeWatcher(player);
            player.sendTitle("§4已淘汰", "§7无法继续比赛", 5, 40, 10);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND,
                    1.0f, 0.7f);
            Msg.send(player, "你已被淘汰，可以自由飞行观看比赛。");
        }
        if (announce) {
            World world = Bukkit.getWorld(race.worldName);
            if (world != null) {
                broadcast(world, "玩家 " + name + " 已淘汰。");
            }
        }
    }

    private void checkLastSurvivor(Race race) {
        if (race.state != State.RUNNING || race.finishing
                || race.singlePlayer || race.population <= 1) {
            return;
        }
        UUID onlyOnlineAlive = null;
        int onlineAlive = 0;
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            if (!isOnlineAliveParticipant(race, entry.getKey(), entry.getValue())) {
                continue;
            }
            onlineAlive++;
            onlyOnlineAlive = entry.getKey();
            if (onlineAlive > 1) {
                break;
            }
        }
        if (onlineAlive != 1) {
            // A disconnected participant remains in the race map for
            // reconnect recovery, but must not keep a stale winner pending.
            if (race.pendingWinner != null
                    && !isOnlineAliveParticipant(race, race.pendingWinner,
                    race.participants.get(race.pendingWinner))) {
                race.pendingWinner = null;
            }
            return;
        }
        UUID playerId = onlyOnlineAlive;
        if (playerId.equals(race.pendingWinner)) {
            return;
        }
        race.pendingWinner = playerId;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            giveSettlementItem(player);
            player.sendTitle("§6最后存活", "§f可继续比赛或立即结算", 5, 50, 10);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                    1.0f, 1.0f);
        }
        World world = Bukkit.getWorld(race.worldName);
        if (world != null) {
            String name = player != null ? player.getName() : playerId.toString();
            broadcast(world, name + " 成为最后存活者，可继续比赛或使用结算物品。");
        }
    }

    private void afterParticipantRemoved(Race race, UUID removedId) {
        if (race.state != State.RUNNING || race.finishing) {
            return;
        }
        if (race.participants.isEmpty()) {
            resetAfterNoParticipants(race, removedId);
            return;
        }
        if (aliveOnlineCount(race) == 0) {
            finishRace(race, null, "本局无人可继续比赛，比赛结束。", null);
            return;
        }
        checkRelayReadiness(race);
        checkLastSurvivor(race);
    }

    private void finishRace(Race race, UUID winner, String message,
                            UUID skipTeleport) {
        if (race == null || race.state != State.RUNNING || race.finishing) {
            return;
        }
        race.finishing = true;
        cancelForceOpenTask(race);

        String winnerName = winner == null ? null : playerName(winner);
        if (winner != null && race.population >= 5) {
            if (!winsStore.recordWin(winner, winnerName)) {
                plugin.getLogger().warning("[PKWS] 无法保存玩家胜场：" + winner);
            }
        }

        World world = Bukkit.getWorld(race.worldName);
        if (world != null) {
            if (winnerName != null) {
                broadcast(world, "§6" + winnerName + " 获胜。"
                        + (race.population >= 5 ? "胜场已记录。" : "本局不记录胜场。"));
            } else {
                broadcast(world, message);
            }
        }

        // Relay closed, start closed is the idle baseline for the next lobby.
        resetTrackForIdle(race.track);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                clearActionBar(player);
            }
        }
        // Every race settlement returns all players currently in the PKWS
        // world to its spawn and retracts observer flight and invisibility.
        // Do not skip the participant that triggered the settlement (for
        // example, the last player abandoning the race).
        restoreWorldPlayers(world, null);

        Set<UUID> disconnectedAtFinish = new HashSet<>();
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            if (!entry.getValue().online) {
                disconnectedAtFinish.add(entry.getKey());
            }
        }
        if (!disconnectedAtFinish.isEmpty()) {
            postRaceReturnPlayers.computeIfAbsent(race.worldName, ignored -> new HashSet<>())
                    .addAll(disconnectedAtFinish);
        }

        race.participants.keySet().forEach(participantWorlds::remove);
        race.participants.clear();
        if (disconnectStore != null) {
            disconnectStore.clearWorld(race.worldName);
        }
        race.relays.clear();
        race.pendingWinner = null;
        race.state = State.IDLE;
        race.track = null;
        race.matchId = null;
        race.startedAt = 0L;
        race.orientation = null;
        race.stageDurations = null;
        race.currentStage = 0;
        race.stageStartedAt = 0L;
        race.stageDeadlineAt = 0L;
        race.warningSecondsSent.clear();
        race.population = 0;
        race.finishing = false;
    }

    private void resetAfterNoParticipants(Race race, UUID skipTeleport) {
        if (race == null || race.state != State.RUNNING) {
            return;
        }
        finishRace(race, null,
                "所有玩家已离场，比赛结束。", skipTeleport);
    }

    private void removeParticipant(Race race, UUID playerId,
                                   boolean restoreAdventure) {
        if (race.participants.remove(playerId) == null) {
            return;
        }
        if (disconnectStore != null) {
            disconnectStore.remove(race.worldName, playerId);
        }
        participantWorlds.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            if (restoreAdventure) {
                player.setGameMode(GameMode.ADVENTURE);
            }
            removePkwsItems(player);
            cleanupPkwsEffects(player);
        }
    }

    private void teleportToCurrentStart(Player player, Participant participant,
                                        PkwsTrack.Piece piece) {
        cleanupPkwsEffects(player);
        Location start = participant == null || participant.checkpoint == null
                ? resolvePieceCheckpoint(player, piece, false)
                : participant.checkpoint.clone();
        if (start == null) {
            return;
        }
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.teleport(start);
        if (participant != null && participant.levelSession != null
                && !participant.levelSession.isCompleted()) {
            participant.levelSession.setInsideRegion(true);
            participant.levelSession.setInsideStart(true);
        }
    }

    private Location resolvePieceCheckpoint(Player player, PkwsTrack.Piece piece,
                                             boolean completed) {
        if (piece == null) {
            return null;
        }
        PointLocation point = completed ? piece.getEnd() : piece.getStart();
        Location location = point == null ? null : point.toLocation();
        if (location != null) {
            return location;
        }
        Region region = piece.getRegion();
        if (region == null || player == null || player.getWorld() == null) {
            return null;
        }
        return new Location(player.getWorld(),
                completed ? region.getMaxX() : region.getMinX(),
                completed ? region.getMaxY() : region.getMinY(),
                completed ? region.getMaxZ() : region.getMinZ());
    }

    private PkwsTrack.Piece currentPiece(Race race, Participant participant) {
        if (race == null || race.track == null || race.track.getPieces().isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(participant.pieceIndex,
                race.track.getPieces().size() - 1));
        return race.track.getPieces().get(index);
    }

    private int findReachedPiece(PkwsTrack track, Location location) {
        int reached = 0;
        for (int index = 0; index < track.getPieces().size(); index++) {
            PkwsTrack.Piece piece = track.getPieces().get(index);
            if (isInPieceStartZone(piece, location)
                    || isInPieceEndZone(piece, location)) {
                reached = index;
            }
        }
        return reached;
    }

    private Race raceForParticipant(UUID playerId) {
        String worldName = participantWorlds.get(playerId);
        return worldName == null ? null : races.get(worldName);
    }

    private int countArrived(Race race, RelayProgress relay) {
        int count = 0;
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            Participant participant = entry.getValue();
            if (isOnlineAliveParticipant(race, entry.getKey(), participant)
                    && relay.arrived.contains(entry.getKey())) {
                count++;
            }
        }
        return count;
    }

    private int aliveOnlineCount(Race race) {
        int count = 0;
        for (Map.Entry<UUID, Participant> entry : race.participants.entrySet()) {
            if (isOnlineAliveParticipant(race, entry.getKey(), entry.getValue())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns whether a participant is currently an active online contestant
     * in this race world.  The participant flag is persisted for reconnects,
     * while the Bukkit lookup protects settlement calculations from a quit or
     * world transfer callback that has not yet updated that flag.
     */
    private boolean isOnlineAliveParticipant(Race race, UUID playerId,
                                             Participant participant) {
        if (race == null || playerId == null || participant == null
                || !participant.online || participant.eliminated
                || participant.lives <= 0) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() && player.getWorld() != null
                && race.worldName.equals(player.getWorld().getName());
    }

    /** Rebuilds the PKWS-owned items for a participant restored into a race. */
    private void restoreParticipantItems(Race race, Player player) {
        if (race == null || player == null
                || !isOnlineAliveParticipant(race, player.getUniqueId(),
                race.participants.get(player.getUniqueId()))) {
            return;
        }
        removePkwsItems(player);
        // Slot 7 is reserved by the existing PKWS item contract.
        player.getInventory().setItem(7, ItemUtil.createQuitItem());
        if (player.getUniqueId().equals(race.pendingWinner)) {
            giveSettlementItem(player);
        }
    }

    private void giveQuitItem(Player player) {
        if (hasQuitItem(player)) {
            return;
        }
        player.getInventory().setItem(7, ItemUtil.createQuitItem());
    }

    private void giveSettlementItem(Player player) {
        if (hasSettlementItem(player)) {
            return;
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (player.getInventory().getItem(slot) == null) {
                player.getInventory().setItem(slot, ItemUtil.createSettlementItem());
                return;
            }
        }
        player.getInventory().setItem(8, ItemUtil.createSettlementItem());
    }

    private boolean hasQuitItem(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (ItemUtil.isQuitItem(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSettlementItem(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (ItemUtil.isSettlementItem(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private void removePkwsItems(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (ItemUtil.isPkwItem(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void teleportToSpawn(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        teleportToSpawn(player, player.getWorld());
    }

    private void teleportToSpawn(Player player, World world) {
        if (player == null || world == null) {
            return;
        }
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        player.teleport(world.getSpawnLocation());
    }

    private void restorePlayer(Player player, World world, boolean teleport) {
        if (player == null || world == null) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        clearWatcherState(player);
        removePkwsItems(player);
        cleanupPkwsEffects(player);
        if (teleport) {
            teleportToSpawn(player, world);
        }
    }

    /** One reset path for every online player in a PKWS world, including
     * spectators and observers.
     * Observer flight, invisibility and hiding are retracted together with the
     * rest of the race state. */
    private void restoreWorldPlayers(World world, UUID skipTeleport) {
        if (world == null) {
            return;
        }
        for (Player player : new ArrayList<>(world.getPlayers())) {
            restorePlayer(player, world,
                    skipTeleport == null || !skipTeleport.equals(player.getUniqueId()));
        }
        watcherWorlds.entrySet().removeIf(entry -> world.getName().equals(entry.getValue()));
    }

    private boolean isInPointZone(Location location, PointLocation point) {
        if (location == null || point == null || location.getWorld() == null
                || !location.getWorld().getName().equals(point.getWorldName())) {
            return false;
        }
        return Math.abs(location.getBlockX() - point.getBlockX()) <= 1
                && Math.abs(location.getBlockY() - point.getBlockY()) <= 1
                && Math.abs(location.getBlockZ() - point.getBlockZ()) <= 1;
    }

    private boolean isInPieceStartZone(PkwsTrack.Piece piece, Location location) {
        Deployment deployment = generatedDeployment(piece);
        return deployment != null
                ? deployment.isInStartZone(location)
                : piece != null && isInPointZone(location, piece.getStart());
    }

    private boolean isInPieceEndZone(PkwsTrack.Piece piece, Location location) {
        Deployment deployment = generatedDeployment(piece);
        return deployment != null
                ? deployment.isInEndZone(location)
                : piece != null && isInPointZone(location, piece.getEnd());
    }

    private Deployment generatedDeployment(PkwsTrack.Piece piece) {
        if (piece == null || piece.getDeploymentId() == null
                || piece.getDeploymentId().isBlank()) {
            return null;
        }
        ParkourMap map = mapManager.getMap(piece.getSourceId());
        return map == null ? null : map.getDeployment(piece.getDeploymentId());
    }

    private boolean isStartOpen(PkwsTrack.Piece start) {
        return start == null || !"CLOSED".equalsIgnoreCase(start.getGateState());
    }

    private PkwsTrack.Piece findStart(PkwsTrack track) {
        if (track == null) {
            return null;
        }
        for (PkwsTrack.Piece piece : track.getPieces()) {
            if (piece.getType() == PkwsTrack.PieceType.START) {
                return piece;
            }
        }
        return null;
    }

    private void resetTrackForIdle(PkwsTrack track) {
        if (track == null) {
            return;
        }
        for (PkwsTrack.Piece piece : track.getPieces()) {
            if (piece.getType() == PkwsTrack.PieceType.RELAY) {
                pasteNodeState(track, piece, 0);
            } else if (piece.getType() == PkwsTrack.PieceType.START) {
                pasteNodeState(track, piece, 0);
            }
        }
        trackStore.save(track);
    }

    private void setAllRelayStates(Race race, boolean open) {
        if (race == null || race.track == null) {
            return;
        }
        for (RelayProgress relay : race.relays) {
            boolean pasted = pasteNodeState(race.track, relay.piece, open ? 1 : 0);
            // Keep the logical barrier closed when the close paste fails; the
            // endpoint interception is the safety net for an imperfect gate.
            relay.open = open && pasted;
        }
        trackStore.save(race.track);
    }

    /**
     * Paste one state for a generated START/RELAY piece. DynamicService never
     * reaches these maps; this is the only gameplay state switch.
     */
    private boolean pasteNodeState(PkwsTrack track, PkwsTrack.Piece piece,
                                   int stateIndex) {
        if (track == null || piece == null
                || (piece.getType() != PkwsTrack.PieceType.START
                && piece.getType() != PkwsTrack.PieceType.RELAY)) {
            return false;
        }
        ParkourMap map = mapManager.getMap(piece.getSourceId());
        if (map == null || map.getDynamicData() == null) {
            return false;
        }
        List<DynamicState> states = map.getDynamicData().getStates();
        if (stateIndex < 0 || stateIndex >= states.size()) {
            return false;
        }
        DynamicState state = states.get(stateIndex);
        if (state.getFile() == null || state.getFile().isBlank()) {
            return false;
        }
        File file = new File(mapManager.getMapFolder(map), state.getFile());
        World world = Bukkit.getWorld(track.getWorldName());
        if (!file.isFile() || world == null || piece.getRegion() == null
                || !ensureChunks(world, piece.getRegion())) {
            return false;
        }
        boolean pasted = schematicService.pasteSchematic(file,
                new Location(world, piece.getRegion().getMinX(),
                        piece.getRegion().getMinY(), piece.getRegion().getMinZ()));
        if (pasted) {
            piece.setGateState(stateIndex == 1 ? "OPEN" : "CLOSED");
        }
        return pasted;
    }

    private boolean ensureChunks(World world, Region region) {
        int minChunkX = region.getMinX() >> 4;
        int maxChunkX = region.getMaxX() >> 4;
        int minChunkZ = region.getMinZ() >> 4;
        int maxChunkZ = region.getMaxZ() >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.getChunkAt(x, z);
                }
            }
        }
        return true;
    }

    private void broadcast(World world, String message) {
        if (world == null || message == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            Msg.send(player, message);
        }
    }

    private void clearActionBar(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
    }

    private void showCountdown(Countdown countdown) {
        World world = Bukkit.getWorld(countdown.worldName);
        if (world == null) {
            return;
        }
        int remaining = countdown.remaining;
        String subtitle = "剩余 " + remaining + " 秒";
        for (Player player : world.getPlayers()) {
            String observerColor = isRaceObserver(player, countdown.worldName)
                    ? "§c" : "";
            player.sendTitle(observerColor + "跑酷即将开始",
                    observerColor + subtitle, 0, 25, 5);
            playCountdownSound(player, remaining);
        }
    }

    private boolean isRaceObserver(Player player, String worldName) {
        return player != null && worldName != null
                && (player.getGameMode() == GameMode.SPECTATOR
                || worldName.equals(watcherWorlds.get(player.getUniqueId())));
    }

    private void playCountdownSound(Player player, int remaining) {
        if (remaining < 1 || remaining > 3) {
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f + (3 - remaining) * 0.15f);
    }

    private void tickCountdown(Countdown countdown) {
        if (countdowns.get(countdown.worldName) != countdown
                || !worldManager.isPkwsWorld(countdown.worldName)) {
            cancelCountdown(countdown.worldName);
            return;
        }
        Race race = races.computeIfAbsent(countdown.worldName, Race::new);
        if (race.state == State.RUNNING) {
            cancelCountdown(countdown.worldName);
            return;
        }
        countdown.remaining--;
        if (countdown.remaining > 0) {
            showCountdown(countdown);
            return;
        }
        cancelCountdown(countdown.worldName);
        PkwsTrack.Piece start = findStart(countdown.track);
        if (start == null || !pasteNodeState(countdown.track, start, 1)) {
            World world = Bukkit.getWorld(countdown.worldName);
            if (world != null) {
                broadcast(world, "起点开放失败，请联系管理员。");
            }
            return;
        }
        trackStore.save(countdown.track);
        race.track = countdown.track;
        World world = Bukkit.getWorld(countdown.worldName);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                String observerColor = isRaceObserver(player, countdown.worldName)
                        ? "§c" : "";
                player.sendTitle(observerColor + "PKWS 开始",
                        observerColor + "起点已开放", 5, 35, 10);
            }
            for (Player player : world.getPlayers()) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                        1.0f, 1.2f);
            }
            startRace(race, world, null);
            if (race.state == State.RUNNING) {
                broadcast(world, "第1阶段起点闸门已开放（开赛触发），阶段1计时开始。");
            } else {
                broadcast(world, "第1阶段起点闸门已开放（当前无参赛者，尚未开始计时）。");
            }
        }
    }

    private void cancelCountdown(String worldName) {
        Countdown countdown = countdowns.remove(worldName);
        if (countdown != null && countdown.task != null) {
            countdown.task.cancel();
        }
    }

    private String playerName(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return player.getName();
        }
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString() : name;
    }

    private static final class Race {
        private final String worldName;
        private State state = State.IDLE;
        private PkwsTrack track;
        private String matchId;
        private long startedAt;
        private PkwsOrientation orientation;
        /** Immutable-for-this-race snapshot of the orientation's 8 durations. */
        private int[] stageDurations;
        /** Current independently timed stage, 1..8. */
        private int currentStage;
        private long stageStartedAt;
        private long stageDeadlineAt;
        private final Set<Integer> warningSecondsSent = new HashSet<>();
        private boolean finishing;
        private boolean singlePlayer;
        private int population;
        private UUID pendingWinner;
        private BukkitTask forceOpenTask;
        private final Map<UUID, Participant> participants = new LinkedHashMap<>();
        private final List<RelayProgress> relays = new ArrayList<>();

        private Race(String worldName) {
            this.worldName = worldName;
        }
    }

    private static final class Participant {
        private int pieceIndex;
        /** Current ordinary PKWS LEVEL being played, or -1 when between levels. */
        private int activeLevelPieceIndex = -1;
        /** Reuses PKW's per-level timer/session state without registering PKW session data. */
        private ParkourSession levelSession;
        private int lives = INITIAL_LIVES;
        private final String lastKnownName;
        private boolean online;
        private boolean eliminated;
        private Location lastLocation;
        /** Per-participant checkpoint: current LEVEL start, completed end, or relay start. */
        private Location checkpoint;
        /** Snapshot of a running LEVEL timer while the participant is offline. */
        private boolean pausedLevelTimer;
        private long pausedLevelElapsedMs;
        private boolean pausedInsideLevelStart;

        private Participant(String lastKnownName) {
            this.lastKnownName = lastKnownName;
        }
    }

    private static final class RelayProgress {
        private final int nodeNumber;
        private final int pieceIndex;
        private final PkwsTrack.Piece piece;
        private final Set<UUID> arrived = new HashSet<>();
        private final List<UUID> arrivalOrder = new ArrayList<>();
        private boolean open;
        private boolean pendingOpen;
        private final Set<Integer> openCountdownSecondsSent = new HashSet<>();
        private long openAt;
        private OpenReason openReason;

        private RelayProgress(int nodeNumber, int pieceIndex,
                              PkwsTrack.Piece piece) {
            this.nodeNumber = nodeNumber;
            this.pieceIndex = pieceIndex;
            this.piece = piece;
        }
    }

    private enum OpenReason {
        ALL_ARRIVED("全员到达"),
        TIME_UP("时间到");

        private final String display;

        OpenReason(String display) {
            this.display = display;
        }
    }

    private static final class Countdown {
        private final String worldName;
        private final PkwsTrack track;
        private int remaining;
        private BukkitTask task;

        private Countdown(String worldName, PkwsTrack track, int remaining) {
            this.worldName = worldName;
            this.track = track;
            this.remaining = remaining;
        }
    }
}
