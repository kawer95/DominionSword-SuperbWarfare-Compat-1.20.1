package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFollowingPolicyTest {
    @Test void waitsWhenResidualCoastingCanLeaveTheArrivalRadius() {
        assertTrue(SuperbWarfareUnitAdapter.wheelStopPending(.003, -.0048, .945));
        assertFalse(SuperbWarfareUnitAdapter.wheelStopPending(.0001, -.0002, .9));
        assertFalse(SuperbWarfareUnitAdapter.wheelStopPending(0, 0, .999));
    }
    @Test void turnsForDistantRearGoalsWhenTheForwardArcIsUnavailable() {
        assertTrue(SuperbWarfareUnitAdapter.wheelTurnRequired(171.6, 32.95, 8, 5, false));
        assertTrue(SuperbWarfareUnitAdapter.wheelTurnRequired(125.9, 35.87, 8, 5, false));
        assertTrue(SuperbWarfareUnitAdapter.wheelTurnRequired(137.8, 66.67, 8, 5, false));
        assertFalse(SuperbWarfareUnitAdapter.wheelTurnRequired(171.6, 32.95, 8, 5, true));
        assertFalse(SuperbWarfareUnitAdapter.wheelTurnRequired(3, 3, 8, 5, false));
    }

    @Test void turningAwayFromTheGoalIsProgressWhenTheHeadingImproves() {
        var data = new net.minecraft.nbt.CompoundTag();
        SuperbWarfareUnitAdapter.updatePilotProgress(data, 33, 172);
        for (int tick = 1; tick <= 40; tick++) {
            SuperbWarfareUnitAdapter.updatePilotProgress(data, 33 + tick * .1D, 172 - tick * 1.5F);
            assertEquals(0, data.getInt("DominionSwordSuperbPilotNoProgressTicks"));
        }
    }

    @Test void slowMovementAccumulatesButAStationaryVehicleStillStalls() {
        var data = new net.minecraft.nbt.CompoundTag();
        SuperbWarfareUnitAdapter.updatePilotProgress(data, 3, 0);
        for (int tick = 1; tick <= 30; tick++) {
            SuperbWarfareUnitAdapter.updatePilotProgress(data, 3 - tick * .02D, 0);
            assertTrue(data.getInt("DominionSwordSuperbPilotNoProgressTicks") < 5);
        }
        data = new net.minecraft.nbt.CompoundTag();
        for (int tick = 0; tick <= 30; tick++) SuperbWarfareUnitAdapter.updatePilotProgress(data, 33, 172);
        assertEquals(30, data.getInt("DominionSwordSuperbPilotNoProgressTicks"));
    }

    @Test void waitsForResidualEnginePowerBeforeFinishingMovement() {
        assertTrue(SuperbWarfareUnitAdapter.wheelStopPending(.02, 0));
        assertTrue(SuperbWarfareUnitAdapter.wheelStopPending(.001, -.02));
        assertFalse(SuperbWarfareUnitAdapter.wheelStopPending(.001, .001));
    }

    @Test void limitsEveryCloseMoveAndManeuver() {
        assertEquals(0.10D, SuperbWarfareUnitAdapter.wheelSpeedLimit(8, 0, false, false));
        assertEquals(0.065D, SuperbWarfareUnitAdapter.wheelSpeedLimit(3, 150, false, true));
        assertEquals(0.10D, SuperbWarfareUnitAdapter.wheelSpeedLimit(40, 100, false, false));
        assertEquals(0.50D, SuperbWarfareUnitAdapter.wheelSpeedLimit(48, 0, false, false));
    }

    @Test void anticipatesAccelerationAndCounterSteersThePedalsInsteadOfHandbraking() {
        assertEquals(1, SuperbWarfareUnitAdapter.wheelPedal(1, 0, 0, 0, .10, 0));
        assertEquals(0, SuperbWarfareUnitAdapter.wheelPedal(1, 0, 0, 0, .10, 2));
        assertEquals(-1, SuperbWarfareUnitAdapter.wheelPedal(1, .09, .15, .1, .10, 0));
        assertEquals(1, SuperbWarfareUnitAdapter.wheelPedal(-1, -.09, -.15, -.1, .10, 0));
        assertEquals(-1, SuperbWarfareUnitAdapter.wheelPedal(0, .001, .001, .03, 0, 0));
        assertEquals(0, SuperbWarfareUnitAdapter.wheelPedal(0, .001, .001, .001, 0, 0));
    }

    @Test void choosesKTurnWhenARearGoalIsInsideTheTurningCircle() {
        assertTrue(SuperbWarfareUnitAdapter.shortReverseFits(153,9,8));
        assertTrue(SuperbWarfareUnitAdapter.shortReverseFits(164,2.8,8));
        assertFalse(SuperbWarfareUnitAdapter.shortReverseFits(124,3.6,8));
        assertTrue(SuperbWarfareUnitAdapter.shortReverseFits(180,20,8));
        assertFalse(SuperbWarfareUnitAdapter.shortReverseFits(180,30,8));
        assertFalse(SuperbWarfareUnitAdapter.shortReverseFits(90,8,8));
    }

    @Test
    void routeProgressIsMeasuredAgainstActiveSegment() {
        assertEquals(0.0D, SuperbWarfareUnitAdapter.routeSegmentDistance(new Vec3(24, 101, 6), new Vec3(21, 100, 6), new Vec3(27, 100, 6)), 1.0E-9D);
        assertEquals(12.0D, SuperbWarfareUnitAdapter.routeSegmentDistance(new Vec3(24, 101, 18), new Vec3(21, 100, 6), new Vec3(27, 100, 6)), 1.0E-9D);
    }

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
    void usesManeuversForFinalGoalsWithoutReversingAtEveryWaypoint() {
        assertFalse(SuperbWarfareUnitAdapter.shouldUseThreePointTurn(true, false, true, false, true));
        assertTrue(SuperbWarfareUnitAdapter.shouldUseThreePointTurn(true, false, true, false, false));
    }

    @Test
    void pivotsTrackedVehiclesForLargeTurnsAtAnyDistance() {
        assertTrue(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 14.0D, 18.0D, false));
        assertTrue(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 26.0D, 18.0D, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 42.0F, 8.0D, 18.0D, false));
        assertTrue(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(true, 90.0F, 40.0D, 18.0D, false));
        assertFalse(SuperbWarfareUnitAdapter.shouldPivotTrackedVehicle(false, 90.0F, 8.0D, 18.0D, false));
        assertEquals(18, SuperbWarfareUnitAdapter.trackedPivotControlKeys(90.0F, 0.25D));
        assertEquals(2, SuperbWarfareUnitAdapter.trackedPivotControlKeys(90.0F, 0.02D));
    }

    @Test
    void pivotsForAOneDegreeCorridorCorrectionOnlyWhenRollingWouldHit() {
        assertTrue(SuperbWarfareUnitAdapter.shouldUseTrackedCorridorPivot(true, 2.0F, false, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldUseTrackedCorridorPivot(true, 2.0F, true, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldUseTrackedCorridorPivot(true, 2.0F, false, false));
        assertFalse(SuperbWarfareUnitAdapter.shouldUseTrackedCorridorPivot(false, 2.0F, false, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldUseTrackedCorridorPivot(true, 1.0F, false, true));
    }

    @Test
    void triesTrackedEscapePivotBeforeDetouringAroundAStall() {
        assertTrue(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, true, 0, 20));
        assertTrue(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, false, 20, 20));
        assertFalse(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(true, false, 19, 20));
        assertFalse(SuperbWarfareUnitAdapter.shouldAttemptTrackedEscapePivot(false, true, 20, 20));
    }

    @Test
    void acceptsNativeFivePointSupportPatterns() {
        assertTrue(SuperbWarfareUnitAdapter.trackedSupportPatternAccepts(0b1_0000));
        assertTrue(SuperbWarfareUnitAdapter.trackedSupportPatternAccepts(0b0_0011));
        assertTrue(SuperbWarfareUnitAdapter.trackedSupportPatternAccepts(0b0_1100));
        assertFalse(SuperbWarfareUnitAdapter.trackedSupportPatternAccepts(0));
        assertFalse(SuperbWarfareUnitAdapter.trackedSupportPatternAccepts(0b0_0001));
    }

    @Test
    void proxyFallbackRequiresAPlanningConflictWithoutARealWall() {
        assertTrue(SuperbWarfareUnitAdapter.shouldActivateTrackedProxyDrive(true, true, false, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldActivateTrackedProxyDrive(true, true, true, true));
        assertFalse(SuperbWarfareUnitAdapter.shouldActivateTrackedProxyDrive(true, true, false, false));
        assertFalse(SuperbWarfareUnitAdapter.shouldActivateTrackedProxyDrive(false, true, false, true));
    }

    @Test
    void trackedProxyUsesPivotAndShortReverseSemantics() {
        assertEquals(2, SuperbWarfareUnitAdapter.trackedProxyKeys(90.0F, 20.0D));
        assertEquals(8, SuperbWarfareUnitAdapter.trackedProxyKeys(180.0F, 14.0D));
        assertEquals(6, SuperbWarfareUnitAdapter.trackedProxyKeys(20.0F, 20.0D));
    }

    @Test
    void realStallRequiresBothTranslationAndRotationToBeSmall() {
        assertTrue(SuperbWarfareUnitAdapter.trackedMotionStalled(true, 0.014D, 0.34F));
        assertFalse(SuperbWarfareUnitAdapter.trackedMotionStalled(true, 0.015D, 0.34F));
        assertFalse(SuperbWarfareUnitAdapter.trackedMotionStalled(true, 0.014D, 0.35F));
        assertFalse(SuperbWarfareUnitAdapter.trackedMotionStalled(false, 0.0D, 0.0F));
    }
}
