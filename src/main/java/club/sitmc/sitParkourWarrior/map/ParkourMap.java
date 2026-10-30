package club.sitmc.sitParkourWarrior.map;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ParkourMap {
    private final String id;
    private String title;
    private String subtitle;
    private Difficulty difficulty = Difficulty.EASY;
    /** PKWS metadata is deliberately separate from the legacy PKW difficulty. */
    private PkwsOrientation pkwsOrientation;
    private Integer pkwsDifficulty;
    private int pkwsWeight;
    private boolean pkwsMetadataPresent;
    private Region region;
    private PointLocation start;
    private PointLocation end;
    private PointLocation startPos1;
    private PointLocation startPos2;
    private PointLocation endPos1;
    private PointLocation endPos2;
    private final DynamicData dynamicData = new DynamicData();
    private boolean deployed;
    private boolean particlesEnabled = true;
    private boolean soundEnabled = true;
    private final List<Deployment> deployments = new ArrayList<>();
    private NodeType nodeType = NodeType.LEVEL;
    private EndTier endTier = EndTier.NORMAL;
    private final List<PointLocation> forkBranchPoints = new ArrayList<>();

    public ParkourMap(String id) {
        this.id = id;
        this.title = id;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    public PkwsOrientation getPkwsOrientation() {
        return pkwsOrientation;
    }

    public void setPkwsOrientation(PkwsOrientation orientation) {
        this.pkwsOrientation = orientation;
        this.pkwsMetadataPresent = true;
    }

    public Integer getPkwsDifficulty() {
        return pkwsDifficulty;
    }

    public void setPkwsDifficulty(Integer difficulty) {
        this.pkwsDifficulty = difficulty != null && difficulty >= 1 && difficulty <= 16
                ? difficulty : null;
        this.pkwsMetadataPresent = true;
    }

    public int getPkwsWeight() {
        return pkwsWeight;
    }

    public void setPkwsWeight(int weight) {
        this.pkwsWeight = Math.max(0, weight);
        this.pkwsMetadataPresent = true;
    }

    /** True after a PKWS field was loaded or explicitly edited. */
    public boolean hasPkwsMetadata() {
        return pkwsMetadataPresent;
    }

    /**
     * Applies values read from disk without turning a legacy map save into a
     * new PKWS metadata write.  The caller has already validated the values.
     */
    public void loadPkwsMetadata(PkwsOrientation orientation, Integer difficulty,
                                 int weight, boolean present) {
        this.pkwsOrientation = orientation;
        this.pkwsDifficulty = difficulty;
        this.pkwsWeight = Math.max(0, weight);
        this.pkwsMetadataPresent = present;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public PointLocation getStart() {
        return start;
    }

    public void setStart(Location start) {
        this.start = PointLocation.fromLocation(start);
    }

    public void setStart(PointLocation start) {
        this.start = start;
    }

    public Location getStartLocation() {
        return start != null ? start.toLocation() : null;
    }

    public PointLocation getEnd() {
        return end;
    }

    public void setEnd(Location end) {
        this.end = PointLocation.fromLocation(end);
    }

    public void setEnd(PointLocation end) {
        this.end = end;
    }

    public Location getEndLocation() {
        return end != null ? end.toLocation() : null;
    }

    public DynamicData getDynamicData() {
        return dynamicData;
    }

    public boolean isDynamicEnabled() {
        return dynamicData.isEnabled();
    }

    public boolean isDeployed() {
        return deployed || !deployments.isEmpty();
    }

    public void setDeployed(boolean deployed) {
        this.deployed = deployed;
    }

    public boolean isParticlesEnabled() {
        return particlesEnabled;
    }

    public void setParticlesEnabled(boolean particlesEnabled) {
        this.particlesEnabled = particlesEnabled;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean soundEnabled) {
        this.soundEnabled = soundEnabled;
    }

    public List<Deployment> getDeployments() {
        return Collections.unmodifiableList(deployments);
    }

    public void clearDeployments() {
        deployments.clear();
    }

    public void addDeployment(Deployment deployment) {
        if (deployment == null) {
            return;
        }
        deployments.add(deployment);
    }

    public boolean removeDeployment(String deploymentId) {
        if (deploymentId == null) {
            return false;
        }
        return deployments.removeIf(d -> deploymentId.equals(d.getId()));
    }

    public Deployment getDeployment(String deploymentId) {
        if (deploymentId == null) {
            return null;
        }
        for (Deployment deployment : deployments) {
            if (deploymentId.equals(deployment.getId())) {
                return deployment;
            }
        }
        return null;
    }

    public Deployment findDeploymentByLocation(Location location) {
        if (location == null) {
            return null;
        }
        for (Deployment deployment : deployments) {
            Region region = deployment.getRegion();
            if (region != null && region.contains(location)) {
                return deployment;
            }
        }
        return null;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType != null ? nodeType : NodeType.LEVEL;
    }

    public EndTier getEndTier() {
        return endTier;
    }

    public void setEndTier(EndTier endTier) {
        this.endTier = endTier != null ? endTier : EndTier.NORMAL;
    }

    public List<PointLocation> getForkBranchPoints() {
        return forkBranchPoints;
    }

    public PointLocation getStartPos1() { return startPos1; }
    public void setStartPos1(PointLocation p) { this.startPos1 = p; }
    public PointLocation getStartPos2() { return startPos2; }
    public void setStartPos2(PointLocation p) { this.startPos2 = p; }
    public PointLocation getEndPos1()   { return endPos1; }
    public void setEndPos1(PointLocation p)   { this.endPos1 = p; }
    public PointLocation getEndPos2()   { return endPos2; }
    public void setEndPos2(PointLocation p)   { this.endPos2 = p; }

    /** True if both pos1 and pos2 are set for start. */
    public boolean hasCustomStartZone() {
        return startPos1 != null && startPos2 != null && startPos1.hasWorld() && startPos2.hasWorld();
    }

    /** True if both pos1 and pos2 are set for end. */
    public boolean hasCustomEndZone() {
        return endPos1 != null && endPos2 != null && endPos1.hasWorld() && endPos2.hasWorld();
    }

    /**
     * Check if a location falls within the start zone.
     * Uses AABB if pos1/pos2 are set, otherwise 3x3x3 around the start center.
     */
    public boolean isInStartZone(Location loc) {
        if (!hasCustomStartZone()) {
            Location s = getStartLocation();
            if (s == null) return false;
            return isNear(loc, s);
        }
        return inAabb(loc, startPos1, startPos2);
    }

    /**
     * Check if a location falls within the end zone.
     * Uses AABB if pos1/pos2 are set, otherwise 3x3x3 around the end center.
     */
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
