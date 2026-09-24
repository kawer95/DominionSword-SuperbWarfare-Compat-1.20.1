package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionFrontierPlanner;
import com.arxyt.dominionsword.api.DominionGroundTransitionPolicy;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure-data frontier domain. The caller's probe supplies every live vehicle/world decision. */
public final class GroundVehicleFrontier implements DominionFrontierPlanner.Domain<GroundVehicleFrontier.State, GroundVehicleFrontier.Key> {
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final double STEP = 1.0D;
    private static final double EXACT_GOAL_RANGE = 6.0D;

    public interface Probe {
        boolean loaded(Vec3 from, Vec3 to);
        boolean occupy(Vec3 position, float yaw);
        boolean turn(Vec3 fromPosition, float fromYaw, float toYaw);
        boolean sweep(Vec3 from, Vec3 to, float yaw);
        double terrainCost(Vec3 position);
    }

    public record State(int x, int z, Vec3 position, float yaw, boolean exactGoal) { }
    public record Key(int x, int z, int y16, int yawBin, boolean exactGoal) { }

    private final Vec3 start;
    private final Vec3 goalPose;
    private final State startState;
    private final int radius;
    private final double maxStepUp;
    private final double[] heights;
    private final Probe probe;
    private final int goalX;
    private final int goalZ;
    private final double initialHeuristic;

    public GroundVehicleFrontier(Vec3 start, float startYaw, Vec3 goalPose, double maxStepUp,
                                 int radius, Probe probe) {
        this.start = checkedPosition(start, "start");
        this.goalPose = checkedPosition(goalPose, "goalPose");
        if (!Float.isFinite(startYaw) || !Double.isFinite(maxStepUp) || maxStepUp < 0.0D || radius < 1)
            throw new IllegalArgumentException("invalid heading or frontier limits");
        this.radius = radius;
        this.maxStepUp = Math.min(2.0D, maxStepUp);
        this.heights = heightCandidates(this.maxStepUp);
        this.probe = Objects.requireNonNull(probe, "probe");
        this.goalX = (int) Math.round((goalPose.x - start.x) / STEP);
        this.goalZ = (int) Math.round((goalPose.z - start.z) / STEP);
        this.startState = new State(0, 0, start, startYaw, false);
        this.initialHeuristic = heuristic(startState);
    }

    public State startState() { return startState; }

    @Override public Key key(State state) {
        return new Key(state.x(), state.z(), (int) Math.round(state.position().y * 16.0D),
                Math.floorMod((int) Math.round(state.yaw() / 45.0D), 8), state.exactGoal());
    }

    @Override public double heuristic(State state) {
        return GroundRouteControlPolicy.frontierHeuristic(flatDistance(state.position(), goalPose),
                state.position().y - goalPose.y);
    }

    @Override public boolean isGoal(State state) {
        double horizontal = flatDistance(state.position(), goalPose);
        double vertical = Math.abs(state.position().y - goalPose.y);
        return state.exactGoal() ? horizontal <= 1.0E-6D && vertical <= 0.75D
                : horizontal <= 1.0E-6D && vertical <= 1.0E-6D;
    }

    @Override public boolean isPartialBoundary(State state) {
        return (Math.abs(goalX) > radius || Math.abs(goalZ) > radius)
                && (Math.abs(state.x()) == radius || Math.abs(state.z()) == radius)
                && heuristic(state) + STEP < initialHeuristic;
    }

    @Override public int successorCount(State state) {
        if (state.exactGoal()) return 0;
        return DX.length * heights.length + (nearExactGoal(state) ? heights.length : 0);
    }

    @Override public DominionFrontierPlanner.Edge<State> successor(State from, int action) {
        int count = successorCount(from);
        if (action < 0 || action >= count) throw new IndexOutOfBoundsException("action=" + action);
        boolean hasExactActions = nearExactGoal(from);
        boolean exact = hasExactActions && action < heights.length;
        int x, z;
        Vec3 next;
        if (exact) {
            if (Math.abs(goalPose.x - start.x) > radius || Math.abs(goalPose.z - start.z) > radius)
                return null;
            x = goalX;
            z = goalZ;
            double y = from.position().y + heights[action];
            if (Math.abs(y - goalPose.y) > 0.75D) return null;
            next = new Vec3(goalPose.x, y, goalPose.z);
        } else {
            int regularAction = action - (hasExactActions ? heights.length : 0);
            int heightIndex = regularAction / DX.length;
            int direction = regularAction % DX.length;
            x = from.x() + DX[direction];
            z = from.z() + DZ[direction];
            if (Math.abs(x) > radius || Math.abs(z) > radius) return null;
            next = new Vec3(start.x + x * STEP, from.position().y + heights[heightIndex],
                    start.z + z * STEP);
        }
        float yaw = heading(from.position(), next, from.yaw());
        if (!probe.loaded(from.position(), next)
                || !probe.occupy(next, yaw)
                || !probe.turn(from.position(), from.yaw(), yaw)
                || !probe.sweep(from.position(), next, yaw)) return null;
        double terrain = probe.terrainCost(next);
        if (!Double.isFinite(terrain) || terrain < 0.0D)
            throw new IllegalArgumentException("invalid terrain cost");
        double distance=flatDistance(from.position(),next);
        // Exact connectors can span several cells; charge terrain by travel length as well.
        double cost = distance + DominionGroundTransitionPolicy.heightPenalty(from.position().y, next.y)
                + Math.max(.75D,terrain)*Math.max(1.0D,distance/Math.sqrt(2.0D));
        return new DominionFrontierPlanner.Edge<>(new State(x, z, next, yaw, exact), Math.max(0.01D, cost));
    }

    private boolean nearExactGoal(State state) {
        return flatDistance(state.position(), goalPose) <= EXACT_GOAL_RANGE;
    }

    private static double[] heightCandidates(double maxStepUp) {
        double[] all = {0.0D, 0.5D, -0.5D, 1.0D, -1.0D, 1.5D, -1.5D, 2.0D, -2.0D};
        List<Double> allowed = new ArrayList<>();
        for (double offset : all) if (Math.abs(offset) <= maxStepUp + 0.01D) allowed.add(offset);
        double[] result = new double[allowed.size()];
        for (int i = 0; i < result.length; i++) result[i] = allowed.get(i);
        return result;
    }

    private static float heading(Vec3 from, Vec3 to, float fallback) {
        double dx = to.x - from.x, dz = to.z - from.z;
        return dx * dx + dz * dz < 1.0E-12D ? fallback : (float) -Math.toDegrees(Math.atan2(dx, dz));
    }

    private static double flatDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static Vec3 checkedPosition(Vec3 position, String name) {
        Objects.requireNonNull(position, name);
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("invalid " + name);
        return position;
    }
}
