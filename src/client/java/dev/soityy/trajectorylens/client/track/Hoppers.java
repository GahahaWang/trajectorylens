package dev.soityy.trajectorylens.client.track;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared vanilla hopper geometry. The suck volume is exactly what
 * {@code HopperBlockEntity.getItemsAtAndAbove} uses (SUCK_AABB = column(16, 11, 32)
 * moved into world space -&gt; x..x+1, y+0.1875..y+1.5, z..z+1), so anything built on
 * this helper agrees with the real game about which items a hopper can take.
 */
public final class Hoppers {

    /** A candidate hopper: a block hopper ({@code pos}) or a hopper minecart ({@code cart}). */
    public record Hit(BlockPos pos, MinecartHopper cart, boolean locked) {

        /** Stable map key for this hopper. */
        public long key() {
            return this.pos != null ? this.pos.asLong() : (0x4000_0000_0000_0000L | (this.cart != null ? this.cart.getId() : 0));
        }

        public Vec3 center() {
            if (this.pos != null) {
                return new Vec3(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5);
            }
            return this.cart != null ? this.cart.position().add(0, 0.35, 0) : Vec3.ZERO;
        }

        public AABB box() {
            if (this.pos != null) {
                return new AABB(this.pos);
            }
            return this.cart != null ? this.cart.getBoundingBox() : AABB.ofSize(Vec3.ZERO, 1, 1, 1);
        }
    }

    private record Candidate(BlockPos pos, boolean locked) {
    }

    public static final class Probe {
        private static final Predicate<BlockState> IS_HOPPER = s -> s.getBlock() instanceof HopperBlock;
        private static final Candidate[] NONE = new Candidate[0];
        private static final byte NO_HOPPER = 1;
        private static final byte MAYBE_HOPPER = 2;

        private final Level level;
        private final int r;
        private final int down;
        private final int up;
        private final Long2ObjectOpenHashMap<Candidate[]> byBase = new Long2ObjectOpenHashMap<>();
        private final Long2ByteOpenHashMap sections = new Long2ByteOpenHashMap();

        public Probe(Level level, int r, int down, int up) {
            this.level = level;
            this.r = r;
            this.down = down;
            this.up = up;
        }

        public Hit find(Vec3 at, double tol, boolean includeLocked) {
            return this.find(at, tol, includeLocked, List.of());
        }

        public Hit find(Vec3 at, double tol, boolean includeLocked, List<MinecartHopper> carts) {
            BlockPos base = BlockPos.containing(at);
            long baseKey = base.asLong();
            Candidate[] candidates = this.byBase.get(baseKey);
            if (candidates == null) {
                candidates = this.scan(base);
                this.byBase.put(baseKey, candidates);
            }
            Hit best = null;
            double bestD = Double.MAX_VALUE;
            for (Candidate c : candidates) {
                if (c.locked() && !includeLocked) {
                    continue;
                }
                double d = suckBox(c.pos()).distanceToSqr(at);
                if (d < bestD) {
                    bestD = d;
                    best = new Hit(c.pos(), null, c.locked());
                }
            }
            if (!carts.isEmpty()) {
                AABB near = new AABB(at, at).inflate(this.r + 1.5);
                for (MinecartHopper cart : carts) {
                    if (!cart.isAlive() || cart.isRemoved() || !cart.getBoundingBox().intersects(near)) {
                        continue;
                    }
                    double d = cart.getBoundingBox().distanceToSqr(at);
                    if (d < bestD) {
                        bestD = d;
                        best = new Hit(null, cart, false);
                    }
                }
            }
            return bestD <= tol * tol ? best : null;
        }

        private Candidate[] scan(BlockPos base) {
            if (!this.mayHaveHopper(base)) {
                return NONE;
            }
            List<Candidate> found = null;
            for (int dy = -this.down; dy <= this.up; dy++) {
                for (int dx = -this.r; dx <= this.r; dx++) {
                    for (int dz = -this.r; dz <= this.r; dz++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        BlockState st = this.level.getBlockState(p);
                        if (!(st.getBlock() instanceof HopperBlock)) {
                            continue;
                        }
                        if (found == null) {
                            found = new ArrayList<>();
                        }
                        found.add(new Candidate(p, !st.getValue(HopperBlock.ENABLED)));
                    }
                }
            }
            return found == null ? NONE : found.toArray(NONE);
        }

