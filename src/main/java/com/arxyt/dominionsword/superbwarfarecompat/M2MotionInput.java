package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.vehicle.navigation.VehicleMotion;

import java.util.Objects;

/** Pure low-speed input policy for one validated M2 stop-to-stop segment. */
public final class M2MotionInput {
    public static final short LEFT = 1;
    public static final short RIGHT = 2;
    public static final short FORWARD = 4;
    public static final short BACK = 8;
    public static final short BRAKE = 16;
    private static final double MAX_STRAIGHT_LENGTH = 4.0D;
    private static final double EPSILON = 1.0E-9D;

    private M2MotionInput() { }

    /** Callers supply conservative estimates in blocks/tick and degrees/tick. */
    public record Calibration(double maxCruiseSpeed, double linearDeceleration,
                              double angularDeceleration, int latencyTicks) {
        public Calibration {
            if (!Double.isFinite(maxCruiseSpeed) || maxCruiseSpeed <= 0.0D
                    || !Double.isFinite(linearDeceleration) || linearDeceleration <= 0.0D
                    || !Double.isFinite(angularDeceleration) || angularDeceleration <= 0.0D
                    || latencyTicks < 0)
                throw new IllegalArgumentException("invalid motion calibration");
        }
    }

    public record Decision(short keys, boolean complete, String reason) {
        public Decision { Objects.requireNonNull(reason, "reason"); }
    }

    public static Decision decide(VehicleMotion.Segment segment, VehicleMotion.Observation observation,
                                  Calibration calibration) {
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(calibration, "calibration");
        if (!observation.supported()) return new Decision((short) 0, false, "SUPPORT_LOST");
        if (!observation.surface().equals(segment.entrySurface())
                && !observation.surface().equals(segment.exitSurface()))
            return new Decision((short) 0, false, "SURFACE_MISMATCH");

        VehicleMotion.Tolerance tolerance = segment.tolerance();
        VehicleMotion.Pose pose = observation.pose();
        VehicleMotion.Pose exit = segment.exit();
        boolean stopped = observation.speed() <= tolerance.stoppedSpeed()
                && observation.angularSpeed() <= tolerance.stoppedAngularSpeed();
        if (observation.surface().equals(segment.exitSurface()) && stopped && tolerance.matches(pose, exit))
            return new Decision((short) 0, true, "COMPLETE");

        if (segment.action() == VehicleMotion.Action.HOLD)
            return new Decision(stopped ? (short) 0 : BRAKE, false, "HOLD");

        if (segment.action() == VehicleMotion.Action.PIVOT) {
            if (observation.speed() > tolerance.stoppedSpeed())
                return new Decision(BRAKE, false, "STOP_BEFORE_PIVOT");
            if (observation.angularSpeed() > segment.maxAngularSpeed() + EPSILON)
                return new Decision(BRAKE, false, "ANGULAR_LIMIT");
            double error = VehicleMotion.angle(exit.yaw() - pose.yaw());
            if (Math.abs(error) <= tolerance.angleDegrees())
                return new Decision(observation.angularSpeed() > tolerance.stoppedAngularSpeed()
                        ? BRAKE : (short) 0, false, "SETTLE_PIVOT");
            double stoppingAngle = stoppingDistance(observation.angularSpeed(),
                    calibration.angularDeceleration(), calibration.latencyTicks());
            if (stoppingAngle + tolerance.angleDegrees() >= Math.abs(error) && observation.angularSpeed() > 0.0D)
                return new Decision(BRAKE, false, "BRAKE_ROTATION");
            // Native tracked steering increases yaw with RIGHT and decreases it with LEFT.
            return new Decision(error > 0.0D ? RIGHT : LEFT, false, "PIVOT");
        }

        if (!straightSegment(segment)) return new Decision(BRAKE, false, "NOT_SHORT_STRAIGHT");
        double yawError = Math.abs(VehicleMotion.angle(pose.yaw() - segment.entry().yaw()));
        if (yawError > tolerance.angleDegrees())
            return new Decision(stopped ? (short) 0 : BRAKE, false, "HEADING_MISMATCH");
        if (observation.angularSpeed() > tolerance.stoppedAngularSpeed())
            return new Decision(BRAKE, false, "ANGULAR_MOTION");
        if (!segment.contains(pose)) return new Decision(BRAKE, false, "OFF_SEGMENT");

        double forwardX = -Math.sin(Math.toRadians(segment.entry().yaw()));
        double forwardZ = Math.cos(Math.toRadians(segment.entry().yaw()));
        double direction = segment.action() == VehicleMotion.Action.FORWARD ? 1.0D : -1.0D;
        double remaining = direction * ((exit.x() - pose.x()) * forwardX
                + (exit.z() - pose.z()) * forwardZ);
        double stopping = stoppingDistance(observation.speed(), calibration.linearDeceleration(),
                calibration.latencyTicks());
        if (observation.speed() > Math.min(calibration.maxCruiseSpeed(), segment.maxSpeed()) + EPSILON
                || remaining <= tolerance.position() + stopping)
            return new Decision(BRAKE, false, "BRAKE_APPROACH");
        return new Decision(segment.action() == VehicleMotion.Action.FORWARD ? FORWARD : BACK,
                false, "ADVANCE");
    }

    private static boolean straightSegment(VehicleMotion.Segment segment) {
        VehicleMotion.Pose entry = segment.entry(), exit = segment.exit();
        double dx = exit.x() - entry.x(), dz = exit.z() - entry.z();
        double distance = Math.hypot(dx, dz);
        if (distance < EPSILON || distance > MAX_STRAIGHT_LENGTH + EPSILON) return false;
        double forwardX = -Math.sin(Math.toRadians(entry.yaw()));
        double forwardZ = Math.cos(Math.toRadians(entry.yaw()));
        double direction = segment.action() == VehicleMotion.Action.FORWARD ? 1.0D : -1.0D;
        double along = direction * (dx * forwardX + dz * forwardZ);
        double cross = Math.abs(dx * forwardZ - dz * forwardX);
        if (along <= 0.0D || cross > Math.max(EPSILON, segment.tolerance().position())) return false;
        for (VehicleMotion.Pose point : segment.trajectory()) {
            if (Math.abs(VehicleMotion.angle(point.yaw() - entry.yaw())) > segment.tolerance().angleDegrees()
                    || Math.abs(VehicleMotion.angle(point.pitch() - entry.pitch())) > segment.tolerance().angleDegrees()
                    || Math.abs(VehicleMotion.angle(point.roll() - entry.roll())) > segment.tolerance().angleDegrees())
                return false;
            double px = point.x() - entry.x(), pz = point.z() - entry.z();
            if (Math.abs(px * forwardZ - pz * forwardX) > Math.max(EPSILON, segment.tolerance().position()))
                return false;
        }
        return true;
    }

    private static double stoppingDistance(double speed, double deceleration, int latencyTicks) {
        return speed * (latencyTicks + 1.0D) + speed * speed / (2.0D * deceleration);
    }
}
