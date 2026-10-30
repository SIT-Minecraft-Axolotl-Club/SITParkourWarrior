package club.sitmc.sitParkourWarrior.visibility;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.listener.PlayerMoveListener;
import club.sitmc.sitParkourWarrior.pkws.PkwsRaceManager;
import club.sitmc.sitParkourWarrior.session.RunProgress;
import club.sitmc.sitParkourWarrior.session.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Proximity-based invisibility + glow for PKW runners, using potion effects.
 * Hysteresis: buffs at ≤6 blocks, remove at >9 blocks.
 *
 * <p>Spectators, PKWS race observers (including eliminated contestants) and
 * players carrying the Segment practice marker are excluded from the whole
 * check: they neither receive an outline nor count as a nearby runner.</p>
 */
public class VisibilityManager {

    private static final double BUFF_DISTANCE = 6.0;
    private static final double CLEAR_DISTANCE = 9.0;
    private static final int EFFECT_DURATION = 15; // ticks, refreshed each scan

    private final JavaPlugin plugin;
    private final PkwWorldManager pkwWorldManager;
    private final SessionManager sessionManager;
    private PkwsRaceManager pkwsRaceManager;

    /** Players we've applied effects to (so we only remove our own). */
    private final Set<UUID> managedPlayers = new HashSet<>();

    public VisibilityManager(JavaPlugin plugin, PkwWorldManager pkwWorldManager, SessionManager sessionManager) {
        this.plugin = plugin;
        this.pkwWorldManager = pkwWorldManager;
        this.sessionManager = sessionManager;
    }

    public void setPkwsRaceManager(PkwsRaceManager pkwsRaceManager) {
        this.pkwsRaceManager = pkwsRaceManager;
    }

    // ---- Periodic scan ----

    public void scanAndUpdate() {
        if (sessionManager == null) return;

        // An observer's permanent invisibility belongs to the race, and the
        // clear branch below drops the potion effect whoever applied it.  An
        // observer therefore never stays in our bookkeeping.
        releasePkwsObservers();

        // Collect eligible players from active runs only (not full server scan).
        // Spectator-mode players, Segment practice-marked players and PKWS race
        // observers are excluded: they neither receive nor count toward
        // proximity invisibility.
        List<Player> eligible = new ArrayList<>();
        for (java.util.UUID id : sessionManager.getActiveRunPlayerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!pkwWorldManager.isPkwWorld(p.getWorld())) continue;
            if (isExcludedFromProximity(p)) continue;
            eligible.add(p);
        }

        if (pkwsRaceManager != null) {
            for (UUID id : pkwsRaceManager.getParticipantIds()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !pkwWorldManager.isPkwsWorld(p.getWorld())
                        || isExcludedFromProximity(p)) {
                    continue;
                }
                eligible.add(p);
            }
        }

        // For each eligible player, check if any other is nearby
        for (Player p : eligible) {
            boolean hasNearby = false;
            for (Player other : eligible) {
                if (other == p) continue;
                if (other.getWorld() != p.getWorld()) continue;
                double dist = p.getLocation().distance(other.getLocation());
                if (dist <= BUFF_DISTANCE) {
                    hasNearby = true;
                    break;
                }
            }

            UUID id = p.getUniqueId();
            if (hasNearby) {
                // Apply / refresh effects
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, EFFECT_DURATION, 0, false, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, EFFECT_DURATION, 0, false, false, true));
                managedPlayers.add(id);
            } else if (managedPlayers.contains(id)) {
                // Check if far enough to clear (hysteresis)
                boolean allFar = true;
                for (Player other : eligible) {
                    if (other == p) continue;
                    if (other.getWorld() != p.getWorld()) continue;
                    if (p.getLocation().distance(other.getLocation()) <= CLEAR_DISTANCE) {
                        allFar = false;
                        break;
                    }
                }
                if (allFar) {
                    p.removePotionEffect(PotionEffectType.INVISIBILITY);
                    p.removePotionEffect(PotionEffectType.GLOWING);
                    managedPlayers.remove(id);
                }
            }
        }
    }

    // ---- Excluded states (spectator / practice / PKWS observer) ----

    /**
     * True when the player must stay out of the proximity visibility check.
     *
     * <p>Covers:</p>
     * <ul>
     *   <li>spectator-mode players (any source: /spec, /gamemode spectator, …)</li>
     *   <li>players carrying the Segment practice marker (sitsegment:special_mode)</li>
     *   <li>PKWS race observers while their race is running</li>
     * </ul>
     */
    private boolean isExcludedFromProximity(Player player) {
        if (player == null) {
            return true;
        }
        if (PlayerMoveListener.isExemptFromPkw(player)) {
            return true;
        }
        return isPkwsRaceObserver(player);
    }

    /**
     * True when the player watches a running PKWS race from inside its world
     * instead of taking part in it: the in-place flying observers and the
     * already eliminated contestants — both no longer registered as
     * participants — are covered, while an active contestant is not.
     *
     * <p>A self-chosen spectator is already covered by the spectator check in
     * {@link PlayerMoveListener#isExemptFromPkw}.</p>
     */
    private boolean isPkwsRaceObserver(Player player) {
        if (player == null || pkwsRaceManager == null || player.getWorld() == null
                || !pkwWorldManager.isPkwsWorld(player.getWorld())) {
            return false;
        }
        if (!pkwsRaceManager.isRunning(player.getWorld().getName())) {
            return false;
        }
        return !pkwsRaceManager.isParticipant(player.getUniqueId());
    }

    /**
     * Drops the bookkeeping entry (never the effect) of any player that turned
     * into a PKWS observer, so a later distance change cannot clear the
     * invisibility the race gave him.
     */
    private void releasePkwsObservers() {
        if (managedPlayers.isEmpty() || pkwsRaceManager == null) {
            return;
        }
        for (UUID id : new ArrayList<>(managedPlayers)) {
            Player tracked = Bukkit.getPlayer(id);
            if (tracked != null && isPkwsRaceObserver(tracked)) {
                managedPlayers.remove(id);
            }
        }
    }

    // ---- Cleanup ----

    /** Remove our effects from a single player. */
    public void cleanupPlayer(Player player) {
        UUID id = player.getUniqueId();
        if (managedPlayers.remove(id)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            player.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    /** Remove our effects from all players. */
    public void cleanupAll() {
        for (UUID id : new HashSet<>(managedPlayers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
                p.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        managedPlayers.clear();
    }
}
