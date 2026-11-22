package dev.soityy.trajectorylens.client.track;

import dev.soityy.trajectorylens.client.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Replays one projectile's flight with the same per-tick physics the game uses.
 * Arrows/throwables/fireballs use ray clipping; TNT uses real move() collision
 * (including its bounce) via a detached shadow entity.
 */
public final class ProjectileSim {

    public static final int HORIZON = 200; // ticks
    private static final int ENTITY_BATCH = 8;

    public record Hit(Vec3 pos, String label, boolean block, Direction face) {
    }

    private static final class ShadowTnt extends PrimedTnt {
        ShadowTnt(Level level) {
            super(level, 0, 0, 0, null);
        }

        @Override
        protected boolean isLocalClientAuthoritative() {
            return true;
        }
    }

    private record StepHit(int step, Hit hit) {
    }

    public static final class Result {
        public final List<Vec3> points = new ArrayList<>();
        public final List<Vec3> vels = new ArrayList<>();
        public ProjectileKind kind;
        public Hit hit;
        public int ticks;
        public int fuseTicks = -1;      // remaining fuse at the END of the simulated path (0 = detonates there)
        public int fuseRemaining = -1;  // fuse right now (for the on-screen countdown)
        public boolean exploded;
        public Vec3 explosionPos;

        public void advance(int n) {
            this.points.subList(0, n).clear();
            this.vels.subList(0, n).clear();
            this.ticks = Math.max(0, this.ticks - n);
        }
    }

    private final Level level;
    private final ItemEntity clipCtx;
    private PrimedTnt tntShadow;

    public ProjectileSim(Level level) {
        this.level = level;
        this.clipCtx = new ItemEntity(level, 0, 0, 0, ItemStack.EMPTY);
    }

    public Result simulate(ProjectileKind kind, Vec3 start, Vec3 velocity, int fuse,
                           int ownerId, int selfId, int horizon) {
        Result r = new Result();
        r.kind = kind;
        r.fuseRemaining = fuse > 0 ? fuse : (kind == ProjectileKind.TNT ? 80 : -1);
        if (kind.moveCollision) {
            simulateTnt(r, start, velocity, fuse, horizon, selfId);
        } else {
            simulateRay(r, start, velocity, ownerId, selfId, horizon);
        }
        return r;
    }

    // ---------- arrows / throwables / fireballs ----------

    private void simulateRay(Result r, Vec3 pos, Vec3 v, int ownerId, int selfId, int horizon) {
        r.points.add(pos);
        r.vels.add(v);
        boolean inWater = isWater(pos);
        boolean arrowLike = r.kind == ProjectileKind.ARROW || r.kind == ProjectileKind.TRIDENT;
        Vec3[] from = new Vec3[ENTITY_BATCH];
        Vec3[] to = new Vec3[ENTITY_BATCH];
        Hit[] blockHits = new Hit[ENTITY_BATCH];
        int t = 0;
        boolean done = false;
        while (!done && t < horizon) {
            int base = r.points.size();
            int firstTick = t;
            int n = 0;
            while (n < ENTITY_BATCH && t < horizon) {
                if (!this.level.isLoaded(net.minecraft.core.BlockPos.containing(pos))) {
                    done = true;
                    break;
                }
                // per-tick physics, mirroring 26.2 exactly:
                //  arrow      : water inertia before the move, air inertia + gravity after it
                //  throwable  : gravity, then inertia (0.99 air / 0.8 water), then the move
                //  fireball   : acceleration along motion, then 0.95 inertia, then the move
                if (arrowLike) {
                    if (inWater) {
                        v = v.scale(r.kind.waterDrag);
                    }
                } else if (r.kind.acceleration > 0) {
                    if (v.lengthSqr() > 1.0E-8) {
                        v = v.add(v.normalize().scale(r.kind.acceleration));
                    }
                    v = v.scale(inWater ? r.kind.waterDrag : r.kind.airDrag);
                } else {
                    v = v.add(0, -r.kind.gravity, 0);
                    v = v.scale(inWater ? r.kind.waterDrag : r.kind.airDrag);
                }
                Vec3 target = pos.add(v);
                BlockHitResult blockHit = this.level.clip(new ClipContext(pos, target,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.clipCtx));
                Hit hit = blockHit.getType() != HitResult.Type.MISS
                    ? new Hit(blockHit.getLocation(), Lang.tr("trajectorylens.projectile.block"), true, blockHit.getDirection())
                    : null;
                from[n] = pos;
                to[n] = target;
                blockHits[n] = hit;
                n++;
                t++;
                Vec3 end = hit != null ? hit.pos() : target;
                r.points.add(end);
                r.ticks = t;
                if (hit != null) {
                    r.vels.add(v);
                    done = true;
                    break;
                }
                pos = end;
                if (arrowLike) {
                    if (!inWater) {
                        v = v.scale(r.kind.airDrag);
                    }
                    v = v.add(0, -r.kind.gravity, 0);
                }
                r.vels.add(v);
                inWater = isWater(pos);
                if (v.lengthSqr() < 1.0E-6 && !inWater) {
                    done = true;
                    break;
                }
            }
            if (n == 0) {
                break;
            }
            StepHit chosen = firstEntityHit(from, to, blockHits, n, ownerId, selfId);
            if (chosen == null && blockHits[n - 1] != null) {
                chosen = new StepHit(n - 1, blockHits[n - 1]);
            }
            if (chosen != null) {
                int idx = base + chosen.step();
                Vec3 end = chosen.hit().pos();
                r.points.set(idx, end);
                r.points.subList(idx + 1, r.points.size()).clear();
                r.vels.subList(idx + 1, r.vels.size()).clear();
                r.ticks = firstTick + chosen.step() + 1;
                r.hit = chosen.hit();
                if (r.kind.explosionPower > 0) {
                    r.exploded = true;
                    r.explosionPos = end;
                }
                return;
            }
        }
    }

