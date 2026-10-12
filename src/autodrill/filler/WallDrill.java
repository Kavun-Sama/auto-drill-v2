package autodrill.filler;

import arc.Core;
import arc.math.geom.Point2;
import arc.struct.IntIntMap;
import arc.struct.IntSet;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static arc.Core.bundle;

/**
 * Fills an ore wall with beam drills (plasma bores).
 *
 * <ol>
 *     <li>Every placement whose beams reach the clicked ore is enumerated.</li>
 *     <li>Bores never share rows along the wall, so a set of bores is a set of disjoint row intervals; the
 *     set with the most mined wall tiles (then the fewest bores) is found by dynamic programming.</li>
 *     <li>Beam nodes are reserved, then every bore is connected to a single duct outlet by a routed duct tree.</li>
 * </ol>
 */
public class WallDrill {
    private static final int ROUTE_MARGIN = 8;
    private static final int NODE_RANGE = 10;
    private static final int NODE_STEP = 5;

    public static void fill(Tile tile, BeamDrill drill, Direction direction) {
        Team team = Vars.player.team();
        Item resource = tile.wallDrop();
        if (resource == null) return;

        Seq<Bore> candidates = candidates(collectOre(tile, resource, direction), drill, direction, resource, team);
        Seq<Bore> bores = select(candidates, drill, direction);
        if (bores.isEmpty()) {
            notifyNoPlacement();
            return;
        }

        IntSet occupied = new IntSet();
        for (Bore bore : bores) addFootprint(occupied, bore.plan);

        reserveBeamNodes(bores, direction, team, occupied);
        Seq<BuildPlan> ducts = new Seq<>();
        Seq<Bore> connected = routeDucts(bores, direction, team, occupied, ducts);
        if (connected.isEmpty()) {
            notifyNoPlacement();
            return;
        }

        // Bores that could not be connected are dropped together with their nodes.
        Seq<BuildPlan> accepted = new Seq<>();
        for (Bore bore : connected) {
            accepted.add(bore.plan);
            accepted.addAll(bore.nodes);
        }
        accepted.addAll(ducts);

        Util.commitPlans(accepted);

        if (Util.DEBUG) {
            Log.info("[AutoDrill] WallDrill: committed " + accepted.size + " plans for " + drill.name);
        }
    }

    // === placement candidates ================================================

    /** One valid bore placement. */
    private static final class Bore {
        int x, y;
        int hits;
        int rowStart;
        int depth;
        int[] backs;
        BuildPlan plan;
        Seq<BuildPlan> nodes = new Seq<>();
    }

    private static int row(Direction direction, int x, int y) {
        return direction.p.x != 0 ? y : x;
    }

