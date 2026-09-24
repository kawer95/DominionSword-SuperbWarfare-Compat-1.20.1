package com.arxyt.dominionsword.superbwarfarecompat;

/** Small decisions shared by the route follower and its regression tests. */
final class GroundRouteControlPolicy {
    private GroundRouteControlPolicy() { }

    // Each one-block edge costs its distance plus at least .75 terrain cost.
    // Diagonal edges give the smallest cost per horizontal block.
    static double frontierHeuristic(double horizontalDistance, double heightDifference) {
        return Math.max(0.0D, horizontalDistance - Math.sqrt(.5D)) * (1.0D + .75D / Math.sqrt(2.0D))
                + Math.max(0.0D, Math.abs(heightDifference) - .75D) * 2.0D;
    }

    static boolean usefulPartial(double distanceFromStart, double initialRemaining, double remaining) {
        return distanceFromStart >= 4.0D && remaining <= initialRemaining - 2.0D;
    }

    static boolean allowDirectOverride(boolean activeRoute, boolean pending, boolean headingFeasible) {
        return !activeRoute && !pending && headingFeasible;
    }

    static boolean holdForRetry(long now, long retryAt, boolean sameGoal) {
        return sameGoal && now < retryAt;
    }

    static boolean alignTrackedRoute(boolean tracked, boolean followingRoute, boolean cannotArc, float absYaw) {
        return tracked && followingRoute && cannotArc && absYaw > 6.0F;
    }

    static boolean continueTrackedPivot(boolean tracked, boolean wasPivoting, float absYaw) {
        return tracked && wasPivoting && absYaw > 6.0F;
    }

    static boolean trackedPivot(boolean tracked, float absYaw, double distance, double pivotRange,
                                boolean narrowOrCannotArc) {
        float threshold = narrowOrCannotArc ? 45.0F : 55.0F;
        if (!tracked || absYaw < threshold) return false;
        if (distance <= pivotRange) return true;
        return narrowOrCannotArc && absYaw >= 82.0F && distance <= pivotRange * 1.5D;
    }
}
