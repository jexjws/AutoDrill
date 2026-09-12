package autodrill.filler;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;

import java.util.PriorityQueue;

/**
 * Intelligent Erekir BeamDrill (Plasma Bore / Large Plasma Bore) placement algorithm.
 * Dedicated to maximizing total effective resource output from wall ore veins.
 *
 * Core principles:
 * 1. Maximize total mining speed: 1 beam = 0.375/s, 2 beams = 0.75/s (pure resource output rate).
 * 2. Minimize footprint area: (占地面积越小越好) strictly rewards compact, aligned layouts (e.g. L-shapes).
 * 3. Exact integer tile containment: fixes boundary false-positive collisions so adjacent drills fit perfectly.
 * 4. Solid drills cannot block active mining lasers (prevents blocked/zero-yield drills).
 * 5. Continuous conveyor spine: hugs drill backs tightly without hollow loops or sky detours.
 * 6. Clean external power bus: strictly in open space, never in gaps between drills.
 */
public class WallDrill {

    /** Candidate placement for a BeamDrill */
    public static class DrillCandidate {
        public int x, y;
        public Direction direction;
        public int size;
        public int backPa, frontPa;
        public int saMin, saMax;
        public int x0, y0; // bottom-left integer tile coordinate of the drill
        public Rect rect;
        public Seq<Tile> minedTiles = new Seq<>();
        public Seq<Tile> activeLaserCorridor = new Seq<>(); // air tiles traversed by active lasers hitting ore
        public Seq<Tile> outputTiles = new Seq<>();
        public Tile primaryOutput;
        public int efficiency; // count of laser beams mining target item
        public int minDist;

        public DrillCandidate(int x, int y, Direction direction, BeamDrill drill) {
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.size = drill.size;
            this.backPa = direction.getBackPa(x, y, size);
            this.frontPa = this.backPa + size - 1;
            this.saMin = direction.getSaMin(x, y, size);
            this.saMax = this.saMin + size - 1;
            int off = -((size - 1) / 2);
            this.x0 = x + off;
            this.y0 = y + off;
            this.minDist = Integer.MAX_VALUE;
            this.rect = Util.getBlockRect(x, y, drill);
        }

        /** Exact integer tile containment check (prevents boundary false positives) */
        public boolean containsTile(int tx, int ty) {
            return tx >= x0 && tx < x0 + size && ty >= y0 && ty < y0 + size;
        }
    }

    /** Node for A* duct pathfinding */
    private static class DuctNode implements Comparable<DuctNode> {
        Tile tile;
        int cost;
        int heuristic;
        DuctNode parent;

        DuctNode(Tile tile, int cost, int heuristic, DuctNode parent) {
            this.tile = tile;
            this.cost = cost;
            this.heuristic = heuristic;
            this.parent = parent;
        }

        @Override
        public int compareTo(DuctNode o) {
            return Integer.compare(this.cost + this.heuristic, o.cost + o.heuristic);
        }
    }

    /** Direct entry point: automatic fill without needing user to choose direction */
    public static void fill(Tile startTile, BeamDrill drill) {
        fill(startTile, drill, null);
    }

