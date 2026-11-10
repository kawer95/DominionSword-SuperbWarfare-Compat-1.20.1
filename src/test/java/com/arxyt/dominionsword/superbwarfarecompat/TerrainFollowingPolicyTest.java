package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFollowingPolicyTest {
    @Test
    void rebasesEachProbeOntoTheLastGroundContact() {
        Vec3 probe = SuperbWarfareUnitAdapter.terrainProbe(new Vec3(12.0D, 80.0D, -3.0D), 63.0D);

        assertEquals(12.0D, probe.x);
        assertEquals(63.0D, probe.y);
        assertEquals(-3.0D, probe.z);
    }

    @Test
    void acceptsContinuousSlopesButNotWallSizedSteps() {
        assertTrue(SuperbWarfareUnitAdapter.terrainStepAllowed(64.0D, 63.0D));
        assertTrue(SuperbWarfareUnitAdapter.terrainStepAllowed(63.0D, 64.0D));
        assertFalse(SuperbWarfareUnitAdapter.terrainStepAllowed(64.0D, 62.5D));
        assertEquals(3.15D, SuperbWarfareUnitAdapter.terrainGridStepHeight(0.0D), 1.0E-9D);
    }

    @Test
    void rejectsAWaypointOrGoalOnAnotherDeckAtTheSameHorizontalPosition() {
        assertTrue(SuperbWarfareUnitAdapter.routePoseHeightMatches(64.0D, 65.0D));
        assertFalse(SuperbWarfareUnitAdapter.routePoseHeightMatches(64.0D, 68.0D));
        assertTrue(SuperbWarfareUnitAdapter.groundHeightMatches(64.0D, 65.0D));
        assertFalse(SuperbWarfareUnitAdapter.groundHeightMatches(64.0D, 68.0D));
    }

    @Test
    void leavesTurningToForwardSteeringInsteadOfAngleBasedReverse() {
        assertFalse(SuperbWarfareUnitAdapter.shouldUseThreePointTurn(true, false, true, false, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldUseThreePointTurn(true, false, true, false, false));
    }

    @Test
    void pivotsTrackedVehiclesOnlyForCloseLargeTurns() {
        assertTrue(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 14.0D, 18.0D, false));
        assertTrue(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 26.0D, 18.0D, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 42.0F, 8.0D, 18.0D, false));
        assertFalse(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 40.0D, 18.0D, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(false, 90.0F, 8.0D, 18.0D, false));
    }

    @Test
    void triesTrackedEscapePivotBeforeDetouringAroundAStall() {
        assertTrue(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, true, 0, 20));
        assertTrue(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, false, 20, 20));
        assertFalse(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, false, 19, 20));
        assertFalse(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(false, true, 20, 20));
    }
    @Test void longRouteProgressDoesNotInvalidateItsActiveSegment() {
        Vec3 start=new Vec3(99,64,0),end=new Vec3(100,64,0);
        assertTrue(SuperbWarfareUnitAdapter.nearActiveRouteSegment(new Vec3(99.5,64,0),start,end,18));
        assertFalse(SuperbWarfareUnitAdapter.nearActiveRouteSegment(new Vec3(99.5,64,25),start,end,18));
        assertFalse(SuperbWarfareUnitAdapter.nearActiveRouteSegment(new Vec3(99.5,90,0),start,end,18));
        assertFalse(SuperbWarfareUnitAdapter.nearActiveRouteSegment(end,null,end,18));
    }

    @Test void bradleyDoesNotConsumeTwoBlockPartialRouteWithoutMoving() {
        Vec3 start=new Vec3(613.19,62.04,312.14);
        Vec3 first=start.add(1,.5,0),tail=start.add(2,.5,0);
        assertFalse(SuperbWarfareUnitAdapter.hasReachedGroundWaypoint(start,first));
        assertFalse(SuperbWarfareUnitAdapter.hasReachedGroundWaypoint(start,tail));
        assertTrue(SuperbWarfareUnitAdapter.hasReachedGroundWaypoint(tail,tail));
        assertFalse(SuperbWarfareUnitAdapter.hasReachedGroundWaypoint(tail.add(0,2,0),tail));
    }

}
