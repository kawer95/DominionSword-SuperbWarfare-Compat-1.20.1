package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.vehicle.navigation.VehicleMotion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M2MotionInputTest {
    private static final VehicleMotion.Tolerance TOLERANCE = new VehicleMotion.Tolerance(.12D, 5.0D, .005D, .05D);
    private static final M2MotionInput.Calibration CALIBRATION = new M2MotionInput.Calibration(.12D, .06D, 1.0D, 0);

    @Test
    void reverseKeepsEntryHeadingAndUsesNativeBackBit() {
        var segment = segment(VehicleMotion.Action.REVERSE, pose(0, 0), pose(-2, 0), .12D, 20.0D);
        var decision = M2MotionInput.decide(segment, observation(pose(0, 0), 0, 0, true), CALIBRATION);
        assertEquals(M2MotionInput.BACK, decision.keys());
        assertFalse(decision.complete());
        assertEquals(0, decision.keys() & (M2MotionInput.LEFT | M2MotionInput.RIGHT | M2MotionInput.FORWARD));
    }

    @Test
    void headingErrorBrakesBeforeAnyTranslationAndLostSupportReleasesInputs() {
        var segment = segment(VehicleMotion.Action.FORWARD, pose(0, 0), pose(2, 0), .12D, 20.0D);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(segment, observation(pose(0, 20), .1D, 0, true), CALIBRATION).keys());
        assertEquals(0,
                M2MotionInput.decide(segment, observation(pose(0, 20), 0, 0, true), CALIBRATION).keys());
        var unsupported = M2MotionInput.decide(segment, observation(pose(0, 0), .1D, 0, false), CALIBRATION);
        assertEquals(0, unsupported.keys());
        assertFalse(unsupported.complete());
        assertEquals("SUPPORT_LOST", unsupported.reason());
    }

    @Test
    void pivotStopsLinearMotionThenUsesOnlyTurnBitsAndWrapsAcrossOneEighty() {
        // SuperbWarfareUnitAdapter.trackedPivotKeys maps positive yawDelta to RIGHT (bit 2).
        var positiveYaw = segment(VehicleMotion.Action.PIVOT, pose(0, 170), pose(0, -170), 0, 10.0D);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(positiveYaw, observation(pose(0, 170), .1D, 0, true), CALIBRATION).keys());
        assertEquals(M2MotionInput.RIGHT,
                M2MotionInput.decide(positiveYaw, observation(pose(0, 170), 0, 0, true), CALIBRATION).keys());
        var negativeYaw = segment(VehicleMotion.Action.PIVOT, pose(0, -170), pose(0, 170), 0, 10.0D);
        assertEquals(M2MotionInput.LEFT,
                M2MotionInput.decide(negativeYaw, observation(pose(0, -170), 0, 0, true), CALIBRATION).keys());
        assertEquals(M2MotionInput.RIGHT,
                M2MotionInput.decide(segment(VehicleMotion.Action.PIVOT, pose(0, 0), pose(0, 180), 0, 10),
                        observation(pose(0, 0), 0, 0, true), CALIBRATION).keys());
        assertEquals(M2MotionInput.LEFT,
                M2MotionInput.decide(segment(VehicleMotion.Action.PIVOT, pose(0, 0), pose(0, -180), 0, 10),
                        observation(pose(0, 0), 0, 0, true), CALIBRATION).keys());
    }

    @Test
    void angularStoppingDistanceBrakesBeforeOvershoot() {
        var pivot = segment(VehicleMotion.Action.PIVOT, pose(0, 0), pose(0, 8), 0, 10.0D);
        var slowActuation = new M2MotionInput.Calibration(.12D, .06D, 1.0D, 1);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(pivot, observation(pose(0, 1), 0, 2.0D, true), slowActuation).keys());
        assertEquals(M2MotionInput.RIGHT,
                M2MotionInput.decide(pivot, observation(pose(0, 1), 0, 0, true), slowActuation).keys());
    }

    @Test
    void brakingAndCompletionRequireSettledSpeedAndNeverThrottleAfterCompletion() {
        var segment = segment(VehicleMotion.Action.FORWARD, pose(0, 0), pose(2, 0), .12D, 20.0D);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(segment, observation(pose(1.2D, 0), .3D, 0, true), CALIBRATION).keys());
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(segment, observation(pose(2, 0), .1D, 0, true), CALIBRATION).keys());
        var done = M2MotionInput.decide(segment, observation(pose(2, 0), 0, 0, true), CALIBRATION);
        assertTrue(done.complete());
        assertEquals(0, done.keys());
    }

    @Test
    void conservativeLowSpeedModelStopsWithoutPassingTheEndpoint() {
        var segment = segment(VehicleMotion.Action.FORWARD, pose(0, 0), pose(2, 0), .12D, 20.0D);
        double z = 0.0D, speed = 0.0D, furthest = 0.0D;
        boolean completed = false;
        for (int tick = 0; tick < 100; tick++) {
            var decision = M2MotionInput.decide(segment, observation(pose(z, 0), speed, 0, true), CALIBRATION);
            if (decision.complete()) {
                assertEquals(0, decision.keys());
                completed = true;
                break;
            }
            if (decision.keys() == M2MotionInput.FORWARD) speed = Math.min(.12D, speed + .04D);
            else if (decision.keys() == M2MotionInput.BRAKE) speed = Math.max(0.0D, speed - .06D);
            else speed = Math.max(0.0D, speed - .06D);
            z += speed;
            furthest = Math.max(furthest, z);
        }
        assertTrue(completed, "the slow actuator model should settle inside tolerance");
        assertTrue(furthest <= 2.0D + TOLERANCE.position());
    }

    @Test
    void rejectsLongOrCurvedMovementCommands() {
        var longSegment = segment(VehicleMotion.Action.FORWARD, pose(0, 0), pose(5, 0), .12D, 20.0D);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(longSegment, observation(pose(0, 0), 0, 0, true), CALIBRATION).keys());
        var curved = segment(VehicleMotion.Action.FORWARD, pose(0, 0), pose(2, 90), .12D, 20.0D);
        assertEquals(M2MotionInput.BRAKE,
                M2MotionInput.decide(curved, observation(pose(0, 0), 0, 0, true), CALIBRATION).keys());

        var epsilonLong = segment(VehicleMotion.Action.FORWARD, pose(0, 0),
                pose(4.0D + 5.0E-10D, 0), .12D, 20.0D);
        assertEquals(M2MotionInput.FORWARD,
                M2MotionInput.decide(epsilonLong, observation(pose(0, 0), 0, 0, true), CALIBRATION).keys());
    }

    private static VehicleMotion.Pose pose(double z, double yaw) {
        return new VehicleMotion.Pose(0.0D, 62.0D, z, yaw, 0.0D, 0.0D);
    }

    private static VehicleMotion.Observation observation(VehicleMotion.Pose pose, double speed,
                                                         double angularSpeed, boolean supported) {
        return new VehicleMotion.Observation(pose, "road", speed, angularSpeed, supported);
    }

    private static VehicleMotion.Segment segment(VehicleMotion.Action action, VehicleMotion.Pose entry,
                                                 VehicleMotion.Pose exit, double maxSpeed, double maxAngularSpeed) {
        return new VehicleMotion.Segment(UUID.randomUUID(), action, "road", "road",
                List.of(entry, exit), TOLERANCE, maxSpeed, maxAngularSpeed,
                List.of(new VehicleMotion.Dependency("flat", 1)));
    }
}
