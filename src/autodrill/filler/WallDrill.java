package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.struct.IntIntMap;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

import static arc.Core.bundle;

/**
 * Fills a whole ore wall deposit with beam drills (plasma bores).
 *
 * <ol>
 *     <li>The deposit is the flood-filled patch of wall tiles with the clicked resource.</li>
 *     <li>Every bore placement, in all four rotations, whose beams hit only that resource is a candidate.
 *     Bores must not overlap each other or block each other's beams; among such sets the one with the most
 *     mined tiles is searched with a randomized greedy packing improved by swaps.</li>
 *     <li>All bores are joined by one duct tree that leaves the field on the side the player picked.</li>
 *     <li>Beam nodes are added until all bores share one power graph.</li>
 * </ol>
 */
public class WallDrill {
    private static final int ROUTE_MARGIN = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RANDOM_RESTARTS = 40;
    private static final int HIT_WEIGHT = 1000;
    private static final int DISTANCE_PENALTY = 3;

    /**
     * @param output the side of the field the collected resources leave on
     */
    public static void fill(Tile tile, BeamDrill drill, Direction output) {
        Seq<BuildPlan> plans = plan(tile, drill, output, Vars.player.team());
        if (plans.isEmpty()) {
            notifyNoPlacement();
            return;
        }

        Util.commitPlans(plans);

        if (Util.DEBUG) {
            Log.info("[AutoDrill] WallDrill: committed " + plans.size + " plans for " + drill.name);
        }
    }

    /** The bores, ducts and beam nodes that fill the deposit under {@code tile}; empty when nothing fits. */
    static Seq<BuildPlan> plan(Tile tile, BeamDrill drill, Direction output, Team team) {
        Seq<BuildPlan> accepted = new Seq<>();
        Item resource = tile.wallDrop();
        if (resource == null) return accepted;

        Terrain terrain = new Terrain(team);
        Seq<Bore> candidates = candidates(collectDeposit(tile, resource), drill, resource, terrain);

        // Bores that cannot be reached by ducts are dropped and the packing is repeated without them.
        Layout best = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS && !candidates.isEmpty(); attempt++) {
            Seq<Bore> selected = select(candidates);
            if (selected.isEmpty()) break;

            Layout layout = route(selected, output, terrain);
            if (best == null || layout.hits() > best.hits()) best = layout;
            if (layout.dropped.isEmpty()) break;
            candidates.removeAll(layout.dropped::contains);
        }
        if (best == null || best.bores.isEmpty()) return accepted;

