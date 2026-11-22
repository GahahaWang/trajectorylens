package dev.soityy.trajectorylens.client.track;

import dev.soityy.trajectorylens.client.Lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks nearby supported projectiles (arrows, throwables, fireballs) and primed
 * TNT, and keeps a freshly simulated flight path for each of them.
 */
public final class ProjectileOverlay {

    public static final int MAX_ENTRIES = 12;
    public static final double SCAN_RADIUS = 48.0;
    private static final int RECOMPUTE_TICKS = 3;
    private static final double POS_EPS = 0.04;
    private static final double VEL_EPS = 0.0025;
    private static final int FUSE_EPS = 2;
    private static final int MAX_FOLLOW_STEPS = 4;
    private static final int MAX_RISKY = 8;
    private static final double STUCK_SPEED_SQR = 1.0E-4;
    public static final int[] TEXT_PERCENT_CHOICES = {50, 75, 100, 125, 150, 200};
    private static final Comparator<BlockPos> SCAN_ORDER = Comparator.<BlockPos>comparingInt(p -> p.getZ())
        .thenComparingInt(p -> p.getY())
        .thenComparingInt(p -> p.getX());

    private boolean projectilesOn = true;
    private boolean tntOn = true;
    private boolean aimOn = true;          // held-item aiming preview
    private boolean chainOn = true;        // causal chain forecast
    private boolean fallingOn = true;      // falling-block landing forecast
    private int textPercent = 100;
    private final java.util.List<Falling> falling = new java.util.ArrayList<>();
    private ProjectileKind aimKind;
    private ProjectileSim.Result aimResult;
    private String aimLabel = "";
    private Vec3 aimStart;
    private Vec3 aimVel;
    private int aimTick = -1;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private ProjectileSim sim;
    private int tick;

    public static final class Entry {
        public Entity entity;
        public ProjectileKind kind;
        public ProjectileSim.Result result;
        public Vec3 lastPos = Vec3.ZERO;
        public Vec3 lastVel = Vec3.ZERO;
        public int lastFuse = -1;
        public int counter;
        // blast-warning extras (TNT / creeper)
        public double explosionPower;
        public int fuseObserved;
        public long impactTick = -100;
        public String impactText = "";
        public final java.util.List<net.minecraft.core.BlockPos> risky = new java.util.ArrayList<>();
        public ChainForecast.Plan chain;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((c, level) -> {
            this.entries.clear();
            this.sim = null;
        });
    }

    public boolean projectilesEnabled() {
        return this.projectilesOn;
    }

    public boolean tntEnabled() {
        return this.tntOn;
    }

    public void setProjectiles(boolean on) {
        this.projectilesOn = on;
    }

    public void setTnt(boolean on) {
        this.tntOn = on;
    }

    public void toggleProjectiles() {
        this.projectilesOn = !this.projectilesOn;
    }

    public void toggleTnt() {
        this.tntOn = !this.tntOn;
    }

    public boolean aimEnabled() {
        return this.aimOn;
    }

    public void setAim(boolean on) {
        this.aimOn = on;
        if (!on) {
            this.clearAim();
        }
    }

    public void toggleAim() {
        this.setAim(!this.aimOn);
    }

    public boolean chainEnabled() {
        return this.chainOn;
    }

    public void setChain(boolean on) {
        this.chainOn = on;
        if (!on) {
            for (Entry e : this.entries.values()) {
                e.chain = null;
            }
        }
    }

    public void toggleChain() {
        this.setChain(!this.chainOn);
    }

    /** Chain forecast depth (blast layers) and horizon (seconds of knock-back flight). */
    public int chainDepth() {
        return ChainForecast.maxDepth;
    }

    public void setChainDepth(int depth) {
        ChainForecast.maxDepth = Math.max(1, Math.min(4, depth));
    }

    public void cycleChainDepth() {
        int[] c = ChainForecast.DEPTH_CHOICES;
        for (int v : c) {
            if (v > ChainForecast.maxDepth) {
                ChainForecast.maxDepth = v;
                return;
            }
        }
        ChainForecast.maxDepth = c[0];
    }

    public int chainHorizonSeconds() {
        return ChainForecast.horizonTicks / 20;
    }

