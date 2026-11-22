package dev.soityy.trajectorylens.client.track;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Reachable-region ("how far could this walking mob get in T ticks") engine.
 *
 * Pure geometric upper bound, deliberately AI-agnostic: any cell the mob could
 * physically reach while walking/jumping is included. Real AI pauses and turns
 * only shrink the used region, so this is a true "maximum walking range".
 *
 * v1 rules: walk on solid ground; auto-jump 1 block up (11 tick penalty);
 * free falling allowed; water slows to 45% speed; lava/void are walls;
 * closed doors, fences and walls are walls; headroom = entity height.
 */
public final class WalkRangeEngine {

    public static final int MAX_CELL_RADIUS = 40;
    private static final int JUMP_PENALTY_TICKS = 11;
    private static final double WATER_SLOW = 0.45;

    private static final byte KNOWN = 1;
    private static final byte LOADED = 2;
    private static final byte AIRISH = 4;
    private static final byte WATER = 8;
    private static final byte SOLID = 16;
    private static final byte FLOOR = 32;

    private WalkRangeEngine() {
    }

    public static final class Region {
        public final List<Vec3> boundarySegments = new ArrayList<>();
        public final LongArrayList cellKeys = new LongArrayList();
        public int cellCount;
        public double usedTicks; // cheapest explored cell cost (debug/info)
    }

    public static final class BlockCache {
        private final Level level;
        private final Long2ByteOpenHashMap flags = new Long2ByteOpenHashMap();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        public BlockCache(Level level) {
            this.level = level;
        }

        private int flags(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            byte f = this.flags.get(k);
            if (f == 0) {
                f = this.classify(x, y, z);
                this.flags.put(k, f);
            }
            return f;
        }

        private byte classify(int x, int y, int z) {
            BlockPos.MutableBlockPos p = this.cursor.set(x, y, z);
            if (!this.level.isLoaded(p)) {
                return KNOWN;
            }
            BlockState s = this.level.getBlockState(p);
            FluidState fluid = s.getFluidState();
            boolean water = fluid.is(FluidTags.WATER);
            boolean lava = fluid.is(FluidTags.LAVA);
            boolean barrier = s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES);
            boolean full = s.isCollisionShapeFullBlock(this.level, p);
            int f = KNOWN | LOADED;
            if (s.isAir() || water || (!lava && !barrier && !full)) {
                f |= AIRISH; // swim/wade
            }
            if (water) {
                f |= WATER;
            }
            if (barrier || full) {
                f |= SOLID; // treat as climb walls / blockers
            }
            if (!lava && !barrier && full) {
                f |= FLOOR; // cannot stand on top of these for pathing purposes
            }
            return (byte) f;
        }

        private boolean airish(int x, int y, int z) {
            return (this.flags(x, y, z) & AIRISH) != 0;
        }

        private boolean water(int x, int y, int z) {
            return (this.flags(x, y, z) & WATER) != 0;
        }

        private boolean solid(int x, int y, int z) {
            return (this.flags(x, y, z) & SOLID) != 0;
        }