    /** Main entry point (overload keeping backwards compatibility) */
    public static void fill(Tile startTile, BeamDrill drill, Direction preferredDirection) {
        if (startTile == null || drill == null) return;
        Team team = Vars.player.team();
        Item sourceItem = startTile.wallDrop();
        if (sourceItem == null) return;

        // --- Phase 1: Collect connected wall ore cluster ---
        Seq<Tile> wallCluster = getConnectedWallTiles(startTile, sourceItem);
        if (wallCluster.isEmpty()) return;

        // --- Phase 2: Generate candidate drill placements across all 4 directions ---
        Seq<DrillCandidate> candidates = generateCandidates(wallCluster, drill, team, sourceItem, preferredDirection);
        if (candidates.isEmpty()) return;

        // --- Phase 3: Heuristic search to maximize total mining speed while minimizing footprint area ---
        Seq<DrillCandidate> selectedDrills = selectMaxYieldDrills(candidates);
        if (selectedDrills.isEmpty()) return;

        Seq<BuildPlan> allPlans = new Seq<>();

        // Add drill build plans
        for (DrillCandidate d : selectedDrills) {
            BuildPlan borePlan = new BuildPlan(d.x, d.y, d.direction.r, drill);
            if (Util.canPlaceWithoutPlanCollision(borePlan, team, allPlans)) {
                allPlans.add(borePlan);
            }
        }
        if (allPlans.isEmpty()) return;

        // --- Phase 4: Construct loop-free, drill-hugging conveyor spine ---
        buildDuctNetwork(selectedDrills, allPlans, team);

        // --- Phase 5: Construct clean external power bus (never in gaps between drills) ---
        buildBeamNodeNetwork(selectedDrills, allPlans, team);

        // --- Commit phase ---
        Util.commitPlans(allPlans);

        if (Util.DEBUG) {
            Log.info("[AutoDrill] WallDrill: placed " + selectedDrills.size + " drills, committed " + allPlans.size + " plans for " + drill.name);
        }
    }

    // =========================================================================
    // Phase 1: Wall tile BFS
    // =========================================================================