        List<int[]> footprints = new ArrayList<>();
        IntSet blocked = new IntSet(best.ductCells);
        blocked.addAll(best.reserved);
        for (Bore bore : best.bores) {
            footprints.add(bore.footprint);
            for (int cell : bore.corridor) blocked.add(cell);
            accepted.add(bore.plan);
        }
        accepted.addAll(best.ducts);
        accepted.addAll(BeamNodePlanner.plan(footprints, blocked, team));
        return accepted;
    }

    // === deposit =============================================================

    /**
     * The tiles of the clicked deposit that a beam can reach, i.e. the ones with a free neighbour. The deposit is
     * flood filled through all of its tiles; gaps of up to one tile do not split it, as walls are often jagged.
     */
    private static Seq<Tile> collectDeposit(Tile start, Item resource) {
        int limit = Core.settings.getInt("mechanical-drill-max-tiles", 200);
        int width = Vars.world.width();

        Queue<Tile> queue = new Queue<>();
        IntSet visited = new IntSet();
        Seq<Tile> exposed = new Seq<>();

        queue.addLast(start);
        visited.add(start.x + start.y * width);
        while (!queue.isEmpty() && exposed.size < limit && visited.size < limit * 40) {
            Tile current = queue.removeFirst();
            if (current.wallDrop() != resource) continue;

            for (Direction direction : Direction.values()) {
                Tile neighbour = current.nearby(direction.p.x, direction.p.y);
                if (neighbour != null && !neighbour.solid()) {
                    exposed.add(current);
                    break;
                }
            }

            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Tile next = current.nearby(dx, dy);
                    if (next != null && visited.add(next.x + next.y * width)) queue.addLast(next);
                }
            }
        }
        return exposed;
    }

    // === candidates ==========================================================

    /** One valid bore placement. */
    private static final class Bore {
        final int rotation;
        final int hits;
        final int weight;
        /** Tiles occupied by the bore. */
        final int[] footprint;
        /** Tiles between the bore and the walls its beams hit; nothing solid may be built there. */
        final int[] corridor;
        /** Free tiles next to the bore, except the beam side, where an output duct can start. */
        final int[] ports;
        /** All tiles next to the bore that pick up its output. */
        final int[] ring;
        final BuildPlan plan;

        Bore(BuildPlan plan, int hits, int distance, int[] footprint, int[] corridor, int[] ports, int[] ring) {
            this.plan = plan;
            this.rotation = plan.rotation;
            this.hits = hits;
            this.weight = hits * HIT_WEIGHT - distance * DISTANCE_PENALTY - 1;
            this.footprint = footprint;
            this.corridor = corridor;
            this.ports = ports;
            this.ring = ring;
        }
    }

    /** Placement checks that are asked repeatedly are cached. */
    private static final class Terrain {
        final Team team;
        final int width = Vars.world.width();
        final int height = Vars.world.height();
        private final IntIntMap ducts = new IntIntMap();

        Terrain(Team team) {
            this.team = team;
        }

        int cell(int x, int y) {
            return x + y * width;
        }

        boolean inside(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        boolean duct(int x, int y) {
            if (!inside(x, y)) return false;
            int key = cell(x, y);
            int state = ducts.get(key, 0);
            if (state == 0) {
                state = Util.canPlaceBlock(Blocks.duct, team, x, y, 0) ? 1 : 2;
                ducts.put(key, state);
            }
            return state == 1;
        }
    }

    private static Seq<Bore> candidates(Seq<Tile> deposit, BeamDrill drill, Item resource, Terrain terrain) {
        Seq<Bore> result = new Seq<>();
        IntSet seen = new IntSet();
        Point2 offset = new Point2();

        for (Direction facing : Direction.values()) {
            for (int lane = 0; lane < drill.size; lane++) {
                drill.nearbySide(0, 0, facing.r, lane, offset);
                for (Tile target : deposit) {
                    for (int distance = 0; distance < drill.range; distance++) {
                        int x = target.x - offset.x - facing.p.x * distance;
                        int y = target.y - offset.y - facing.p.y * distance;
                        if (!terrain.inside(x, y) || !seen.add(terrain.cell(x, y) * 4 + facing.r)) continue;

                        Bore bore = evaluate(x, y, facing, drill, resource, terrain);
                        if (bore != null) result.add(bore);
                    }
                }
            }
        }
        return result;
    }

    private static Bore evaluate(int x, int y, Direction facing, BeamDrill drill, Item resource, Terrain terrain) {
        if (!Util.canPlaceBlock(drill, terrain.team, x, y, facing.r)) return null;

        Point2 side = new Point2();
        IntSeq corridor = new IntSeq();
        IntSeq front = new IntSeq();
        int hits = 0, distance = 0;
        for (int lane = 0; lane < drill.size; lane++) {
            drill.nearbySide(x, y, facing.r, lane, side);
            front.add(terrain.cell(side.x, side.y));
            for (int j = 0; j < drill.range; j++) {
                Tile other = Vars.world.tile(side.x + facing.p.x * j, side.y + facing.p.y * j);
                if (other == null) break;
                if (!other.solid()) continue;

                Item item = other.wallDrop();
                if (item != null && item.hardness <= drill.tier
                    && (drill.blockedItems == null || !drill.blockedItems.contains(item))) {
                    // A beam on another resource would slow the bore down and mix the output.
                    if (item != resource) return null;
                    hits++;
                    distance += j;
                    for (int k = 0; k < j; k++) {
                        corridor.add(terrain.cell(side.x + facing.p.x * k, side.y + facing.p.y * k));
                    }
                }
                break;
            }
        }
        if (hits == 0) return null;

        int low = (drill.size - 1) / 2;
        int originX = x - low, originY = y - low;
        IntSeq footprint = new IntSeq();
        for (int dx = 0; dx < drill.size; dx++) {
            for (int dy = 0; dy < drill.size; dy++) footprint.add(terrain.cell(originX + dx, originY + dy));
        }

        IntSeq ring = new IntSeq();
        IntSeq ports = new IntSeq();
        for (int i = 0; i < drill.size; i++) {
            int[][] around = {
                {originX - 1, originY + i}, {originX + drill.size, originY + i},
                {originX + i, originY - 1}, {originX + i, originY + drill.size}
            };
            for (int[] position : around) {
                if (!terrain.inside(position[0], position[1])) continue;
                int cell = terrain.cell(position[0], position[1]);
                ring.add(cell);
                if (!front.contains(cell) && terrain.duct(position[0], position[1])) ports.add(cell);
            }
        }
        if (ports.isEmpty()) return null;

        return new Bore(new BuildPlan(x, y, facing.r, drill), hits, distance, footprint.toArray(),
            corridor.toArray(), ports.toArray(), ring.toArray());
    }

    // === packing =============================================================

    /**
     * Picks the set of bores with the most mined tiles. Two bores conflict when they overlap or when one stands in
     * the other's beam corridor; beams crossing each other are fine.
     */
    private static Seq<Bore> select(Seq<Bore> bores) {
        Seq<Bore> selected = new Seq<>();
        int[][] conflicts = conflicts(bores);
        int count = bores.size;
        Random random = new Random(1);

        Packing best = null;
        for (int run = 0; run < 4 + RANDOM_RESTARTS; run++) {
            double[] key = new double[count];
            for (int i = 0; i < count; i++) {
                Bore bore = bores.get(i);
                int origin = bore.footprint[0];
                // Equal weights are broken by a sweep from one of four sides, which packs walls tightly.
                int sweep = switch (run % 4) {
                    case 0 -> origin % Vars.world.width();
                    case 1 -> -(origin % Vars.world.width());
                    case 2 -> origin / Vars.world.width();
                    default -> -(origin / Vars.world.width());
                };
                double noise = run < 4 ? 1.0 : 0.85 + 0.3 * random.nextDouble();
                key[i] = bore.weight * noise - sweep * 1e-4 + (run < 4 ? 0 : random.nextDouble() * 0.4);
            }
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(key[b], key[a]));

            Packing packing = new Packing(bores, conflicts);
            packing.greedy(order);
            packing.improve();
            if (best == null || packing.total > best.total) best = packing;
        }

        for (int i = 0; i < count; i++) if (best.selected[i]) selected.add(bores.get(i));
        return selected;
    }

    private static int[][] conflicts(Seq<Bore> bores) {
        IntMap<IntSeq> footprints = new IntMap<>();
        IntMap<IntSeq> corridors = new IntMap<>();
        for (int i = 0; i < bores.size; i++) {
            for (int cell : bores.get(i).footprint) index(footprints, cell, i);
            for (int cell : bores.get(i).corridor) index(corridors, cell, i);
        }

        int[][] result = new int[bores.size][];
        int[] stamp = new int[bores.size];
        Arrays.fill(stamp, -1);
        IntSeq found = new IntSeq();
        for (int i = 0; i < bores.size; i++) {
            stamp[i] = i;
            found.clear();
            Bore bore = bores.get(i);
            // Whatever overlaps this bore or stands in its corridor, and whoever's corridor crosses this bore.
            for (int cell : bore.footprint) {
                collect(footprints.get(cell), stamp, i, found);
                collect(corridors.get(cell), stamp, i, found);
            }
            for (int cell : bore.corridor) collect(footprints.get(cell), stamp, i, found);
            result[i] = found.toArray();
        }
        return result;
    }

    private static void index(IntMap<IntSeq> map, int cell, int bore) {
        IntSeq list = map.get(cell);
        if (list == null) map.put(cell, list = new IntSeq());
        list.add(bore);
    }

    private static void collect(IntSeq list, int[] stamp, int owner, IntSeq found) {
        if (list == null) return;
        for (int i = 0; i < list.size; i++) {
            int other = list.get(i);
            if (stamp[other] != owner) {
                stamp[other] = owner;
                found.add(other);
            }
        }
    }

    /** A conflict-free set of bores under construction. */
    private static final class Packing {
        private final Seq<Bore> bores;
        private final int[][] conflicts;
        final boolean[] selected;
        private final int[] blockers;
        long total;

        Packing(Seq<Bore> bores, int[][] conflicts) {
            this.bores = bores;
            this.conflicts = conflicts;
            this.selected = new boolean[bores.size];
            this.blockers = new int[bores.size];
        }

        private int weight(int i) {
            return bores.get(i).weight;
        }

        private boolean free(int i) {
            return !selected[i] && blockers[i] == 0;
        }

        private void add(int i) {
            selected[i] = true;
            total += weight(i);
            for (int other : conflicts[i]) blockers[other]++;
        }

        private void remove(int i) {
            selected[i] = false;
            total -= weight(i);
            for (int other : conflicts[i]) blockers[other]--;
        }

        void greedy(Integer[] order) {
            for (int i : order) if (free(i)) add(i);
        }

        /** Local search: a bore replaces its conflicts when it is worth more, one bore is traded for several. */
        void improve() {
            boolean changed = true;
            for (int pass = 0; changed && pass < 20; pass++) {
                changed = false;
                for (int i = 0; i < bores.size; i++) {
                    if (selected[i] || blockers[i] == 0) continue;
                    long lost = 0;
                    for (int other : conflicts[i]) if (selected[other]) lost += weight(other);
                    if (weight(i) <= lost) continue;

                    for (int other : conflicts[i]) if (selected[other]) remove(other);
                    add(i);
                    changed = true;
                }

                for (int i = 0; i < bores.size; i++) {
                    if (selected[i]) changed |= split(i);
                }
            }
        }

        /** Replaces a selected bore with the better set of bores that only it was blocking. */
        private boolean split(int i) {
            List<Integer> freed = new ArrayList<>();
            for (int other : conflicts[i]) if (!selected[other] && blockers[other] == 1) freed.add(other);
            if (freed.size() < 2) return false;
            Collections.sort(freed, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Integer.compare(weight(b), weight(a));
                }
            });

            remove(i);
            List<Integer> picked = new ArrayList<>();
            long gained = 0;
            for (int other : freed) {
                if (!free(other)) continue;
                add(other);
                picked.add(other);
                gained += weight(other);
            }
            if (gained > weight(i)) return true;

            for (int other : picked) remove(other);
            add(i);
            return false;
        }
    }

    // === duct routing ========================================================

    /** The accepted part of a selection: connected bores, their ducts and the bores that could not be connected. */
    private static final class Layout {
        final Seq<Bore> bores = new Seq<>();
        final Seq<BuildPlan> ducts = new Seq<>();
        final IntSet ductCells = new IntSet();
        /** The tile after the outlet stays empty so that nothing is fed back into the tree. */
        final IntSet reserved = new IntSet();
        final ObjectSet<Bore> dropped = new ObjectSet<>();

        int hits() {
            int hits = 0;
            for (Bore bore : bores) hits += bore.hits;
            return hits;
        }
    }

    private static final class Step {
        final int cell;
        final int dir;
        final int cost;
        final Step previous;

        Step(int cell, int dir, int cost, Step previous) {
            this.cell = cell;
            this.dir = dir;
            this.cost = cost;
            this.previous = previous;
        }
    }

    /**
     * Connects every bore to one outlet with a tree of ducts. The outlet is the start tile farthest towards the
     * chosen output side, followed by one more duct pointing the same way. Bores that cannot be connected are
     * reported as dropped.
     */
    private static Layout route(Seq<Bore> selected, Direction output, Terrain terrain) {
        Layout layout = new Layout();
        IntSet occupied = new IntSet();
        for (Bore bore : selected) for (int cell : bore.footprint) occupied.add(cell);

        int root = chooseRoot(selected, output, occupied, terrain);
        if (root < 0) {
            for (Bore bore : selected) layout.dropped.add(bore);
            return layout;
        }

        int width = terrain.width;
        IntIntMap tree = new IntIntMap();
        int outlet = root + output.p.x + output.p.y * width;
        for (int cell : new int[]{root, outlet}) {
            tree.put(cell, output.r);
            occupied.add(cell);
            layout.ducts.add(new BuildPlan(cell % width, cell / width, output.r, Blocks.duct));
        }
        int beyond = outlet + output.p.x + output.p.y * width;
        if (terrain.inside(outlet % width + output.p.x, outlet / width + output.p.y)) {
            occupied.add(beyond);
            layout.reserved.add(beyond);
        }

        int minX = terrain.width, minY = terrain.height, maxX = 0, maxY = 0;
        for (Bore bore : selected) {
            for (int cell : bore.footprint) {
                minX = Math.min(minX, cell % width);
                maxX = Math.max(maxX, cell % width);
                minY = Math.min(minY, cell / width);
                maxY = Math.max(maxY, cell / width);
            }
        }
        minX = Math.max(0, Math.min(minX, root % width) - ROUTE_MARGIN);
        minY = Math.max(0, Math.min(minY, root / width) - ROUTE_MARGIN);
        maxX = Math.min(terrain.width - 1, Math.max(maxX, root % width) + ROUTE_MARGIN);
        maxY = Math.min(terrain.height - 1, Math.max(maxY, root / width) + ROUTE_MARGIN);

        // Nearest bores first so that farther ones merge into the existing tree.
        List<Bore> order = new ArrayList<>();
        for (Bore bore : selected) order.add(bore);
        final int rootX = root % width, rootY = root / width;
        Collections.sort(order, new Comparator<Bore>() {
            @Override
            public int compare(Bore a, Bore b) {
                return Integer.compare(distance(a), distance(b));
            }

            private int distance(Bore bore) {
                int cell = bore.footprint[0];
                return Math.abs(cell % width - rootX) + Math.abs(cell / width - rootY);
            }
        });

        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        for (Bore bore : order) {
            boolean connected = false;
            for (int cell : bore.ring) {
                // A duct whose front is the bore does not take items from it.
                if (tree.containsKey(cell) && !faces(cell, tree.get(cell, 0), bore, width)) connected = true;
            }
            if (connected) {
                layout.bores.add(bore);
                continue;
            }

            PriorityQueue<Step> queue = new PriorityQueue<>(64, new Comparator<Step>() {
                @Override
                public int compare(Step a, Step b) {
                    return Integer.compare(a.cost, b.cost);
                }
            });
            IntSet visited = new IntSet();
            for (int port : bore.ports) if (!occupied.contains(port)) queue.add(new Step(port, -1, 0, null));

            Step goal = null;
            int goalDirection = -1;
            while (!queue.isEmpty() && goal == null) {
                Step step = queue.poll();
                if (!visited.add(step.cell * 5 + step.dir + 1)) continue;

                int cx = step.cell % width, cy = step.cell / width;
                for (int d = 0; d < 4; d++) {
                    int nx = cx + dx[d], ny = cy + dy[d];
                    if (nx < minX || ny < minY || nx > maxX || ny > maxY) continue;

                    int next = nx + ny * width;
                    if (tree.containsKey(next)) {
                        goal = step;
                        goalDirection = d;
                        break;
                    }
                    if (occupied.contains(next) || !terrain.duct(nx, ny)) continue;
                    int turn = step.dir != -1 && step.dir != d ? 3 : 0;
                    queue.add(new Step(next, d, step.cost + 10 + turn, step));
                }
            }
            if (goal == null) {
                layout.dropped.add(bore);
                continue;
            }

            // Every duct points to the next one on the way to the tree.
            int rotation = goalDirection;
            for (Step step = goal; step != null; step = step.previous) {
                tree.put(step.cell, rotation);
                occupied.add(step.cell);
                layout.ducts.add(new BuildPlan(step.cell % width, step.cell / width, rotation, Blocks.duct));
                rotation = step.dir;
            }
            layout.bores.add(bore);
        }

        for (IntIntMap.Keys keys = tree.keys(); keys.hasNext; ) layout.ductCells.add(keys.next());
        return layout;
    }

    private static boolean faces(int cell, int rotation, Bore bore, int width) {
        Direction direction = Direction.values()[rotation];
        int front = cell + direction.p.x + direction.p.y * width;
        for (int tile : bore.footprint) if (tile == front) return true;
        return false;
    }

    /**
     * The start tile of the duct tree: the output-most port that has a free tile for the outlet duct behind it,
     * preferably with a free buildable tile after the outlet so that the line can be continued. Ties go to the one
     * closest to the middle of the field.
     */
    private static int chooseRoot(Seq<Bore> selected, Direction output, IntSet occupied, Terrain terrain) {
        int width = terrain.width;
        double centerX = 0, centerY = 0;
        for (Bore bore : selected) {
            centerX += bore.footprint[0] % width;
            centerY += bore.footprint[0] / width;
        }
        centerX /= selected.size;
        centerY /= selected.size;

        int best = -1, bestTier = 0, bestReach = 0;
        double bestDistance = 0;
        for (Bore bore : selected) {
            for (int port : bore.ports) {
                int x = port % width, y = port / width;
                int outletX = x + output.p.x, outletY = y + output.p.y;
                if (occupied.contains(port) || !terrain.inside(outletX, outletY)
                    || occupied.contains(terrain.cell(outletX, outletY)) || !terrain.duct(outletX, outletY)) continue;

                int beyondX = outletX + output.p.x, beyondY = outletY + output.p.y;
                int tier = terrain.inside(beyondX, beyondY) && !occupied.contains(terrain.cell(beyondX, beyondY))
                    && terrain.duct(beyondX, beyondY) ? 0 : 1;
                int reach = x * output.p.x + y * output.p.y;
                double distance = Math.abs(x - centerX) + Math.abs(y - centerY);

                boolean better = best < 0 || tier < bestTier
                    || (tier == bestTier && (reach > bestReach || (reach == bestReach && (distance < bestDistance
                    || (distance == bestDistance && port < best)))));
                if (better) {
                    best = port;
                    bestTier = tier;
                    bestReach = reach;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    // === helpers =============================================================

    private static void notifyNoPlacement() {
        if (Vars.ui != null) Vars.ui.showInfoToast(bundle.get("auto-drill.no-placement"), 4f);
    }
}
