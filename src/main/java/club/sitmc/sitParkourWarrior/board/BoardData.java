package club.sitmc.sitParkourWarrior.board;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Persistent data for a single leaderboard display board.
 */
public class BoardData {

    private final String worldName;
    private final String tier;       // standard / advance / expect / countdown
    private final double x, y, z;
    private UUID entityUuid;         // TextDisplay entity UUID, null if not spawned yet

    public BoardData(String worldName, String tier, Location loc) {
        this.worldName = worldName;
        this.tier = tier;
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
    }

    public BoardData(String worldName, String tier, double x, double y, double z, UUID entityUuid) {
        this.worldName = worldName;
        this.tier = tier;
        this.x = x;
        this.y = y;
        this.z = z;
        this.entityUuid = entityUuid;
    }

    public String getWorldName() { return worldName; }
    public String getTier()      { return tier; }
    public double getX()         { return x; }
    public double getY()         { return y; }
    public double getZ()         { return z; }
    public UUID getEntityUuid()  { return entityUuid; }

    public void setEntityUuid(UUID uuid) { this.entityUuid = uuid; }

    public Location toLocation() {
        World w = org.bukkit.Bukkit.getWorld(worldName);
        return w != null ? new Location(w, x, y, z) : null;
    }

    /** 10-block cubic detection range. */
    public boolean isInRange(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) return false;
        return Math.abs(loc.getX() - x) <= 10
                && Math.abs(loc.getY() - y) <= 10
                && Math.abs(loc.getZ() - z) <= 10;
    }

    public double distanceSquared(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) return Double.MAX_VALUE;
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public String tierDisplayName() {
        switch (tier) {
            case "standard":  return "标准榜";
            case "advance":   return "进阶榜";
            case "expect":    return "卓越榜";
            case "countdown": return "得分榜";
            default:          return tier;
        }
    }
}