    /**
     * Ore wall tiles of the clicked patch that a beam can hit, i.e. the ones with open space in front of them.
     * The patch is flood filled through all of its tiles; only the hittable ones count towards the size limit.
     */
    private static Seq<Tile> collectOre(Tile start, Item resource, Direction direction) {
        int baseSetting = Core.settings.getInt("mechanical-drill-max-tiles", 200);
        int maxTiles = (int) (baseSetting * 0.25f);

        Queue<Tile> queue = new Queue<>();
        IntSet visited = new IntSet();
        Seq<Tile> exposed = new Seq<>();
        int width = Vars.world.width();

        queue.addLast(start);
        visited.add(start.x + start.y * width);
        while (!queue.isEmpty() && exposed.size < maxTiles && visited.size < maxTiles * 10) {
            Tile current = queue.removeFirst();
            if (current.wallDrop() != resource) continue;

            Tile front = current.nearby(-direction.p.x, -direction.p.y);
            if (front != null && !front.solid()) exposed.add(current);

            // Jagged walls: tiles up to two apart still belong to the same patch.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Tile next = current.nearby(dx, dy);
                    if (next != null && visited.add(next.x + next.y * width)) queue.addLast(next);
                }
            }
        }
        return exposed;
    }

    private static Seq<Bore> candidates(Seq<Tile> ore, BeamDrill drill, Direction direction, Item resource, Team team) {
        Seq<Bore> result = new Seq<>();
        IntSet seen = new IntSet();
        int width = Vars.world.width();
        Point2 offset = new Point2();

        for (int lane = 0; lane < drill.size; lane++) {
            drill.nearbySide(0, 0, direction.r, lane, offset);
            int offsetX = offset.x, offsetY = offset.y;
            for (Tile target : ore) {
                for (int distance = 0; distance < drill.range; distance++) {
                    int x = target.x - offsetX - direction.p.x * distance;
                    int y = target.y - offsetY - direction.p.y * distance;
                    if (x < 0 || y < 0 || x >= width || y >= Vars.world.height()) continue;
                    if (!seen.add(x + y * width)) continue;

                    Bore bore = evaluate(x, y, drill, direction, resource, team);
                    if (bore != null) result.add(bore);
                }
            }
        }
        return result;
    }

    private static Bore evaluate(int x, int y, BeamDrill drill, Direction direction, Item resource, Team team) {
        if (!Util.canPlaceBlock(drill, team, x, y, direction.r)) return null;

        Point2 side = new Point2();
        int hits = 0;
        for (int lane = 0; lane < drill.size; lane++) {
            drill.nearbySide(x, y, direction.r, lane, side);
            for (int distance = 0; distance < drill.range; distance++) {
                Tile other = Vars.world.tile(side.x + direction.p.x * distance, side.y + direction.p.y * distance);
                if (other == null) break;
                if (other.solid()) {
                    Item item = other.wallDrop();
                    if (item != null && item.hardness <= drill.tier
                        && (drill.blockedItems == null || !drill.blockedItems.contains(item))) {
                        // Mixed beams would slow the drill and mix the output: reject such placements.
                        if (item != resource) return null;
                        hits++;
                    }
                    break;
                }
            }
        }
        if (hits == 0) return null;

        // The output duct needs at least one free tile behind the bore.
        int width = Vars.world.width();
        int[] backs = new int[drill.size];
        int count = 0;
        for (int lane = 0; lane < drill.size; lane++) {
            drill.nearbySide(x, y, (direction.r + 2) % 4, lane, side);
            if (Util.canPlaceBlock(Blocks.duct, team, side.x, side.y, 0)) backs[count++] = side.x + side.y * width;
        }
        if (count == 0) return null;

        Bore bore = new Bore();
        bore.x = x;
        bore.y = y;
        bore.hits = hits;
        bore.rowStart = row(direction, x, y) - (drill.size - 1) / 2;
        bore.depth = x * direction.p.x + y * direction.p.y;
        bore.backs = java.util.Arrays.copyOf(backs, count);
        bore.plan = new BuildPlan(x, y, direction.r, drill);
        return bore;
    }

    // === selection ===========================================================

    /** Weighted interval scheduling over rows: the most mined tiles, then the fewest bores. */
    private static Seq<Bore> select(Seq<Bore> candidates, BeamDrill drill, Direction direction) {
        Seq<Bore> selected = new Seq<>();
        if (candidates.isEmpty()) return selected;

        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;
        for (Bore bore : candidates) {
            minRow = Math.min(minRow, bore.rowStart);
            maxRow = Math.max(maxRow, bore.rowStart);
        }
        int rows = maxRow - minRow + 1;

        // Best placement per start row: most hits, then closest to the wall (shortest ducts).
        Bore[] best = new Bore[rows];
        for (Bore bore : candidates) {
            Bore current = best[bore.rowStart - minRow];
            if (current == null || bore.hits > current.hits
                || (bore.hits == current.hits && bore.depth > current.depth)) {
                best[bore.rowStart - minRow] = bore;
            }
        }

        long[] value = new long[rows + drill.size + 1];
        for (int r = rows - 1; r >= 0; r--) {
            value[r] = value[r + 1];
            if (best[r] != null) {
                long take = best[r].hits * 1000L - 1 + value[r + drill.size];
                if (take > value[r]) value[r] = take;
            }
        }

        int r = 0;
        while (r < rows) {
            if (best[r] != null && value[r] != value[r + 1]) {
                selected.add(best[r]);
                r += drill.size;
            } else {
                r++;
            }
        }
        return selected;
    }

    // === beam nodes ==========================================================

    /** Position on the line behind the bores: {@code depth} along the facing direction, {@code row} across it. */
    private static Tile at(Direction direction, int depth, int row) {
        int x = direction.p.x != 0 ? depth * direction.p.x : row;
        int y = direction.p.x != 0 ? row : depth * direction.p.y;
        return Vars.world.tile(x, y);
    }

    private static int deepest(Seq<Bore> bores, Direction direction) {
        int deepest = Integer.MAX_VALUE;
        int width = Vars.world.width();
        for (Bore bore : bores) {
            for (int back : bore.backs) {
                deepest = Math.min(deepest, (back % width) * direction.p.x + (back / width) * direction.p.y);
            }
        }
        return deepest;
    }

    private static void reserveBeamNodes(Seq<Bore> bores, Direction direction, Team team, IntSet occupied) {
        int column = deepest(bores, direction) - 2;
        int width = Vars.world.width();

        for (Bore bore : bores) {
            int depth = column;
            while (true) {
                Tile node = at(direction, depth, bore.rowStart);
                if (node == null) break;

                BuildPlan plan = new BuildPlan(node.x, node.y, 0, Blocks.beamNode);
                if (Util.canPlaceBlock(Blocks.beamNode, team, node.x, node.y, 0) && !occupied.contains(node.x + node.y * width)) {
                    bore.nodes.add(plan);
                    occupied.add(node.x + node.y * width);
                }
                // Chain nodes towards the bore while it is out of laser range.
                if (bore.depth - depth <= NODE_RANGE) break;
                depth += NODE_STEP;
            }
        }
    }

    // === duct routing ========================================================

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
     * Connects every bore to one outlet with a tree of ducts. The outlet is the back tile farthest from the wall,
     * followed by one more duct away from the wall. Returns the bores that got connected.
     */
    private static Seq<Bore> routeDucts(Seq<Bore> bores, Direction direction, Team team, IntSet occupied,
                                        Seq<BuildPlan> ductPlans) {
        int width = Vars.world.width(), height = Vars.world.height();
        Direction away = Direction.getOpposite(direction);
        Seq<Bore> connected = new Seq<>();

        int rootCell = -1, rootDepth = Integer.MAX_VALUE;
        for (Bore bore : bores) {
            for (int back : bore.backs) {
                int depth = (back % width) * direction.p.x + (back / width) * direction.p.y;
                if (occupied.contains(back)) continue;
                if (depth < rootDepth || (depth == rootDepth && back < rootCell)) {
                    rootDepth = depth;
                    rootCell = back;
                }
            }
        }
        if (rootCell < 0) return connected;

        IntIntMap tree = new IntIntMap();
        tree.put(rootCell, away.r);
        ductPlans.add(new BuildPlan(rootCell % width, rootCell / width, away.r, Blocks.duct));
        occupied.add(rootCell);
        int outX = rootCell % width + away.p.x, outY = rootCell / width + away.p.y;
        if (outX >= 0 && outY >= 0 && outX < width && outY < height && !occupied.contains(outX + outY * width)
            && Util.canPlaceBlock(Blocks.duct, team, outX, outY, away.r)) {
            tree.put(outX + outY * width, away.r);
            ductPlans.add(new BuildPlan(outX, outY, away.r, Blocks.duct));
            occupied.add(outX + outY * width);
        }

        int minX = width, minY = height, maxX = 0, maxY = 0;
        for (Bore bore : bores) {
            for (int back : bore.backs) {
                minX = Math.min(minX, back % width);
                maxX = Math.max(maxX, back % width);
                minY = Math.min(minY, back / width);
                maxY = Math.max(maxY, back / width);
            }
        }
        minX = Math.max(0, Math.min(minX, rootCell % width) - ROUTE_MARGIN);
        minY = Math.max(0, Math.min(minY, rootCell / width) - ROUTE_MARGIN);
        maxX = Math.min(width - 1, Math.max(maxX, rootCell % width) + ROUTE_MARGIN);
        maxY = Math.min(height - 1, Math.max(maxY, rootCell / width) + ROUTE_MARGIN);

        // Nearest bores first so that farther ones merge into the existing tree.
        final int rx = rootCell % width, ry = rootCell / width;
        List<Bore> order = new ArrayList<>();
        for (Bore bore : bores) order.add(bore);
        Collections.sort(order, new Comparator<Bore>() {
            @Override
            public int compare(Bore a, Bore b) {
                return Integer.compare(distance(a), distance(b));
            }

            private int distance(Bore bore) {
                int best = Integer.MAX_VALUE;
                for (int back : bore.backs) best = Math.min(best, Math.abs(back % width - rx) + Math.abs(back / width - ry));
                return best;
            }
        });

        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        for (Bore bore : order) {
            boolean done = false;
            for (int back : bore.backs) if (tree.containsKey(back)) done = true;
            if (done) {
                connected.add(bore);
                continue;
            }

            PriorityQueue<Step> queue = new PriorityQueue<>(64, new Comparator<Step>() {
                @Override
                public int compare(Step a, Step b) {
                    return Integer.compare(a.cost, b.cost);
                }
            });
            IntSet visited = new IntSet();
            for (int back : bore.backs) if (!occupied.contains(back)) queue.add(new Step(back, -1, 0, null));

            Step goal = null;
            int goalDir = -1;
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
                        goalDir = d;
                        break;
                    }
                    if (occupied.contains(next) || !Util.canPlaceBlock(Blocks.duct, team, nx, ny, 0)) continue;
                    queue.add(new Step(next, d, step.cost + 10 + (step.dir != -1 && step.dir != d ? 3 : 0), step));
                }
            }
            if (goal == null) continue;

            // Rotations follow the path towards the tree.
            int nextDir = goalDir;
            for (Step step = goal; step != null; step = step.previous) {
                tree.put(step.cell, nextDir);
                occupied.add(step.cell);
                ductPlans.add(new BuildPlan(step.cell % width, step.cell / width, nextDir, Blocks.duct));
                nextDir = step.dir;
            }
            connected.add(bore);
        }
        return connected;
    }

    // === helpers =============================================================

    private static void addFootprint(IntSet cells, BuildPlan plan) {
        int low = (plan.block.size - 1) / 2;
        for (int dx = 0; dx < plan.block.size; dx++) {
            for (int dy = 0; dy < plan.block.size; dy++) {
                cells.add(plan.x - low + dx + (plan.y - low + dy) * Vars.world.width());
            }
        }
    }

    private static void notifyNoPlacement() {
        if (Vars.ui != null) Vars.ui.showInfoToast(bundle.get("auto-drill.no-placement"), 4f);
    }
}
