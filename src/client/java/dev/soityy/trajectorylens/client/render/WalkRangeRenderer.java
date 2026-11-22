package dev.soityy.trajectorylens.client.render;

import dev.soityy.trajectorylens.client.track.WalkRangeEngine;
import dev.soityy.trajectorylens.client.track.WalkRangeOverlay;

import java.util.List;

import dev.soityy.trajectorylens.client.track.WalkRangeOverlay.RegionEntry;
import dev.soityy.trajectorylens.util.PathTools;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Border-only walk-range visualization:
 *  - per-cell grid borders in a small area around the mob (each square == one block),
 *  - bold silhouette outline for the whole region,
 *  - marker ring at the mob.
 * Budgets are generous and shared fairly: with several mobs each one still gets
 * its near grid, outline and ring.
 */
public final class WalkRangeRenderer {

    private static final int NEAR_CELLS_PER_MOB = 96;  // grid squares near the mob
    private static final int MAX_LINES = 12000;         // frame budget (grid uses 4 lines/cell)
    private static final double INSET = 0.03;

    private WalkRangeRenderer() {
    }

    public static void draw(WalkRangeOverlay overlay) {
        if (!overlay.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        int lineBudget = MAX_LINES;
        for (RegionEntry e : overlay.regions().values()) {
            if (level.getEntity(e.mob.getId()) != e.mob || !e.mob.isAlive()) {
                continue;
            }
            WalkRangeEngine.Region r = e.region;
            if (r == null || r.cellKeys.isEmpty()) {
                continue;
            }
            int rgb = e.color & 0xFFFFFF;
            int gridStroke = (0x66 << 24) | rgb;
            int boldStroke = (0xFF << 24) | rgb;
            double mobX = e.mob.getX();
            double mobZ = e.mob.getZ();

            // 1) nearest cells only -> grid squares around the mob
            long[] near = nearestCells(e, r, mobX, mobZ);
            for (int i = 0; i < near.length && lineBudget >= 4; i++) {
                long k = near[i];
                int cx = WalkRangeEngine.cellX(k);
                int cy = WalkRangeEngine.cellY(k);
                int cz = WalkRangeEngine.cellZ(k);
                double top = cy + INSET;
                Gizmos.rect(new Vec3(cx, top, cz), new Vec3(cx + 1.0, top, cz),
                    new Vec3(cx + 1.0, top, cz + 1.0), new Vec3(cx, top, cz + 1.0),
                    GizmoStyle.stroke(gridStroke, 1.0F)).setAlwaysOnTop();
                lineBudget -= 4;
            }

            // 2) bold silhouette outline of the whole reachable region
            List<Vec3> segs = r.boundarySegments;
            if (!segs.isEmpty() && lineBudget > 0) {
                int step = segs.size() > 4000 ? 4 : 2;
                for (int i = 0; i + 1 < segs.size() && lineBudget > 0; i += step) {
                    Gizmos.line(segs.get(i), segs.get(i + 1), boldStroke, 2.5F).setAlwaysOnTop();
                    lineBudget--;
                }
            }

            // 3) marker ring at the mob
            Vec3 pos = e.mob.position();
            Gizmos.circle(pos.add(0, 0.08, 0), 0.5F,
                GizmoStyle.strokeAndFill(boldStroke, 2.0F, (0x66000000 | rgb))).setAlwaysOnTop();
            if (lineBudget <= 0) {
                break;
            }
        }
    }

    private static long[] nearestCells(RegionEntry e, WalkRangeEngine.Region r, double mobX, double mobZ) {
        if (e.nearCells != null && e.nearRegion == r && e.nearX == mobX && e.nearZ == mobZ) {
            return e.nearCells;
        }
        LongArrayList keys = r.cellKeys;
        double[] dist = new double[keys.size()];
        for (int i = 0; i < dist.length; i++) {
            long k = keys.getLong(i);
            double dx = WalkRangeEngine.cellX(k) + 0.5 - mobX;
            double dz = WalkRangeEngine.cellZ(k) + 0.5 - mobZ;
            dist[i] = dx * dx + dz * dz;
        }
        int[] order = PathTools.smallestIndices(dist, NEAR_CELLS_PER_MOB);
        long[] near = new long[order.length];
        for (int i = 0; i < order.length; i++) {
            near[i] = keys.getLong(order[i]);
        }
        e.nearCells = near;
        e.nearRegion = r;
        e.nearX = mobX;
        e.nearZ = mobZ;
        return near;
    }
}