    // ---------- TNT ----------

    private void simulateTnt(Result r, Vec3 pos, Vec3 v, int fuse, int horizon, int selfId) {
        if (this.tntShadow == null) {
            this.tntShadow = new ShadowTnt(this.level);
        }
        PrimedTnt tnt = this.tntShadow;
        tnt.setPos(pos.x, pos.y, pos.z);
        tnt.setDeltaMovement(v);
        tnt.resetFallDistance();
        r.points.add(pos);
        r.vels.add(v);
        int remaining = fuse > 0 ? fuse : 80;
        int limit = Math.min(horizon, remaining + 1);
        for (int t = 0; t < limit; t++) {
            tnt.setDeltaMovement(tnt.getDeltaMovement().add(0, -r.kind.gravity, 0));
            tnt.move(MoverType.SELF, tnt.getDeltaMovement());
            tnt.setDeltaMovement(tnt.getDeltaMovement().scale(r.kind.airDrag));
            if (tnt.onGround()) {
                tnt.setDeltaMovement(tnt.getDeltaMovement().multiply(0.7, -0.5, 0.7));
            }
            r.points.add(tnt.position());
            r.vels.add(tnt.getDeltaMovement());
            r.ticks = t + 1;
            if (t + 1 >= remaining) {
                r.fuseTicks = 0;
                r.exploded = true;
                r.explosionPos = tnt.position();
                break;
            }
            r.fuseTicks = remaining - (t + 1);
        }
    }

    // ---------- helpers ----------

    private StepHit firstEntityHit(Vec3[] from, Vec3[] to, Hit[] blockHits, int n, int ownerId, int selfId) {
        AABB[] sweeps = new AABB[n];
        AABB union = null;
        for (int i = 0; i < n; i++) {
            sweeps[i] = new AABB(from[i], to[i]).inflate(1.0);
            union = union == null ? sweeps[i] : union.minmax(sweeps[i]);
        }
        List<LivingEntity> candidates = this.level.getEntitiesOfClass(LivingEntity.class, union,
            e -> e.getId() != selfId && e.getId() != ownerId && e.isAlive() && !e.isSpectator());
        if (candidates.isEmpty()) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            Entity best = null;
            Vec3 bestPos = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity e : candidates) {
                if (!e.getBoundingBox().intersects(sweeps[i])) {
                    continue;
                }
                Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(from[i], to[i]);
                if (hit.isPresent()) {
                    double d = from[i].distanceToSqr(hit.get());
                    if (d < bestDist) {
                        bestDist = d;
                        best = e;
                        bestPos = hit.get();
                    }
                }
            }
            if (best != null && (blockHits[i] == null || bestDist < from[i].distanceToSqr(blockHits[i].pos()))) {
                return new StepHit(i, new Hit(bestPos, best.getName().getString(), false, null));
            }
        }
        return null;
    }

    private boolean isWater(Vec3 pos) {
        return this.level.isLoaded(net.minecraft.core.BlockPos.containing(pos))
            && this.level.getFluidState(net.minecraft.core.BlockPos.containing(pos))
                .is(net.minecraft.tags.FluidTags.WATER);
    }
}
