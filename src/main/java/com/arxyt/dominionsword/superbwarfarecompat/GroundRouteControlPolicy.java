package com.arxyt.dominionsword.superbwarfarecompat;

/** Small decisions shared by the route follower and its regression tests. */
final class GroundRouteControlPolicy {
    private GroundRouteControlPolicy() { }

    static boolean allowDirectOverride(boolean activeRoute, boolean pending, boolean headingFeasible) {
        return !activeRoute && !pending && headingFeasible;
    }

    static boolean holdForRetry(long now, long retryAt, boolean sameGoal) {
        return sameGoal && now < retryAt;
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
