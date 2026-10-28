package club.sitmc.sitParkourWarrior.map;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinate utility for resolving template fork-branch-points to
 * real-world locations based on a deployment's offset.
 */
public class CourseLinker {

    private CourseLinker() {
        // Utility class — no instantiation.
    }

    /**
     * Resolve fork-branch-points to real-world locations.
     * If the deployment already stores translated fork-branch-points
     * (FORK nodes deployed after the type-aware deploy fix), use them
     * directly. Otherwise fall back to offset-based translation using
     * the deployment start and template start.
     */
    public static List<Location> resolveForkBranchPoints(ParkourMap map, Deployment deployment) {
        List<Location> resolved = new ArrayList<>();

        // Fast path: deployment carries pre-computed world coordinates.
        List<PointLocation> stored = deployment.getForkBranchPoints();
        if (!stored.isEmpty()) {
            for (PointLocation fp : stored) {
                Location loc = fp.toLocation();
                if (loc != null) {
                    resolved.add(loc);
                }
            }
            return resolved;
        }

        // Fallback: offset-based translation (backward compat / non-FORK).
        PointLocation templateStart = map.getStart();
        PointLocation depStart = deployment.getStart();
        if (templateStart == null || depStart == null || !depStart.hasWorld()) {
            return resolved;
        }
        Location depStartLoc = depStart.toLocation();
        if (depStartLoc == null || depStartLoc.getWorld() == null) {
            return resolved;
        }
        World world = depStartLoc.getWorld();
        int offsetX = depStart.getBlockX() - templateStart.getBlockX();
        int offsetY = depStart.getBlockY() - templateStart.getBlockY();
        int offsetZ = depStart.getBlockZ() - templateStart.getBlockZ();
        for (PointLocation template : map.getForkBranchPoints()) {
            resolved.add(new Location(world,
                    template.getX() + offsetX,
                    template.getY() + offsetY,
                    template.getZ() + offsetZ));
        }
        return resolved;
    }
}
