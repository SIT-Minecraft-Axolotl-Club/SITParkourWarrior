package club.sitmc.sitParkourWarrior.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A world-aware point that stores a worldName string instead of a World reference.
 * Survives world unload/rename cycles without data loss.
 */
public class PointLocation {
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public PointLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * Resolve to a Bukkit Location. Returns null if the world is not loaded.
     */
    public Location toLocation() {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Create from a Bukkit Location whose world must be non-null.
     */
    public static PointLocation fromLocation(Location loc) {
        if (loc == null) {
            return null;
        }
        String wn = loc.getWorld() != null ? loc.getWorld().getName() : null;
        return new PointLocation(wn, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /**
     * Create from a Bukkit Location, using fallbackWorldName when the location's world is null.
     */
    public static PointLocation fromLocation(Location loc, String fallbackWorldName) {
        if (loc == null) {
            return null;
        }
        String wn = loc.getWorld() != null ? loc.getWorld().getName() : fallbackWorldName;
        return new PointLocation(wn, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public boolean hasWorld() {
        return worldName != null && !worldName.isBlank();
    }

    // ---- field accessors (mirror Location's API for drop-in compatibility) ----

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public int getBlockX() {
        return (int) Math.floor(x);
    }

    public int getBlockY() {
        return (int) Math.floor(y);
    }

    public int getBlockZ() {
        return (int) Math.floor(z);
    }
}
