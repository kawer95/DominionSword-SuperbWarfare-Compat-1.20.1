package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionFrontierPlanner;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundVehicleFrontierTest {
    private static final Vec3 START = new Vec3(614.203D, 62.04D, 310.267D);
    private static final Vec3 GOAL = new Vec3(623.83D, 62.0D, 302.81D);
    private static final double EAST_WALL = 627.0D;

    /** M2 collision OBB: local forward center .25, extents 1.75/3.875, padding .85. */
    private static final class M2WallProbe implements GroundVehicleFrontier.Probe {
        int loaded, occupied, turns, sweeps, terrain;

        @Override public boolean loaded(Vec3 from, Vec3 to) { loaded++; return true; }

        @Override public boolean occupy(Vec3 position, float yaw) {
            occupied++;
            double radians = Math.toRadians(yaw);
            double forwardX = -Math.sin(radians);
            double centerX = position.x + forwardX * 0.25D;
            double eastExtent = Math.abs(Math.cos(radians)) * (1.75D + 0.85D)
                    + Math.abs(forwardX) * (3.875D + 0.85D);
            return centerX + eastExtent <= EAST_WALL - 0.01D
                    && Math.abs(position.y - START.y) < 0.1D;
        }

        @Override public boolean turn(Vec3 fromPosition, float fromYaw, float toYaw) {
            turns++;
            double delta = Math.IEEEremainder(toYaw - fromYaw, 360.0D);
            int samples = Math.max(1, (int) Math.ceil(Math.abs(delta) / 10.0D));
            for (int i = 1; i <= samples; i++) {
                if (!occupy(fromPosition, (float) (fromYaw + delta * i / samples))) return false;
            }
            return true;
        }

        @Override public boolean sweep(Vec3 from, Vec3 to, float yaw) {
            sweeps++;
            int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 0.25D));
            for (int i = 1; i <= samples; i++) {
                if (!occupy(from.lerp(to, i / (double) samples), yaw)) return false;
            }
            return true;
        }

        @Override public double terrainCost(Vec3 position) { terrain++; return 0.75D; }
    }

    @Test
    void wallSideGoalCannotBeFalselyCompletedButDeliversValidatedProgress() {
        M2WallProbe probe = new M2WallProbe();
        GroundVehicleFrontier domain = new GroundVehicleFrontier(START, 175.0F, GOAL, 1.0D, 32, probe);
        var planner = new DominionFrontierPlanner<>(domain, domain.startState(), 4096, 32768);
        DominionFrontierPlanner.Result<GroundVehicleFrontier.State> result = null;
        for (int tick = 0; tick < 40; tick++) {
            AtomicInteger grants = new AtomicInteger(72);
            result = planner.advance(() -> grants.getAndDecrement() > 0);
            if (result.status() != DominionFrontierPlanner.Status.PENDING) break;
        }
        if (result.status() == DominionFrontierPlanner.Status.PENDING) {
            double initialRemaining = horizontal(START, GOAL);
            result = planner.finishPartial(state -> GroundRouteControlPolicy.usefulPartial(
                    horizontal(START, state.position()), initialRemaining,
                    horizontal(state.position(), GOAL)));
        }

        assertEquals(DominionFrontierPlanner.Status.PARTIAL, result.status());
        List<GroundVehicleFrontier.State> path = result.path();
        assertTrue(path.size() > 2);
        assertFalse(path.get(path.size() - 1).exactGoal(), "the wall-side goal cannot be claimed without a valid edge");
        assertEquals(START, path.get(0).position());
        for (int i = 1; i < path.size(); i++) {
            var from = path.get(i - 1);
            var to = path.get(i);
            assertTrue(probe.turn(from.position(), from.yaw(), to.yaw()), "unsafe turn at edge " + i);
            assertTrue(probe.sweep(from.position(), to.position(), to.yaw()), "unsafe sweep at edge " + i);
        }
        assertTrue(probe.loaded > 0 && probe.turns > 0 && probe.sweeps > 0 && probe.terrain > 0);
    }

    @Test
    void reachesNearbyNonGridGoalByAnExactValidatedConnector() {
        Vec3 reachableGoal = new Vec3(619.2D, 62.0D, 302.8D);
        M2WallProbe probe = new M2WallProbe();
        GroundVehicleFrontier domain = new GroundVehicleFrontier(START, 175.0F, reachableGoal, 1.0D, 32, probe);
        var planner = new DominionFrontierPlanner<>(domain, domain.startState(), 4096, 32768);
        DominionFrontierPlanner.Result<GroundVehicleFrontier.State> result = null;
        for (int tick = 0; tick < 100; tick++) {
            AtomicInteger grants = new AtomicInteger(72);
            result = planner.advance(() -> grants.getAndDecrement() > 0);
            if (result.status() != DominionFrontierPlanner.Status.PENDING) break;
        }
        assertEquals(DominionFrontierPlanner.Status.COMPLETE, result.status());
        var last = result.path().get(result.path().size() - 1);
        assertTrue(last.exactGoal());
        assertEquals(reachableGoal.x, last.position().x, 1.0E-9D);
        assertEquals(reachableGoal.z, last.position().z, 1.0E-9D);
        assertTrue(probe.occupy(last.position(), last.yaw()));
    }

    @Test
    void hundredTickDeadlineWithoutUsefulProgressIsNoPath() {
        GroundVehicleFrontier.Probe awayOnly = new GroundVehicleFrontier.Probe() {
            @Override public boolean loaded(Vec3 from, Vec3 to) { return true; }
            @Override public boolean occupy(Vec3 position, float yaw) {
                return position.x <= START.x + 1.0E-6D && position.z >= START.z - 1.0E-6D
                        && Math.abs(position.y - START.y) < 0.1D;
            }
            @Override public boolean turn(Vec3 fromPosition, float fromYaw, float toYaw) { return true; }
            @Override public boolean sweep(Vec3 from, Vec3 to, float yaw) { return true; }
            @Override public double terrainCost(Vec3 position) { return 0.75D; }
        };
        GroundVehicleFrontier domain = new GroundVehicleFrontier(START, 175.0F, GOAL, 1.0D, 32, awayOnly);
        var planner = new DominionFrontierPlanner<>(domain, domain.startState(), 4096, 32768);
        DominionFrontierPlanner.Result<GroundVehicleFrontier.State> result = null;
        for (int tick = 0; tick < 100; tick++) {
            AtomicInteger grants = new AtomicInteger(72);
            result = planner.advance(() -> grants.getAndDecrement() > 0);
            if (result.status() != DominionFrontierPlanner.Status.PENDING) break;
        }
        if (result.status() == DominionFrontierPlanner.Status.PENDING) {
            double initialRemaining = horizontal(START, GOAL);
            result = planner.finishAtLimit(state -> GroundRouteControlPolicy.usefulPartial(
                    horizontal(START, state.position()), initialRemaining,
                    horizontal(state.position(), GOAL)));
        }
        assertEquals(DominionFrontierPlanner.Status.NO_PATH, result.status());
        assertTrue(result.probes() <= 100 * 72);
    }

    @Test
    void exactGoalKeepsItsOwnKeyAndChecksRealPoseHeight() {
        M2WallProbe probe = new M2WallProbe();
        GroundVehicleFrontier domain = new GroundVehicleFrontier(START, 175.0F, GOAL, 1.0D, 32, probe);
        var rounded = new GroundVehicleFrontier.State(10, -7,
                new Vec3(START.x + 10, START.y, START.z - 7), -160.0F, false);
        var exact = new GroundVehicleFrontier.State(10, -7,
                new Vec3(GOAL.x, START.y, GOAL.z), -160.0F, true);
        assertFalse(domain.key(rounded).equals(domain.key(exact)));
        assertFalse(domain.isGoal(rounded));
        assertTrue(domain.isGoal(exact));
        assertFalse(domain.isGoal(new GroundVehicleFrontier.State(10, -7,
                new Vec3(GOAL.x, GOAL.y + 1.0D, GOAL.z), -160.0F, true)));
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
    @Test void longExactConnectorStillPaysTheHeuristicTerrainLowerBound() {
        Vec3 goal=START.add(0,0,-5);
        GroundVehicleFrontier domain=new GroundVehicleFrontier(START,180F,goal,1,32,new M2WallProbe());
        var edge=domain.successor(domain.startState(),0);
        assertTrue(edge!=null && edge.state().exactGoal());
        assertTrue(domain.heuristic(domain.startState())<=edge.cost()+domain.heuristic(edge.state())+1e-8);
    }

}