        private boolean mayHaveHopper(BlockPos base) {
            int sx0 = SectionPos.blockToSectionCoord(base.getX() - this.r);
            int sx1 = SectionPos.blockToSectionCoord(base.getX() + this.r);
            int sy0 = SectionPos.blockToSectionCoord(base.getY() - this.down);
            int sy1 = SectionPos.blockToSectionCoord(base.getY() + this.up);
            int sz0 = SectionPos.blockToSectionCoord(base.getZ() - this.r);
            int sz1 = SectionPos.blockToSectionCoord(base.getZ() + this.r);
            for (int sx = sx0; sx <= sx1; sx++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    for (int sy = sy0; sy <= sy1; sy++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        byte known = this.sections.get(key);
                        if (known == 0) {
                            known = this.sectionMayHaveHopper(sx, sy, sz) ? MAYBE_HOPPER : NO_HOPPER;
                            this.sections.put(key, known);
                        }
                        if (known == MAYBE_HOPPER) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean sectionMayHaveHopper(int sx, int sy, int sz) {
            LevelChunk chunk = this.level.getChunkSource().getChunkNow(sx, sz);
            if (chunk == null) {
                return false;
            }
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= chunk.getSectionsCount()) {
                return false;
            }
            LevelChunkSection section = chunk.getSection(index);
            return !section.hasOnlyAir() && section.maybeHas(IS_HOPPER);
        }
    }

    private Hoppers() {
    }

    public static AABB suckBox(BlockPos p) {
        return new AABB(p.getX(), p.getY() + 0.1875, p.getZ(), p.getX() + 1.0, p.getY() + 1.5, p.getZ() + 1.0);
    }

    /**
     * Nearest hopper able to swallow an item last seen at {@code at}: scans a
     * (2r+1) x (down+up+1) x (2r+1) block box around it plus hopper minecarts, and only
     * accepts a hit within {@code tol} blocks of the suck volume (or the cart body), so
     * an item merely lying beside a hopper is never blamed on it.
     *
     * @param includeLocked keep redstone-locked hoppers (they can never suck, but they are
     *                      exactly what you want to *show* when diagnosing a jam)
     */
    public static Hit find(Level level, Vec3 at, int r, int down, int up, double tol, boolean includeLocked) {
        return find(level, at, r, down, up, tol, includeLocked, true);
    }

    /**
     * @param checkCarts also look for hopper minecarts. Pass false from per-tick scans
     *                   (the entity lookup is the expensive half) and handle carts in bulk.
     */
    public static Hit find(Level level, Vec3 at, int r, int down, int up, double tol, boolean includeLocked, boolean checkCarts) {
        if (level == null || at == null) {
            return null;
        }
        BlockPos base = BlockPos.containing(at);
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -down; dy <= up; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    var st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof HopperBlock)) {
                        continue;
                    }
                    boolean locked = !st.getValue(HopperBlock.ENABLED);
                    if (locked && !includeLocked) {
                        continue;
                    }
                    double d = suckBox(p).distanceToSqr(at);
                    if (d < bestD) {
                        bestD = d;
                        best = new Hit(p, null, locked);
                    }
                }
            }
        }
        for (MinecartHopper cart : checkCarts
            ? level.getEntitiesOfClass(MinecartHopper.class, new AABB(at, at).inflate(r + 1.5))
            : java.util.List.<MinecartHopper>of()) {
            if (!cart.isAlive() || cart.isRemoved()) {
                continue;
            }
            double d = cart.getBoundingBox().distanceToSqr(at);
            if (d < bestD) {
                bestD = d;
                best = new Hit(null, cart, false);
            }
        }
        return bestD <= tol * tol ? best : null;
    }
}