    public void setChainHorizonSeconds(int seconds) {
        ChainForecast.horizonTicks = Math.max(20, Math.min(600, seconds * 20));
    }

    public void cycleChainHorizon() {
        int[] c = ChainForecast.HORIZON_CHOICES;
        for (int v : c) {
            if (v > ChainForecast.horizonTicks) {
                ChainForecast.horizonTicks = v;
                return;
            }
        }
        ChainForecast.horizonTicks = c[0];
    }

    public int textPercent() {
        return this.textPercent;
    }

    public void setTextPercent(int percent) {
        this.textPercent = Math.max(25, Math.min(400, percent));
    }

    public void cycleTextPercent() {
        for (int v : TEXT_PERCENT_CHOICES) {
            if (v > this.textPercent) {
                this.textPercent = v;
                return;
            }
        }
        this.textPercent = TEXT_PERCENT_CHOICES[0];
    }

    public @org.jspecify.annotations.Nullable ProjectileKind aimKind() {
        return this.aimKind;
    }

    public ProjectileSim.Result aimResult() {
        return this.aimResult;
    }

    public String aimLabel() {
        return this.aimLabel;
    }

    public Map<Integer, Entry> entries() {
        return this.entries;
    }

    public boolean fallingEnabled() {
        return this.fallingOn;
    }

    public void setFalling(boolean on) {
        this.fallingOn = on;
        if (!on) {
            this.falling.clear();
        }
    }

    public void toggleFalling() {
        this.setFalling(!this.fallingOn);
    }

    /** Nearby falling blocks (sand/gravel/anvil/dripstone) with their predicted landing spot. */
    public java.util.List<Falling> falling() {
        return this.falling;
    }

    /** One falling block: where it started, where it will land, what it will hit. */
    public record Falling(Entity entity, Vec3 from, Vec3 landing, String block, String crush, int ticks) {
    }

    public String summary() {
        return Lang.tr("trajectorylens.projectile.summary", Lang.onOff(this.projectilesOn), Lang.onOff(this.tntOn),
            Lang.onOff(this.aimOn), Lang.onOff(this.chainOn), Lang.onOff(this.fallingOn), this.entries.size());
    }