    private static Seq<Tile> getConnectedWallTiles(Tile startTile, Item sourceItem) {
        arc.struct.Queue<Tile> queue = new arc.struct.Queue<>();
        Seq<Tile> wallTiles = new Seq<>();
        ObjectSet<Tile> visited = new ObjectSet<>();

        queue.addLast(startTile);
        visited.add(startTile);

        int baseSetting = Core.settings.getInt("mechanical-drill-max-tiles", 200);
        int maxTiles = Math.max(baseSetting * 3, 600);

        while (!queue.isEmpty() && wallTiles.size < maxTiles) {
            Tile currentTile = queue.removeFirst();

            if (currentTile.wallDrop() == sourceItem) {
                wallTiles.add(currentTile);

                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        if (x == 0 && y == 0) continue;
                        Tile neighbor = currentTile.nearby(x, y);
                        if (neighbor == null || visited.contains(neighbor)) continue;

                        if (neighbor.wallDrop() == sourceItem) {
                            visited.add(neighbor);
                            queue.addLast(neighbor);
                        }
                    }
                }
            }
        }

        return wallTiles;
    }

    // =========================================================================
    // Phase 2: Multi-directional Candidate Generation
    // =========================================================================

    private static Seq<DrillCandidate> generateCandidates(Seq<Tile> wallCluster, BeamDrill drill, Team team, Item sourceItem, Direction preferredDirection) {
        Seq<DrillCandidate> candidates = new Seq<>();
        ObjectSet<String> seen = new ObjectSet<>();

        Direction[] directionsToTest;
        if (preferredDirection != null) {
            directionsToTest = new Direction[]{preferredDirection, Direction.getOpposite(preferredDirection), Direction.values()[((preferredDirection.ordinal() + 1) % 4)], Direction.values()[((preferredDirection.ordinal() + 3) % 4)]};
        } else {
            directionsToTest = Direction.values();
        }

        for (Tile w : wallCluster) {
            for (Direction dir : directionsToTest) {
                Tile inFront = w.nearby(-dir.p.x, -dir.p.y);
                if (inFront == null || inFront.solid()) continue;

                int paW = dir.primaryAxis(w.x, w.y);
                int saW = dir.secondaryAxis(w.x, w.y);

                for (int k = 0; k < drill.size; k++) {
                    int saMin = saW - k;
                    for (int d = 1; d <= drill.range; d++) {
                        int frontPa = paW - d;
                        int backPa = frontPa - drill.size + 1;

                        Point2 anchor = dir.anchorFromAxes(backPa, saMin, drill.size);
                        String key = anchor.x + "," + anchor.y + "," + dir.r;
                        if (seen.contains(key)) continue;
                        seen.add(key);

                        if (!Util.canPlaceBlock(drill, team, anchor.x, anchor.y, dir.r)) continue;

                        DrillCandidate cand = new DrillCandidate(anchor.x, anchor.y, dir, drill);
                        if (!evaluateMining(cand, drill, sourceItem)) continue;

                        if (!evaluateDuctClearance(cand, team)) continue;

                        candidates.add(cand);
                    }
                }
            }
        }

        return candidates;
    }

    private static boolean evaluateMining(DrillCandidate cand, BeamDrill drill, Item sourceItem) {
        int beamsHitting = 0;

        for (int k = 0; k < cand.size; k++) {
            int sa = cand.saMin + k;
            Seq<Tile> beamAir = new Seq<>();

            for (int d = 1; d <= drill.range; d++) {
                int pa = cand.frontPa + d;
                Point2 pt = cand.direction.anchorFromAxes(pa, sa, 1);
                Tile t = Vars.world.tile(pt.x, pt.y);
                if (t == null) break;

                if (t.solid()) {
                    if (t.wallDrop() == sourceItem) {
                        beamsHitting++;
                        cand.minedTiles.add(t);
                        cand.minDist = Math.min(cand.minDist, d);
                        for (Tile at : beamAir) {
                            if (!cand.activeLaserCorridor.contains(at)) {
                                cand.activeLaserCorridor.add(at);
                            }
                        }
                    }
                    break; // Solid rock stops the laser
                } else {
                    beamAir.add(t);
                }
            }
        }

        cand.efficiency = beamsHitting;
        return beamsHitting > 0;
    }

    private static boolean evaluateDuctClearance(DrillCandidate cand, Team team) {
        // Primary output: tile directly behind the drill
        Point2 backPt = cand.direction.anchorFromAxes(cand.backPa - 1, cand.saMin, 1);
        Tile backTile = Vars.world.tile(backPt.x, backPt.y);
        if (backTile != null && !backTile.solid() && Util.canPlaceBlock(Blocks.duct, team, backTile.x, backTile.y, 0)) {
            cand.primaryOutput = backTile;
            cand.outputTiles.add(backTile);
        }

        // Perimeter tiles
        int off = -((cand.size - 1) / 2);
        for (int dx = -1; dx <= cand.size; dx++) {
            for (int dy = -1; dy <= cand.size; dy++) {
                if (dx >= 0 && dx < cand.size && dy >= 0 && dy < cand.size) continue;
                Tile t = Vars.world.tile(cand.x + off + dx, cand.y + off + dy);
                if (t != null && !t.solid() && Util.canPlaceBlock(Blocks.duct, team, t.x, t.y, 0)) {
                    if (!cand.outputTiles.contains(t)) {
                        cand.outputTiles.add(t);
                    }
                    if (cand.primaryOutput == null) {
                        cand.primaryOutput = t;
                    }
                }
            }
        }

        return cand.primaryOutput != null;
    }

    // =========================================================================
    // Phase 3: Global Optimization (Maximum Speed + Minimal Footprint Area)
    // =========================================================================

    /**
     * Compatibility check:
     * 1. Footprints cannot overlap.
     * 2. Physical laser obstruction: a solid drill body cannot block another drill's active laser beam!
     * 3. Duct reservation: 'b' cannot cover all output tiles of 'a'.
     */
    private static boolean areCompatible(DrillCandidate a, DrillCandidate b) {
        // 1. Footprint overlap using exact integer tile coordinates
        if (a.x0 < b.x0 + b.size && a.x0 + a.size > b.x0 && a.y0 < b.y0 + b.size && a.y0 + a.size > b.y0) {
            return false;
        }

        // 2. Physical laser obstruction using exact integer containment
        for (int i = 0; i < a.activeLaserCorridor.size; i++) {
            Tile t = a.activeLaserCorridor.get(i);
            if (b.containsTile(t.x, t.y)) return false;
        }
        for (int i = 0; i < b.activeLaserCorridor.size; i++) {
            Tile t = b.activeLaserCorridor.get(i);
            if (a.containsTile(t.x, t.y)) return false;
        }

        // 3. Duct output reservation
        boolean aHasDuct = false;
        for (int i = 0; i < a.outputTiles.size; i++) {
            Tile t = a.outputTiles.get(i);
            if (!b.containsTile(t.x, t.y)) {
                aHasDuct = true;
                break;
            }
        }
        if (!aHasDuct) return false;

        boolean bHasDuct = false;
        for (int i = 0; i < b.outputTiles.size; i++) {
            Tile t = b.outputTiles.get(i);
            if (!a.containsTile(t.x, t.y)) {
                bHasDuct = true;
                break;
            }
        }
        if (!bHasDuct) return false;

        return true;
    }

    /**
     * Evaluates a solution subset:
     * 1. Total mining speed: 375 per beam * 1000
     * 2. Drill count penalty: -50 per drill
     * 3. Footprint Area penalty: -25 per tile of bounding box (占地面积越小越好)
     * 4. Span penalty: -30 per perimeter unit (prefers compact, aligned layouts)
     */
    private static int evaluateYield(IntSeq indices, Seq<DrillCandidate> candidates) {
        if (indices.isEmpty()) return 0;
        int totalSpeed = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        for (int i = 0; i < indices.size; i++) {
            DrillCandidate d = candidates.get(indices.get(i));
            totalSpeed += d.efficiency * 375;

            minX = Math.min(minX, d.x0);
            maxX = Math.max(maxX, d.x0 + d.size);
            minY = Math.min(minY, d.y0);
            maxY = Math.max(maxY, d.y0 + d.size);
        }

        int width = maxX - minX;
        int height = maxY - minY;
        int area = width * height;
        int span = width + height;

        return totalSpeed * 1000 - indices.size * 50 - area * 25 - span * 30;
    }

    private static Seq<DrillCandidate> selectMaxYieldDrills(Seq<DrillCandidate> candidates) {
        int n = candidates.size;
        if (n == 0) return new Seq<>();

        boolean[][] compat = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            compat[i][i] = true;
            for (int j = i + 1; j < n; j++) {
                boolean ok = areCompatible(candidates.get(i), candidates.get(j));
                compat[i][j] = ok;
                compat[j][i] = ok;
            }
        }

        int bestScore = Integer.MIN_VALUE;
        IntSeq bestIndices = new IntSeq();

        int iterations = Math.min(300, Math.max(100, n * 3));
        for (int iter = 0; iter < iterations; iter++) {
            IntSeq current = new IntSeq();
            boolean[] available = new boolean[n];
            for (int i = 0; i < n; i++) available[i] = true;

            int availCount = n;
            while (availCount > 0) {
                int[] cScores = new int[availCount];
                int[] cIndices = new int[availCount];
                int count = 0;

                for (int i = 0; i < n; i++) {
                    if (!available[i]) continue;
                    current.add(i);
                    cScores[count] = evaluateYield(current, candidates);
                    cIndices[count] = i;
                    current.pop();
                    count++;
                }

                int chosen;
                if (iter == 0) {
                    int topIdx = 0;
                    for (int i = 1; i < count; i++) {
                        if (cScores[i] > cScores[topIdx]) topIdx = i;
                    }
                    chosen = cIndices[topIdx];
                } else {
                    int topK = Math.min(count, 4);
                    for (int i = 0; i < topK; i++) {
                        int maxI = i;
                        for (int j = i + 1; j < count; j++) {
                            if (cScores[j] > cScores[maxI]) maxI = j;
                        }
                        int tmpS = cScores[i]; cScores[i] = cScores[maxI]; cScores[maxI] = tmpS;
                        int tmpI = cIndices[i]; cIndices[i] = cIndices[maxI]; cIndices[maxI] = tmpI;
                    }
                    chosen = cIndices[Mathf.random(topK - 1)];
                }

                current.add(chosen);
                available[chosen] = false;
                availCount--;

                for (int i = 0; i < n; i++) {
                    if (available[i] && !compat[chosen][i]) {
                        available[i] = false;
                        availCount--;
                    }
                }
            }

            // Local 1-swap improvement
            boolean improved = true;
            for (int round = 0; round < 3 && improved; round++) {
                improved = false;
                int curScore = evaluateYield(current, candidates);

                for (int outIdx = 0; outIdx < current.size; outIdx++) {
                    int removed = current.get(outIdx);

                    for (int addCand = 0; addCand < n; addCand++) {
                        if (addCand == removed || current.contains(addCand)) continue;

                        boolean ok = true;
                        for (int k = 0; k < current.size; k++) {
                            if (k != outIdx && !compat[addCand][current.get(k)]) {
                                ok = false;
                                break;
                            }
                        }
                        if (!ok) continue;

                        current.set(outIdx, addCand);
                        int newScore = evaluateYield(current, candidates);
                        if (newScore > curScore) {
                            curScore = newScore;
                            improved = true;
                            break;
                        } else {
                            current.set(outIdx, removed);
                        }
                    }
                    if (improved) break;
                }
            }

            int finalScore = evaluateYield(current, candidates);
            if (finalScore > bestScore) {
                bestScore = finalScore;
                bestIndices = new IntSeq(current);
            }
        }

        Seq<DrillCandidate> result = new Seq<>();
        for (int i = 0; i < bestIndices.size; i++) {
            result.add(candidates.get(bestIndices.get(i)));
        }
        return result;
    }

    // =========================================================================
    // Phase 4: Zero-Loop Continuous Conveyor Spine (Hugs Drill Backs Tightly)
    // =========================================================================

    private static void buildDuctNetwork(Seq<DrillCandidate> selectedDrills, Seq<BuildPlan> allPlans, Team team) {
        ObjectSet<Tile> drillTiles = new ObjectSet<>();
        for (DrillCandidate d : selectedDrills) {
            for (int dx = 0; dx < d.size; dx++) {
                for (int dy = 0; dy < d.size; dy++) {
                    Tile t = Vars.world.tile(d.x0 + dx, d.y0 + dy);
                    if (t != null) drillTiles.add(t);
                }
            }
        }

        // Perimeter tiles directly adjacent to any drill
        ObjectSet<Tile> adjTiles = new ObjectSet<>();
        for (DrillCandidate d : selectedDrills) {
            for (int dx = -1; dx <= d.size; dx++) {
                for (int dy = -1; dy <= d.size; dy++) {
                    if (dx >= 0 && dx < d.size && dy >= 0 && dy < d.size) continue;
                    Tile t = Vars.world.tile(d.x0 + dx, d.y0 + dy);
                    if (t != null && !drillTiles.contains(t) && !t.solid()) {
                        adjTiles.add(t);
                    }
                }
            }
        }

        // 1. Collect each drill's primary back anchor tile
        Seq<Tile> anchors = new Seq<>();
        for (DrillCandidate d : selectedDrills) {
            Tile out = d.primaryOutput;
            if (out == null || out.solid() || drillTiles.contains(out)) {
                for (Tile t : d.outputTiles) {
                    if (!drillTiles.contains(t) && !t.solid()) {
                        out = t;
                        break;
                    }
                }
            }
            if (out != null && !anchors.contains(out)) {
                anchors.add(out);
            }
        }
        if (anchors.isEmpty()) return;

        // 2. Sort anchors along the primary chain axis
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Tile a : anchors) {
            minX = Math.min(minX, a.x);
            maxX = Math.max(maxX, a.x);
            minY = Math.min(minY, a.y);
            maxY = Math.max(maxY, a.y);
        }

        boolean horizontal = (maxX - minX) >= (maxY - minY);
        if (horizontal) {
            anchors.sort(Tile::getX);
        } else {
            anchors.sort(Tile::getY);
        }

        // 3. Choose exit at one of the chain ends (first or last) with clear open space outward
        Tile first = anchors.first();
        Tile last = anchors.peek();

        int firstScore = scoreExitClearance(first, horizontal ? Direction.LEFT : Direction.DOWN, drillTiles);
        int lastScore = scoreExitClearance(last, horizontal ? Direction.RIGHT : Direction.UP, drillTiles);

        boolean exitAtLast = lastScore >= firstScore;
        Tile exitAnchor = exitAtLast ? last : first;
        Direction exitDir = exitAtLast ? (horizontal ? Direction.RIGHT : Direction.UP) : (horizontal ? Direction.LEFT : Direction.DOWN);

        ObjectSet<Tile> ductTiles = new ObjectSet<>();
        ObjectMap<Tile, Tile> parentMap = new ObjectMap<>();

        // Add exit and outward extensions
        ductTiles.add(exitAnchor);
        Tile ext1 = exitAnchor.nearby(exitDir.p.x, exitDir.p.y);
        Tile ext2 = null;
        if (ext1 != null && !drillTiles.contains(ext1) && !ext1.solid() && Util.canPlaceBlock(Blocks.duct, team, ext1.x, ext1.y, exitDir.r)) {
            ductTiles.add(ext1);
            parentMap.put(exitAnchor, ext1);
            ext2 = ext1.nearby(exitDir.p.x, exitDir.p.y);
            if (ext2 != null && !drillTiles.contains(ext2) && !ext2.solid() && Util.canPlaceBlock(Blocks.duct, team, ext2.x, ext2.y, exitDir.r)) {
                ductTiles.add(ext2);
                parentMap.put(ext1, ext2);
            }
        }

        Seq<Tile> orderedAnchors = exitAtLast ? anchors : anchors.copy().reverse();

        for (int i = 0; i < orderedAnchors.size; i++) {
            Tile src = orderedAnchors.get(i);
            if (ductTiles.contains(src)) continue;

            Seq<Tile> path = findShortestDuctPath(src, ductTiles, drillTiles, adjTiles, team);
            if (path != null) {
                for (int k = 0; k < path.size - 1; k++) {
                    Tile cur = path.get(k);
                    Tile nxt = path.get(k + 1);
                    ductTiles.add(cur);
                    parentMap.put(cur, nxt);
                }
            }
        }

        Tile finalExit = (ext2 != null && ductTiles.contains(ext2)) ? ext2 : ((ext1 != null && ductTiles.contains(ext1)) ? ext1 : exitAnchor);

        for (Tile t : ductTiles) {
            int rot;
            if (t == finalExit) {
                rot = exitDir.r;
            } else {
                Tile p = parentMap.get(t);
                if (p != null) {
                    rot = t.relativeTo(p);
                } else {
                    rot = exitDir.r;
                }
            }

            BuildPlan plan = new BuildPlan(t.x, t.y, rot, Blocks.duct);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                allPlans.add(plan);
            }
        }
    }

    private static int scoreExitClearance(Tile t, Direction dir, ObjectSet<Tile> drillTiles) {
        int score = 0;
        for (int step = 1; step <= 5; step++) {
            Tile far = t.nearby(dir.p.x * step, dir.p.y * step);
            if (far != null && !far.solid() && !drillTiles.contains(far)) {
                score += 10;
            }
        }
        return score;
    }

    private static Seq<Tile> findShortestDuctPath(Tile start, ObjectSet<Tile> targets, ObjectSet<Tile> drillTiles, ObjectSet<Tile> adjTiles, Team team) {
        PriorityQueue<DuctNode> openSet = new PriorityQueue<>();
        ObjectMap<Tile, Integer> gScores = new ObjectMap<>();

        int initialH = minDistanceToTargets(start, targets);
        openSet.add(new DuctNode(start, 0, initialH, null));
        gScores.put(start, 0);

        while (!openSet.isEmpty()) {
            DuctNode current = openSet.poll();

            if (targets.contains(current.tile)) {
                Seq<Tile> path = new Seq<>();
                DuctNode curr = current;
                while (curr != null) {
                    path.add(curr.tile);
                    curr = curr.parent;
                }
                path.reverse();
                return path;
            }

            if (current.cost > gScores.get(current.tile, Integer.MAX_VALUE)) continue;

            for (int r = 0; r < 4; r++) {
                Tile nb = current.tile.nearby(r);
                if (nb == null) continue;

                if (!targets.contains(nb)) {
                    if (drillTiles.contains(nb) || nb.solid()) continue;
                    if (!Util.canPlaceBlock(Blocks.duct, team, nb.x, nb.y, 0)) continue;
                }

                int stepCost = targets.contains(nb) ? 0 : (adjTiles.contains(nb) ? 1 : 4);
                int newCost = current.cost + stepCost;

                if (newCost < gScores.get(nb, Integer.MAX_VALUE)) {
                    gScores.put(nb, newCost);
                    int h = minDistanceToTargets(nb, targets);
                    openSet.add(new DuctNode(nb, newCost, h, current));
                }
            }
        }

        return null;
    }

    private static int minDistanceToTargets(Tile t, ObjectSet<Tile> targets) {
        int minDist = Integer.MAX_VALUE;
        for (Tile target : targets) {
            minDist = Math.min(minDist, Math.abs(t.x - target.x) + Math.abs(t.y - target.y));
        }
        return minDist == Integer.MAX_VALUE ? 0 : minDist;
    }

    // =========================================================================
    // Phase 5: Clean External Power Bus (Strictly Outside, Never in Gaps)
    // =========================================================================

    private static void buildBeamNodeNetwork(Seq<DrillCandidate> selectedDrills, Seq<BuildPlan> allPlans, Team team) {
        ObjectSet<Tile> forbiddenTiles = new ObjectSet<>();
        for (BuildPlan p : allPlans) {
            int off = -((p.block.size - 1) / 2);
            for (int dx = 0; dx < p.block.size; dx++) {
                for (int dy = 0; dy < p.block.size; dy++) {
                    Tile t = Vars.world.tile(p.x + off + dx, p.y + off + dy);
                    if (t != null) forbiddenTiles.add(t);
                }
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BuildPlan p : allPlans) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }

        int busY = Math.min(maxY + 2, Vars.world.height() - 1);
        boolean busValid = true;
        for (int x = minX; x <= maxX; x++) {
            Tile t = Vars.world.tile(x, busY);
            if (t == null || t.solid() || forbiddenTiles.contains(t)) {
                busValid = false;
                break;
            }
        }
        if (!busValid) busY = Math.min(maxY + 1, Vars.world.height() - 1);

        Seq<Tile> busNodes = new Seq<>();
        IntSeq busXs = new IntSeq();

        for (DrillCandidate d : selectedDrills) {
            int bx = d.x;
            if (!busXs.contains(bx)) {
                busXs.add(bx);
            }
        }
        busXs.sort();

        for (int i = 0; i < busXs.size; i++) {
            int x = busXs.get(i);
            Tile t = Vars.world.tile(x, busY);
            if (t == null || forbiddenTiles.contains(t) || t.solid()) continue;

            BuildPlan plan = new BuildPlan(t.x, t.y, 0, Blocks.beamNode);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                allPlans.add(plan);
                forbiddenTiles.add(t);
                busNodes.add(t);
            }
        }

        busNodes.sort(Tile::getX);
        for (int i = 0; i < busNodes.size - 1; i++) {
            Tile n1 = busNodes.get(i);
            Tile n2 = busNodes.get(i + 1);
            int dist = Math.abs(n2.x - n1.x);
            if (dist > 8) {
                int step = (n2.x - n1.x) > 0 ? 6 : -6;
                for (int curX = n1.x + step; Math.abs(n2.x - curX) > 8; curX += step) {
                    Tile mid = Vars.world.tile(curX, busY);
                    if (mid != null && !forbiddenTiles.contains(mid) && !mid.solid()) {
                        BuildPlan chain = new BuildPlan(mid.x, mid.y, 0, Blocks.beamNode);
                        if (Util.canPlaceWithoutPlanCollision(chain, team, allPlans)) {
                            allPlans.add(chain);
                            forbiddenTiles.add(mid);
                        }
                    }
                }
            }
        }
    }
}
