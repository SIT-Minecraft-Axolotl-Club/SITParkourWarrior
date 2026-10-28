package club.sitmc.sitParkourWarrior.map;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Deployment {
    private final String id;
    private final Region region;
    private final PointLocation start;
    private final PointLocation end;
    private final List<PointLocation> forkBranchPoints;
    private final PointLocation startPos1;
    private final PointLocation startPos2;
    private final PointLocation endPos1;
    private final PointLocation endPos2;

    public Deployment(String id, Region region, Location start, Location end) {
        this(id, region, PointLocation.fromLocation(start), PointLocation.fromLocation(end),
                Collections.emptyList(), null, null, null, null);
    }

    public Deployment(String id, Region region, PointLocation start, PointLocation end) {
        this(id, region, start, end, Collections.emptyList(), null, null, null, null);
    }

    public Deployment(String id, Region region, PointLocation start, PointLocation end,
                      List<PointLocation> forkBranchPoints) {
        this(id, region, start, end, forkBranchPoints, null, null, null, null);
    }

    public Deployment(String id, Region region, PointLocation start, PointLocation end,
                      List<PointLocation> forkBranchPoints,
                      PointLocation startPos1, PointLocation startPos2,
                      PointLocation endPos1, PointLocation endPos2) {
        this.id = id;
        this.region = region;
        this.start = start;
        this.end = end;
        this.forkBranchPoints = Collections.unmodifiableList(new ArrayList<>(forkBranchPoints));
        this.startPos1 = startPos1;
        this.startPos2 = startPos2;
        this.endPos1 = endPos1;
        this.endPos2 = endPos2;
    }

    public String getId() { return id; }
    public Region getRegion() { return region; }
    public PointLocation getStart() { return start; }
    public Location getStartLocation() { return start != null ? start.toLocation() : null; }
    public PointLocation getEnd() { return end; }
    public Location getEndLocation() { return end != null ? end.toLocation() : null; }
    public List<PointLocation> getForkBranchPoints() { return forkBranchPoints; }
    public PointLocation getStartPos1() { return startPos1; }
    public PointLocation getStartPos2() { return startPos2; }
    public PointLocation getEndPos1()   { return endPos1; }
    public PointLocation getEndPos2()   { return endPos2; }

    public boolean hasCustomStartZone() {
        return startPos1 != null && startPos2 != null && startPos1.hasWorld() && startPos2.hasWorld();
    }

    public boolean hasCustomEndZone() {
        return endPos1 != null && endPos2 != null && endPos1.hasWorld() && endPos2.hasWorld();
    }

    public boolean isInStartZone(Location loc) {
        if (!hasCustomStartZone()) {
            Location s = getStartLocation();
            if (s == null) return false;
            return isNear(loc, s);
        }
        return inAabb(loc, startPos1, startPos2);
    }

    public boolean isInEndZone(Location loc) {
        if (!hasCustomEndZone()) {
            Location e = getEndLocation();
            if (e == null) return false;
            return isNear(loc, e);
        }
        return inAabb(loc, endPos1, endPos2);
    }

    private static boolean isNear(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().getName().equals(b.getWorld().getName())) return false;
        return Math.abs(a.getBlockX() - b.getBlockX()) <= 1
                && Math.abs(a.getBlockY() - b.getBlockY()) <= 1
                && Math.abs(a.getBlockZ() - b.getBlockZ()) <= 1;
    }

    private static boolean inAabb(Location loc, PointLocation p1, PointLocation p2) {
        int minX = Math.min(p1.getBlockX(), p2.getBlockX());
        int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
        int minY = Math.min(p1.getBlockY(), p2.getBlockY());
        int maxY = Math.max(p1.getBlockY(), p2.getBlockY());
        int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        return bx >= minX && bx <= maxX && by >= minY && by <= maxY && bz >= minZ && bz <= maxZ;
    }
}