    /**
     * Held-item aiming preview: bows while drawing, charged crossbows, tridents
     * while charging, and throwables in hand. Launch velocity mirrors vanilla
     * (direction * power + shooter movement; BowItem#getPowerForTime).
     */
    public void updateAim(Minecraft mc, float partialTick) {
        var p = mc.player;
        if (!this.aimOn || p == null || this.sim == null) {
            this.clearAim();
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack st = p.getItemInHand(hand);
            if (st.isEmpty()) {
                continue;
            }
            ProjectileKind kind = null;
            double speed = 0;
            String label = "";
            if (st.is(Items.BOW)) {
                if (!p.isUsingItem() || !ItemStack.isSameItemSameComponents(p.getUseItem(), st)) {
                    continue;
                }
                float power = BowItem.getPowerForTime(p.getTicksUsingItem());
                if (power <= 0.05F) {
                    continue;
                }
                kind = ProjectileKind.ARROW;
                speed = power * 3.0;
                label = Lang.tr("trajectorylens.projectile.aim_bow", Math.round(power * 100));
            } else if (st.is(Items.CROSSBOW)) {
                if (!CrossbowItem.isCharged(st)) {
                    continue;
                }
                kind = ProjectileKind.ARROW;
                speed = 3.15;
                label = Lang.tr("trajectorylens.projectile.aim_crossbow");
            } else if (st.is(Items.TRIDENT)) {
                if (!p.isUsingItem() || !ItemStack.isSameItemSameComponents(p.getUseItem(), st)) {
                    continue;
                }
                kind = ProjectileKind.TRIDENT;
                speed = 2.5;
                label = Lang.tr("trajectorylens.projectile.trident");
            } else if (st.is(Items.SNOWBALL)) {
                kind = ProjectileKind.SNOWBALL;
                speed = 1.5;
                label = Lang.tr("trajectorylens.projectile.snowball");
            } else if (st.is(Items.EGG)) {
                kind = ProjectileKind.EGG;
                speed = 1.5;
                label = Lang.tr("trajectorylens.projectile.egg");
            } else if (st.is(Items.ENDER_PEARL)) {
                kind = ProjectileKind.ENDER_PEARL;
                speed = 1.5;
                label = Lang.tr("trajectorylens.projectile.ender_pearl");
            } else if (st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION)) {
                kind = ProjectileKind.POTION;
                speed = 1.5;
                label = Lang.tr("trajectorylens.projectile.potion");
            } else if (st.is(Items.EXPERIENCE_BOTTLE)) {
                kind = ProjectileKind.SNOWBALL;
                speed = 1.5;
                label = Lang.tr("trajectorylens.projectile.xp_bottle");
            }
            if (kind == null) {
                continue;
            }
            Vec3 start = p.getEyePosition(partialTick);
            Vec3 dir = p.getViewVector(partialTick);
            Vec3 v = dir.scale(speed);
            Vec3 mv = p.getDeltaMovement();
            v = v.add(mv.x, p.onGround() ? 0.0 : mv.y, mv.z);
            this.aimLabel = label;
            if (kind == this.aimKind && this.aimResult != null && this.aimTick == this.tick
                && start.equals(this.aimStart) && v.equals(this.aimVel)) {
                return;
            }
            this.aimKind = kind;
            this.aimStart = start;
            this.aimVel = v;
            this.aimTick = this.tick;
            this.aimResult = this.sim.simulate(kind, start, v, -1, p.getId(), -1, 200);
            return;
        }
        this.clearAim();
    }

    private void clearAim() {
        this.aimKind = null;
        this.aimResult = null;
        this.aimLabel = "";
        this.aimStart = null;
        this.aimVel = null;
    }

    private void onTick(Minecraft mc) {
        this.tick++;
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            if (!this.entries.isEmpty()) {
                this.entries.clear();
            }
            return;
        }
        if (this.sim == null) {
            this.sim = new ProjectileSim(level);
        }

        Iterator<Map.Entry<Integer, Entry>> it = this.entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entry> e = it.next();
            Entity ent = level.getEntity(e.getKey());
            if (ent == null || !ent.isAlive()) {
                it.remove();
            }
        }

        for (Entity ent : level.getEntitiesOfClass(Entity.class, mc.player.getBoundingBox().inflate(SCAN_RADIUS))) {
            ProjectileKind kind = ProjectileKind.of(ent);
            if (kind == null || !ent.isAlive()) {
                continue;
            }
            boolean on = kind == ProjectileKind.TNT ? this.tntOn : this.projectilesOn;
            if (!on) {
                continue;
            }
            Vec3 vel = ent.getDeltaMovement();
            if (ent instanceof AbstractArrow && isStuck(level, ent, vel)) {
                this.entries.remove(ent.getId());
                continue;
            }
            boolean interesting = kind == ProjectileKind.TNT || vel.lengthSqr() > 1.0E-6 || ent instanceof AbstractArrow;
            if (!interesting) {
                continue;
            }
            int fuse = kind == ProjectileKind.TNT && ent instanceof net.minecraft.world.entity.item.PrimedTnt tnt
                ? tnt.getFuse() : -1;
            int id = ent.getId();
            Entry en = this.entries.get(id);
            if (en == null) {
                if (this.entries.size() >= MAX_ENTRIES) {
                    continue;
                }
                en = new Entry();
                en.entity = ent;
                en.kind = kind;
                en.explosionPower = kind.explosionPower;
                this.entries.put(id, en);
            }
            en.entity = ent;
            en.counter++;
            int step = en.result == null || en.counter >= RECOMPUTE_TICKS ? -1 : followedStep(en, ent.position(), vel, fuse);
            if (step >= 0) {
                if (step > 0) {
                    en.lastPos = en.result.points.get(step);
                    en.lastVel = en.result.vels.get(step);
                    en.lastFuse = fuse;
                    en.result.advance(step);
                }
                if (fuse > 0) {
                    en.result.fuseRemaining = fuse;
                }
            } else {
                en.counter = 0;
                en.lastPos = ent.position();
                en.lastVel = vel;
                en.lastFuse = fuse;
                int ownerId = ent instanceof Projectile proj && proj.getOwner() != null ? proj.getOwner().getId() : -1;
                en.result = this.sim.simulate(kind, ent.position(), vel, fuse, ownerId, id, 200);
            }
            if (en.explosionPower > 0 && this.tick - en.impactTick >= 20) {
                Vec3 center = en.result != null && en.result.explosionPos != null
                    ? en.result.explosionPos : ent.position();
                scanImpact(en, center, en.explosionPower);
                if (this.chainOn) {
                    int chainFuse = en.result != null ? Math.max(0, en.result.fuseRemaining) : 0;
                    try {
                        en.chain = ChainForecast.compute(level, center, (float) en.explosionPower, chainFuse, ent);
                    } catch (Exception ex) {
                        en.chain = null; // never let the forecast break the tick
                    }
                } else {
                    en.chain = null;
                }
                en.impactTick = this.tick;
            }
        }

        // falling blocks: where they land and whether somebody is standing there
        this.falling.clear();
        if (this.fallingOn) {
            for (net.minecraft.world.entity.item.FallingBlockEntity fb
                : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                    mc.player.getBoundingBox().inflate(SCAN_RADIUS))) {
                if (!fb.isAlive() || this.falling.size() >= 10) {
                    continue;
                }
                ChainForecast.PushArc arc = ChainForecast.simulateArc(level, fb, fb.position(), fb.getDeltaMovement());
                Vec3 land = arc.landing != null ? arc.landing : fb.position();
                String crush = "";
                var box = new net.minecraft.world.phys.AABB(land.x - 0.6, land.y - 2.0, land.z - 0.6,
                    land.x + 0.6, land.y + 0.4, land.z + 0.6);
                for (net.minecraft.world.entity.LivingEntity le
                    : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box)) {
                    crush = le == mc.player
                        ? Lang.tr("trajectorylens.projectile.falling_hits_you")
                        : Lang.tr("trajectorylens.projectile.falling_hits", le.getName().getString());
                    break;
                }
                this.falling.add(new Falling(fb, fb.position(), land,
                    fb.getBlockState().getBlock().getName().getString(), crush, Math.max(1, arc.points.size() - 1)));
            }
        }

        // creepers about to blow up: swell direction is synced, fuse is 30 ticks
        if (this.tntOn) {
            for (net.minecraft.world.entity.monster.Creeper creeper
                : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
                    mc.player.getBoundingBox().inflate(SCAN_RADIUS))) {
                boolean swelling = creeper.getSwellDir() > 0;
                int cid = creeper.getId();
                Entry en = this.entries.get(cid);
                if (!swelling) {
                    if (en != null && en.kind == ProjectileKind.CREEPER) {
                        this.entries.remove(cid);
                    }
                    continue;
                }
                if (en == null) {
                    if (this.entries.size() >= MAX_ENTRIES) {
                        continue;
                    }
                    en = new Entry();
                    en.entity = creeper;
                    en.kind = ProjectileKind.CREEPER;
                    this.entries.put(cid, en);
                }
                en.fuseObserved++;
                en.explosionPower = creeper.isPowered() ? 6.0 : 3.0;
                Vec3 pos = creeper.position();
                ProjectileSim.Result r = new ProjectileSim.Result();
                r.kind = ProjectileKind.CREEPER;
                r.points.add(pos);
                r.points.add(pos);
                r.exploded = true;
                r.explosionPos = pos;
                r.fuseRemaining = Math.max(0, 30 - en.fuseObserved);
                r.ticks = en.fuseObserved;
                en.result = r;
                if (this.tick - en.impactTick >= 20 || en.impactText.isEmpty()) {
                    scanImpact(en, pos, en.explosionPower);
                    if (this.chainOn) {
                        en.chain = ChainForecast.compute(level, pos, (float) en.explosionPower, r.fuseRemaining, creeper);
                    } else {
                        en.chain = null;
                    }
                    en.impactTick = this.tick;
                }
            }
        }
    }

    private static boolean isStuck(ClientLevel level, Entity arrow, Vec3 vel) {
        Vec3 pos = arrow.position();
        return vel.lengthSqr() < STUCK_SPEED_SQR && !level.noCollision(new net.minecraft.world.phys.AABB(pos, pos).inflate(0.06));
    }

    private static int followedStep(Entry en, Vec3 pos, Vec3 vel, int fuse) {
        int best = -1;
        double bestErr = Double.MAX_VALUE;
        double seedErr = en.lastPos.distanceToSqr(pos);
        if (seedErr <= POS_EPS && en.lastVel.distanceToSqr(vel) <= VEL_EPS
            && (fuse < 0 || Math.abs(fuse - en.lastFuse) <= FUSE_EPS)) {
            best = 0;
            bestErr = seedErr;
        }
        List<Vec3> points = en.result.points;
        List<Vec3> vels = en.result.vels;
        int last = Math.min(MAX_FOLLOW_STEPS, Math.min(points.size(), vels.size()) - 1);
        for (int k = 1; k <= last; k++) {
            double err = points.get(k).distanceToSqr(pos);
            if (err <= POS_EPS && err < bestErr && vels.get(k).distanceToSqr(vel) <= VEL_EPS
                && (fuse < 0 || Math.abs(fuse - (en.lastFuse - k)) <= FUSE_EPS)) {
                best = k;
                bestErr = err;
            }
        }
        return best;
    }

    /** Lists what an explosion would hit: entities by category and risky blocks. */
    private void scanImpact(Entry en, Vec3 center, double power) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        double radius = power * 2.0;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(center, center).inflate(radius);
        int players = 0;
        int villagers = 0;
        int tamed = 0;
        int mobs = 0;
        for (net.minecraft.world.entity.LivingEntity le
            : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box)) {
            if (le instanceof net.minecraft.world.entity.player.Player) {
                players++;
            } else if (le instanceof net.minecraft.world.entity.npc.villager.Villager) {
                villagers++;
            } else if (le instanceof net.minecraft.world.entity.TamableAnimal ta && ta.isTame()) {
                tamed++;
            } else {
                mobs++;
            }
        }
        int containers = 0;
        int hoppers = 0;
        int spawners = 0;
        en.risky.clear();
        int r = (int) Math.ceil(radius);
        BlockPos origin = BlockPos.containing(center);
        int minX = origin.getX() - r;
        int minY = origin.getY() - r;
        int minZ = origin.getZ() - r;
        int maxX = origin.getX() + r;
        int maxY = origin.getY() + r;
        int maxZ = origin.getZ() + r;
        List<BlockPos> found = new ArrayList<>();
        for (int cx = SectionPos.blockToSectionCoord(minX); cx <= SectionPos.blockToSectionCoord(maxX); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= SectionPos.blockToSectionCoord(maxZ); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY
                        || p.getZ() < minZ || p.getZ() > maxZ) {
                        continue;
                    }
                    if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
                        spawners++;
                    } else if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                        || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
                        || be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) {
                        containers++;
                    } else if (be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) {
                        hoppers++;
                    } else {
                        continue;
                    }
                    found.add(p);
                }
            }
        }
        found.sort(SCAN_ORDER);
        for (int i = 0; i < found.size() && i < MAX_RISKY; i++) {
            en.risky.add(found.get(i).immutable());
        }
        List<String> hit = new ArrayList<>();
        addCount(hit, "trajectorylens.projectile.impact_players", players);
        addCount(hit, "trajectorylens.projectile.impact_villagers", villagers);
        addCount(hit, "trajectorylens.projectile.impact_pets", tamed);
        addCount(hit, "trajectorylens.projectile.impact_mobs", mobs);
        StringBuilder sb = new StringBuilder(Lang.tr("trajectorylens.projectile.impact",
            hit.isEmpty() ? Lang.tr("trajectorylens.projectile.impact_no_mobs") : String.join(" ", hit)));
        var pl = Minecraft.getInstance().player;
        if (pl != null && pl.position().distanceTo(center) <= radius) {
            sb.append(Lang.tr("trajectorylens.projectile.impact_you_inside"));
        }
        List<String> risk = new ArrayList<>();
        addCount(risk, "trajectorylens.projectile.impact_containers", containers);
        addCount(risk, "trajectorylens.projectile.impact_hoppers", hoppers);
        addCount(risk, "trajectorylens.projectile.impact_spawners", spawners);
        if (!risk.isEmpty()) {
            sb.append(Lang.tr("trajectorylens.projectile.impact_risk", String.join(" ", risk)));
        }
        en.impactText = sb.toString();
    }

    private static void addCount(List<String> out, String key, int count) {
        if (count > 0) {
            out.add(Lang.tr(key, count));
        }
    }
}