        private boolean floor(int x, int y, int z) {
            return (this.flags(x, y, z) & FLOOR) != 0;
        }
    }

    /** Decode a packed cell key back into (x, y, z) block coordinates. */
    public static int cellX(long k) {
        return BlockPos.getX(k);
    }

    public static int cellY(long k) {
        return BlockPos.getY(k);
    }

    public static int cellZ(long k) {
        return BlockPos.getZ(k);
    }

    /**
     * @param speedBlocksPerTick attribute walking speed (blocks per tick)
     * @param heightCeil        ceil(bbHeight) in blocks
     * @param startX/startY/startZ feet position
     */
    public static Region compute(BlockCache cache, double speedBlocksPerTick, int heightCeil,
                                 double startX, double startY, double startZ, int budgetTicks) {
        Region out = new Region();
        if (speedBlocksPerTick <= 0.001 || heightCeil <= 0 || cache == null) {
            return out;
        }
        int fx = (int) Math.floor(startX);
        int fy = (int) Math.floor(startY);
        int fz = (int) Math.floor(startZ);
        int minY = cache.level.getMinY();

        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.MAX_VALUE);
        CostQueue queue = new CostQueue();
        long startKey = key(fx, fy, fz);
        best.put(startKey, 0.0);
        queue.push(startKey, 0.0);
        LongOpenHashSet closed = new LongOpenHashSet();
        while (!queue.isEmpty()) {
            double cost = queue.peekCost();
            long k = queue.pop();
            if (cost > best.get(k) + 1.0E-9 || !closed.add(k)) {
                continue;
            }
            out.cellKeys.add(k);
            out.cellCount++;
            if (cost > out.usedTicks) {
                out.usedTicks = cost;
            }
            int x = BlockPos.getX(k);
            int y = BlockPos.getY(k);
            int z = BlockPos.getZ(k);
            boolean waterHere = cache.water(x, y, z);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    double horiz = Math.sqrt(dx * dx + dz * dz) / speedBlocksPerTick;
                    int nDist = Math.max(Math.abs(nx - fx), Math.abs(nz - fz));
                    if (nDist > MAX_CELL_RADIUS) {
                        continue;
                    }
                    double waterCost = (waterHere || cache.water(nx, y, nz)) ? horiz / WATER_SLOW - horiz : 0.0;

                    // 1) same level
                    tryAdd(cache, best, queue, nx, y, nz, cost + horiz + waterCost, heightCeil, budgetTicks);
                    // 2) one block up (auto jump on top of a full block)
                    if (cache.solid(nx, y, nz) && isStandableAt(cache, nx, y + 1, nz, heightCeil, true)) {
                        tryAdd(cache, best, queue, nx, y + 1, nz, cost + horiz + JUMP_PENALTY_TICKS, heightCeil, budgetTicks);
                    }
                    // 3) fall down to first solid floor
                    if (cache.airish(nx, y, nz)) {
                        int gy = y - 1;
                        int limit = Math.max(minY + 1, y - 24);
                        while (gy > limit && cache.airish(nx, gy, nz)) {
                            gy--;
                        }
                        if (gy > limit && isStandableAt(cache, nx, gy + 1, nz, heightCeil, true)) {
                            // falling is "free" in time for the upper bound; charge a small tick for realism
                            tryAdd(cache, best, queue, nx, gy + 1, nz, cost + horiz + 3.0, heightCeil, budgetTicks);
                        }
                    }
                }
            }
        }

        // boundary: reachable cell whose 4-neighbor at the same level is not reachable
        for (LongIterator it = closed.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int x = BlockPos.getX(k);
            int y = BlockPos.getY(k);
            int z = BlockPos.getZ(k);
            emitEdge(out.boundarySegments, closed, x, y, z, 1, 0);
            emitEdge(out.boundarySegments, closed, x, y, z, -1, 0);
            emitEdge(out.boundarySegments, closed, x, y, z, 0, 1);
            emitEdge(out.boundarySegments, closed, x, y, z, 0, -1);
        }
        return out;
    }

    private static void emitEdge(List<Vec3> segs, LongOpenHashSet cells, int x, int y, int z, int dx, int dz) {
        long nk = key(x + dx, y, z + dz);
        if (cells.contains(nk)) {
            return;
        }
        // IMPORTANT: block column n occupies [n, n+1); its edges are at INTEGER
        // coordinates. All range geometry must sit on integers to align with blocks.
        double top = y + 0.05;
        if (dx == 1) {
            segs.add(new Vec3(x + 1.0, top, z));
            segs.add(new Vec3(x + 1.0, top, z + 1.0));
        } else if (dx == -1) {
            segs.add(new Vec3(x, top, z));
            segs.add(new Vec3(x, top, z + 1.0));
        } else if (dz == 1) {
            segs.add(new Vec3(x, top, z + 1.0));
            segs.add(new Vec3(x + 1.0, top, z + 1.0));
        } else {
            segs.add(new Vec3(x, top, z));
            segs.add(new Vec3(x + 1.0, top, z));
        }
    }

    private static void tryAdd(BlockCache cache, Long2DoubleOpenHashMap best, CostQueue queue,
                               int x, int y, int z, double cost, int heightCeil, int budgetTicks) {
        if (cost > budgetTicks) {
            return;
        }
        if (!isStandableAt(cache, x, y, z, heightCeil, false)) {
            return;
        }
        long k = key(x, y, z);
        if (best.get(k) <= cost) {
            return;
        }
        best.put(k, cost);
        queue.push(k, cost);
    }

    /** A mob can stand here: feet cell clear (or water), proper headroom, valid floor. */
    private static boolean isStandableAt(BlockCache cache, int x, int y, int z, int heightCeil, boolean floorPreValidated) {
        int feet = cache.flags(x, y, z);
        if ((feet & AIRISH) == 0) {
            return false;
        }
        // headroom above feet
        for (int h = 1; h < heightCeil; h++) {
            if (!cache.airish(x, y + h, z)) {
                return false;
            }
        }
        if ((feet & WATER) != 0) {
            return true; // swimming / wading
        }
        if (floorPreValidated) {
            return true; // caller already verified the support below
        }
        return cache.floor(x, y - 1, z);
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Public alias for packed cell key (same encoding as stored in Region.cellKeys). */
    public static long keyOf(int x, int y, int z) {
        return key(x, y, z);
    }

    private static final class CostQueue {
        private long[] keys = new long[256];
        private double[] costs = new double[256];
        private int size;

        boolean isEmpty() {
            return this.size == 0;
        }

        double peekCost() {
            return this.costs[0];
        }

        void push(long key, double cost) {
            if (this.size == this.keys.length) {
                this.keys = Arrays.copyOf(this.keys, this.size * 2);
                this.costs = Arrays.copyOf(this.costs, this.size * 2);
            }
            int i = this.size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (this.costs[parent] <= cost) {
                    break;
                }
                this.keys[i] = this.keys[parent];
                this.costs[i] = this.costs[parent];
                i = parent;
            }
            this.keys[i] = key;
            this.costs[i] = cost;
        }

        long pop() {
            long top = this.keys[0];
            int last = --this.size;
            if (last > 0) {
                long key = this.keys[last];
                double cost = this.costs[last];
                int i = 0;
                while (true) {
                    int child = 2 * i + 1;
                    if (child >= last) {
                        break;
                    }
                    if (child + 1 < last && this.costs[child + 1] < this.costs[child]) {
                        child++;
                    }
                    if (this.costs[child] >= cost) {
                        break;
                    }
                    this.keys[i] = this.keys[child];
                    this.costs[i] = this.costs[child];
                    i = child;
                }
                this.keys[i] = key;
                this.costs[i] = cost;
            }
            return top;
        }
    }
}
