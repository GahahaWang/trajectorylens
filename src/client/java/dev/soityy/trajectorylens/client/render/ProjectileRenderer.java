package dev.soityy.trajectorylens.client.render;

import dev.soityy.trajectorylens.client.Lang;

import dev.soityy.trajectorylens.client.track.ChainForecast;
import dev.soityy.trajectorylens.client.track.ProjectileKind;
import dev.soityy.trajectorylens.client.track.ProjectileOverlay;
import dev.soityy.trajectorylens.client.track.ProjectileSim;

import java.util.ArrayList;
import java.util.List;

import dev.soityy.trajectorylens.client.track.ProjectileOverlay.Entry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Draws projectile flight paths, hit markers, TNT fuses and blast rings. */
public final class ProjectileRenderer {

    private static final int MAX_LINES = 4000;
    private static final int RING_SEGMENTS = 24;
    private static final double TEXT_REF_DISTANCE = 10.0;
    private static final double TEXT_REF_FOV = 70.0;
    private static final double TEXT_MIN_FACTOR = 0.02;
    private static final double TEXT_ROW_STEP = 0.3;
    private static final double[] RING_COS = new double[RING_SEGMENTS + 1];
    private static final double[] RING_SIN = new double[RING_SEGMENTS + 1];

    static {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double a = 2 * Math.PI * i / RING_SEGMENTS;
            RING_COS[i] = Math.cos(a);
            RING_SIN[i] = Math.sin(a);
        }
    }

    private ProjectileRenderer() {
    }

    public static void draw(ProjectileOverlay overlay) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        DeltaTracker dt = mc.getDeltaTracker();
        overlay.updateAim(mc, dt.getGameTimeDeltaPartialTick(true));
        Labeler text = Labeler.of(mc.gameRenderer.mainCamera(), overlay.textPercent());
        int budget = MAX_LINES;
        for (Entry e : new ArrayList<>(overlay.entries().values())) {
            if (level.getEntity(e.entity.getId()) != e.entity || !e.entity.isAlive()) {
                continue;
            }
            ProjectileSim.Result r = e.result;
            if (r == null || r.points.size() < 2) {
                continue;
            }
            int rgb = e.kind.color & 0xFFFFFF;
            int color = (0xFF << 24) | rgb;
            List<Vec3> pts = r.points;
            Vec3 at = renderPos(e.entity, dt);
            if (e.kind != ProjectileKind.CREEPER) { // creepers have no flight path
                if (at.distanceToSqr(pts.get(0)) > 1.0E-6) {
                    Gizmos.line(at, pts.get(0), color, 2.0F).setAlwaysOnTop();
                    budget--;
                }
                for (int i = 0; i + 1 < pts.size() && budget > 0; i++) {
                    Gizmos.line(pts.get(i), pts.get(i + 1), color, 2.0F).setAlwaysOnTop();
                    budget--;
                }
            }
            // hit marker
            if (r.hit != null) {
                drawHitMarker(r.hit, 0.4F, GizmoStyle.strokeAndFill(color, 2.0F, (0x66000000 | rgb)));
                String txt = r.hit.block()
                    ? Lang.tr("trajectorylens.projectile.hit_block")
                    : Lang.tr("trajectorylens.projectile.hit_entity", r.hit.label());
                text.draw("§f" + txt, r.hit.pos(), labelOffset(r.hit, 0.6), 0, 0xFFFFFFFF, 0.75F);
            }
            // TNT fuse + blast rings
            if (e.kind == ProjectileKind.TNT || e.kind == ProjectileKind.CREEPER) {
                int fuse = r.fuseRemaining >= 0 ? r.fuseRemaining : (r.fuseTicks >= 0 ? r.fuseTicks : 0);
                String head = e.kind == ProjectileKind.TNT
                    ? "§c" + Lang.tr("trajectorylens.projectile.tnt")
                    : Lang.tr("trajectorylens.projectile.label_creeper");
                text.draw(String.format("%s §f%.1fs", head, fuse / 20.0F), at, 1.2, 0, 0xFFFF8888, 0.85F);
                if (!e.impactText.isEmpty()) {
                    text.draw("§7" + e.impactText, at, 1.2, 1, 0xFFDDDDDD, 0.7F);
                }
                for (net.minecraft.core.BlockPos bp : e.risky) {
                    Gizmos.cuboid(new net.minecraft.world.phys.AABB(bp), GizmoStyle.stroke(0xFFFFD040), false)
                        .setAlwaysOnTop();
                    budget -= 12;
                }
            }
            // causal chain: chained blasts, who gets launched where, falling blocks
            if (e.chain != null) {
                int order = 1;
                for (ChainForecast.Step s : e.chain.steps) {
                    int sc = order == 1 ? 0xFFFF8040 : 0xFFFFC040;
                    Gizmos.circle(s.pos, Math.max(0.6F, s.power * 0.5F),
                        GizmoStyle.strokeAndFill(sc, 1.6F, (0x22000000 | (sc & 0xFFFFFF)))).setAlwaysOnTop();
                    text.draw(Lang.tr("trajectorylens.projectile.chain_step", "§l", order,
                        Lang.fixed(s.delayTicks / 20.0, 1), Lang.fixed(s.power, 0)), s.pos, 1.6, 0, 0xFFFFD080, 0.7F);
                    order++;
                }
                int lineNo = 0;
                for (ChainForecast.PushArc arc : e.chain.pushes) {
                    List<Vec3> ap = arc.points;
                    for (int i = 0; i + 1 < ap.size() && budget > 0; i++) {
                        Gizmos.line(ap.get(i), ap.get(i + 1), (0xAA << 24) | (arc.color & 0xFFFFFF), 1.4F).setAlwaysOnTop();
                        budget--;
                    }
                    if (arc.landing != null) {
                        Gizmos.circle(arc.landing, 0.35F, GizmoStyle.stroke(arc.color)).setAlwaysOnTop();
                        text.draw(arc.note, arc.landing, 0.5, lineNo, 0xFFFFFFFF, 0.68F);
                    }
                    lineNo++;
                }
                int noteNo = 0;
                for (String note : e.chain.notes) {
                    text.draw(note, at, 2.0, noteNo, 0xFFFFFFFF, 0.68F);
                    noteNo++;
                }
            }

            double power = e.explosionPower > 0 ? e.explosionPower : e.kind.explosionPower;
            if (r.exploded && r.explosionPos != null && power > 0) {
                double inner = power;
                double outer = power * 2.0;
                emitSphere(r.explosionPos, inner, (0xCC << 24) | rgb, budget);
                budget -= 3 * 24;
                emitSphere(r.explosionPos, outer, (0x66 << 24) | rgb, budget);
                budget -= 3 * 24;
                Gizmos.point(r.explosionPos, color, 0.5F).setAlwaysOnTop();
            }
        }

        // falling blocks: dashed drop line + landing ring, red when somebody is under it
        for (ProjectileOverlay.Falling f : overlay.falling()) {
            boolean hit = !f.crush().isEmpty();
            int rgb = hit ? 0xFF4848 : 0xC0A060;
            int stroke = (0xEE << 24) | rgb;
            Vec3 a = f.entity().isAlive() ? renderPos(f.entity(), dt) : f.from();
            Vec3 b = f.landing();
            int segs = 8;
            for (int i = 0; i + 1 < segs && budget > 0; i++) {
                Vec3 p0 = a.add(b.subtract(a).scale(i / (double) segs));
                Vec3 p1 = a.add(b.subtract(a).scale((i + 1) / (double) segs));
                Gizmos.line(p0, p1, stroke, 1.6F).setAlwaysOnTop();
                budget--;
            }
            Gizmos.circle(b.add(0, 0.05, 0), 0.5F, GizmoStyle.strokeAndFill(stroke, 2.0F, (0x2A << 24) | rgb))
                .setAlwaysOnTop();
            text.draw(Lang.tr("trajectorylens.projectile.falling_label", f.block(), Lang.fixed(f.ticks() / 20.0, 1),
                hit ? (" §c" + f.crush()) : ""), b, 0.6, 0, hit ? 0xFFFF9090 : 0xFFFFFFFF, 0.7F);
        }

        // held-item aiming preview
        ProjectileSim.Result aim = overlay.aimResult();
        ProjectileKind aimKind = overlay.aimKind();
        if (aim != null && aimKind != null && aim.points.size() >= 2) {
            int rgb = aimKind.color & 0xFFFFFF;
            int color = (0xFF << 24) | rgb;
            List<Vec3> pts = aim.points;
            for (int i = 0; i + 1 < pts.size() && budget > 0; i++) {
                Gizmos.line(pts.get(i), pts.get(i + 1), color, 1.6F).setAlwaysOnTop();
                budget--;
            }
            Vec3 start = pts.get(0);
            text.draw(Lang.tr("trajectorylens.projectile.aim_label", overlay.aimLabel()), start, 0.5, 0, 0xFFB0FFFF, 0.75F);
            if (aim.hit != null) {
                drawHitMarker(aim.hit, 0.35F, GizmoStyle.strokeAndFill(color, 1.8F, (0x55000000 | rgb)));
                String txt = aim.hit.block()
                    ? Lang.tr("trajectorylens.projectile.aim_hit_block")
                    : Lang.tr("trajectorylens.projectile.aim_hit_entity", aim.hit.label());
                text.draw(txt, aim.hit.pos(), labelOffset(aim.hit, 0.55), 0, 0xFFFFFFFF, 0.75F);
            }
        }
    }

    private static void drawHitMarker(ProjectileSim.Hit hit, float radius, GizmoStyle style) {
        Direction face = hit.face();
        if (face == null || face.getAxis() == Direction.Axis.Y) {
            Gizmos.circle(hit.pos(), radius, style).setAlwaysOnTop();
            return;
        }
        Vec3 center = hit.pos().add(face.getStepX() * 0.02, 0, face.getStepZ() * 0.02);
        Vec3 across = new Vec3(-face.getStepZ(), 0, face.getStepX());
        Gizmos.addGizmo(new WallCircle(center, across, radius, style)).setAlwaysOnTop();
    }

    private record WallCircle(Vec3 center, Vec3 across, float radius, GizmoStyle style) implements Gizmo {
        @Override
        public void emit(GizmoPrimitives out, float alpha) {
            Vec3[] ring = new Vec3[RING_SEGMENTS + 1];
            for (int i = 0; i <= RING_SEGMENTS; i++) {
                ring[i] = this.center.add(this.across.scale(RING_COS[i] * this.radius)).add(0, RING_SIN[i] * this.radius, 0);
            }
            if (this.style.hasFill()) {
                out.addTriangleFan(ring, this.style.multipliedFill(alpha));
            }
            if (this.style.hasStroke()) {
                int stroke = this.style.multipliedStroke(alpha);
                for (int i = 0; i < RING_SEGMENTS; i++) {
                    out.addLine(ring[i], ring[i + 1], stroke, this.style.strokeWidth());
                }
            }
        }
    }

    private static Vec3 labelOffset(ProjectileSim.Hit hit, double up) {
        Direction face = hit.face();
        if (face == null || face == Direction.UP) {
            return new Vec3(0, up, 0);
        }
        if (face == Direction.DOWN) {
            return new Vec3(0, -up, 0);
        }
        return new Vec3(face.getStepX() * 0.3, up, face.getStepZ() * 0.3);
    }

    private record Labeler(Vec3 camera, double view, double userScale) {

        static Labeler of(Camera camera, int percent) {
            float fov = camera.getFov();
            double view = Math.tan(Math.toRadians((fov > 1.0F ? fov : TEXT_REF_FOV) / 2.0))
                / (TEXT_REF_DISTANCE * Math.tan(Math.toRadians(TEXT_REF_FOV / 2.0)));
            return new Labeler(camera.position(), view, percent / 100.0);
        }

        void draw(String text, Vec3 anchor, double lift, int row, int color, float scale) {
            draw(text, anchor, new Vec3(0, lift, 0), row, color, scale);
        }

        void draw(String text, Vec3 anchor, Vec3 offset, int row, int color, float scale) {
            double k = this.userScale * Math.max(TEXT_MIN_FACTOR, this.camera.distanceTo(anchor) * this.view);
            Vec3 pos = anchor.add(offset.scale(Math.max(1.0, k))).add(0, row * TEXT_ROW_STEP * k, 0);
            Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(color).withScale((float) (scale * k)))
                .setAlwaysOnTop();
        }
    }

    private static Vec3 renderPos(Entity e, DeltaTracker dt) {
        float t = dt.getGameTimeDeltaPartialTick(!e.level().tickRateManager().isEntityFrozen(e));
        return new Vec3(Mth.lerp(t, e.xOld, e.getX()), Mth.lerp(t, e.yOld, e.getY()), Mth.lerp(t, e.zOld, e.getZ()));
    }

    /** Wireframe sphere approximated by three orthogonal rings. */
    private static void emitSphere(Vec3 c, double radius, int color, int budget) {
        for (int plane = 0; plane < 3; plane++) {
            Vec3 p0 = ringPoint(c, radius, 0, plane);
            for (int i = 0; i < RING_SEGMENTS && budget > 0; i++) {
                Vec3 p1 = ringPoint(c, radius, i + 1, plane);
                Gizmos.line(p0, p1, color, 1.6F).setAlwaysOnTop();
                p0 = p1;
                budget--;
            }
        }
    }

    private static Vec3 ringPoint(Vec3 c, double radius, int step, int plane) {
        double x = RING_COS[step] * radius;
        double y = RING_SIN[step] * radius;
        return switch (plane) {
            case 0 -> c.add(x, 0, y);
            case 1 -> c.add(x, y, 0);
            default -> c.add(0, x, y);
        };
    }
}
