package club.sitmc.sitParkourWarrior.course;

import club.sitmc.sitParkourWarrior.config.PkwWorldManager;
import club.sitmc.sitParkourWarrior.map.Deployment;
import club.sitmc.sitParkourWarrior.map.MapManager;
import club.sitmc.sitParkourWarrior.map.NodeType;
import club.sitmc.sitParkourWarrior.map.ParkourMap;
import club.sitmc.sitParkourWarrior.map.PointLocation;
import club.sitmc.sitParkourWarrior.map.Region;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyses deployed nodes within each PKW world independently and writes
 * a multi-world course.yml keyed by world name.
 */
public class CourseLayoutAnalyzer {

    private final MapManager mapManager;
    private final PkwWorldManager pkwWorldManager;
    private final File dataFolder;

    /** In-memory cache: worldName → list of branch bindings (for runtime queries). */
    private final java.util.Map<String, java.util.List<BranchBindingInfo>> branchBindingsByWorld = new LinkedHashMap<>();

    /** In-memory cache: worldName → (mapId:deploymentId → LevelRoleInfo). */
    private final java.util.Map<String, java.util.Map<String, LevelRoleInfo>> levelRoleCache = new LinkedHashMap<>();

    public CourseLayoutAnalyzer(MapManager mapManager, PkwWorldManager pkwWorldManager, File dataFolder) {
        this.mapManager = mapManager;
        this.pkwWorldManager = pkwWorldManager;
        this.dataFolder = dataFolder;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /** Recompute and persist a single PKW world. Other worlds are untouched. */
    public void recomputeWorld(String worldName) {
        java.util.List<DeployedNode> nodes = collectDeployedForWorld(worldName);
        AnalysisResult result = analyze(worldName, nodes);
        cacheBindings(worldName, result);
        YamlConfiguration config = loadExistingCourseYml();
        writeWorldSection(config, worldName, nodes, result);
        saveCourseYml(config);
    }

    /** Full recompute across all PKW worlds. Overwrites the entire course.yml. */
    public void recomputeAllPkwWorlds() {
        YamlConfiguration config = new YamlConfiguration();
        branchBindingsByWorld.clear();
        for (String worldName : pkwWorldManager.getWorlds()) {
            java.util.List<DeployedNode> nodes = collectDeployedForWorld(worldName);
            AnalysisResult result = analyze(worldName, nodes);
            cacheBindings(worldName, result);
            writeWorldSection(config, worldName, nodes, result);
        }
        saveCourseYml(config);
    }

    /** Remove a single world's section from course.yml (world is no longer PKW). */
    public void removeWorld(String worldName) {
        branchBindingsByWorld.remove(worldName);
        YamlConfiguration config = loadExistingCourseYml();
        config.set("worlds." + worldName, null);
        saveCourseYml(config);
    }

    /**
     * Look up the FORK binding for a BRANCH_END from the in-memory cache.
     * Triggers a recompute on cache miss.
     */
    public BranchBindingInfo getBranchBinding(String worldName, String branchEndMapId, String branchEndDepId) {
        java.util.List<BranchBindingInfo> list = branchBindingsByWorld.get(worldName);
        if ((list == null || list.isEmpty()) && pkwWorldManager.isPkwWorld(worldName)) {
            recomputeWorld(worldName);
            list = branchBindingsByWorld.get(worldName);
        }
        if (list == null) return null;
        for (BranchBindingInfo info : list) {
            if (info.branchEndMapId.equals(branchEndMapId) && info.branchEndDepId.equals(branchEndDepId)) {
                return info;
            }
        }
        return null;
    }

    private void cacheBindings(String worldName, AnalysisResult result) {
        java.util.List<BranchBindingInfo> list = new ArrayList<>();
        for (BranchBinding bb : result.branches) {
            list.add(new BranchBindingInfo(
                    bb.branchEndMapId, bb.branchEndDepId,
                    bb.forkMapId, bb.forkDepId,
                    bb.forkPoint.clone()));
        }
        branchBindingsByWorld.put(worldName, list);

        java.util.Map<String, LevelRoleInfo> lrMap = new LinkedHashMap<>();
        for (LevelEntry e : result.levels) {
            lrMap.put(e.mapId + ":" + e.depId, new LevelRoleInfo(
                    e.role, e.order,
                    e.branchEndMapId, e.branchEndDepId,
                    e.globalEndMapId, e.globalEndDepId, e.endTier));
        }
        levelRoleCache.put(worldName, lrMap);
    }

    // ---------------------------------------------------------------
    // Data gathering — only deployed nodes in one specific world
    // ---------------------------------------------------------------

    private java.util.List<DeployedNode> collectDeployedForWorld(String worldName) {
        java.util.List<DeployedNode> list = new ArrayList<>();
        for (ParkourMap map : mapManager.getMaps().values()) {
            // PKWS nodes have their own future track model.  They must never
            // enter the legacy PKW course.yml or its runtime bindings.
            if (map.getNodeType().isPkwsType()) {
                continue;
            }
            for (Deployment dep : map.getDeployments()) {
                String w = inferWorld(map, dep);
                if (w != null && w.equals(worldName)) {
                    list.add(new DeployedNode(map, dep, worldName));
                }
            }
        }
        return list;
    }

    private String inferWorld(ParkourMap map, Deployment dep) {
        Region region = dep.getRegion();
        if (region != null && region.getWorldName() != null && !region.getWorldName().isBlank()) {
            return region.getWorldName();
        }
        PointLocation start = dep.getStart();
        if (start != null && start.hasWorld()) {
            return start.getWorldName();
        }
        PointLocation end = dep.getEnd();
        if (end != null && end.hasWorld()) {
            return end.getWorldName();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Deployed node wrapper
    // ---------------------------------------------------------------

    private static class DeployedNode {
        final ParkourMap map;
        final Deployment dep;
        final String worldName;

        DeployedNode(ParkourMap map, Deployment dep, String worldName) {
            this.map = map;
            this.dep = dep;
            this.worldName = worldName;
        }

        NodeType type() { return map.getNodeType(); }
        String mapId()   { return map.getId(); }
        String depId()   { return dep.getId(); }

        Location startLoc() {
            PointLocation s = dep.getStart();
            return toLocation(s);
        }

        Location endLoc() {
            PointLocation e = dep.getEnd();
            return toLocation(e);
        }

        java.util.List<Location> forkPointLocs() {
            java.util.List<Location> list = new ArrayList<>();
            for (PointLocation fp : dep.getForkBranchPoints()) {
                Location loc = toLocation(fp);
                if (loc != null) list.add(loc);
            }
            return list;
        }

        Location regionCenter() {
            Region r = dep.getRegion();
            if (r == null) return null;
            Location end = endLoc();
            Location start = startLoc();
            World w = null;
            if (end != null) w = end.getWorld();
            else if (start != null) w = start.getWorld();
            else {
                w = Bukkit.getWorld(worldName);
                if (w == null) return null;
            }
            return new Location(w,
                    (r.getMinX() + r.getMaxX()) / 2.0,
                    (r.getMinY() + r.getMaxY()) / 2.0,
                    (r.getMinZ() + r.getMaxZ()) / 2.0);
        }

        private Location toLocation(PointLocation pt) {
            if (pt == null) return null;
            Location loc = pt.toLocation();
            if (loc != null) return loc;
            World w = Bukkit.getWorld(worldName);
            if (w == null) return null;
            return new Location(w, pt.getX(), pt.getY(), pt.getZ(), pt.getYaw(), pt.getPitch());
        }
    }

    // ---------------------------------------------------------------
    // Analysis result
    // ---------------------------------------------------------------

    private static class AnalysisResult {
        final java.util.List<LevelEntry> levels = new ArrayList<>();
        final java.util.List<BranchBinding> branches = new ArrayList<>();
        final java.util.List<FinalBinding> finals = new ArrayList<>();
    }

    private static class LevelEntry {
        String mapId, depId;
        String role;           // main / branch / final / unclassified
        String branchEndMapId, branchEndDepId;
        int order;
        String globalEndMapId, globalEndDepId, endTier;
    }

    private static class BranchBinding {
        String branchEndMapId, branchEndDepId;
        String forkMapId, forkDepId;
        Location forkPoint;
    }

    private static class FinalBinding {
        String forkMapId, forkDepId;
        Location forkPoint;
        String globalEndMapId, globalEndDepId, endTier;
    }

    // ---------------------------------------------------------------
    // Main analysis pipeline
    // ---------------------------------------------------------------

    private AnalysisResult analyze(String worldName, java.util.List<DeployedNode> allNodes) {

        DeployedNode globalStart = null;
        java.util.List<DeployedNode> levels = new ArrayList<>();
        java.util.List<DeployedNode> forks = new ArrayList<>();
        java.util.List<DeployedNode> globalEnds = new ArrayList<>();
        java.util.List<DeployedNode> branchEnds = new ArrayList<>();

        for (DeployedNode n : allNodes) {
            switch (n.type()) {
                case GLOBAL_START: if (globalStart == null) globalStart = n; break;
                case LEVEL:        levels.add(n); break;
                case FORK:         forks.add(n); break;
                case GLOBAL_END:   globalEnds.add(n); break;
                case BRANCH_END:   branchEnds.add(n); break;
                // Defensive cases: collectDeployedForWorld already filters
                // these, so a PKWS node can never affect this analysis.
                case PKWS_RELAY:
                case PKWS_START:
                case PKWS_END:
                    break;
            }
        }

        AnalysisResult result = new AnalysisResult();

        // Build fork-point pool: each forkPoint can be bound at most once.
        java.util.List<ForkPointInfo> pool = new ArrayList<>();
        for (DeployedNode fork : forks) {
            for (Location fp : fork.forkPointLocs()) {
                pool.add(new ForkPointInfo(fork.mapId(), fork.depId(), fp.clone()));
            }
        }

        // ---- Branch bindings: BRANCH_END → nearest same-axis forkPoint ----
        java.util.Map<String, BranchBinding> branchBindingsByDep = new LinkedHashMap<>();
        for (DeployedNode be : branchEnds) {
            Location beEnd = be.endLoc();
            if (beEnd == null) continue;
            ForkPointInfo match = findNearestSameAxis(beEnd, pool);
            if (match != null) {
                pool.remove(match);
                BranchBinding bb = new BranchBinding();
                bb.branchEndMapId = be.mapId();
                bb.branchEndDepId = be.depId();
                bb.forkMapId = match.forkMapId;
                bb.forkDepId = match.forkDepId;
                bb.forkPoint = match.location;
                branchBindingsByDep.put(be.depId(), bb);
                result.branches.add(bb);
            }
        }

        // ---- Final bindings: GLOBAL_END → nearest same-axis forkPoint ----
        java.util.List<FinalBinding> finalBindings = new ArrayList<>();
        java.util.List<LineSegment> finalSegments = new ArrayList<>();
        for (DeployedNode ge : globalEnds) {
            Location geEnd = ge.endLoc();
            if (geEnd == null) continue;
            ForkPointInfo match = findNearestSameAxis(geEnd, pool);
            if (match != null) {
                pool.remove(match);
                FinalBinding fb = new FinalBinding();
                fb.forkMapId = match.forkMapId;
                fb.forkDepId = match.forkDepId;
                fb.forkPoint = match.location;
                fb.globalEndMapId = ge.mapId();
                fb.globalEndDepId = ge.depId();
                fb.endTier = ge.map.getEndTier().toConfigString();
                result.finals.add(fb);
                finalBindings.add(fb);
                finalSegments.add(new LineSegment(match.location, geEnd));
            }
        }

        // ---- Main axis: GLOBAL_START → nearest same-axis forkPoint (any) ----
        java.util.List<Vec3> mainAxis = null;
        if (globalStart != null) {
            Location gsLoc = globalStart.startLoc();
            if (gsLoc != null) {
                // Rebuild full list of all forkPoints for main-axis search (unconstrained by binding).
                java.util.List<ForkPointInfo> allFp = new ArrayList<>();
                for (DeployedNode fork : forks) {
                    for (Location fp : fork.forkPointLocs()) {
                        allFp.add(new ForkPointInfo(null, null, fp.clone()));
                    }
                }
                ForkPointInfo mainFp = findNearestSameAxis(gsLoc, allFp);
                if (mainFp != null) {
                    mainAxis = new ArrayList<>();
                    mainAxis.add(Vec3.fromLocation(gsLoc));
                    mainAxis.add(Vec3.fromLocation(mainFp.location));
                }
            }
        }

        // ---- Level classification (priority: branch > final > main) ----
        for (DeployedNode lv : levels) {
            Region region = lv.dep.getRegion();
            if (region == null) {
                result.levels.add(unclassifiedEntry(lv));
                continue;
            }

            // 1) Branch check
            boolean matched = false;
            for (BranchBinding bb : branchBindingsByDep.values()) {
                Location forkPt = bb.forkPoint;
                DeployedNode be = findBranchEndByDepId(branchEnds, bb.branchEndDepId);
                if (be == null) continue;
                Location endPt = be.endLoc();
                if (forkPt == null || endPt == null) continue;

                if (regionIntersectsSegmentXZ(region, forkPt, endPt)) {
                    LevelEntry entry = new LevelEntry();
                    entry.mapId = lv.mapId();
                    entry.depId = lv.depId();
                    entry.role = "branch";
                    entry.branchEndMapId = bb.branchEndMapId;
                    entry.branchEndDepId = bb.branchEndDepId;
                    entry.order = 0;
                    result.levels.add(entry);
                    matched = true;
                    break;
                }
            }
            if (matched) continue;

            // 2) Final check (segment in XZ, infinite in Y)
            for (LineSegment seg : finalSegments) {
                if (regionIntersectsSegmentXZ(region, seg.a, seg.b)) {
                    DeployedNode ge = findOwnerGlobalEnd(globalEnds, seg);
                    LevelEntry entry = new LevelEntry();
                    entry.mapId = lv.mapId();
                    entry.depId = lv.depId();
                    entry.role = "final";
                    if (ge != null) {
                        entry.globalEndMapId = ge.mapId();
                        entry.globalEndDepId = ge.depId();
                        entry.endTier = ge.map.getEndTier().toConfigString();
                    }
                    result.levels.add(entry);
                    matched = true;
                    break;
                }
            }
            if (matched) continue;

            // 3) Main-axis check
            if (mainAxis != null && regionIntersectsMainAxis(region, mainAxis.get(0), mainAxis.get(1))) {
                LevelEntry entry = new LevelEntry();
                entry.mapId = lv.mapId();
                entry.depId = lv.depId();
                entry.role = "main";
                result.levels.add(entry);
                continue;
            }

            result.levels.add(unclassifiedEntry(lv));
        }

        // Fill branch level orders (unchanged)
        java.util.Map<String, java.util.List<LevelEntry>> groups = new LinkedHashMap<>();
        for (LevelEntry e : result.levels) {
            if (!"branch".equals(e.role)) continue;
            groups.computeIfAbsent(e.branchEndDepId, k -> new ArrayList<>()).add(e);
        }
        for (java.util.Map.Entry<String, java.util.List<LevelEntry>> g : groups.entrySet()) {
            BranchBinding bb = branchBindingsByDep.get(g.getKey());
            if (bb == null || bb.forkPoint == null) continue;
            Location origin = bb.forkPoint;
            java.util.List<LevelEntry> entries = g.getValue();
            entries.sort(Comparator.comparingDouble(e -> {
                DeployedNode lv = findLevel(allNodes, e.mapId, e.depId);
                if (lv == null) return Double.MAX_VALUE;
                Location c = lv.regionCenter();
                if (c == null) return Double.MAX_VALUE;
                return c.distance(origin);
            }));
            for (int i = 0; i < entries.size(); i++) {
                int ord = i + 1;
                entries.get(i).order = ord > 3 ? 3 : ord;
            }
        }

        return result;
    }

    // ---------------------------------------------------------------
    // Fork-point pool entry
    // ---------------------------------------------------------------

    private static class ForkPointInfo {
        final String forkMapId;
        final String forkDepId;
        final Location location;

        ForkPointInfo(String forkMapId, String forkDepId, Location location) {
            this.forkMapId = forkMapId;
            this.forkDepId = forkDepId;
            this.location = location;
        }
    }

    // ---------------------------------------------------------------
    // Same-axis nearest search (from end → forkPoint)
    // ---------------------------------------------------------------

    /**
     * Find the nearest forkPoint in the pool that shares either X or Z
     * block coordinate with {@code from}. Returns null if no same-axis
     * point exists.
     */
    private ForkPointInfo findNearestSameAxis(Location from, java.util.List<ForkPointInfo> pool) {
        ForkPointInfo best = null;
        double bestDist = Double.MAX_VALUE;
        for (ForkPointInfo fpi : pool) {
            Location fp = fpi.location;
            if (from.getBlockX() != fp.getBlockX() && from.getBlockZ() != fp.getBlockZ()) {
                continue;
            }
            if (from.getWorld() == null || fp.getWorld() == null) continue;
            if (!from.getWorld().getName().equals(fp.getWorld().getName())) continue;
            double d = from.distance(fp);
            if (d < bestDist) {
                bestDist = d;
                best = fpi;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // Geometry — Slab-method line/AABB (dead code kept for stability)
    // ---------------------------------------------------------------

    private boolean regionIntersectsLine(Region region, Vec3 p0, Vec3 p1) {
        double[] origin = { p0.x, p0.y, p0.z };
        double[] dir = { p1.x - p0.x, p1.y - p0.y, p1.z - p0.z };
        double[] mins = { (double) region.getMinX(), (double) region.getMinY(), (double) region.getMinZ() };
        double[] maxs = { (double) region.getMaxX(), (double) region.getMaxY(), (double) region.getMaxZ() };

        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit  = Double.POSITIVE_INFINITY;

        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1e-12) {
                if (origin[i] < mins[i] || origin[i] > maxs[i]) return false;
            } else {
                double t1 = (mins[i] - origin[i]) / dir[i];
                double t2 = (maxs[i] - origin[i]) / dir[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tEnter = Math.max(tEnter, t1);
                tExit  = Math.min(tExit, t2);
            }
        }
        return tEnter <= tExit;
    }

    /**
     * XZ-plane-only slab test for the main axis. The main axis is treated
     * as a vertical plane — Y is ignored so the line hits every AABB whose
     * XZ projection overlaps, regardless of height differences.
     */
    private boolean regionIntersectsMainAxis(Region region, Vec3 p0, Vec3 p1) {
        double[] origin = { p0.x, p0.z };
        double[] dir    = { p1.x - p0.x, p1.z - p0.z };
        double[] mins   = { (double) region.getMinX(), (double) region.getMinZ() };
        double[] maxs   = { (double) region.getMaxX(), (double) region.getMaxZ() };

        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit  = Double.POSITIVE_INFINITY;

        for (int i = 0; i < 2; i++) {
            if (Math.abs(dir[i]) < 1e-12) {
                if (origin[i] < mins[i] || origin[i] > maxs[i]) return false;
            } else {
                double t1 = (mins[i] - origin[i]) / dir[i];
                double t2 = (maxs[i] - origin[i]) / dir[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tEnter = Math.max(tEnter, t1);
                tExit  = Math.min(tExit, t2);
            }
        }
        return tEnter <= tExit;
    }

    /**
     * XZ-plane-only slab test for a segment. Used for branch and final lines
     * so that height differences between fork-points and ends don't cause misses.
     */
    private boolean regionIntersectsSegmentXZ(Region region, Location a, Location b) {
        double[] origin = { a.getX(), a.getZ() };
        double[] dir    = { b.getX() - a.getX(), b.getZ() - a.getZ() };
        double[] mins   = { (double) region.getMinX(), (double) region.getMinZ() };
        double[] maxs   = { (double) region.getMaxX(), (double) region.getMaxZ() };

        double tEnter = 0.0;
        double tExit  = 1.0;

        for (int i = 0; i < 2; i++) {
            if (Math.abs(dir[i]) < 1e-12) {
                if (origin[i] < mins[i] || origin[i] > maxs[i]) return false;
            } else {
                double t1 = (mins[i] - origin[i]) / dir[i];
                double t2 = (maxs[i] - origin[i]) / dir[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tEnter = Math.max(tEnter, t1);
                tExit  = Math.min(tExit, t2);
            }
        }
        return tEnter <= tExit;
    }

    // ---------------------------------------------------------------
    // Utility — nearest node
    // ---------------------------------------------------------------

    private DeployedNode findNearestNode(Location from, java.util.List<DeployedNode> candidates, boolean useEndPoint) {
        DeployedNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (DeployedNode n : candidates) {
            Location loc = useEndPoint ? n.endLoc() : n.startLoc();
            if (loc == null) continue;
            if (from.getWorld() == null || loc.getWorld() == null) continue;
            if (!from.getWorld().getName().equals(loc.getWorld().getName())) continue;
            double d = from.distance(loc);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private DeployedNode findLevel(java.util.List<DeployedNode> all, String mapId, String depId) {
        for (DeployedNode n : all) {
            if (n.type() == NodeType.LEVEL && n.mapId().equals(mapId) && n.depId().equals(depId)) return n;
        }
        return null;
    }

    private DeployedNode findBranchEndByDepId(java.util.List<DeployedNode> branchEnds, String depId) {
        for (DeployedNode be : branchEnds) {
            if (be.depId().equals(depId)) return be;
        }
        return null;
    }

    private DeployedNode findOwnerGlobalEnd(java.util.List<DeployedNode> globalEnds, LineSegment seg) {
        return findNearestNode(seg.b, globalEnds, true);
    }

    private LevelEntry unclassifiedEntry(DeployedNode lv) {
        LevelEntry e = new LevelEntry();
        e.mapId = lv.mapId();
        e.depId = lv.depId();
        e.role = "unclassified";
        return e;
    }

    // ---------------------------------------------------------------
    // Vec3 / LineSegment
    // ---------------------------------------------------------------

    private static class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        static Vec3 fromLocation(Location l) { return new Vec3(l.getX(), l.getY(), l.getZ()); }
    }

    private static class LineSegment {
        final Location a, b;
        LineSegment(Location a, Location b) { this.a = a; this.b = b; }
    }

    // ---------------------------------------------------------------
    // Public query type — BranchBindingInfo
    // ---------------------------------------------------------------

    /**
     * A resolved branch-end → fork-point binding for runtime queries.
     * Exposes the IDs of both ends and the teleport target Location.
     */
    public static class BranchBindingInfo {
        public final String branchEndMapId;
        public final String branchEndDepId;
        public final String forkMapId;
        public final String forkDepId;
        public final Location forkPoint;

        BranchBindingInfo(String branchEndMapId, String branchEndDepId,
                          String forkMapId, String forkDepId, Location forkPoint) {
            this.branchEndMapId = branchEndMapId;
            this.branchEndDepId = branchEndDepId;
            this.forkMapId = forkMapId;
            this.forkDepId = forkDepId;
            this.forkPoint = forkPoint;
        }
    }

    /**
     * Look up a LEVEL's role from the in-memory cache.
     * Triggers a recompute on cache miss.
     */
    public LevelRoleInfo getLevelRole(String worldName, String mapId, String deploymentId) {
        java.util.Map<String, LevelRoleInfo> worldCache = levelRoleCache.get(worldName);
        if ((worldCache == null || worldCache.isEmpty()) && pkwWorldManager.isPkwWorld(worldName)) {
            recomputeWorld(worldName);
            worldCache = levelRoleCache.get(worldName);
        }
        if (worldCache == null) return null;
        return worldCache.get(mapId + ":" + deploymentId);
    }

    /**
     * Public query type for a LEVEL's classification role.
     */
    public static class LevelRoleInfo {
        public final String role;   // main / branch / final / unclassified
        public final int order;     // 1/2/3 for branch, 0 otherwise
        public final String branchEndMapId, branchEndDepId;
        public final String globalEndMapId, globalEndDepId, endTier;

        LevelRoleInfo(String role, int order,
                      String branchEndMapId, String branchEndDepId,
                      String globalEndMapId, String globalEndDepId, String endTier) {
            this.role = role;
            this.order = order;
            this.branchEndMapId = branchEndMapId;
            this.branchEndDepId = branchEndDepId;
            this.globalEndMapId = globalEndMapId;
            this.globalEndDepId = globalEndDepId;
            this.endTier = endTier;
        }
    }

    // ---------------------------------------------------------------
    // course.yml read / write (multi-world format)
    // ---------------------------------------------------------------

    private File courseFile() {
        return new File(dataFolder, "course.yml");
    }

    /** Read existing course.yml, or return an empty config if none exists. */
    private YamlConfiguration loadExistingCourseYml() {
        File file = courseFile();
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        return new YamlConfiguration();
    }

    private void saveCourseYml(YamlConfiguration config) {
        try {
            config.save(courseFile());
        } catch (IOException e) {
            System.err.println("[SITPKW] Failed to write course.yml: " + e.getMessage());
        }
    }

    /**
     * Write (or overwrite) the section for one world inside a course.yml config.
     */
    private void writeWorldSection(YamlConfiguration config, String worldName,
                                   java.util.List<DeployedNode> allNodes, AnalysisResult result) {
        String base = "worlds." + worldName;

        // ---- nodes ----
        java.util.List<java.util.Map<String, String>> nodeList = new ArrayList<>();
        for (DeployedNode n : allNodes) {
            java.util.Map<String, String> entry = new LinkedHashMap<>();
            entry.put("map_id", n.mapId());
            entry.put("deployment_id", n.depId());
            entry.put("node_type", n.type().toConfigString());
            nodeList.add(entry);
        }
        config.set(base + ".nodes", nodeList);

        // ---- levels ----
        java.util.List<java.util.Map<String, Object>> levelList = new ArrayList<>();
        for (LevelEntry e : result.levels) {
            java.util.Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("map_id", e.mapId);
            entry.put("deployment_id", e.depId);
            entry.put("role", e.role);
            if ("branch".equals(e.role)) {
                entry.put("branch_end_map_id", e.branchEndMapId);
                entry.put("branch_end_deployment_id", e.branchEndDepId);
                entry.put("order", e.order);
            } else if ("final".equals(e.role)) {
                entry.put("global_end_map_id", e.globalEndMapId);
                entry.put("global_end_deployment_id", e.globalEndDepId);
                entry.put("end_tier", e.endTier);
            }
            levelList.add(entry);
        }
        config.set(base + ".levels", levelList);

        // ---- branches ----
        java.util.List<java.util.Map<String, Object>> branchList = new ArrayList<>();
        for (BranchBinding bb : result.branches) {
            java.util.Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("branch_end_map_id", bb.branchEndMapId);
            entry.put("branch_end_deployment_id", bb.branchEndDepId);
            entry.put("fork_map_id", bb.forkMapId);
            entry.put("fork_deployment_id", bb.forkDepId);
            java.util.Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("world", bb.forkPoint.getWorld() != null ? bb.forkPoint.getWorld().getName() : worldName);
            fp.put("x", bb.forkPoint.getX());
            fp.put("y", bb.forkPoint.getY());
            fp.put("z", bb.forkPoint.getZ());
            entry.put("fork_point", fp);
            branchList.add(entry);
        }
        config.set(base + ".branches", branchList);

        // ---- finals: terminal FORK forkPoint → GLOBAL_END bindings ----
        java.util.List<java.util.Map<String, Object>> finalList = new ArrayList<>();
        for (FinalBinding fb : result.finals) {
            java.util.Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fork_map_id", fb.forkMapId);
            entry.put("fork_deployment_id", fb.forkDepId);
            java.util.Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("world", fb.forkPoint.getWorld() != null ? fb.forkPoint.getWorld().getName() : worldName);
            fp.put("x", fb.forkPoint.getX());
            fp.put("y", fb.forkPoint.getY());
            fp.put("z", fb.forkPoint.getZ());
            entry.put("fork_point", fp);
            entry.put("global_end_map_id", fb.globalEndMapId);
            entry.put("global_end_deployment_id", fb.globalEndDepId);
            entry.put("end_tier", fb.endTier);
            finalList.add(entry);
        }
        config.set(base + ".finals", finalList);
    }
}
