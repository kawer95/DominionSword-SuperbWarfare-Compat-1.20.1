package com.arxyt.dominionsword.superbwarfarecompat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundRouteControlPolicyTest {
    @Test
    void anActivePartialRouteKeepsItsWaypoints() {
        assertFalse(GroundRouteControlPolicy.allowDirectOverride(true, false, true));
        assertFalse(GroundRouteControlPolicy.allowDirectOverride(false, true, true));
        assertFalse(GroundRouteControlPolicy.allowDirectOverride(false, false, false));
        assertTrue(GroundRouteControlPolicy.allowDirectOverride(false, false, true));
    }

    @Test
    void failureCooldownAppliesOnlyToTheSameGoal() {
        assertTrue(GroundRouteControlPolicy.holdForRetry(100L, 140L, true));
        assertFalse(GroundRouteControlPolicy.holdForRetry(100L, 140L, false));
        assertFalse(GroundRouteControlPolicy.holdForRetry(140L, 140L, true));
    }

    @Test
    void nearTrackedVehiclePivotsWhenItCannotArcAtFiftyOneDegrees() {
        assertTrue(GroundRouteControlPolicy.trackedPivot(true, 51.2F, 1.41D, 12.0D, true));
        assertFalse(GroundRouteControlPolicy.trackedPivot(true, 51.2F, 1.41D, 12.0D, false));
        assertFalse(GroundRouteControlPolicy.trackedPivot(false, 51.2F, 1.41D, 12.0D, true));
    }
    @Test void startedPivotFinishesAlignmentBeforeForwardDriveResumes() {
        assertTrue(GroundRouteControlPolicy.continueTrackedPivot(true,true,44.0F));
        assertTrue(GroundRouteControlPolicy.continueTrackedPivot(true,true,12.0F));
        assertFalse(GroundRouteControlPolicy.continueTrackedPivot(true,true,5.0F));
        assertFalse(GroundRouteControlPolicy.continueTrackedPivot(true,false,12.0F));
        assertFalse(GroundRouteControlPolicy.continueTrackedPivot(false,true,44.0F));
    }

    @Test void shortRouteEdgesNeedAlignmentBeforeTrackForwardInput() {
        assertTrue(GroundRouteControlPolicy.alignTrackedRoute(true,true,true,30F));
        assertFalse(GroundRouteControlPolicy.alignTrackedRoute(true,true,true,5F));
        assertFalse(GroundRouteControlPolicy.alignTrackedRoute(false,true,true,30F));
        assertFalse(GroundRouteControlPolicy.alignTrackedRoute(true,false,true,30F));
    }
    @Test void partialReleaseRequiresMotionAndRealProgressTowardGoal() {
        assertTrue(GroundRouteControlPolicy.usefulPartial(4,20,16));
        assertFalse(GroundRouteControlPolicy.usefulPartial(2,20,18));
        assertFalse(GroundRouteControlPolicy.usefulPartial(6,20,21));
        assertFalse(GroundRouteControlPolicy.usefulPartial(6,20,19));
    }
    @Test void frontierHeuristicAccountsForMandatoryTerrainCost() {
        double h=GroundRouteControlPolicy.frontierHeuristic(10,0);
        assertTrue(h>10);
        assertTrue(h<10*1.75);
        assertTrue(GroundRouteControlPolicy.frontierHeuristic(.5,.5)==0);
    }

}
