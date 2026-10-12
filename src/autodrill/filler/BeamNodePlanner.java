package autodrill.filler;

import arc.struct.IntIntMap;
import arc.struct.IntQueue;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerNode;

import java.util.List;

/**
 * Places beam nodes so that a set of power consumers ends up in one power graph.
 *
 * <p>A {@link BeamNode} links to the first power building in each cardinal direction within its range; lasers
 * pass over everything else and an insulated building stops them. Consumers do not conduct to each other, so each
 * one has to be linked by a node. Groups of linked buildings are joined one by one: a breadth-first search from a
 * group over free tiles on its laser lines finds the fewest nodes that reach another group.</p>
 */
final class BeamNodePlanner {
    private static final int NODE_KEY = 1 << 16;
    private static final int EXISTING_KEY = 1 << 20;
    private static final int NONE = -1;

    private final Team team;
    private final int range;
    private final int width = Vars.world.width();
    private final int height = Vars.world.height();
    private final List<int[]> consumers;
    private final IntSet blocked;

    /** Tile index to vertex: consumers are numbered from zero, nodes from {@link #NODE_KEY}. */
    private final IntIntMap owner = new IntIntMap();
    private final IntIntMap parent = new IntIntMap();
    private final IntIntMap placeable = new IntIntMap();
    private final Seq<BuildPlan> nodes = new Seq<>();

    private BeamNodePlanner(List<int[]> consumers, IntSet blocked, Team team) {
        this.consumers = consumers;
        this.blocked = blocked;
        this.team = team;
        this.range = ((BeamNode) Blocks.beamNode).range;
    }

    /**
     * @param consumers footprints (tile indices) of the buildings that need power
     * @param blocked   tiles that must stay free of nodes in addition to the consumer footprints
     */
    static Seq<BuildPlan> plan(List<int[]> consumers, IntSet blocked, Team team) {
        BeamNodePlanner planner = new BeamNodePlanner(consumers, blocked, team);
        planner.run();
        return planner.nodes;
    }

    private void run() {
        for (int i = 0; i < consumers.size(); i++) {
            for (int cell : consumers.get(i)) owner.put(cell, i);
        }

        rebuild();
        for (int guard = consumers.size(); guard > 0 && components() > 1; guard--) {
            if (!joinNearestGroup()) break;
        }
        // A lone consumer still gets a node, which is where the player attaches power.
        if (nodes.isEmpty()) attachFirstNode();
    }

    // === search ==============================================================

    /**
     * Joins the group of the first consumer with the nearest other group. Returns false when none is reachable.
     *
     * <p>Free tiles on the laser lines of the group are the first candidates; a node on one of them is linked to
     * the group. From every candidate the search continues along its own lines until a line hits another group.</p>
     */
    private boolean joinNearestGroup() {
        int source = find(0);
        IntQueue queue = new IntQueue();
        IntSet visited = new IntSet();
        IntIntMap previous = new IntIntMap();

        for (int i = 0; i < consumers.size(); i++) {
            if (find(i) != source) continue;
            for (int cell : consumers.get(i)) extend(cell, source, false, queue, visited, previous);
        }
        for (int k = 0; k < nodes.size; k++) {
            if (find(NODE_KEY + k) != source) continue;
            extend(nodes.get(k).x + nodes.get(k).y * width, source, false, queue, visited, previous);
        }

        while (!queue.isEmpty()) {
            if (extend(queue.removeFirst(), source, true, queue, visited, previous)) return true;
        }
        return false;
    }

    /**
     * Walks the four laser lines from a tile and queues the free tiles on them. When {@code searching}, a line that
     * reaches a building of another group ends the search and the chain of nodes up to {@code origin} is placed.
     */
    private boolean extend(int origin, int source, boolean searching, IntQueue queue, IntSet visited,
                           IntIntMap previous) {
        int x = origin % width, y = origin / width;
        for (Direction direction : Direction.values()) {
            for (int j = 1; j <= range; j++) {
                int cx = x + direction.p.x * j, cy = y + direction.p.y * j;
                if (cx < 0 || cy < 0 || cx >= width || cy >= height) break;

                int cell = cx + cy * width;
                int key = owner.get(cell, NONE);
                if (key != NONE) {
                    if (searching && find(key) != source) {
                        place(origin, previous);
                        return true;
                    }
                    break;
                }
                Building building = Vars.world.build(cx, cy);
                if (building != null && (building.isInsulated() || conducts(building))) break;

                if (!blocked.contains(cell) && visited.add(cell) && isPlaceable(cell)) {
                    previous.put(cell, searching ? origin : NONE);
                    queue.addLast(cell);
                }
            }
        }
        return false;
    }

    /** Places the chain of nodes that ends at {@code last}. */
    private void place(int last, IntIntMap previous) {
        IntSeq chain = new IntSeq();
        for (int cell = last; cell != NONE; cell = previous.get(cell, NONE)) chain.add(cell);
        for (int i = 0; i < chain.size; i++) addNode(chain.get(i));
    }

    private void attachFirstNode() {
        for (int j = 1; j <= range; j++) {
            for (int[] footprint : consumers) {
                for (int cell : footprint) {
                    for (Direction direction : Direction.values()) {
                        int x = cell % width + direction.p.x * j, y = cell / width + direction.p.y * j;
                        if (x < 0 || y < 0 || x >= width || y >= height) continue;

                        int target = x + y * width;
                        if (owner.containsKey(target) || blocked.contains(target) || !isPlaceable(target)) continue;
                        addNode(target);
                        return;
                    }
                }
            }
        }
    }

    private void addNode(int cell) {
        owner.put(cell, NODE_KEY + nodes.size);
        nodes.add(new BuildPlan(cell % width, cell / width, 0, Blocks.beamNode));
        rebuild();
    }

    // === link simulation =====================================================

    private static boolean conducts(Building building) {
        return building.block.hasPower && building.block.connectedPower && !(building.block instanceof PowerNode);
    }

    private void rebuild() {
        parent.clear();
        for (int k = 0; k < nodes.size; k++) {
            int x = nodes.get(k).x, y = nodes.get(k).y;
            for (Direction direction : Direction.values()) {
                for (int j = 1; j <= range; j++) {
                    int cx = x + direction.p.x * j, cy = y + direction.p.y * j;
                    if (cx < 0 || cy < 0 || cx >= width || cy >= height) break;

                    int key = owner.get(cx + cy * width, NONE);
                    if (key != NONE) {
                        union(NODE_KEY + k, key);
                        break;
                    }
                    Building building = Vars.world.build(cx, cy);
                    if (building == null) continue;
                    if (building.isInsulated()) break;
                    if (conducts(building) && building.team == team) {
                        union(NODE_KEY + k, EXISTING_KEY + cx + cy * width);
                        break;
                    }
                }
            }
        }
    }

    private int find(int key) {
        int up = parent.get(key, key);
        if (up == key) return key;
        int root = find(up);
        parent.put(key, root);
        return root;
    }

    private void union(int a, int b) {
        int rootA = find(a), rootB = find(b);
        if (rootA != rootB) parent.put(rootA, rootB);
    }

    private int components() {
        IntSet roots = new IntSet();
        for (int i = 0; i < consumers.size(); i++) roots.add(find(i));
        return roots.size;
    }

    private boolean isPlaceable(int cell) {
        int state = placeable.get(cell, 0);
        if (state == 0) {
            state = Util.canPlaceBlock(Blocks.beamNode, team, cell % width, cell / width, 0) ? 1 : 2;
            placeable.put(cell, state);
        }
        return state == 1;
    }
}
