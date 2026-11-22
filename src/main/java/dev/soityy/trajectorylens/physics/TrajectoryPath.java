package dev.soityy.trajectorylens.physics;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/** Result of one shadow simulation run. */
public class TrajectoryPath {
    public enum EndReason {
        RUNNING, REST, BURNED, VOID, HORIZON, UNLOADED
    }

    public final List<Vec3> points = new ArrayList<>();
    public final List<Vec3> vels = new ArrayList<>();
    public EndReason endReason = EndReason.RUNNING;
    public int ticks;

    public Vec3 endPoint() {
        return this.points.isEmpty() ? Vec3.ZERO : this.points.get(this.points.size() - 1);
    }

    public void advance(int n) {
        this.points.subList(0, n).clear();
        this.vels.subList(0, n).clear();
        this.ticks = Math.max(0, this.ticks - n);
    }
}
