package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionUnitAdapter;
import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionAsyncGridPlanner;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/** Dominion Sword bridge for Mob passengers riding Superb Warfare vehicles. */
public final class SuperbWarfareUnitAdapter implements DominionUnitAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean PATH_LOGGING = Boolean.getBoolean("dominionsword.superbwarfare.pathLog");
    private static final String VEHICLE_CLASS_NAME = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static final Map<Class<?>, Boolean> VEHICLE_CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, Double> GLOBAL_BLOCK_COST_CACHE = new ConcurrentHashMap<>();

    private static final short KEY_LEFT = 1;
    private static final short KEY_RIGHT = 2;
    private static final short KEY_FORWARD = 4;
    private static final short KEY_BACK = 8;
    private static final short KEY_BRAKE_OR_UP = 16;
    private static final short KEY_DOWN = 32;

    private static final double ARRIVAL_DISTANCE = 5.0D;
    private static final double BRAKE_DISTANCE = 8.0D;
    private static final double STUCK_EPSILON = 0.08D;
    private static final float STEER_DEAD_ZONE = 6.0F;
    private static final float FORWARD_ARC = 42.0F;
    private static final float TURN_ONLY_ARC = 105.0F;
    private static final float REVERSE_NAV_ENTER_ARC = 150.0F;
    private static final float REVERSE_NAV_EXIT_ARC = 70.0F;
    private static final int REVERSE_NAV_HOLD_TICKS = 50;
    private static final int[] THREE_POINT_DURATIONS = {34, 28, 30, 24, 24};
    private static final int MAX_THREE_POINT_TICKS = 170;
    private static final double SUPPORT_PROBE_DEPTH = 0.18D;
    private static final double AVOIDANCE_STEP = 3.0D;
    private static final double AVOIDANCE_LOOKAHEAD = 18.0D;
    private static final double AVOIDANCE_SEARCH_RADIUS = 24.0D;
    private static final int AVOIDANCE_MAX_ITERATIONS = 64;
    private static final int AVOIDANCE_YAW_BINS = 16;
    private static final double AVOIDANCE_YAW_STEP = 360.0D / AVOIDANCE_YAW_BINS;
    private static final long AVOIDANCE_LOCK_TICKS = 18L;
    private static final long AVOIDANCE_FAIL_COOLDOWN_TICKS = 40L;
    private static final long AVOIDANCE_REPATH_COOLDOWN_TICKS = 20L;
    private static final long WAYPOINT_CACHE_TICKS = 40L;
    private static final long RESERVATION_TTL_TICKS = 16L;
    private static final double RESERVATION_PATH_PENALTY = 18.0D;
    private static final double FLOW_FIELD_SEARCH_RADIUS = AVOIDANCE_SEARCH_RADIUS * 2.25D;
    private static final int FLOW_FIELD_MAX_ITERATIONS = AVOIDANCE_MAX_ITERATIONS * 5;
    private static final double FLOW_FIELD_LOOKAHEAD_DISTANCE = 12.0D;
    private static final int FLOW_FIELD_LOOKAHEAD_STEPS = 8;
    private static final double DIRECT_FINAL_APPROACH_MIN_RANGE = 15.0D;
    private static final double DIRECT_FINAL_APPROACH_MAX_RANGE = 24.0D;
    private static final double DYNAMIC_LOOKAHEAD_MIN = 6.0D;
    private static final double DYNAMIC_LOOKAHEAD_MAX = 28.0D;
    private static final double FINAL_APPROACH_LOOKAHEAD_MIN = 3.0D;
    private static final double FINAL_APPROACH_LOOKAHEAD_MAX = 10.0D;
    private static final double PILOT_TARGET_SOFT_CHANGE_DISTANCE = 2.0D;
    private static final double PILOT_TARGET_HARD_RESET_DISTANCE = 8.0D;
    private static final double PILOT_TARGET_SAME_CORRIDOR_DOT = 0.92D;
    private static final double PILOT_TARGET_SAME_CORRIDOR_SIDE = 6.0D;
    private static final long EFFECTIVE_ARRIVAL_TIMEOUT_TICKS = 60L;
    private static final String ROUTE_MODE_DIRECT = "DIRECT";
    private static final String ROUTE_MODE_ROUTE = "FOLLOW_ROUTE";
    private static final String ROUTE_MODE_FINAL = "FINAL_APPROACH";
    private static final String ROUTE_MODE_STUCK = "STUCK_RECOVERY";
    private static final String ROUTE_MODE_YIELD = "YIELDING";
    private static final Map<UUID, CachedWaypoint> WAYPOINT_CACHE = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, ActiveFlowField> ACTIVE_FLOW_FIELDS = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<MethodKey> BROKEN_METHODS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Set<UUID>> IGNORED_VEHICLES = ThreadLocal.withInitial(Set::of);
    private static final String PILOT_LAST_DISTANCE = "DominionSwordSuperbPilotLastDistance";
    private static final String PILOT_NO_PROGRESS_TICKS = "DominionSwordSuperbPilotNoProgressTicks";
    private static final String PILOT_REVERSE_TICKS = "DominionSwordSuperbPilotReverseTicks";
    private static final String PILOT_REVERSE_STEER = "DominionSwordSuperbPilotReverseSteer";
    private static final String PILOT_REVERSE_NAV_TICKS = "DominionSwordSuperbPilotReverseNavTicks";
    private static final String PILOT_REVERSE_NAV_STEER = "DominionSwordSuperbPilotReverseNavSteer";
    private static final String PILOT_THREE_POINT_ACTIVE = "DominionSwordSuperbPilotThreePointActive";
    private static final String PILOT_THREE_POINT_STEP = "DominionSwordSuperbPilotThreePointStep";
    private static final String PILOT_THREE_POINT_STEP_TICKS = "DominionSwordSuperbPilotThreePointStepTicks";
    private static final String PILOT_THREE_POINT_TOTAL_TICKS = "DominionSwordSuperbPilotThreePointTotalTicks";
    private static final String PILOT_THREE_POINT_STEER = "DominionSwordSuperbPilotThreePointSteer";
    private static final String PILOT_THREE_POINT_LAST_X = "DominionSwordSuperbPilotThreePointLastX";
    private static final String PILOT_THREE_POINT_LAST_Z = "DominionSwordSuperbPilotThreePointLastZ";
    private static final String PILOT_THREE_POINT_LAST_ABS_YAW = "DominionSwordSuperbPilotThreePointLastAbsYaw";
    private static final String PILOT_THREE_POINT_STUCK_TICKS = "DominionSwordSuperbPilotThreePointStuckTicks";
    private static final int THREE_POINT_REVERSE_PHASE = 0;
    private static final int THREE_POINT_FORWARD_PHASE = 1;
    private static final int THREE_POINT_PHASE_STUCK_TICKS = 10;
    private static final int THREE_POINT_FORWARD_PHASE_TICKS = 18;
    private static final String PILOT_TARGET_X = "DominionSwordSuperbPilotTargetX";
    private static final String PILOT_TARGET_Z = "DominionSwordSuperbPilotTargetZ";
    private static final String PILOT_CAPTURED_TARGET_X = "DominionSwordSuperbPilotCapturedTargetX";
    private static final String PILOT_CAPTURED_TARGET_Z = "DominionSwordSuperbPilotCapturedTargetZ";
    private static final String PILOT_AVOID_TARGET_X = "DominionSwordSuperbPilotAvoidTargetX";
    private static final String PILOT_AVOID_TARGET_Z = "DominionSwordSuperbPilotAvoidTargetZ";
    private static final String PILOT_AVOID_X = "DominionSwordSuperbPilotAvoidX";
    private static final String PILOT_AVOID_Y = "DominionSwordSuperbPilotAvoidY";
    private static final String PILOT_AVOID_Z = "DominionSwordSuperbPilotAvoidZ";
    private static final String PILOT_AVOID_UNTIL = "DominionSwordSuperbPilotAvoidUntil";
    private static final String PILOT_AVOID_EGRESS = "DominionSwordSuperbPilotAvoidEgress";
    private static final String PILOT_AVOID_FAIL_UNTIL = "DominionSwordSuperbPilotAvoidFailUntil";
    private static final String PILOT_AVOID_REPATH_AFTER = "DominionSwordSuperbPilotAvoidRepathAfter";
    private static final String PILOT_PATH_TARGET_X = "DominionSwordSuperbPilotPathTargetX";
    private static final String PILOT_PATH_TARGET_Z = "DominionSwordSuperbPilotPathTargetZ";
    private static final String PILOT_PATH_INDEX = "DominionSwordSuperbPilotPathIndex";
    private static final String PILOT_PATH_POINTS = "DominionSwordSuperbPilotPathPoints";
    private static final String PILOT_ROUTE_GENERATION = "DominionSwordSuperbPilotRouteGeneration";
    private static final String PILOT_PATH_GENERATION = "DominionSwordSuperbPilotPathGeneration";
    private static final String PILOT_FINAL_TARGET_X = "DominionSwordSuperbPilotFinalTargetX";
    private static final String PILOT_FINAL_TARGET_Z = "DominionSwordSuperbPilotFinalTargetZ";
    private static final String PILOT_SAFE_TARGET_X = "DominionSwordSuperbPilotSafeTargetX";
    private static final String PILOT_SAFE_TARGET_Y = "DominionSwordSuperbPilotSafeTargetY";
    private static final String PILOT_SAFE_TARGET_Z = "DominionSwordSuperbPilotSafeTargetZ";
    private static final String PILOT_LAST_INPUT_TICK = "DominionSwordSuperbPilotLastInputTick";
    private static final String PILOT_LAST_INPUT_KEYS = "DominionSwordSuperbPilotLastInputKeys";
    private static final String PILOT_LAST_PATH_DEBUG_TICK = "DominionSwordSuperbPilotLastPathDebugTick";
    private static final String PILOT_ROUTE_MODE = "DominionSwordSuperbPilotRouteMode";
    private static final String PILOT_DRIVE_MODE = "DominionSwordSuperbPilotDriveMode";
    private static final String PILOT_EFFECTIVE_ARRIVE_SINCE = "DominionSwordSuperbPilotEffectiveArriveSince";
    private static final String PATH_POLICY_NBT = "DominionVehiclePathfindingPolicy";
    private static final String PATH_POLICY_AUTO = "auto_on_stuck";
    private static final String PATH_POLICY_ALWAYS = "always";
    private static final String PATH_POLICY_DIRECT = "direct_only";
    private static final String HELI_MODE = "DominionSwordSuperbHeliMode";
    private static final String HELI_NAV_X = "DominionSwordSuperbHeliNavX";
    private static final String HELI_NAV_Y = "DominionSwordSuperbHeliNavY";
    private static final String HELI_NAV_Z = "DominionSwordSuperbHeliNavZ";
    private static final String HELI_HOLD_ALTITUDE = "DominionSwordSuperbHeliHoldAltitude";
    private static final String HELI_LOCKED_ALTITUDE = "DominionSwordSuperbHeliLockedAltitude";
    private static final String HELI_LAST_CONTROL_TRACE_TICK = "DominionSwordSuperbHeliLastControlTraceTick";
    private static final String HELI_LAST_WEAPON_TRACE_TICK = "DominionSwordSuperbHeliLastWeaponTraceTick";
    private static final String HELI_BRAKE_LATCH = "DominionSwordSuperbHeliBrakeLatch";
    private static final String HELI_DEBUG_DESIRED_VERTICAL_SPEED = "DominionSwordSuperbHeliDebugDesiredVerticalSpeed";
    private static final String HELI_FILTERED_ROLL = "DominionSwordSuperbHeliFilteredRoll";
    private static final double HELI_LOW_HOVER_ALTITUDE = 10.0D;
    private static final double HELI_MEDIUM_HOVER_ALTITUDE = 20.0D;
    private static final double HELI_HIGH_HOVER_ALTITUDE = 30.0D;
    private static final double HELI_HORIZONTAL_ARRIVAL = 3.0D;
    private static final double HELI_LANDING_HORIZONTAL = 4.0D;
    private static final double HELI_DIRECT_TRANSLATION_RANGE = 30.0D;
    private static final double HELI_DIRECT_TRANSLATION_DEAD_ZONE = 1.5D;
    private static final double HELI_ALTITUDE_DEAD_ZONE = 1.25D;
    private static final double HELI_ALTITUDE_SPEED_GAIN = 0.075D;
    private static final double HELI_ALTITUDE_SPEED_LIMIT = 0.22D;
    private static final double HELI_VERTICAL_SPEED_DEAD_ZONE = 0.035D;
    private static final double HELI_MAX_CRUISE_PITCH_DEGREES = 7.0D;
    private static final double HELI_MAX_TRANSLATION_PITCH_DEGREES = 6.0D;
    private static final double HELI_MAX_TRANSLATION_ROLL_DEGREES = 7.0D;
    private static final double HELI_MAX_PITCH_INPUT = 9.0D;
    private static final double HELI_MAX_YAW_INPUT = 9.0D;
    private static final String PILOT_ASYNC_ROUTE_PENDING = "DominionSwordSuperbPilotAsyncRoutePending";
    private static final int ASYNC_SNAPSHOT_CELLS_PER_TICK = 72;
    private static final Map<UUID, AsyncRouteBuild> ASYNC_ROUTES = new ConcurrentHashMap<>();
    private static final String HELI_CLEARANCE_SCAN_TICK = "DominionSwordSuperbHeliClearanceScanTick";
    private static final String HELI_CLEARANCE_SCAN_TARGET_X = "DominionSwordSuperbHeliClearanceTargetX";
    private static final String HELI_CLEARANCE_SCAN_TARGET_Z = "DominionSwordSuperbHeliClearanceTargetZ";
    private static final String HELI_CLEARANCE_SCAN_GROUND_Y = "DominionSwordSuperbHeliClearanceGroundY";
    private static final long HELI_CLEARANCE_SCAN_INTERVAL = 5L;
    private static final double HELI_CLEARANCE_SCAN_STEP = 2.0D;
    private static final double HELI_CLEARANCE_SCAN_RANGE = 128.0D;
    private static final String HELI_AVOID_TARGET_X = "DominionSwordSuperbHeliAvoidTargetX";
    private static final String HELI_AVOID_TARGET_Z = "DominionSwordSuperbHeliAvoidTargetZ";
    private static final String HELI_AVOID_WAYPOINT_X = "DominionSwordSuperbHeliAvoidWaypointX";
    private static final String HELI_AVOID_WAYPOINT_Z = "DominionSwordSuperbHeliAvoidWaypointZ";
    private static final String HELI_AVOID_EXPIRES = "DominionSwordSuperbHeliAvoidExpires";
    private static final long HELI_AVOID_TTL = 80L;
    private static final double[] HELI_OBSTACLE_LATERAL_SAMPLES = {-1.0D, -0.5D, 0.0D, 0.5D, 1.0D};
    private static final double[] HELI_OBSTACLE_VERTICAL_SAMPLES = {-1.0D, -0.5D, 0.0D, 0.5D, 1.0D};

    @Override
    public int priority() {
        return 200;
    }

    public static void primeFleetReservations(List<Entity> vehicles, List<Vec3> targets) {
        int count = Math.min(vehicles.size(), targets.size());
        for (int i = 0; i < count; i++) {
            Entity vehicle = vehicles.get(i);
            Vec3 target = targets.get(i);
            if (vehicle == null || target == null || !isSuperbWarfareVehicle(vehicle)) continue;
            VehicleProfile profile = VehicleProfile.from(vehicle);
            VehicleGridManager.rememberFutureFootprint(vehicle, target, profile.voxelRadius, profile.length, RESERVATION_TTL_TICKS);
        }
    }

    public Vec3 fleetWaypoint(Mob mob, Vec3 target) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || target == null) return target;
        VehicleProfile profile = VehicleProfile.from(vehicle);
        Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        double speed = Math.sqrt(velocity.lengthSqr());
        return navigationTarget(mob, vehicle, target, profile, speed);
    }

    public boolean canDriveDirect(Mob mob, Vec3 target, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || target == null) return false;
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            Vec3 safeTarget = safeTargetNear(vehicle, target, profile);
            double distance = flatDistance(vehicle.position(), safeTarget);
            return canTravelDirect(vehicle, vehicle.position(), safeTarget, profile, Math.max(AVOIDANCE_LOOKAHEAD, distance + profile.length));
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    public List<Vec3> fleetRoute(Mob mob, Vec3 start, Vec3 target, int fleetRadius) {
        return fleetRoute(mob, start, target, fleetRadius, Set.of());
    }

    public List<Vec3> fleetRoute(Mob mob, Vec3 start, Vec3 target, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || start == null || target == null) return List.of(target);
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            double distance = start.multiply(1.0D, 0.0D, 1.0D).distanceTo(target.multiply(1.0D, 0.0D, 1.0D));
            double maxCheck = Math.min(Math.max(AVOIDANCE_LOOKAHEAD, FLOW_FIELD_SEARCH_RADIUS), Math.max(AVOIDANCE_LOOKAHEAD, distance));
            if (canTravelDirect(vehicle, start, target, profile, maxCheck)
                    && !hasForeignReservationOnPath(vehicle, start, target, profile, maxCheck)) {
                return List.of(target);
            }
            Vec3 searchTarget = fleetClippedTarget(start, target);
            List<Vec3> path = findAvoidancePath(vehicle, start, searchTarget, profile, AVOIDANCE_SEARCH_RADIUS * 2.25D, AVOIDANCE_MAX_ITERATIONS * 3);
            if (path.size() <= 1) {
                Vec3 fallback = findLateralAvoidanceWaypoint(vehicle, searchTarget, profile);
                return fallback == null ? List.of(searchTarget) : List.of(fallback, searchTarget);
            }
            if (searchTarget.distanceToSqr(target) > 9.0D) path.add(target);
            return path;
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    public void prepareMoveRoute(Mob mob, Vec3 finalTarget, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || finalTarget == null) return;
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            Vec3 safeTarget = safeTargetNear(vehicle, finalTarget, profile);
            String policy = pathPolicy(vehicle);
            CompoundTag data = mob.getPersistentData();
            long generation = data.getLong(PILOT_ROUTE_GENERATION) + 1L;
            clearAvoidanceState(data);
            data.putLong(PILOT_ROUTE_GENERATION, generation);
            data.putDouble(PILOT_FINAL_TARGET_X, finalTarget.x);
            data.putDouble(PILOT_FINAL_TARGET_Z, finalTarget.z);
            data.putDouble(PILOT_SAFE_TARGET_X, safeTarget.x);
            data.putDouble(PILOT_SAFE_TARGET_Y, safeTarget.y);
            data.putDouble(PILOT_SAFE_TARGET_Z, safeTarget.z);
            if (PATH_POLICY_DIRECT.equals(policy)) {
                List<Vec3> route = List.of(vehicle.position(), safeTarget);
                storeAvoidanceRoute(data, safeTarget, route, 1, generation);
                refreshPlannedPath(mob, vehicle, route);
                pathDebug(mob, vehicle, "ROUTE_PREPARED_DIRECT_ONLY", "final=%s safe=%s generation=%d policy=%s", fmt(finalTarget), fmt(safeTarget), generation, policy);
                return;
            }
            List<Vec3> route;
            double distance = flatDistance(vehicle.position(), safeTarget);
            boolean direct = canTravelDirect(vehicle, vehicle.position(), safeTarget, profile, Math.max(AVOIDANCE_LOOKAHEAD, distance + profile.length));
            if (PATH_POLICY_ALWAYS.equals(policy) || !direct) {
                if (startAsyncRoute(mob, vehicle, safeTarget, profile, generation)) {
                    data.putBoolean(PILOT_ASYNC_ROUTE_PENDING, true);
                    storeAvoidanceRoute(data, safeTarget, List.of(vehicle.position(), vehicle.position()), 1, generation);
                    return;
                }
                route = findAvoidancePath(vehicle, vehicle.position(), avoidanceRouteTarget(vehicle.position(), safeTarget), profile,
                        avoidanceRouteSearchRadius(vehicle.position(), safeTarget), avoidanceRouteIterations(avoidanceRouteSearchRadius(vehicle.position(), safeTarget)));
                if (route.size() > 2) route = simplifyAvoidanceRoute(vehicle, route, profile);
                if (route == null || route.size() <= 1) route = List.of(vehicle.position(), safeTarget);
                else if (flatDistance(route.get(route.size() - 1), safeTarget) > 1.0D) route = appendedRoute(route, safeTarget);
            } else {
                route = List.of(vehicle.position(), safeTarget);
            }
            int startIndex = firstUsefulWaypointIndex(route, vehicle.position(), profile);
            storeAvoidanceRoute(data, safeTarget, route, startIndex <= 0 ? 1 : startIndex, generation);
            refreshPlannedPath(mob, vehicle, route);
            pathDebug(mob, vehicle, "ROUTE_PREPARED", "final=%s safe=%s generation=%d policy=%s direct=%s startIndex=%d route=%s", fmt(finalTarget), fmt(safeTarget), generation, policy, direct, startIndex, fmtPath(route));
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    public Vec3 fleetCommandTarget(Mob mob, Vec3 desired, Vec3 anchor, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || desired == null) return desired;
        if (anchor == null) anchor = desired;
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            Vec3 origin = vehicle.position();
            Vec3 offset = desired.subtract(anchor).multiply(1.0D, 0.0D, 1.0D);
            double maxCheck = Math.max(AVOIDANCE_LOOKAHEAD, Math.min(AVOIDANCE_SEARCH_RADIUS, origin.multiply(1.0D, 0.0D, 1.0D).distanceTo(desired.multiply(1.0D, 0.0D, 1.0D))));
            double[] fractions = {1.0D, 0.75D, 0.5D, 0.25D, 0.0D};
            for (double fraction : fractions) {
                Vec3 candidate = anchor.add(offset.scale(fraction));
                Vec3 occupy = findSimpleOccupiablePosition(vehicle, candidate, profile);
                if (occupy == null) continue;
                if (canTravelDirect(vehicle, origin, occupy, profile, maxCheck)
                        && !hasForeignReservationOnPath(vehicle, origin, occupy, profile, maxCheck)) {
                    return occupy;
                }
            }
            return anchor;
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    public List<Vec3> fleetFlowRoute(Mob mob, Vec3 start, Vec3 target, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || start == null || target == null) return List.of(target);
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            Vec3 searchTarget = fleetClippedTarget(start, target);
            List<Vec3> route = findFlowFieldRoute(vehicle, start, searchTarget, profile);
            if (route.size() <= 1) route = findAvoidancePath(vehicle, start, searchTarget, profile, FLOW_FIELD_SEARCH_RADIUS, AVOIDANCE_MAX_ITERATIONS * 2);
            if (route.size() <= 1) return fleetRoute(mob, start, target, fleetRadius, ignoredVehicles);
            if (searchTarget.distanceToSqr(target) > 9.0D) route.add(target);
            return route;
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    public List<Vec3> currentPreparedRoute(Mob mob, Vec3 fallbackTarget, int fleetRadius, Set<UUID> ignoredVehicles) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null || driver(vehicle) != mob || fallbackTarget == null) return List.of(fallbackTarget);
        CompoundTag data = mob.getPersistentData();
        if (hasActiveSafeTarget(data, fallbackTarget) && data.contains(PILOT_PATH_POINTS, Tag.TAG_LIST)
                && (!data.contains(PILOT_PATH_GENERATION) || data.getLong(PILOT_PATH_GENERATION) == data.getLong(PILOT_ROUTE_GENERATION))) {
            ListTag points = data.getList(PILOT_PATH_POINTS, Tag.TAG_COMPOUND);
            List<Vec3> route = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                Vec3 point = readPathPoint(points.getCompound(i));
                if (point != null) route.add(point);
            }
            if (!route.isEmpty()) return route;
        }
        return fleetRoute(mob, vehicle.position(), safeTargetNear(vehicle, fallbackTarget, VehicleProfile.from(vehicle).withFleetRadius(fleetRadius)), fleetRadius, ignoredVehicles);
    }

    public void primeFlowField(Vec3 targetCenter, int fleetRadius, Set<UUID> ignoredVehicles, List<Mob> drivers) {
        if (targetCenter == null || drivers == null || drivers.isEmpty()) return;
        Mob leader = drivers.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (leader == null) return;
        Entity vehicle = vehicleOf(leader);
        if (vehicle == null) return;
        Set<UUID> previousIgnored = IGNORED_VEHICLES.get();
        Set<UUID> previousGridIgnored = VehicleGridManager.setIgnoredVehicles(ignoredVehicles);
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        try {
            VehicleProfile profile = VehicleProfile.from(vehicle).withFleetRadius(fleetRadius);
            ActiveFlowField field = buildFlowField(vehicle, targetCenter, profile);
            if (field == null) return;
            long expires = vehicle.level().getGameTime() + WAYPOINT_CACHE_TICKS;
            for (Mob driver : drivers) if (driver != null) ACTIVE_FLOW_FIELDS.put(driver.getUUID(), field.withExpiresAt(expires));
        } finally {
            IGNORED_VEHICLES.set(previousIgnored);
            VehicleGridManager.setIgnoredVehicles(previousGridIgnored);
        }
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof Mob mob && vehicleOf(mob) != null;
    }

    @Override
    public boolean select(ServerPlayer player, Entity entity) {
        return entity instanceof Mob mob && vehicleOf(mob) != null;
    }

    @Override
    public boolean move(ServerPlayer player, Entity entity, Vec3 target) {
        try (LagTrace ignoredTrace = LagTrace.start("unit.move", "entity=" + (entity == null ? "null" : entity.getId()) + " target=" + (target == null ? "null" : fmt(target)))) {
            if (!(entity instanceof Mob mob)) return false;
            Entity vehicle = vehicleOf(mob);
            if (vehicle == null) return false;
            LagTrace.mark("resolve_vehicle:id=" + vehicle.getId());

            mob.setTarget(null);

            if (driver(vehicle) != mob) {
                LagTrace.mark("not_driver");
                if (isHelicopter(vehicle)) stopVehicle(vehicle);
                return true;
            }

            if (isHelicopter(vehicle)) {
                LagTrace.mark("helicopter");
                return moveHelicopter(mob, vehicle, target);
            }

            VehicleProfile profile = VehicleProfile.from(vehicle);
            Vec3 commandTarget = target;
            CompoundTag data = mob.getPersistentData();
            ensurePreparedRoute(mob, vehicle, commandTarget, profile);
            LagTrace.mark("prepare_route");
            Vec3 finalTarget = activeSafeTarget(data, commandTarget);
            Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
            double speed = Math.sqrt(velocity.lengthSqr());
            if (hasCapturedFinalTarget(data, vehicle, finalTarget, profile)) {
                pathDebugThrottled(mob, vehicle, "FINAL_CAPTURE_HOLD", "final=%s pos=%s dist=%.2f", fmt(finalTarget), fmt(vehicle.position()), flatDistance(vehicle.position(), finalTarget));
                LagTrace.mark("captured_hold");
                processInput(vehicle, KEY_BRAKE_OR_UP);
                LagTrace.mark("process_input:brake_capture");
                return true;
            }
            String policy = pathPolicy(vehicle);
            Vec3 flowTarget = PATH_POLICY_DIRECT.equals(policy) ? null : flowFieldTarget(mob, vehicle, finalTarget, profile);
            LagTrace.mark("flow_target:" + (flowTarget != null));
            if (flowTarget != null) target = flowTarget;
            else target = PATH_POLICY_DIRECT.equals(policy) ? finalTarget : navigationTarget(mob, vehicle, finalTarget, profile, speed);
            LagTrace.mark("navigation_target");
            if (target == null) {
                pathDebug(mob, vehicle, "BRAKE_NULL_TARGET", "final=%s pos=%s", fmt(finalTarget), fmt(vehicle.position()));
                LagTrace.mark("null_target");
                processInput(vehicle, KEY_BRAKE_OR_UP);
                LagTrace.mark("process_input:brake_null");
                return true;
            }
            Vec3 delta = target.subtract(vehicle.position());
            double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            boolean intermediateWaypoint = target.distanceToSqr(finalTarget) > 4.0D;
            double activeArrivalDistance = intermediateWaypoint ? routePointReach(profile) : arrivalDistance(profile);
            LagTrace.mark("distance_checks");
        if (horizontalDistance <= activeArrivalDistance) {
            if (intermediateWaypoint) {
                clearAvoidanceWaypoint(mob.getPersistentData());
                pathDebugThrottled(mob, vehicle, "SKIP_NEAR_WAYPOINT", "waypoint=%s final=%s dist=%.2f reach=%.2f", fmt(target), fmt(finalTarget), horizontalDistance, activeArrivalDistance);
                processInput(vehicle, KEY_BRAKE_OR_UP);
                return true;
            }
            if (horizontalDistance <= activeArrivalDistance) {
                clearPilotState(mob);
                    processInput(vehicle, KEY_BRAKE_OR_UP);
                    LagTrace.mark("process_input:brake_near_waypoint");
                    return true;
                }
            }
            VehicleGridManager.rememberFutureFootprint(vehicle, target, profile.voxelRadius, profile.length, RESERVATION_TTL_TICKS);
            LagTrace.mark("remember_footprint");

            resetPilotStateIfTargetChanged(mob, finalTarget);
            updatePilotProgress(data, horizontalDistance);
            LagTrace.mark("progress_update");
        if (!intermediateWaypoint && shouldHardBrakeNearFinal(vehicle, finalTarget, horizontalDistance, activeArrivalDistance, speed, profile)) {
            if (hasPassedFinalTarget(vehicle, finalTarget) && horizontalDistance <= finalCaptureDistance(profile, speed) + 0.50D) {
                clearPilotState(mob);
                data = mob.getPersistentData();
                data.putDouble(PILOT_CAPTURED_TARGET_X, finalTarget.x);
                data.putDouble(PILOT_CAPTURED_TARGET_Z, finalTarget.z);
                pathDebug(mob, vehicle, "FINAL_OVERSHOOT_CAPTURE", "final=%s pos=%s dist=%.2f speed=%.3f", fmt(finalTarget), fmt(vehicle.position()), horizontalDistance, speed);
            } else {
                pathDebugThrottled(mob, vehicle, "FINAL_HARD_BRAKE", "final=%s pos=%s dist=%.2f reach=%.2f speed=%.3f", fmt(finalTarget), fmt(vehicle.position()), horizontalDistance, activeArrivalDistance, speed);
            }
                processInput(vehicle, KEY_BRAKE_OR_UP);
                LagTrace.mark("process_input:hard_brake");
                return true;
            }
            LagTrace.mark("final_brake_checks");
            if (!intermediateWaypoint && shouldCaptureFinalTarget(horizontalDistance, speed, data, profile, vehicle.level().getGameTime())) {
            clearPilotState(mob);
            data = mob.getPersistentData();
            data.putDouble(PILOT_CAPTURED_TARGET_X, finalTarget.x);
            data.putDouble(PILOT_CAPTURED_TARGET_Z, finalTarget.z);
            pathDebug(mob, vehicle, "FINAL_CAPTURE_STOP", "final=%s pos=%s dist=%.2f speed=%.3f capture=%.2f", fmt(finalTarget), fmt(vehicle.position()), horizontalDistance, speed, finalCaptureDistance(profile, speed));
                processInput(vehicle, KEY_BRAKE_OR_UP);
                LagTrace.mark("process_input:capture_stop");
                return true;
            }
            LagTrace.mark("capture_checks");
            if (intermediateWaypoint && data.getInt(PILOT_NO_PROGRESS_TICKS) >= 10 && speed < 0.08D) {
            Vec3 egress = findEmergencyEgressWaypoint(vehicle, finalTarget, profile);
            if (egress != null && flatDistance(vehicle.position(), egress) > activeArrivalDistance + 0.75D) {
                clearAvoidanceState(data);
                data.putDouble(PILOT_AVOID_TARGET_X, finalTarget.x);
                data.putDouble(PILOT_AVOID_TARGET_Z, finalTarget.z);
                data.putDouble(PILOT_AVOID_X, egress.x);
                data.putDouble(PILOT_AVOID_Y, egress.y);
                data.putDouble(PILOT_AVOID_Z, egress.z);
                data.putLong(PILOT_AVOID_UNTIL, vehicle.level().getGameTime() + Math.max(AVOIDANCE_LOCK_TICKS, 30));
                data.putBoolean(PILOT_AVOID_EGRESS, true);
                data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
                WAYPOINT_CACHE.remove(vehicle.getUUID());
                pathDebug(mob, vehicle, "ROUTE_STALL_EGRESS", "old=%s egress=%s final=%s distOld=%.2f speed=%.3f", fmt(target), fmt(egress), fmt(finalTarget), horizontalDistance, speed);
                target = egress;
                delta = target.subtract(vehicle.position());
                horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                intermediateWaypoint = target.distanceToSqr(finalTarget) > 4.0D;
                activeArrivalDistance = intermediateWaypoint ? routePointReach(profile) : arrivalDistance(profile);
            } else {
                clearAvoidanceState(data);
                data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
                WAYPOINT_CACHE.remove(vehicle.getUUID());
                pathDebug(mob, vehicle, "ROUTE_STALL_REPATH", "old=%s final=%s distOld=%.2f speed=%.3f", fmt(target), fmt(finalTarget), horizontalDistance, speed);
                    processInput(vehicle, KEY_BRAKE_OR_UP);
                    LagTrace.mark("process_input:stall_repath");
                    return true;
                }
            }
            LagTrace.mark("stall_checks");

            float desiredYaw = (float) Mth.wrapDegrees(-(Mth.atan2(delta.x, delta.z) * Mth.RAD_TO_DEG));
        float yawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());
        float absYawDelta = Math.abs(yawDelta);
        boolean finalApproach = !intermediateWaypoint && horizontalDistance <= Math.max(AVOIDANCE_LOOKAHEAD, profile.length * 3.0D);

        int reverseTicks = data.getInt(PILOT_REVERSE_TICKS);
        boolean wideTurnAvailable = absYawDelta >= FORWARD_ARC && canWideTurn(vehicle, yawDelta, profile);
        if (shouldYieldToFleetReservation(vehicle, target, finalTarget, profile, horizontalDistance, intermediateWaypoint, wideTurnAvailable)) {
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_YIELD);
            pathDebugThrottled(mob, vehicle, "YIELD_FLEET_RESERVATION", "target=%s final=%s dist=%.2f intermediate=%s wideTurn=%s", fmt(target), fmt(finalTarget), horizontalDistance, intermediateWaypoint, wideTurnAvailable);
                processInput(vehicle, KEY_BRAKE_OR_UP);
                LagTrace.mark("process_input:yield");
                return true;
            }
            LagTrace.mark("yield_check");
            DriveDecision driveDecision = decideDriveMode(vehicle, yawDelta, absYawDelta, horizontalDistance, speed, profile, wideTurnAvailable, finalApproach);
            LagTrace.mark("drive_decision:" + driveDecision.mode().name());
        data.putString(PILOT_DRIVE_MODE, driveDecision.mode().name());
        if (vehicle.horizontalCollision && reverseTicks <= 0) {
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_STUCK);
            Vec3 egress = findEmergencyEgressWaypoint(vehicle, finalTarget, profile);
            if (egress != null && flatDistance(vehicle.position(), egress) > activeArrivalDistance + 0.75D) {
                clearAvoidanceState(data);
                WAYPOINT_CACHE.remove(vehicle.getUUID());
                data.putDouble(PILOT_AVOID_TARGET_X, finalTarget.x);
                data.putDouble(PILOT_AVOID_TARGET_Z, finalTarget.z);
                data.putDouble(PILOT_AVOID_X, egress.x);
                data.putDouble(PILOT_AVOID_Y, egress.y);
                data.putDouble(PILOT_AVOID_Z, egress.z);
                data.putLong(PILOT_AVOID_UNTIL, vehicle.level().getGameTime() + Math.max(AVOIDANCE_LOCK_TICKS, 36));
                data.putBoolean(PILOT_AVOID_EGRESS, true);
                data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
                pathDebug(mob, vehicle, "COLLISION_EGRESS", "old=%s egress=%s final=%s speed=%.3f", fmt(target), fmt(egress), fmt(finalTarget), speed);
                target = egress;
                delta = target.subtract(vehicle.position());
                horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                desiredYaw = (float) Mth.wrapDegrees(-(Mth.atan2(delta.x, delta.z) * Mth.RAD_TO_DEG));
                yawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());
                absYawDelta = Math.abs(yawDelta);
                intermediateWaypoint = true;
                activeArrivalDistance = routePointReach(profile);
                wideTurnAvailable = absYawDelta >= FORWARD_ARC && canWideTurn(vehicle, yawDelta, profile);
                driveDecision = decideDriveMode(vehicle, yawDelta, absYawDelta, horizontalDistance, speed, profile, wideTurnAvailable, false);
                data.putString(PILOT_DRIVE_MODE, driveDecision.mode().name());
            } else if (driveDecision.mode() == DriveMode.THREE_POINT && !data.getBoolean(PILOT_THREE_POINT_ACTIVE)) {
                startThreePointTurn(data, yawDelta);
            } else {
                reverseTicks = 12;
                data.putInt(PILOT_REVERSE_STEER, yawDelta >= 0 ? -1 : 1);
            }
        }
            LagTrace.mark("collision_checks");
            int stuckThreshold = 20 + Math.floorMod(vehicle.getId(), 10);
            if (data.getInt(PILOT_NO_PROGRESS_TICKS) >= stuckThreshold && reverseTicks <= 0) {
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_STUCK);
            if (driveDecision.mode() == DriveMode.THREE_POINT && !data.getBoolean(PILOT_THREE_POINT_ACTIVE)) {
                startThreePointTurn(data, yawDelta);
            } else if (driveDecision.mode() == DriveMode.FORWARD && !vehicle.horizontalCollision && absYawDelta < REVERSE_NAV_ENTER_ARC) {
                reverseTicks = 0;
            } else {
                reverseTicks = 14;
                data.putInt(PILOT_REVERSE_STEER, yawDelta >= 0 ? -1 : 1);
            }
                data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
            }
            LagTrace.mark("stuck_checks");

            short keys;
        if (data.getBoolean(PILOT_THREE_POINT_ACTIVE)) {
            if (canExitThreePoint(vehicle, yawDelta, absYawDelta, horizontalDistance, profile)) {
                clearThreePointState(data);
                keys = cruiseKeys(vehicle, yawDelta, absYawDelta, horizontalDistance, wideTurnAvailable, activeArrivalDistance, Double.POSITIVE_INFINITY, intermediateWaypoint);
            } else {
                keys = threePointKeys(vehicle, data, absYawDelta);
            }
            data.remove(PILOT_REVERSE_NAV_TICKS);
            data.remove(PILOT_REVERSE_TICKS);
        } else if (driveDecision.mode() == DriveMode.THREE_POINT) {
            startThreePointTurn(data, yawDelta);
            keys = threePointKeys(vehicle, data, absYawDelta);
            data.remove(PILOT_REVERSE_NAV_TICKS);
            data.remove(PILOT_REVERSE_TICKS);
        } else if (reverseTicks > 0) {
            keys = KEY_BACK;
            int reverseSteer = data.getInt(PILOT_REVERSE_STEER);
            if (reverseSteer > 0) keys |= KEY_RIGHT;
            else if (reverseSteer < 0) keys |= KEY_LEFT;
            data.putInt(PILOT_REVERSE_TICKS, reverseTicks - 1);
        } else if (driveDecision.mode() == DriveMode.TURN_AROUND) {
            keys = turnAroundKeys(yawDelta);
            data.remove(PILOT_REVERSE_NAV_TICKS);
            data.remove(PILOT_REVERSE_TICKS);
            clearThreePointState(data);
        } else if (driveDecision.mode() == DriveMode.REVERSE_SHORT) {
            keys = reverseNavigationKeys(vehicle, desiredYaw, horizontalDistance, activeArrivalDistance, data, true);
            data.putInt(PILOT_REVERSE_NAV_TICKS, REVERSE_NAV_HOLD_TICKS);
            clearThreePointState(data);
        } else {
            data.remove(PILOT_REVERSE_NAV_TICKS);
            clearThreePointState(data);
            double routeSpeedLimit = routeCurvatureSpeedLimit(data, vehicle.position(), target, speed, profile);
            keys = finalApproach
                    ? finalApproachKeys(yawDelta, absYawDelta, horizontalDistance, activeArrivalDistance, speed, profile)
                    : cruiseKeys(vehicle, yawDelta, absYawDelta, horizontalDistance, wideTurnAvailable, activeArrivalDistance, routeSpeedLimit, intermediateWaypoint);
        }

            logDecision(vehicle, driveDecision.mode(), data.getBoolean(PILOT_THREE_POINT_ACTIVE), finalApproach, intermediateWaypoint,
                    target, finalTarget, yawDelta, absYawDelta, horizontalDistance, speed, estimatedTurnRadius(profile), wideTurnAvailable,
                    isShortReverseTarget(absYawDelta, horizontalDistance, profile), cannotArcToTarget(absYawDelta, horizontalDistance, profile), keys);
            LagTrace.mark("keys:" + keys);
            processInput(vehicle, keys);
            alignWeaponsForward(vehicle, keys);
            LagTrace.mark("process_input:drive");
            return true;
        }
    }

    private static void alignWeaponsForward(Entity vehicle, short keys) {
        boolean moving = (keys & 4) != 0;
        if (!moving) return;
        Vec3 forward = vehicle.position().add(vehicle.getLookAngle().multiply(24.0D, 0.0D, 24.0D));
        invokeIfPresent(vehicle, "turretAutoAimFromVector", new Class<?>[]{Vec3.class}, forward);
        invokeIfPresent(vehicle, "passengerWeaponAutoAimFormVector", new Class<?>[]{Vec3.class}, forward);
    }

    @Override
    public boolean attack(ServerPlayer player, Entity entity, LivingEntity target) {
        if (!(entity instanceof Mob mob)) return false;
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null) return false;

        mob.setTarget(target);
        mob.lookAt(target, 180.0F, 180.0F);
        mob.getLookControl().setLookAt(target, 180.0F, 180.0F);
        autoAimForSeat(vehicle, mob, target);
        if (driver(vehicle) == mob && isHelicopter(vehicle) && hasClearWeaponLine(vehicle, target)) {
            aimHelicopterBodyWeapon(vehicle, target);
        }
        traceHelicopterWeapon(mob, vehicle, target);
        return true;
    }

    @Override
    public boolean hold(ServerPlayer player, Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null) return false;

        stopVehicle(vehicle);
        mob.setTarget(null);
        clearPilotState(mob);
        return true;
    }

    @Override
    public boolean release(ServerPlayer player, Entity entity) {
        return hold(player, entity);
    }

    @Override
    public boolean clearAttack(ServerPlayer player, Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        Entity vehicle = vehicleOf(mob);
        if (vehicle == null) return false;

        mob.setTarget(null);
        return true;
    }

    private static Entity vehicleOf(Mob mob) {
        Entity vehicle = mob.getVehicle();
        return vehicle != null && isSuperbWarfareVehicle(vehicle) ? vehicle : null;
    }

    private static Entity driver(Entity vehicle) {
        Object value = invokeWithArgs(vehicle, "getNthEntity", new Class<?>[]{int.class}, 0);
        return value instanceof Entity entity ? entity : null;
    }

    private enum DriveMode {
        FORWARD,
        REVERSE_SHORT,
        TURN_AROUND,
        THREE_POINT
    }

    private record DriveDecision(DriveMode mode, double forwardEta, double reverseEta, double turnAroundEta) {}

    private static DriveDecision decideDriveMode(Entity vehicle, float yawDelta, float absYawDelta, double horizontalDistance,
                                                double speed, VehicleProfile profile, boolean wideTurnAvailable, boolean finalApproach) {
        double turnRadius = estimatedTurnRadius(profile);
        boolean tracked = isTrackedVehicle(vehicle);
        boolean wheeled = !tracked && turnRadius > 3.0D;
        boolean rearTarget = absYawDelta >= REVERSE_NAV_ENTER_ARC;
        double shortReverseDistance = Math.max(25.0D, profile.length * 2.0D);
        double turnPenalty = absYawDelta / Math.max(8.0D, profile.yawStep);
        double stopPenalty = Math.max(0.0D, speed) * 18.0D;
        double forwardEta = horizontalDistance / Math.max(0.08D, profile.forwardStep) + turnPenalty + stopPenalty;
        if (wheeled && absYawDelta >= 85.0F) {
            double arcLength = turnRadius * Math.toRadians(absYawDelta);
            forwardEta += arcLength / Math.max(0.08D, profile.forwardStep) + 28.0D;
        } else if (absYawDelta >= TURN_ONLY_ARC && !wideTurnAvailable) {
            forwardEta += 60.0D;
        }
        if (absYawDelta >= REVERSE_NAV_ENTER_ARC && wideTurnAvailable && !wheeled) forwardEta -= 22.0D;
        if (absYawDelta <= 100.0F) forwardEta -= 18.0D;

        float rearYawDelta = Math.abs(Mth.wrapDegrees(yawDelta - 180.0F));
        rearYawDelta = Math.min(rearYawDelta, Math.abs(Mth.wrapDegrees(yawDelta + 180.0F)));
        double reverseEta = horizontalDistance / Math.max(0.06D, profile.reverseStep) + rearYawDelta / Math.max(8.0D, profile.yawStep) + stopPenalty * 1.15D;
        if (!rearTarget) reverseEta += 80.0D;
        if (horizontalDistance > shortReverseDistance) reverseEta += (horizontalDistance - shortReverseDistance) * 9.0D;
        if (finalApproach && horizontalDistance <= shortReverseDistance) reverseEta -= 12.0D;

        double turnAroundEta = stopPenalty + 50.0D + horizontalDistance / Math.max(0.08D, profile.forwardStep);
        if (wideTurnAvailable && !wheeled) turnAroundEta = forwardEta + 18.0D;
        if (horizontalDistance <= shortReverseDistance) turnAroundEta += 35.0D;
        boolean canThreePoint = canThreePointTurn(vehicle, yawDelta, profile);
        if (!canThreePoint) turnAroundEta += 80.0D;

        boolean shortReverseTarget = rearTarget && horizontalDistance <= shortReverseDistance;
        boolean cannotArcToTarget = wheeled && absYawDelta >= 45.0F && horizontalDistance <= Math.max(turnRadius * 2.0D, profile.length * 2.5D);
        boolean shouldThreePoint = !shortReverseTarget && cannotArcToTarget;
        DriveMode mode;
        if (shortReverseTarget) {
            mode = DriveMode.REVERSE_SHORT;
        } else if (tracked) {
            mode = DriveMode.FORWARD;
        } else if (shouldThreePoint) {
            mode = DriveMode.THREE_POINT;
        } else if (rearTarget && horizontalDistance > shortReverseDistance) {
            mode = DriveMode.TURN_AROUND;
        } else {
            mode = DriveMode.FORWARD;
        }
        return new DriveDecision(mode, forwardEta, reverseEta, turnAroundEta);
    }

    private static double estimatedTurnRadius(VehicleProfile profile) {
        return profile.forwardStep / Math.max(0.01D, Math.toRadians(profile.yawStep));
    }

    private static boolean isShortReverseTarget(float absYawDelta, double horizontalDistance, VehicleProfile profile) {
        return absYawDelta >= REVERSE_NAV_ENTER_ARC && horizontalDistance <= Math.max(25.0D, profile.length * 2.0D);
    }

    private static boolean cannotArcToTarget(float absYawDelta, double horizontalDistance, VehicleProfile profile) {
        double turnRadius = estimatedTurnRadius(profile);
        return turnRadius > 3.0D && absYawDelta >= 45.0F && horizontalDistance <= Math.max(turnRadius * 2.0D, profile.length * 2.5D);
    }

    private static short cruiseKeys(Entity vehicle, float yawDelta, float absYawDelta, double horizontalDistance, boolean wideTurnAvailable, double stopDistance, double routeSpeedLimit, boolean intermediateWaypoint) {
        short keys = 0;
        if (absYawDelta > STEER_DEAD_ZONE) {
            keys |= yawDelta > 0 ? KEY_RIGHT : KEY_LEFT;
        }

        Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        double speed = Math.sqrt(velocity.lengthSqr());
        double brakeStart = Math.max(stopDistance + 0.35D, sourceBasedBrakeDistance(speed, stopDistance));
        if (absYawDelta < TURN_ONLY_ARC || wideTurnAvailable) {
            keys |= KEY_FORWARD;
        } else if (absYawDelta >= TURN_ONLY_ARC) {
            keys |= KEY_BRAKE_OR_UP;
        }

        boolean braking = false;
        if (!intermediateWaypoint && horizontalDistance < brakeStart) {
            keys |= KEY_BRAKE_OR_UP;
            braking = true;
        }
        if (Double.isFinite(routeSpeedLimit) && speed > routeSpeedLimit) {
            keys |= KEY_BRAKE_OR_UP;
            braking = true;
        }
        if (braking) keys = brakeOnly(keys);
        return keys;
    }

    private static short finalApproachKeys(float yawDelta, float absYawDelta, double horizontalDistance, double stopDistance, double speed, VehicleProfile profile) {
        short keys = 0;
        if (absYawDelta > STEER_DEAD_ZONE) {
            keys |= yawDelta > 0 ? KEY_RIGHT : KEY_LEFT;
        }

        double brakeStart = sourceBasedBrakeDistance(speed, stopDistance);
        double hardAlignDistance = Math.max(6.0D, profile.length + 2.0D);
        boolean badlyMisaligned = absYawDelta > 55.0F;
        boolean moderatelyMisaligned = absYawDelta > 32.0F;

        boolean braking = false;
        if (badlyMisaligned) {
            if (speed < 0.06D && horizontalDistance > hardAlignDistance) {
                keys |= KEY_FORWARD;
            } else {
                keys |= KEY_BRAKE_OR_UP;
                braking = true;
            }
        } else if (moderatelyMisaligned) {
            if (speed > 0.18D || horizontalDistance <= hardAlignDistance) {
                keys |= KEY_BRAKE_OR_UP;
                braking = true;
            } else {
                keys |= KEY_FORWARD;
            }
        } else {
            keys |= KEY_FORWARD;
        }

        if (horizontalDistance <= stopDistance + 0.15D || horizontalDistance <= brakeStart) {
            keys |= KEY_BRAKE_OR_UP;
            braking = true;
        }
        if (braking) keys = brakeOnly(keys);
        return keys;
    }

    private static short brakeOnly(short keys) {
        return (short) ((keys | KEY_BRAKE_OR_UP) & ~KEY_FORWARD & ~KEY_BACK);
    }

    private static boolean shouldHardBrakeNearFinal(Entity vehicle, Vec3 finalTarget, double horizontalDistance, double stopDistance, double speed, VehicleProfile profile) {
        if (finalTarget == null || speed < 0.05D) return false;
        double effective = Math.max(stopDistance + 1.0D, finalCaptureDistance(profile, speed) + 0.50D);
        if (hasPassedFinalTarget(vehicle, finalTarget) && horizontalDistance <= effective) return true;
        if (horizontalDistance > stopDistance + 1.25D) return false;
        Vec3 toTarget = finalTarget.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        if (toTarget.lengthSqr() < 1.0E-6D || velocity.lengthSqr() < 1.0E-6D) return false;
        double closingPerTick = velocity.dot(toTarget.normalize());
        return closingPerTick > 0.02D && horizontalDistance - closingPerTick * 2.0D <= stopDistance + 0.05D;
    }

    private static boolean hasPassedFinalTarget(Entity vehicle, Vec3 finalTarget) {
        if (finalTarget == null) return false;
        Vec3 toTarget = finalTarget.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        if (toTarget.lengthSqr() < 1.0E-6D || velocity.lengthSqr() < 1.0E-6D) return false;
        return velocity.dot(toTarget.normalize()) < -0.02D;
    }

    private static double arrivalDistance(VehicleProfile profile) {
        return 1.0D;
    }

    private static double finalCaptureDistance(VehicleProfile profile, double speed) {
        return Mth.clamp(2.0D + profile.width * 0.25D + Math.max(0.0D, speed - 0.05D) * 10.0D, 2.25D, 6.0D);
    }

    private static double finalCaptureHoldDistance(VehicleProfile profile) {
        return Math.max(6.0D, profile.length + 1.0D);
    }

    private static boolean shouldCaptureFinalTarget(double horizontalDistance, double speed, CompoundTag data, VehicleProfile profile, long now) {
        if (horizontalDistance <= 1.0D) return true;
        double effective = finalCaptureDistance(profile, speed);
        if (horizontalDistance <= effective) {
            if (!data.contains(PILOT_EFFECTIVE_ARRIVE_SINCE)) {
                data.putLong(PILOT_EFFECTIVE_ARRIVE_SINCE, now);
                return false;
            }
            if (now - data.getLong(PILOT_EFFECTIVE_ARRIVE_SINCE) >= EFFECTIVE_ARRIVAL_TIMEOUT_TICKS) return true;
            return data.getInt(PILOT_NO_PROGRESS_TICKS) >= 12 && speed < 0.10D;
        }
        data.remove(PILOT_EFFECTIVE_ARRIVE_SINCE);
        return false;
    }

    private static boolean hasCapturedFinalTarget(CompoundTag data, Entity vehicle, Vec3 finalTarget, VehicleProfile profile) {
        if (!data.contains(PILOT_CAPTURED_TARGET_X) || !data.contains(PILOT_CAPTURED_TARGET_Z)) return false;
        double dx = finalTarget.x - data.getDouble(PILOT_CAPTURED_TARGET_X);
        double dz = finalTarget.z - data.getDouble(PILOT_CAPTURED_TARGET_Z);
        if (dx * dx + dz * dz > PILOT_TARGET_SOFT_CHANGE_DISTANCE * PILOT_TARGET_SOFT_CHANGE_DISTANCE) {
            data.remove(PILOT_CAPTURED_TARGET_X);
            data.remove(PILOT_CAPTURED_TARGET_Z);
            return false;
        }
        if (flatDistance(vehicle.position(), finalTarget) <= finalCaptureHoldDistance(profile)) return true;
        data.remove(PILOT_CAPTURED_TARGET_X);
        data.remove(PILOT_CAPTURED_TARGET_Z);
        return false;
    }

    private static double routePointReach(VehicleProfile profile) {
        return Mth.clamp(profile.width + 0.75D, 3.5D, 5.0D);
    }

    private static short reverseNavigationKeys(Entity vehicle, float desiredYaw, double horizontalDistance, double stopDistance, CompoundTag data, boolean alignRear) {
        float frontYawDelta = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());
        float rearYawDelta = Mth.wrapDegrees(desiredYaw - Mth.wrapDegrees(vehicle.getYRot() + 180.0F));
        float steeringDelta = alignRear ? rearYawDelta : frontYawDelta;
        float absSteeringDelta = Math.abs(steeringDelta);
        int lockedSteer = data.getInt(PILOT_REVERSE_NAV_STEER);
        if (absSteeringDelta <= STEER_DEAD_ZONE) {
            data.remove(PILOT_REVERSE_NAV_STEER);
            lockedSteer = 0;
        } else if (lockedSteer == 0 || absSteeringDelta > 28.0F) {
            lockedSteer = steeringDelta > 0.0F ? 1 : -1;
            data.putInt(PILOT_REVERSE_NAV_STEER, lockedSteer);
        }
        short keys = KEY_BACK;
        if (lockedSteer != 0) {
            if (alignRear) keys |= lockedSteer > 0 ? KEY_RIGHT : KEY_LEFT;
            else keys |= lockedSteer > 0 ? KEY_LEFT : KEY_RIGHT;
        }
        if (horizontalDistance < Math.max(2.25D, stopDistance + 0.75D)) keys |= KEY_BRAKE_OR_UP;
        return keys;
    }

    private static short reverseCruiseKeys(float yawDelta, float absYawDelta, double horizontalDistance, double stopDistance) {
        short keys = 0;
        if (absYawDelta > STEER_DEAD_ZONE) keys |= yawDelta > 0 ? KEY_RIGHT : KEY_LEFT;
        if (absYawDelta <= FORWARD_ARC) keys |= KEY_FORWARD;
        else if (absYawDelta >= TURN_ONLY_ARC) keys |= KEY_BRAKE_OR_UP;
        if (horizontalDistance < Math.max(2.25D, stopDistance + 0.75D)) keys |= KEY_BRAKE_OR_UP;
        return keys;
    }

    private static short turnAroundKeys(float yawDelta) {
        short keys = KEY_FORWARD;
        keys |= yawDelta > 0.0F ? KEY_RIGHT : KEY_LEFT;
        return keys;
    }

    private static final String PILOT_YIELD_UNTIL = "DominionSwordSuperbPilotYieldUntil";
    private static boolean shouldYieldToFleetReservation(Entity vehicle, Vec3 target, Vec3 finalTarget, VehicleProfile profile,
                                                         double horizontalDistance, boolean intermediateWaypoint, boolean wideTurnAvailable) {
        if (target == null || horizontalDistance <= Math.max(routePointReach(profile) + 1.0D, 4.0D)) return false;
        CompoundTag data = vehicle.getPersistentData();
        long now = vehicle.level().getGameTime();
        long yieldUntil = data.getLong(PILOT_YIELD_UNTIL);
        if (yieldUntil > now) return true;
        double lookahead = Math.min(horizontalDistance, Math.max(10.0D, profile.length * 2.0D));
        if (!hasForeignReservationOnPath(vehicle, vehicle.position(), target, profile, lookahead)) return false;
        if (!intermediateWaypoint && flatDistance(vehicle.position(), finalTarget) <= Math.max(AVOIDANCE_LOOKAHEAD, profile.length * 2.5D)) return false;
        if (wideTurnAvailable || lateralBypassAvailable(vehicle, target, profile, lookahead)) return false;
        double ownerSpeed = foreignReservationMaxOwnerSpeedOnPath(vehicle, vehicle.position(), target, profile, lookahead);
        if (ownerSpeed >= 0.12D || !lateralBypassAvailable(vehicle, target, profile, Math.max(lookahead, profile.length * 2.5D))) {
            data.putLong(PILOT_YIELD_UNTIL, now + 5L + Math.floorMod(vehicle.getId(), 6));
            return true;
        }
        return false;
    }

    private static boolean lateralBypassAvailable(Entity vehicle, Vec3 target, VehicleProfile profile, double lookahead) {
        Vec3 forward = target.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        if (forward.lengthSqr() < 1.0E-6D) return false;
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double side = Math.max(profile.width + 2.0D, profile.voxelRadius + 2.0D);
        double ahead = Math.min(Math.max(profile.length * 2.0D, 10.0D), Math.max(10.0D, lookahead));
        Vec3 origin = vehicle.position();
        for (double sign : new double[]{1.0D, -1.0D}) {
            Vec3 lane = origin.add(forward.scale(ahead)).add(right.scale(side * sign));
            Vec3 occupy = findSimpleOccupiablePosition(vehicle, lane, profile);
            if (occupy != null
                    && canTravelDirect(vehicle, origin, occupy, profile, Math.max(AVOIDANCE_LOOKAHEAD, origin.distanceTo(occupy) + 2.0D))
                    && foreignReservationPenalty(vehicle, occupy, profile) <= RESERVATION_PATH_PENALTY * 0.75D) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 navigationTarget(Mob mob, Entity vehicle, Vec3 finalTarget, VehicleProfile profile, double speed) {
        CompoundTag data = mob.getPersistentData();
        Vec3 position = vehicle.position();
        long now = vehicle.level().getGameTime();
        double finalDistance = flatDistance(position, finalTarget);
        double finalApproachRange = directFinalApproachRange(profile);
        if (finalDistance <= finalApproachRange
                && canTravelDirect(vehicle, position, finalTarget, profile, finalApproachRange)) {
            clearAvoidanceState(data);
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_FINAL);
            pathDebugThrottled(mob, vehicle, "DIRECT_FINAL_APPROACH", "final=%s dist=%.2f range=%.2f profile=%s", fmt(finalTarget), finalDistance, finalApproachRange, profileSummary(profile));
            return finalTarget;
        }

        Vec3 queued = queuedAvoidanceWaypoint(mob, vehicle, finalTarget, profile, speed);
        if (queued != null) return queued;

        if (hasValidAvoidance(data, finalTarget)) {
            Vec3 waypoint = new Vec3(data.getDouble(PILOT_AVOID_X), data.getDouble(PILOT_AVOID_Y), data.getDouble(PILOT_AVOID_Z));
            double reach = routePointReach(profile);
            if (position.distanceToSqr(waypoint) <= reach * reach) {
                clearAvoidanceWaypoint(data);
            } else if (data.getBoolean(PILOT_AVOID_EGRESS) && now <= data.getLong(PILOT_AVOID_UNTIL)) {
                pathDebugThrottled(mob, vehicle, "USE_EGRESS_WAYPOINT", "waypoint=%s final=%s distWp=%.2f until=%d now=%d", fmt(waypoint), fmt(finalTarget), flatDistance(position, waypoint), data.getLong(PILOT_AVOID_UNTIL), now);
                return waypoint;
            } else if (canTravelDirect(vehicle, position, waypoint, profile, AVOIDANCE_LOOKAHEAD)
                    ) {
                data.putLong(PILOT_AVOID_UNTIL, now + AVOIDANCE_LOCK_TICKS);
                pathDebugThrottled(mob, vehicle, "USE_LOCKED_WAYPOINT", "waypoint=%s final=%s distWp=%.2f", fmt(waypoint), fmt(finalTarget), flatDistance(position, waypoint));
                return waypoint;
            } else if (now <= data.getLong(PILOT_AVOID_UNTIL)) {
                clearAvoidanceWaypoint(data);
            } else {
                clearAvoidanceWaypoint(data);
            }
        }

        double dynamicLookahead = dynamicLookaheadDistance(speed, profile, false);
        Vec3 lookaheadTarget = clippedLookahead(position, finalTarget, dynamicLookahead);
        boolean lookaheadDirect = canTravelDirect(vehicle, position, lookaheadTarget, profile, Math.max(dynamicLookahead, flatDistance(position, lookaheadTarget)));
        boolean lookaheadReserved = lookaheadDirect && hasForeignReservationOnPath(vehicle, position, lookaheadTarget, profile, Math.max(dynamicLookahead, profile.length * 2.0D));
        boolean shouldRouteAroundReservation = lookaheadReserved && lateralBypassAvailable(vehicle, lookaheadTarget, profile, Math.max(dynamicLookahead, profile.length * 2.5D));
        if (lookaheadDirect && !shouldRouteAroundReservation) {
            clearAvoidanceState(data);
            WAYPOINT_CACHE.remove(vehicle.getUUID());
            boolean finalCarrot = lookaheadTarget.distanceToSqr(finalTarget) <= 1.0D;
            data.putString(PILOT_ROUTE_MODE, finalCarrot ? ROUTE_MODE_FINAL : ROUTE_MODE_DIRECT);
            pathDebugThrottled(mob, vehicle, "DIRECT_LOOKAHEAD", "lookahead=%s final=%s distLook=%.2f distFinal=%.2f dyn=%.2f finalCarrot=%s", fmt(lookaheadTarget), fmt(finalTarget), flatDistance(position, lookaheadTarget), finalDistance, dynamicLookahead, finalCarrot);
            return finalCarrot ? finalTarget : lookaheadTarget;
        } else if (shouldRouteAroundReservation) {
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_ROUTE);
            pathDebugThrottled(mob, vehicle, "DIRECT_RESERVED_REPATH", "lookahead=%s final=%s dyn=%.2f", fmt(lookaheadTarget), fmt(finalTarget), dynamicLookahead);
        }

        Vec3 cached = cachedWaypoint(vehicle, finalTarget);
        if (cached != null && position.distanceToSqr(cached) > 9.0D
                && cached.distanceToSqr(finalTarget) > 64.0D
                && canTravelDirect(vehicle, position, cached, profile, AVOIDANCE_LOOKAHEAD)) {
            pathDebugThrottled(mob, vehicle, "USE_CACHED_WAYPOINT", "cached=%s final=%s distCached=%.2f", fmt(cached), fmt(finalTarget), flatDistance(position, cached));
            return cached;
        }

        if (now < data.getLong(PILOT_AVOID_FAIL_UNTIL) || now < data.getLong(PILOT_AVOID_REPATH_AFTER)) {
            pathDebugThrottled(mob, vehicle, "REPATH_COOLDOWN_BRAKE", "lookahead=%s final=%s failUntil=%d repathAfter=%d now=%d", fmt(lookaheadTarget), fmt(finalTarget), data.getLong(PILOT_AVOID_FAIL_UNTIL), data.getLong(PILOT_AVOID_REPATH_AFTER), now);
            return null;
        }
        data.putLong(PILOT_AVOID_REPATH_AFTER, now + AVOIDANCE_REPATH_COOLDOWN_TICKS);
        data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_ROUTE);
        pathDebug(mob, vehicle, "REPATH_START", "pos=%s yaw=%.1f lookahead=%s final=%s profile=%s", fmt(position), vehicle.getYRot(), fmt(lookaheadTarget), fmt(finalTarget), profileSummary(profile));
        Vec3 routeTarget = avoidanceRouteTarget(position, finalTarget);
        double routeSearchRadius = avoidanceRouteSearchRadius(position, routeTarget);
        int routeIterations = avoidanceRouteIterations(routeSearchRadius);
        List<Vec3> route;
        try (LagTrace ignoredTrace = LagTrace.start("unit.repath", "vehicle=" + vehicle.getId() + " radius=" + String.format(Locale.ROOT, "%.1f", routeSearchRadius) + " iterations=" + routeIterations)) {
            route = findAvoidancePath(vehicle, position, routeTarget, profile, routeSearchRadius, routeIterations);
            LagTrace.mark("astar:size=" + route.size());
            if (route.size() > 2) route = simplifyAvoidanceRoute(vehicle, route, profile);
            LagTrace.mark("simplify:size=" + route.size());
        }
        int waypointIndex = firstUsefulWaypointIndex(route, position, profile);
        Vec3 waypoint = waypointIndex > 0 && waypointIndex < route.size() ? route.get(waypointIndex) : null;
        if (waypoint != null) {
            data.putDouble(PILOT_AVOID_TARGET_X, finalTarget.x);
            data.putDouble(PILOT_AVOID_TARGET_Z, finalTarget.z);
            data.putDouble(PILOT_AVOID_X, waypoint.x);
            data.putDouble(PILOT_AVOID_Y, waypoint.y);
            data.putDouble(PILOT_AVOID_Z, waypoint.z);
            data.putLong(PILOT_AVOID_UNTIL, now + AVOIDANCE_LOCK_TICKS);
            data.remove(PILOT_AVOID_EGRESS);
            data.remove(PILOT_AVOID_FAIL_UNTIL);
            cacheWaypoint(vehicle, finalTarget, waypoint);
            storeAvoidanceRoute(data, finalTarget, route, waypointIndex);
            refreshPlannedPath(mob, vehicle, route);
            pathDebug(mob, vehicle, "REPATH_CHOSEN_ROUTE", "waypoint=%s index=%d lookahead=%s routeTarget=%s final=%s distWp=%.2f search=%.1f iterations=%d route=%s", fmt(waypoint), waypointIndex, fmt(lookaheadTarget), fmt(routeTarget), fmt(finalTarget), flatDistance(position, waypoint), routeSearchRadius, routeIterations, fmtPath(route));
            return waypoint;
        }

        Vec3 egress = findEmergencyEgressWaypoint(vehicle, finalTarget, profile);
        if (egress != null) {
            data.putDouble(PILOT_AVOID_TARGET_X, finalTarget.x);
            data.putDouble(PILOT_AVOID_TARGET_Z, finalTarget.z);
            data.putDouble(PILOT_AVOID_X, egress.x);
            data.putDouble(PILOT_AVOID_Y, egress.y);
            data.putDouble(PILOT_AVOID_Z, egress.z);
            data.putLong(PILOT_AVOID_UNTIL, now + Math.max(AVOIDANCE_LOCK_TICKS, 30));
            data.putBoolean(PILOT_AVOID_EGRESS, true);
            data.remove(PILOT_AVOID_FAIL_UNTIL);
            cacheWaypoint(vehicle, finalTarget, egress);
            pathDebug(mob, vehicle, "REPATH_EGRESS", "egress=%s lookahead=%s final=%s dist=%.2f", fmt(egress), fmt(lookaheadTarget), fmt(finalTarget), flatDistance(position, egress));
            return egress;
        }

        clearAvoidanceWaypoint(data);
        data.putLong(PILOT_AVOID_FAIL_UNTIL, now + AVOIDANCE_FAIL_COOLDOWN_TICKS);
        pathDebug(mob, vehicle, "REPATH_FAILED_BRAKE", "lookahead=%s final=%s cooldown=%d", fmt(lookaheadTarget), fmt(finalTarget), AVOIDANCE_FAIL_COOLDOWN_TICKS);
        return null;
    }

    private static Vec3 findLateralAvoidanceWaypoint(Entity vehicle, Vec3 target, VehicleProfile profile) {
        Vec3 start = vehicle.position();
        Vec3 forward = target.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        if (forward.lengthSqr() < 1.0E-6D) return null;
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double baseSide = Math.max(profile.width * 0.75D + 2.0D, 4.0D);
        List<SafeCandidate> candidates = new ArrayList<>();
        for (int sideSign : new int[]{1, -1}) {
            for (double side = baseSide; side <= baseSide + 9.0D; side += 3.0D) {
                for (double ahead = 8.0D; ahead <= 24.0D; ahead += 8.0D) {
                    Vec3 raw = start.add(forward.scale(ahead)).add(right.scale(side * sideSign));
                    float yaw = yawTo(start, raw);
                    Vec3 candidate = findOccupiablePosition(vehicle, raw, yaw, profile);
                    if (candidate == null) continue;
                    double reservationPenalty = foreignReservationPenalty(vehicle, candidate, profile);
                    double score = candidate.distanceToSqr(target) + side * side * 0.18D + ahead * 0.4D + reservationPenalty * 4.0D;
                    candidates.add(new SafeCandidate(candidate, score));
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(SafeCandidate::score));
        int checkLimit = Math.min(5, candidates.size());
        for (int i = 0; i < checkLimit; i++) {
            Vec3 candidate = candidates.get(i).position();
            if (!canTravelDirect(vehicle, start, candidate, profile, AVOIDANCE_LOOKAHEAD)) continue;
            if (hasForeignReservationOnPath(vehicle, start, candidate, profile, AVOIDANCE_LOOKAHEAD) && i + 1 < checkLimit) continue;
            return candidate;
        }
        return candidates.get(0).position();
    }

    private static Vec3 findVisibilityBypassWaypoint(Entity vehicle, Vec3 lookaheadTarget, Vec3 finalTarget, VehicleProfile profile) {
        Vec3 start = vehicle.position();
        Vec3 toFinal = finalTarget.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        Vec3 toLookahead = lookaheadTarget.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        Vec3 reference = toFinal.lengthSqr() > 1.0E-6D ? toFinal.normalize() : toLookahead.lengthSqr() > 1.0E-6D ? toLookahead.normalize() : null;
        if (reference == null) return null;
        Vec3 right = new Vec3(-reference.z, 0.0D, reference.x);
        double baseRadius = Math.max(profile.voxelRadius + 2.0D, profile.width * 0.5D + 3.0D);
        double bestScore = Double.MAX_VALUE;
        Vec3 best = null;
        int tested = 0, occupyFail = 0, firstLegFail = 0, progressFail = 0, accepted = 0;
        double directDistance = Math.max(AVOIDANCE_LOOKAHEAD, Math.min(48.0D, Math.sqrt(toFinal.lengthSqr())));
        Vec3 progressProbe = start.add(reference.scale(directDistance));
        for (double radius = baseRadius; radius <= baseRadius + 30.0D; radius += 3.0D) {
            for (int angle = -150; angle <= 150; angle += 15) {
                if (Math.abs(angle) < 20) continue;
                double radians = Math.toRadians(angle);
                Vec3 dir = reference.scale(Math.cos(radians)).add(right.scale(Math.sin(radians)));
                if (dir.lengthSqr() < 1.0E-6D) continue;
                dir = dir.normalize();
                for (double aheadBias : new double[]{0.0D, 4.0D, 8.0D}) {
                    tested++;
                    Vec3 raw = start.add(dir.scale(radius)).add(reference.scale(aheadBias));
                    float yaw = yawTo(start, raw);
                    Vec3 candidate = findOccupiablePosition(vehicle, raw, yaw, profile);
                    if (candidate == null) { occupyFail++; continue; }
                    double firstLeg = Math.max(AVOIDANCE_LOOKAHEAD, start.distanceTo(candidate) + 2.0D);
                    if (!canTravelDirect(vehicle, start, candidate, profile, firstLeg)) { firstLegFail++; continue; }
                    Vec3 progressTarget = candidate.add(reference.scale(Math.max(10.0D, profile.length + 4.0D)));
                    Vec3 progressOccupy = findOccupiablePosition(vehicle, progressTarget, yawTo(candidate, progressTarget), profile);
                    if (progressOccupy == null || !canTravelDirect(vehicle, candidate, progressOccupy, profile, Math.max(AVOIDANCE_LOOKAHEAD, candidate.distanceTo(progressOccupy) + 2.0D))) {
                        progressFail++;
                        continue;
                    }
                    if (candidate.distanceToSqr(progressProbe) > start.distanceToSqr(progressProbe) + 16.0D) {
                        progressFail++;
                        continue;
                    }
                    accepted++;
                    double side = Math.abs(candidate.subtract(start).multiply(1.0D, 0.0D, 1.0D).dot(right));
                    double forward = candidate.subtract(start).multiply(1.0D, 0.0D, 1.0D).dot(reference);
                    double score = radius * 0.8D + side * 0.4D - forward * 0.25D + candidate.distanceToSqr(lookaheadTarget) * 0.02D
                            + foreignReservationPenalty(vehicle, candidate, profile) * 4.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        pathDebug(vehicle, "VISIBILITY_BYPASS_SCAN", "lookahead=%s final=%s tested=%d accepted=%d occupyFail=%d firstLegFail=%d progressFail=%d best=%s score=%.2f baseRadius=%.2f",
                fmt(lookaheadTarget), fmt(finalTarget), tested, accepted, occupyFail, firstLegFail, progressFail, fmt(best), bestScore, baseRadius);
        return best;
    }

    private static Vec3 clippedLookahead(Vec3 position, Vec3 finalTarget) {
        return clippedTarget(position, finalTarget, AVOIDANCE_SEARCH_RADIUS);
    }

    private static Vec3 clippedLookahead(Vec3 position, Vec3 finalTarget, double range) {
        return clippedTarget(position, finalTarget, Mth.clamp(range, DYNAMIC_LOOKAHEAD_MIN, AVOIDANCE_SEARCH_RADIUS * 2.0D));
    }

    private static Vec3 avoidanceRouteTarget(Vec3 position, Vec3 finalTarget) {
        double distance = flatDistance(position, finalTarget);
        double max = AVOIDANCE_SEARCH_RADIUS * 4.0D;
        return distance <= max ? finalTarget : clippedTarget(position, finalTarget, max);
    }

    private static double avoidanceRouteSearchRadius(Vec3 position, Vec3 routeTarget) {
        double distance = flatDistance(position, routeTarget);
        return Mth.clamp(distance + AVOIDANCE_STEP * 4.0D, AVOIDANCE_SEARCH_RADIUS * 2.0D, AVOIDANCE_SEARCH_RADIUS * 4.5D);
    }

    private static int avoidanceRouteIterations(double searchRadius) {
        double cells = searchRadius / AVOIDANCE_STEP;
        return Mth.clamp((int) Math.ceil(cells * cells * 0.75D), AVOIDANCE_MAX_ITERATIONS * 6, AVOIDANCE_MAX_ITERATIONS * 18);
    }

    private static Vec3 clippedTarget(Vec3 position, Vec3 finalTarget, double range) {
        Vec3 delta = finalTarget.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(delta.lengthSqr());
        if (distance <= range || distance < 1.0E-6D) return finalTarget;
        return position.add(delta.normalize().scale(range));
    }

    private static double directFinalApproachRange(VehicleProfile profile) {
        return Mth.clamp(Math.max(DIRECT_FINAL_APPROACH_MIN_RANGE, profile.length * 3.0D), DIRECT_FINAL_APPROACH_MIN_RANGE, DIRECT_FINAL_APPROACH_MAX_RANGE);
    }

    private static double dynamicLookaheadDistance(double speed, VehicleProfile profile, boolean finalApproach) {
        if (finalApproach) {
            return Mth.clamp(FINAL_APPROACH_LOOKAHEAD_MIN + Math.max(0.0D, speed) * 10.0D, FINAL_APPROACH_LOOKAHEAD_MIN, FINAL_APPROACH_LOOKAHEAD_MAX);
        }
        return Mth.clamp(DYNAMIC_LOOKAHEAD_MIN + Math.max(0.0D, speed) * 20.0D + profile.voxelRadius * 0.75D, DYNAMIC_LOOKAHEAD_MIN, DYNAMIC_LOOKAHEAD_MAX);
    }

    private static boolean startAsyncRoute(Mob mob, Entity vehicle, Vec3 safe, VehicleProfile profile, long generation) {
        if (ASYNC_ROUTES.containsKey(vehicle.getUUID())) return true;
        int radius = Math.min(28, (int) Math.ceil(AVOIDANCE_SEARCH_RADIUS / AVOIDANCE_STEP));
        Vec3 start = vehicle.position();
        int gx = Mth.clamp((int) Math.round((safe.x - start.x) / AVOIDANCE_STEP), -radius, radius);
        int gz = Mth.clamp((int) Math.round((safe.z - start.z) / AVOIDANCE_STEP), -radius, radius);
        ASYNC_ROUTES.put(vehicle.getUUID(), new AsyncRouteBuild(mob.getUUID(), start, safe, profile, generation, radius, gx, gz));
        return true;
    }

    private static void advanceAsyncRoute(Mob mob, Entity vehicle, Vec3 commandTarget, VehicleProfile profile) {
        AsyncRouteBuild build = ASYNC_ROUTES.get(vehicle.getUUID());
        CompoundTag data = mob.getPersistentData();
        if (build == null || !build.pilot.equals(mob.getUUID()) || build.generation != data.getLong(PILOT_ROUTE_GENERATION)) { data.remove(PILOT_ASYNC_ROUTE_PENDING); return; }
        if (build.future == null) {
            int budget = ASYNC_SNAPSHOT_CELLS_PER_TICK;
            while (budget-- > 0 && build.cursor < build.points.size()) {
                DominionAsyncGridPlanner.Point point = build.points.get(build.cursor++);
                Vec3 raw = build.start.add(point.x() * AVOIDANCE_STEP, 0.0D, point.z() * AVOIDANCE_STEP);
                Vec3 occupy = findSimpleOccupiablePosition(vehicle, raw, build.profile);
                if (occupy != null) build.cells.put(point.key(), new DominionAsyncGridPlanner.Cell(occupy.y, terrainCost(vehicle, occupy)));
            }
            if (build.cursor < build.points.size()) return;
            build.future = DominionAsyncGridPlanner.submit(new DominionAsyncGridPlanner.Snapshot(new DominionAsyncGridPlanner.Point(0, 0), new DominionAsyncGridPlanner.Point(build.goalX, build.goalZ), Map.copyOf(build.cells), AVOIDANCE_MAX_ITERATIONS, build.profile.maxStepUp, 2.0D));
            return;
        }
        if (!build.future.isDone()) return;
        DominionAsyncGridPlanner.Result result = build.future.getNow(null);
        ASYNC_ROUTES.remove(vehicle.getUUID());
        data.remove(PILOT_ASYNC_ROUTE_PENDING);
        if (result == null || !result.found()) { clearAvoidanceState(data); return; }
        List<Vec3> route = new ArrayList<>();
        for (DominionAsyncGridPlanner.Point point : result.points()) {
            DominionAsyncGridPlanner.Cell cell = build.cells.get(point.key());
            if (cell != null) route.add(new Vec3(build.start.x + point.x() * AVOIDANCE_STEP, cell.y(), build.start.z + point.z() * AVOIDANCE_STEP));
        }
        if (route.size() <= 1 || !validateAsyncRoute(vehicle, route, build.profile)) { clearAvoidanceState(data); return; }
        if (flatDistance(route.get(route.size() - 1), build.safe) > 1.0D) route.add(build.safe);
        storeAvoidanceRoute(data, build.safe, route, firstUsefulWaypointIndex(route, vehicle.position(), build.profile), build.generation);
        refreshPlannedPath(mob, vehicle, route);
        pathDebug(mob, vehicle, "ASYNC_ROUTE_APPLIED", "generation=%d cells=%d visited=%d points=%d", build.generation, build.cells.size(), result.visited(), route.size());
    }

    private static boolean validateAsyncRoute(Entity vehicle, List<Vec3> route, VehicleProfile profile) {
        for (int i = 1; i < route.size(); i++) if (!canSweepSimplePose(vehicle, route.get(i - 1), route.get(i), profile)) return false;
        return true;
    }

    private static void ensurePreparedRoute(Mob mob, Entity vehicle, Vec3 commandTarget, VehicleProfile profile) {
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(PILOT_ASYNC_ROUTE_PENDING)) {
            advanceAsyncRoute(mob, vehicle, commandTarget, profile);
            return;
        }
        if (hasActiveSafeTarget(data, commandTarget) && data.contains(PILOT_PATH_POINTS, Tag.TAG_LIST)) return;
        prepareRouteWithoutFleet(mob, vehicle, commandTarget, profile);
    }

    private static void prepareRouteWithoutFleet(Mob mob, Entity vehicle, Vec3 commandTarget, VehicleProfile profile) {
        if (PATH_POLICY_DIRECT.equals(pathPolicy(vehicle))) {
            CompoundTag data = mob.getPersistentData();
            long generation = data.getLong(PILOT_ROUTE_GENERATION) + 1L;
            Vec3 safeTarget = safeTargetNear(vehicle, commandTarget, profile);
            clearAvoidanceState(data);
            data.putLong(PILOT_ROUTE_GENERATION, generation);
            data.putDouble(PILOT_FINAL_TARGET_X, commandTarget.x);
            data.putDouble(PILOT_FINAL_TARGET_Z, commandTarget.z);
            data.putDouble(PILOT_SAFE_TARGET_X, safeTarget.x);
            data.putDouble(PILOT_SAFE_TARGET_Y, safeTarget.y);
            data.putDouble(PILOT_SAFE_TARGET_Z, safeTarget.z);
            List<Vec3> route = List.of(vehicle.position(), safeTarget);
            storeAvoidanceRoute(data, safeTarget, route, 1, generation);
            refreshPlannedPath(mob, vehicle, route);
            return;
        }
        new SuperbWarfareUnitAdapter().prepareMoveRoute(mob, commandTarget, profile.voxelRadius, IGNORED_VEHICLES.get());
    }

    private static boolean hasActiveSafeTarget(CompoundTag data, Vec3 commandTarget) {
        if (!data.contains(PILOT_FINAL_TARGET_X) || !data.contains(PILOT_FINAL_TARGET_Z)
                || !data.contains(PILOT_SAFE_TARGET_X) || !data.contains(PILOT_SAFE_TARGET_Y) || !data.contains(PILOT_SAFE_TARGET_Z)) return false;
        double dx = commandTarget.x - data.getDouble(PILOT_FINAL_TARGET_X);
        double dz = commandTarget.z - data.getDouble(PILOT_FINAL_TARGET_Z);
        return dx * dx + dz * dz <= PILOT_TARGET_SOFT_CHANGE_DISTANCE * PILOT_TARGET_SOFT_CHANGE_DISTANCE;
    }

    private static Vec3 activeSafeTarget(CompoundTag data, Vec3 fallback) {
        if (data.contains(PILOT_SAFE_TARGET_X) && data.contains(PILOT_SAFE_TARGET_Y) && data.contains(PILOT_SAFE_TARGET_Z)) {
            return new Vec3(data.getDouble(PILOT_SAFE_TARGET_X), data.getDouble(PILOT_SAFE_TARGET_Y), data.getDouble(PILOT_SAFE_TARGET_Z));
        }
        return fallback;
    }

    private static String pathPolicy(Entity vehicle) {
        String policy = vehicle == null ? "" : vehicle.getPersistentData().getString(PATH_POLICY_NBT);
        if (PATH_POLICY_ALWAYS.equals(policy)) return PATH_POLICY_ALWAYS;
        if (PATH_POLICY_DIRECT.equals(policy)) return PATH_POLICY_DIRECT;
        return PATH_POLICY_AUTO;
    }

    private static List<Vec3> appendedRoute(List<Vec3> route, Vec3 point) {
        List<Vec3> result = new ArrayList<>(route);
        result.add(point);
        return result;
    }

    private static boolean hasValidAvoidance(CompoundTag data, Vec3 finalTarget) {
        if (!data.contains(PILOT_AVOID_X) || !data.contains(PILOT_AVOID_Y) || !data.contains(PILOT_AVOID_Z)) return false;
        if (!data.contains(PILOT_AVOID_TARGET_X) || !data.contains(PILOT_AVOID_TARGET_Z)) return false;
        double dx = finalTarget.x - data.getDouble(PILOT_AVOID_TARGET_X);
        double dz = finalTarget.z - data.getDouble(PILOT_AVOID_TARGET_Z);
        return dx * dx + dz * dz <= 16.0D;
    }

    private static boolean hasValidAvoidanceRoute(CompoundTag data, Vec3 finalTarget, Vec3 position) {
        if (!data.contains(PILOT_PATH_POINTS, Tag.TAG_LIST)) return false;
        if (data.contains(PILOT_PATH_GENERATION) && data.getLong(PILOT_PATH_GENERATION) != data.getLong(PILOT_ROUTE_GENERATION)) return false;
        if (!data.contains(PILOT_PATH_TARGET_X) || !data.contains(PILOT_PATH_TARGET_Z)) return false;
        double dx = finalTarget.x - data.getDouble(PILOT_PATH_TARGET_X);
        double dz = finalTarget.z - data.getDouble(PILOT_PATH_TARGET_Z);
        if (dx * dx + dz * dz <= 16.0D) return true;
        Vec3 oldTarget = new Vec3(data.getDouble(PILOT_PATH_TARGET_X), position.y, data.getDouble(PILOT_PATH_TARGET_Z));
        return sameTargetCorridor(position, oldTarget, finalTarget);
    }

    private static Vec3 queuedAvoidanceWaypoint(Mob mob, Entity vehicle, Vec3 finalTarget, VehicleProfile profile, double speed) {
        CompoundTag data = mob.getPersistentData();
        if (!hasValidAvoidanceRoute(data, finalTarget, vehicle.position())) return null;
        ListTag points = data.getList(PILOT_PATH_POINTS, Tag.TAG_COMPOUND);
        int index = Math.max(1, data.getInt(PILOT_PATH_INDEX));
        Vec3 position = vehicle.position();
        Vec3 routeStart = points.isEmpty() ? null : readPathPoint(points.getCompound(0));
        if (routeStart == null || flatDistance(position, routeStart) > Math.max(AVOIDANCE_LOOKAHEAD, profile.length * 2.0D)) {
            clearAvoidanceRoute(data);
            pathDebug(mob, vehicle, "ROUTE_DROPPED_STALE_START", "routeStart=%s final=%s pos=%s", fmt(routeStart), fmt(finalTarget), fmt(position));
            return null;
        }
        while (index < points.size()) {
            Vec3 point = readPathPoint(points.getCompound(index));
            if (point == null) {
                clearAvoidanceRoute(data);
                return null;
            }
            Vec3 previousPoint = index > 0 ? readPathPoint(points.getCompound(index - 1)) : null;
            Vec3 nextPoint = index + 1 < points.size() ? readPathPoint(points.getCompound(index + 1)) : null;
            double reach = routePointReach(profile);
            if (position.distanceToSqr(point) <= reach * reach
                    || hasPassedRoutePoint(position, previousPoint, point)
                    || hasMissedAndShouldSkipRoutePoint(position, previousPoint, point, nextPoint)
                    || canConsumeAlignedWaypoint(vehicle, position, previousPoint, point, nextPoint, profile, speed)) {
                index++;
                data.putInt(PILOT_PATH_INDEX, index);
                pathDebugThrottled(mob, vehicle, "ROUTE_POINT_ADVANCE", "index=%d/%d point=%s final=%s pos=%s dist=%.2f", index - 1, points.size() - 1, fmt(point), fmt(finalTarget), fmt(position), flatDistance(position, point));
                continue;
            }
            data.putInt(PILOT_PATH_INDEX, index);
            Vec3 carrot = routeLookaheadPoint(points, index, position, finalTarget, profile, speed);
            data.putDouble(PILOT_AVOID_X, carrot.x);
            data.putDouble(PILOT_AVOID_Y, carrot.y);
            data.putDouble(PILOT_AVOID_Z, carrot.z);
            data.putLong(PILOT_AVOID_UNTIL, vehicle.level().getGameTime() + AVOIDANCE_LOCK_TICKS);
            data.putString(PILOT_ROUTE_MODE, ROUTE_MODE_ROUTE);
            pathDebugThrottled(mob, vehicle, "USE_ROUTE_CARROT", "index=%d/%d point=%s carrot=%s final=%s distPoint=%.2f distCarrot=%.2f lookahead=%.2f", index, points.size() - 1, fmt(point), fmt(carrot), fmt(finalTarget), flatDistance(position, point), flatDistance(position, carrot), dynamicLookaheadDistance(speed, profile, false));
            return carrot;
        }
        clearAvoidanceRoute(data);
        pathDebugThrottled(mob, vehicle, "ROUTE_COMPLETE_FINAL", "final=%s pos=%s dist=%.2f", fmt(finalTarget), fmt(position), flatDistance(position, finalTarget));
        return finalTarget;
    }

    private static Vec3 routeLookaheadPoint(ListTag points, int currentIndex, Vec3 position, Vec3 finalTarget, VehicleProfile profile, double speed) {
        if (points == null || points.isEmpty()) return finalTarget;
        double lookahead = dynamicLookaheadDistance(speed, profile, false);
        Vec3 previous = position;
        double walked = 0.0D;
        for (int i = Math.max(1, currentIndex); i < points.size(); i++) {
            Vec3 point = readPathPoint(points.getCompound(i));
            if (point == null) break;
            double segment = flatDistance(previous, point);
            walked += segment;
            if (walked >= lookahead) return point;
            previous = point;
        }
        return finalTarget;
    }

    private static boolean hasPassedRoutePoint(Vec3 position, Vec3 previousPoint, Vec3 point) {
        if (previousPoint == null || point == null) return false;
        Vec3 segment = point.subtract(previousPoint).multiply(1.0D, 0.0D, 1.0D);
        if (segment.lengthSqr() < 1.0E-6D) return false;
        Vec3 beyond = position.subtract(point).multiply(1.0D, 0.0D, 1.0D);
        return beyond.dot(segment) > 0.0D;
    }

    private static boolean hasMissedAndShouldSkipRoutePoint(Vec3 position, Vec3 previousPoint, Vec3 point, Vec3 nextPoint) {
        if (point == null || nextPoint == null) return false;
        Vec3 toNext = nextPoint.subtract(point).multiply(1.0D, 0.0D, 1.0D);
        if (toNext.lengthSqr() < 1.0E-6D) return false;
        double distPoint = flatDistance(position, point);
        double distNext = flatDistance(position, nextPoint);
        if (distNext + 1.0D < distPoint) return true;
        if (previousPoint == null) return false;
        Vec3 routeDir = nextPoint.subtract(previousPoint).multiply(1.0D, 0.0D, 1.0D);
        Vec3 fromPoint = position.subtract(point).multiply(1.0D, 0.0D, 1.0D);
        return routeDir.lengthSqr() > 1.0E-6D && fromPoint.dot(routeDir.normalize()) > 1.5D && distPoint > 2.5D;
    }

    private static boolean canConsumeAlignedWaypoint(Entity vehicle, Vec3 position, Vec3 previousPoint, Vec3 point, Vec3 nextPoint, VehicleProfile profile, double speed) {
        if (point == null || nextPoint == null) return false;
        Vec3 currentLeg = point.subtract(previousPoint == null ? position : previousPoint).multiply(1.0D, 0.0D, 1.0D);
        Vec3 nextLeg = nextPoint.subtract(point).multiply(1.0D, 0.0D, 1.0D);
        Vec3 toPoint = point.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        Vec3 toNext = nextPoint.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        if (currentLeg.lengthSqr() < 1.0E-6D || nextLeg.lengthSqr() < 1.0E-6D || toNext.lengthSqr() < 1.0E-6D) return false;
        double turnAngle = routeTurnAngleDegrees(previousPoint == null ? position : previousPoint, point, nextPoint);
        if (turnAngle > 28.0D) return false;
        double lookahead = dynamicLookaheadDistance(speed, profile, false);
        if (toPoint.lengthSqr() > lookahead * lookahead) return false;
        if (toPoint.lengthSqr() > 1.0E-6D && toPoint.normalize().dot(toNext.normalize()) < 0.82D) return false;
        double directCheck = Math.max(AVOIDANCE_LOOKAHEAD, Math.min(lookahead + profile.length, flatDistance(position, nextPoint) + 1.0D));
        return canTravelDirect(vehicle, position, nextPoint, profile, directCheck);
    }

    private static double sourceBasedBrakeDistance(double speed, double stopDistance) {
        double dryBrakeDecay = 0.97D * 0.6D; // VehicleEngineUtils wheel brakeOnly: no throttle decay * brake power decay.
        double effectiveSpeed = Math.max(speed, speed + speed * speed * 2.0D);
        double idealStop = effectiveSpeed / Math.max(0.05D, 1.0D - dryBrakeDecay);
        return stopDistance + 1.25D + idealStop * 2.75D;
    }

    private static double routeCurvatureSpeedLimit(CompoundTag data, Vec3 position, Vec3 currentTarget, double speed, VehicleProfile profile) {
        if (!data.contains(PILOT_PATH_POINTS, Tag.TAG_LIST)) return Double.POSITIVE_INFINITY;
        ListTag points = data.getList(PILOT_PATH_POINTS, Tag.TAG_COMPOUND);
        if (points.size() < 3) return Double.POSITIVE_INFINITY;
        int index = Math.max(1, data.getInt(PILOT_PATH_INDEX));
        if (index >= points.size()) return Double.POSITIVE_INFINITY;
        int lookaheadPoints = Mth.clamp(2 + (int) Math.floor(Math.max(0.0D, speed) * 16.0D), 2, 5);
        double maxScanDistance = dynamicLookaheadDistance(speed, profile, false) + Math.max(6.0D, profile.length);
        double maxAngle = 0.0D;
        Vec3 a = position;
        Vec3 b = readPathPoint(points.getCompound(index));
        double walked = b == null ? 0.0D : flatDistance(position, b);
        for (int i = index + 1; i < points.size() && i <= index + lookaheadPoints; i++) {
            Vec3 c = readPathPoint(points.getCompound(i));
            if (a == null || b == null || c == null) {
                a = b;
                b = c;
                continue;
            }
            walked += flatDistance(b, c);
            if (walked > maxScanDistance) break;
            double angle = routeTurnAngleDegrees(a, b, c);
            maxAngle = Math.max(maxAngle, angle);
            a = b;
            b = c;
        }
        double turnRadius = profile.forwardStep / Math.max(0.01D, Math.toRadians(profile.yawStep));
        boolean wheeled = turnRadius > 3.0D;
        if (maxAngle <= 15.0D) return Double.POSITIVE_INFINITY;
        if (wheeled) {
            if (maxAngle > 60.0D) return 0.12D;
            if (maxAngle > 35.0D) return 0.20D;
            if (maxAngle > 20.0D) return 0.30D;
        } else {
            if (maxAngle > 75.0D) return 0.18D;
            if (maxAngle > 45.0D) return 0.26D;
        }
        return 0.34D;
    }

    private static double routeTurnAngleDegrees(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 ab = b.subtract(a).multiply(1.0D, 0.0D, 1.0D);
        Vec3 bc = c.subtract(b).multiply(1.0D, 0.0D, 1.0D);
        if (ab.lengthSqr() < 1.0E-6D || bc.lengthSqr() < 1.0E-6D) return 0.0D;
        double dot = Mth.clamp(ab.normalize().dot(bc.normalize()), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }

    private static int firstUsefulWaypointIndex(List<Vec3> route, Vec3 position, VehicleProfile profile) {
        if (route.size() <= 1) return -1;
        double reach = routePointReach(profile);
        double reachSqr = reach * reach;
        for (int i = 1; i < route.size(); i++) {
            if (position.distanceToSqr(route.get(i)) > reachSqr) return i;
        }
        return route.size() - 1;
    }

    private static void storeAvoidanceRoute(CompoundTag data, Vec3 finalTarget, List<Vec3> route, int startIndex) {
        storeAvoidanceRoute(data, finalTarget, route, startIndex, data.getLong(PILOT_ROUTE_GENERATION));
    }

    private static void storeAvoidanceRoute(CompoundTag data, Vec3 finalTarget, List<Vec3> route, int startIndex, long generation) {
        ListTag points = new ListTag();
        for (Vec3 point : route) {
            CompoundTag item = new CompoundTag();
            item.putDouble("x", point.x);
            item.putDouble("y", point.y);
            item.putDouble("z", point.z);
            points.add(item);
        }
        data.put(PILOT_PATH_POINTS, points);
        data.putDouble(PILOT_PATH_TARGET_X, finalTarget.x);
        data.putDouble(PILOT_PATH_TARGET_Z, finalTarget.z);
        data.putInt(PILOT_PATH_INDEX, Mth.clamp(startIndex, 1, Math.max(1, route.size() - 1)));
        data.putLong(PILOT_PATH_GENERATION, generation);
    }

    private static void refreshPlannedPath(Mob mob, Entity vehicle, List<Vec3> route) {
        if (mob == null || vehicle == null || route == null || route.size() < 2 || !(vehicle.level() instanceof ServerLevel level)) return;
        UUID controller = DominionControlApi.controller(vehicle);
        if (controller == null) controller = DominionControlApi.controller(mob);
        if (controller == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(controller);
        if (player == null) return;
        DominionControlApi.refreshPlannedVehiclePath(player, vehicle, route);
    }

    private static Vec3 readPathPoint(CompoundTag item) {
        if (!item.contains("x") || !item.contains("y") || !item.contains("z")) return null;
        return new Vec3(item.getDouble("x"), item.getDouble("y"), item.getDouble("z"));
    }

    private static Vec3 cachedWaypoint(Entity vehicle, Vec3 finalTarget) {
        CachedWaypoint cached = WAYPOINT_CACHE.get(vehicle.getUUID());
        long now = vehicle.level().getGameTime();
        if (cached == null || cached.expiresAt() < now || cached.target().distanceToSqr(finalTarget) > 16.0D) {
            WAYPOINT_CACHE.remove(vehicle.getUUID());
            return null;
        }
        return cached.waypoint();
    }

    private static void cacheWaypoint(Entity vehicle, Vec3 finalTarget, Vec3 waypoint) {
        WAYPOINT_CACHE.put(vehicle.getUUID(), new CachedWaypoint(finalTarget, waypoint, vehicle.level().getGameTime() + WAYPOINT_CACHE_TICKS));
    }

    private static void clearAvoidanceState(CompoundTag data) {
        clearAvoidanceWaypoint(data);
        clearAvoidanceRoute(data);
        data.remove(PILOT_AVOID_FAIL_UNTIL);
        data.remove(PILOT_AVOID_REPATH_AFTER);
    }

    private static void clearAvoidanceWaypoint(CompoundTag data) {
        data.remove(PILOT_AVOID_TARGET_X);
        data.remove(PILOT_AVOID_TARGET_Z);
        data.remove(PILOT_AVOID_X);
        data.remove(PILOT_AVOID_Y);
        data.remove(PILOT_AVOID_Z);
        data.remove(PILOT_AVOID_UNTIL);
        data.remove(PILOT_AVOID_EGRESS);
    }

    private static void clearAvoidanceRoute(CompoundTag data) {
        data.remove(PILOT_PATH_TARGET_X);
        data.remove(PILOT_PATH_TARGET_Z);
        data.remove(PILOT_PATH_INDEX);
        data.remove(PILOT_PATH_POINTS);
        data.remove(PILOT_PATH_GENERATION);
    }

    private static boolean canTravelDirect(Entity vehicle, Vec3 from, Vec3 to, VehicleProfile profile, double maxDistance) {
        Vec3 flat = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(flat.lengthSqr());
        if (distance < 1.0E-6D) return true;
        double checked = Math.min(distance, maxDistance);
        Vec3 dir = flat.normalize();
        double step = 1.0D;
        Vec3 previous = findSimpleOccupiablePosition(vehicle, from, profile);
        if (previous == null) previous = from;
        for (double d = step; d <= checked + 0.01D; d += step) {
            Vec3 sample = from.add(dir.scale(Math.min(d, checked)));
            Vec3 occupy = findSimpleOccupiablePosition(vehicle, sample, profile);
            if (occupy == null) return false;
            if (!canSweepSimplePose(vehicle, previous, occupy, profile)) return false;
            previous = occupy;
        }
        return true;
    }

    private static Vec3 findAvoidanceWaypoint(Entity vehicle, Vec3 target, VehicleProfile profile) {
        List<Vec3> route = findAvoidanceRoute(vehicle, target, profile);
        if (route.size() <= 1) return null;
        return route.get(1);
    }

    private static List<Vec3> findAvoidanceRoute(Entity vehicle, Vec3 target, VehicleProfile profile) {
        List<Vec3> raw = findAvoidancePath(vehicle, vehicle.position(), target, profile, AVOIDANCE_SEARCH_RADIUS * 2.0D, AVOIDANCE_MAX_ITERATIONS * 6);
        if (raw.size() <= 2) return raw;
        return simplifyAvoidanceRoute(vehicle, raw, profile);
    }

    private static List<Vec3> simplifyAvoidanceRoute(Entity vehicle, List<Vec3> raw, VehicleProfile profile) {
        if (raw.size() <= 2) return raw;
        List<Vec3> simplified = new ArrayList<>();
        int index = 0;
        simplified.add(raw.get(0));
        while (index < raw.size() - 1) {
            int best = index + 1;
            Vec3 from = raw.get(index);
            for (int j = index + 2; j < raw.size(); j++) {
                Vec3 to = raw.get(j);
                double max = Math.max(AVOIDANCE_LOOKAHEAD, Math.min(AVOIDANCE_SEARCH_RADIUS * 2.0D, flatDistance(from, to) + 1.0D));
                if (!canTravelDirect(vehicle, from, to, profile, max)) break;
                best = j;
            }
            simplified.add(raw.get(best));
            index = best;
        }
        return simplified;
    }

    private static List<Vec3> findAvoidancePath(Entity vehicle, Vec3 routeStart, Vec3 target, VehicleProfile profile, double searchRadius, int maxIterations) {
        try (LagTrace ignoredTrace = LagTrace.start("unit.astar", "vehicle=" + vehicle.getId() + " radius=" + String.format(Locale.ROOT, "%.1f", searchRadius) + " maxIter=" + maxIterations)) {
        Vec3 startPos = findSimpleOccupiablePosition(vehicle, routeStart, profile);
        if (startPos == null) startPos = routeStart;

        int targetX = gridIndex(target.x - startPos.x);
        int targetZ = gridIndex(target.z - startPos.z);
        AvoidNode end = new AvoidNode(targetX, targetZ, 0);
        PriorityQueue<AvoidItem> open = new PriorityQueue<>(Comparator.comparingDouble(AvoidItem::f));
        Set<AvoidNode> closed = new HashSet<>();
        Map<AvoidNode, AvoidNode> cameFrom = new HashMap<>();
        Map<AvoidNode, Double> gScore = new HashMap<>();
        Map<AvoidNode, Vec3> occupancyCache = new HashMap<>();
        Map<BlockPos, Double> terrainCache = new HashMap<>();
        AvoidNode startNode = new AvoidNode(0, 0, yawBin(vehicle.getYRot()));
        gScore.put(startNode, 0.0D);
        open.add(new AvoidItem(startNode, 0.0D, heuristic(startNode, end)));

        int iterations = 0;
        AvoidNode best = startNode;
        double bestH = heuristic(startNode, end);
        boolean reached = false;
        int occupyFail = 0, sweepFail = 0, radiusSkip = 0, closedSkip = 0, pushed = 1;
        while (!open.isEmpty() && iterations++ < maxIterations) {
            AvoidItem item = open.poll();
            AvoidNode current = item.node();
            if (!closed.add(current)) continue;
            double h = heuristic(current, end);
            if (h < bestH) {
                bestH = h;
                best = current;
            }
            if (h <= 1.0D || worldPos(startPos, current).distanceToSqr(target) <= 16.0D) {
                best = current;
                reached = true;
                break;
            }

            Vec3 currentWorld = worldPos(startPos, current);
            Vec3 currentOccupy = current.equals(startNode) ? startPos : cachedSimpleOccupy(vehicle, currentWorld, current, profile, occupancyCache);
            if (currentOccupy == null) continue;
            for (AvoidStep step : neighbors(current, profile)) {
                AvoidNode neighbor = step.node();
                if (closed.contains(neighbor)) { closedSkip++; continue; }
                Vec3 world = worldPos(startPos, neighbor);
                if (world.distanceToSqr(startPos) > searchRadius * searchRadius) { radiusSkip++; continue; }
                Vec3 occupy = cachedSimpleOccupy(vehicle, world, neighbor, profile, occupancyCache);
                if (occupy == null) { occupyFail++; continue; }
                if (!canSweepSimplePose(vehicle, currentOccupy, occupy, profile)) { sweepFail++; continue; }
                double terrain = cachedTerrainCost(vehicle, occupy, terrainCache);
                double reservationPenalty = foreignReservationPenalty(vehicle, occupy, profile);
                double moveCost = step.cost();
                double directionPenalty = avoidanceDirectionPenalty(startPos, currentWorld, occupy, target, current.equals(startNode));
                double tentative = gScore.getOrDefault(current, Double.MAX_VALUE) + moveCost * terrain + reservationPenalty + directionPenalty;
                if (tentative < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    cameFrom.put(neighbor, current);
                    gScore.put(neighbor, tentative);
                    open.add(new AvoidItem(neighbor, tentative, tentative + heuristic(neighbor, end) * 1.15D));
                    pushed++;
                }
            }
        }

        if (!reached) {
            pathDebug(vehicle, "ASTAR_FAILED", "start=%s target=%s startNode=%s end=%s iterations=%d closed=%d pushed=%d best=%s bestH=%.2f openLeft=%d occupyFail=%d sweepFail=%d radiusSkip=%d closedSkip=%d searchRadius=%.1f maxIter=%d",
                    fmt(startPos), fmt(target), startNode, end, iterations, closed.size(), pushed, best, bestH, open.size(), occupyFail, sweepFail, radiusSkip, closedSkip, searchRadius, maxIterations);
            LagTrace.mark("astar_failed:closed=" + closed.size() + ":pushed=" + pushed);
            return List.of();
        }
        List<AvoidNode> path = reconstructAvoidance(cameFrom, best);
        List<Vec3> result = new ArrayList<>(path.size());
        result.add(startPos);
        for (AvoidNode node : path) {
            if (node.equals(startNode)) continue;
            Vec3 candidate = cachedSimpleOccupy(vehicle, worldPos(startPos, node), node, profile, occupancyCache);
            if (candidate != null) result.add(candidate);
        }
        pathDebug(vehicle, "ASTAR_ROUTE", "start=%s target=%s iterations=%d closed=%d nodes=%s points=%s", fmt(startPos), fmt(target), iterations, closed.size(), path, fmtPath(result));
        LagTrace.mark("astar_route:closed=" + closed.size() + ":points=" + result.size());
        return result;
        }
    }

    private static double avoidanceDirectionPenalty(Vec3 start, Vec3 current, Vec3 next, Vec3 target, boolean firstStep) {
        Vec3 toTarget = target.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        Vec3 step = next.subtract(current).multiply(1.0D, 0.0D, 1.0D);
        if (toTarget.lengthSqr() < 1.0E-6D || step.lengthSqr() < 1.0E-6D) return 0.0D;
        double dot = step.normalize().dot(toTarget.normalize());
        if (dot >= 0.72D) return 0.0D;
        double penalty = (0.72D - dot) * 1.15D;
        if (dot < 0.15D) penalty += 1.25D;
        if (dot < -0.15D) penalty += 2.75D;
        return firstStep ? penalty * 2.0D : penalty;
    }

    private static Vec3 findEmergencyEgressWaypoint(Entity vehicle, Vec3 finalTarget, VehicleProfile profile) {
        try (LagTrace ignoredTrace = LagTrace.start("unit.egress", "vehicle=" + vehicle.getId())) {
        Vec3 start = vehicle.position();
        Vec3 toTarget = finalTarget.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        Vec3 forward = toTarget.lengthSqr() > 1.0E-6D ? toTarget.normalize() : Vec3.directionFromRotation(0.0F, vehicle.getYRot()).multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double bestScore = Double.MAX_VALUE;
        Vec3 best = null;
        int tested = 0;
        int occupyFail = 0;
        double base = Math.max(AVOIDANCE_STEP, Math.min(6.0D, profile.width + 1.5D));
        for (double radius = base; radius <= Math.max(18.0D, profile.length * 2.5D); radius += AVOIDANCE_STEP) {
                for (int angle = -150; angle <= 180; angle += 30) {
                tested++;
                double radians = Math.toRadians(angle);
                Vec3 dir = forward.scale(Math.cos(radians)).add(right.scale(Math.sin(radians)));
                if (dir.lengthSqr() < 1.0E-6D) continue;
                dir = dir.normalize();
                Vec3 raw = start.add(dir.scale(radius));
                Vec3 occupy = findSimpleOccupiablePosition(vehicle, raw, profile);
                if (occupy == null) {
                    occupyFail++;
                    continue;
                }
                double progress = occupy.subtract(start).multiply(1.0D, 0.0D, 1.0D).normalize().dot(forward);
                double side = Math.abs(occupy.subtract(start).multiply(1.0D, 0.0D, 1.0D).dot(right));
                double reservation = foreignReservationPenalty(vehicle, occupy, profile);
                double score = occupy.distanceToSqr(finalTarget) * 0.03D
                        + radius * 0.35D
                        + side * 0.08D
                        - progress * 8.0D
                        + reservation * 4.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = occupy;
                }
            }
            if (best != null && best.subtract(start).multiply(1.0D, 0.0D, 1.0D).dot(forward) > 0.5D) break;
        }
        pathDebug(vehicle, "EGRESS_SCAN", "final=%s tested=%d occupyFail=%d best=%s score=%.2f", fmt(finalTarget), tested, occupyFail, fmt(best), bestScore);
        LagTrace.mark("egress_done:tested=" + tested + ":fail=" + occupyFail);
        return best;
        }
    }

    private static List<Vec3> findFlowFieldRoute(Entity vehicle, Vec3 routeStart, Vec3 target, VehicleProfile profile) {
        ActiveFlowField field = buildFlowField(vehicle, target, profile);
        if (field == null) return List.of();
        return field.routeFrom(vehicle, routeStart, profile);
    }

    private static ActiveFlowField buildFlowField(Entity vehicle, Vec3 target, VehicleProfile profile) {
        Vec3 targetPos = findSimpleOccupiablePosition(vehicle, target, profile);
        if (targetPos == null) targetPos = target;
        int limit = Mth.ceil(FLOW_FIELD_SEARCH_RADIUS / AVOIDANCE_STEP);
        AvoidNode endNode = new AvoidNode(0, 0, 0);
        PriorityQueue<FlowItem> open = new PriorityQueue<>(Comparator.comparingDouble(FlowItem::cost));
        Map<AvoidNode, Double> cost = new HashMap<>();
        Map<AvoidNode, Vec3> occupancyCache = new HashMap<>();
        Map<BlockPos, Double> terrainCache = new HashMap<>();
        cost.put(endNode, 0.0D);
        open.add(new FlowItem(endNode, 0.0D));

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < FLOW_FIELD_MAX_ITERATIONS) {
            FlowItem item = open.poll();
            AvoidNode current = item.node();
            double currentCost = cost.getOrDefault(current, Double.MAX_VALUE);
            if (item.cost() > currentCost + 1.0E-6D) continue;
            Vec3 currentOccupy = cachedSimpleOccupy(vehicle, worldPos(targetPos, current), current, profile, occupancyCache);
            if (currentOccupy == null) continue;
            for (AvoidStep step : neighbors(current, profile)) {
                AvoidNode next = step.node();
                if (!withinFlowBounds(next, limit)) continue;
                Vec3 nextWorld = worldPos(targetPos, next);
                Vec3 nextOccupy = cachedSimpleOccupy(vehicle, nextWorld, next, profile, occupancyCache);
                if (nextOccupy == null || !canSweepSimplePose(vehicle, currentOccupy, nextOccupy, profile)) continue;
                double terrain = cachedTerrainCost(vehicle, nextOccupy, terrainCache);
                double reservation = foreignReservationPenalty(vehicle, nextOccupy, profile);
                double nextCost = currentCost + step.cost() * terrain + reservation * RESERVATION_PATH_PENALTY;
                if (nextCost < cost.getOrDefault(next, Double.MAX_VALUE)) {
                    cost.put(next, nextCost);
                    open.add(new FlowItem(next, nextCost));
                }
            }
        }
        return cost.isEmpty() ? null : new ActiveFlowField(vehicle.level().dimension(), targetPos, limit, cost, 0L);
    }

    private static List<Vec3> routeFromFlowField(Entity vehicle, Vec3 routeStart, VehicleProfile profile, ActiveFlowField field) {
        Vec3 startPos = findSimpleOccupiablePosition(vehicle, routeStart, profile);
        if (startPos == null) startPos = routeStart;
        Vec3 targetPos = field.target();
        AvoidNode startNode = new AvoidNode(gridIndex(startPos.x - targetPos.x), gridIndex(startPos.z - targetPos.z), 0);
        AvoidNode endNode = new AvoidNode(0, 0, 0);
        Map<AvoidNode, Vec3> occupancyCache = new HashMap<>();
        if (!field.cost().containsKey(startNode)) return List.of();

        List<Vec3> route = new ArrayList<>();
        AvoidNode current = startNode;
        route.add(startPos);
        Set<AvoidNode> seen = new HashSet<>();
        for (int i = 0; i < FLOW_FIELD_MAX_ITERATIONS && !current.equals(endNode); i++) {
            if (!seen.add(current)) break;
            AvoidNode next = bestFlowNeighbor(current, field.cost(), field.limit());
            if (next == null || next.equals(current)) break;
            Vec3 world = cachedSimpleOccupy(vehicle, worldPos(targetPos, next), next, profile, occupancyCache);
            if (world == null) break;
            route.add(world);
            current = next;
        }
        if (!current.equals(endNode)) return List.of();
        route.add(targetPos);
        return route;
    }

    private static Vec3 flowFieldTarget(Mob mob, Entity vehicle, Vec3 finalTarget, VehicleProfile profile) {
        ActiveFlowField field = ACTIVE_FLOW_FIELDS.get(mob.getUUID());
        long now = vehicle.level().getGameTime();
        if (field == null || field.expiresAt() < now || !field.level().equals(vehicle.level().dimension()) || field.target().distanceToSqr(finalTarget) > 4096.0D) {
            ACTIVE_FLOW_FIELDS.remove(mob.getUUID());
            return null;
        }
        Vec3 start = vehicle.position();
        if (flatDistance(start, finalTarget) <= FLOW_FIELD_LOOKAHEAD_DISTANCE
                && canTravelDirect(vehicle, start, finalTarget, profile, FLOW_FIELD_LOOKAHEAD_DISTANCE)) {
            VehicleGridManager.rememberFutureFootprint(vehicle, finalTarget, profile.voxelRadius, profile.length, RESERVATION_TTL_TICKS);
            return finalTarget;
        }
        AvoidNode current = new AvoidNode(gridIndex(start.x - field.target().x), gridIndex(start.z - field.target().z), 0);
        List<Vec3> projected = new ArrayList<>();
        Vec3 lastOccupy = null;
        for (int i = 0; i < FLOW_FIELD_LOOKAHEAD_STEPS; i++) {
            AvoidNode next = bestFlowNeighbor(current, field.cost(), field.limit());
            if (next == null) break;
            Vec3 world = worldPos(field.target(), next);
            Vec3 occupy = findSimpleOccupiablePosition(vehicle, world, profile);
            if (occupy == null) break;
            projected.add(occupy);
            lastOccupy = occupy;
            current = next;
            if (flatDistance(start, occupy) >= FLOW_FIELD_LOOKAHEAD_DISTANCE) break;
            if (flatDistance(occupy, finalTarget) <= AVOIDANCE_STEP + 0.5D) break;
        }
        if (lastOccupy == null) return null;
        VehicleGridManager.rememberFutureFootprints(vehicle, projected, profile.voxelRadius, profile.length, RESERVATION_TTL_TICKS);
        return lastOccupy;
    }

    private static boolean withinFlowBounds(AvoidNode node, int limit) {
        return Math.abs(node.x()) <= limit && Math.abs(node.z()) <= limit;
    }

    private static AvoidNode bestFlowNeighbor(AvoidNode current, Map<AvoidNode, Double> cost, int limit) {
        AvoidNode best = current;
        double bestCost = cost.getOrDefault(current, Double.MAX_VALUE);
        for (AvoidStep step : neighbors(current, null)) {
            AvoidNode next = step.node();
            if (!withinFlowBounds(next, limit)) continue;
            double c = cost.getOrDefault(next, Double.MAX_VALUE);
            if (c < bestCost - 1.0E-6D) {
                bestCost = c;
                best = next;
            }
        }
        return best.equals(current) ? null : best;
    }

    private static Vec3 fleetClippedTarget(Vec3 start, Vec3 target) {
        Vec3 delta = target.subtract(start).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(delta.lengthSqr());
        double range = AVOIDANCE_SEARCH_RADIUS * 2.0D;
        if (distance <= range || distance < 1.0E-6D) return target;
        return start.add(delta.normalize().scale(range));
    }

    private static Vec3 cachedSimpleOccupy(Entity vehicle, Vec3 world, AvoidNode node, VehicleProfile profile, Map<AvoidNode, Vec3> cache) {
        if (cache.containsKey(node)) return cache.get(node);
        Vec3 result = findSimpleOccupiablePosition(vehicle, world, profile);
        cache.put(node, result);
        return result;
    }

    private static Vec3 cachedOccupiable(Entity vehicle, Vec3 world, AvoidNode node, VehicleProfile profile, Map<AvoidNode, Vec3> cache) {
        if (cache.containsKey(node)) return cache.get(node);
        Vec3 result = findOccupiablePosition(vehicle, world, yawFromBin(node.yaw()), profile);
        cache.put(node, result);
        return result;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        if (delta.lengthSqr() < 1.0E-6D) return 0.0F;
        return (float) Mth.wrapDegrees(-(Mth.atan2(delta.x, delta.z) * Mth.RAD_TO_DEG));
    }

    private static Vec3 findOccupiablePosition(Entity vehicle, Vec3 around, float yaw, VehicleProfile profile) {
        double baseY = around.y;
        double maxUp = Math.max(0.05D, profile.maxStepUp + 0.05D);
        for (double dy = maxUp; dy >= -2.5D; dy -= 0.25D) {
            Vec3 candidate = new Vec3(around.x, baseY + dy, around.z);
            if (canOccupyVehicleSpace(vehicle, candidate, yaw, profile)) return candidate;
        }
        return null;
    }

    private static Vec3 findSimpleOccupiablePosition(Entity vehicle, Vec3 around, VehicleProfile profile) {
        double baseY = around.y;
        for (double dy : profile.simpleStepCandidates) {
            Vec3 candidate = new Vec3(around.x, baseY + dy, around.z);
            if (canOccupySimpleVehicleSpace(vehicle, candidate, profile)) return candidate;
        }
        return null;
    }

    private static Vec3 safeTargetNear(Entity vehicle, Vec3 finalTarget, VehicleProfile profile) {
        Vec3 direct = findSimpleOccupiablePosition(vehicle, finalTarget, profile);
        if (direct != null) return direct;
        Vec3 origin = vehicle.position();
        Vec3 toTarget = finalTarget.subtract(origin).multiply(1.0D, 0.0D, 1.0D);
        Vec3 forward = toTarget.lengthSqr() > 1.0E-6D ? toTarget.normalize() : Vec3.directionFromRotation(0.0F, vehicle.getYRot()).multiply(1.0D, 0.0D, 1.0D);
        if (forward.lengthSqr() < 1.0E-6D) forward = new Vec3(0.0D, 0.0D, 1.0D);
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        List<SafeCandidate> candidates = new ArrayList<>();
        double maxRadius = Math.max(18.0D, profile.length * 2.0D + 6.0D);
        for (double radius = Math.max(1.0D, profile.voxelRadius); radius <= maxRadius; radius += 1.5D) {
            for (int angle = 0; angle < 360; angle += 18) {
                double radians = Math.toRadians(angle);
                Vec3 raw = finalTarget.add(forward.scale(Math.cos(radians) * radius)).add(right.scale(Math.sin(radians) * radius));
                Vec3 occupy = findSimpleOccupiablePosition(vehicle, raw, profile);
                if (occupy == null) continue;
                double distance = flatDistance(occupy, finalTarget);
                double score = distance * distance + foreignReservationPenalty(vehicle, occupy, profile) * 2.0D;
                candidates.add(new SafeCandidate(occupy, score));
            }
        }
        if (!candidates.isEmpty()) {
            candidates.sort(Comparator.comparingDouble(SafeCandidate::score));
            SafeCandidate best = candidates.get(0);
            int checkLimit = Math.min(5, candidates.size());
            for (int i = 0; i < checkLimit; i++) {
                SafeCandidate candidate = candidates.get(i);
                double travelDistance = flatDistance(origin, candidate.position());
                if (canTravelDirect(vehicle, origin, candidate.position(), profile, Math.max(AVOIDANCE_LOOKAHEAD, travelDistance + profile.length))) {
                    double score = candidate.score() - 16.0D;
                    if (score < best.score()) best = new SafeCandidate(candidate.position(), score);
                    break;
                }
            }
            pathDebug(vehicle, "SAFE_TARGET_ADJUSTED", "final=%s safe=%s score=%.2f candidates=%d profile=%s", fmt(finalTarget), fmt(best.position()), best.score(), candidates.size(), profileSummary(profile));
            return best.position();
        }
        pathDebug(vehicle, "SAFE_TARGET_FALLBACK_RAW", "final=%s profile=%s", fmt(finalTarget), profileSummary(profile));
        return finalTarget;
    }

    private static int gridIndex(double value) {
        return Mth.floor(value / AVOIDANCE_STEP);
    }

    private static Vec3 worldPos(Vec3 origin, AvoidNode node) {
        return new Vec3(origin.x + node.x() * AVOIDANCE_STEP, origin.y, origin.z + node.z() * AVOIDANCE_STEP);
    }

    private static List<AvoidStep> neighbors(AvoidNode node, VehicleProfile profile) {
        List<AvoidStep> result = new ArrayList<>(8);
        result.add(new AvoidStep(new AvoidNode(node.x() + 1, node.z(), 0), 1.0D));
        result.add(new AvoidStep(new AvoidNode(node.x() - 1, node.z(), 0), 1.0D));
        result.add(new AvoidStep(new AvoidNode(node.x(), node.z() + 1, 0), 1.0D));
        result.add(new AvoidStep(new AvoidNode(node.x(), node.z() - 1, 0), 1.0D));
        result.add(new AvoidStep(new AvoidNode(node.x() + 1, node.z() + 1, 0), 1.42D));
        result.add(new AvoidStep(new AvoidNode(node.x() + 1, node.z() - 1, 0), 1.42D));
        result.add(new AvoidStep(new AvoidNode(node.x() - 1, node.z() + 1, 0), 1.42D));
        result.add(new AvoidStep(new AvoidNode(node.x() - 1, node.z() - 1, 0), 1.42D));
        return result;
    }

    private static List<AvoidStep> driveNeighbors(AvoidNode node, VehicleProfile profile) {
        List<AvoidStep> result = new ArrayList<>(10);
        addDriveStep(result, node, 0, 1.0D, 1.0D);
        addDriveStep(result, node, 1, 1.0D, 1.22D);
        addDriveStep(result, node, -1, 1.0D, 1.22D);
        addDriveStep(result, node, 0, 2.0D, 1.85D);
        addDriveStep(result, node, 1, 2.0D, 2.15D);
        addDriveStep(result, node, -1, 2.0D, 2.15D);
        addDriveStep(result, node, 0, -1.0D, 2.6D);
        addDriveStep(result, node, 1, -1.0D, 3.0D);
        addDriveStep(result, node, -1, -1.0D, 3.0D);
        return result;
    }

    private static void addDriveStep(List<AvoidStep> result, AvoidNode node, int yawDeltaBins, double cells, double cost) {
        int yaw = wrapYawBin(node.yaw() + yawDeltaBins);
        float directionYaw = yawFromBin(cells >= 0.0D ? yaw : wrapYawBin(yaw + AVOIDANCE_YAW_BINS / 2));
        Vec3 dir = Vec3.directionFromRotation(0.0F, directionYaw).multiply(1.0D, 0.0D, 1.0D).normalize();
        int dx = (int) Math.round(dir.x * Math.abs(cells));
        int dz = (int) Math.round(dir.z * Math.abs(cells));
        if (dx == 0 && dz == 0) {
            if (Math.abs(dir.x) > Math.abs(dir.z)) dx = dir.x >= 0 ? 1 : -1;
            else dz = dir.z >= 0 ? 1 : -1;
        }
        result.add(new AvoidStep(new AvoidNode(node.x() + dx, node.z() + dz, yaw), cost));
    }

    private static double heuristic(AvoidNode a, AvoidNode b) {
        double dx = Math.abs(a.x() - b.x());
        double dz = Math.abs(a.z() - b.z());
        double diagonal = Math.min(dx, dz);
        double straight = Math.max(dx, dz) - diagonal;
        double base = straight + diagonal * 1.42D;
        return base + base * base * 0.001D;
    }

    private static List<AvoidNode> reconstructAvoidance(Map<AvoidNode, AvoidNode> cameFrom, AvoidNode current) {
        List<AvoidNode> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(0, current);
        }
        return path;
    }

    private static double terrainCost(Entity vehicle, Vec3 position) {
        BlockPos below = BlockPos.containing(position.x, position.y - 0.15D, position.z);
        Block block = vehicle.level().getBlockState(below).getBlock();
        return GLOBAL_BLOCK_COST_CACHE.computeIfAbsent(block, SuperbWarfareUnitAdapter::blockTerrainCost);
    }

    private static double blockTerrainCost(Block block) {
        String name = block.builtInRegistryHolder().key().location().getPath().toLowerCase(Locale.ROOT);
        double cost = 1.0D;
        if (name.contains("path") || name.contains("road") || name.contains("stone") || name.contains("asphalt")) cost *= 0.75D;
        if (name.contains("sand") || name.contains("gravel") || name.contains("mud")) cost *= 1.25D;
        if (name.contains("ice") || name.contains("leaves") || name.contains("farmland")) cost *= 1.6D;
        return cost;
    }

    private static double cachedTerrainCost(Entity vehicle, Vec3 position, Map<BlockPos, Double> cache) {
        BlockPos below = BlockPos.containing(position.x, position.y - 0.15D, position.z);
        Double cached = cache.get(below);
        if (cached != null) return cached;
        double cost = terrainCost(vehicle, position);
        cache.put(below, cost);
        return cost;
    }

    private static boolean canSweepPose(Entity vehicle, Vec3 from, float fromYaw, Vec3 to, float toYaw, VehicleProfile profile) {
        int steps = Math.max(3, Mth.ceil(from.distanceTo(to) / Math.max(0.75D, profile.length * 0.22D)));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 sample = from.lerp(to, t);
            float yaw = (float) Mth.wrapDegrees(fromYaw + Mth.wrapDegrees(toYaw - fromYaw) * t);
            if (!canOccupyVehicleSpace(vehicle, sample, yaw, profile)) return false;
        }
        return true;
    }

    private static boolean canSweepSimplePose(Entity vehicle, Vec3 from, Vec3 to, VehicleProfile profile) {
        int steps = Math.max(2, Mth.ceil(from.distanceTo(to) / Math.max(1.5D, profile.length * 0.5D)));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 sample = from.lerp(to, t);
            if (!canOccupySimpleVehicleSpace(vehicle, sample, profile)) return false;
        }
        return true;
    }

    private static boolean canSweepPathPrefix(Entity vehicle, Vec3 origin, float startYaw, List<AvoidNode> path, int until, VehicleProfile profile) {
        Vec3 previous = origin;
        float previousYaw = startYaw;
        for (int i = 1; i <= until; i++) {
            Vec3 next = findOccupiablePosition(vehicle, worldPos(origin, path.get(i)), yawFromBin(path.get(i).yaw()), profile);
            if (next == null) return false;
            float nextYaw = yawFromBin(path.get(i).yaw());
            if (!canSweepPose(vehicle, previous, previousYaw, next, nextYaw, profile)) return false;
            previous = next;
            previousYaw = nextYaw;
        }
        return true;
    }

    private static boolean canSweepSimplePathPrefix(Entity vehicle, Vec3 origin, List<AvoidNode> path, int until, VehicleProfile profile) {
        Vec3 previous = origin;
        for (int i = 1; i <= until; i++) {
            Vec3 next = findSimpleOccupiablePosition(vehicle, worldPos(origin, path.get(i)), profile);
            if (next == null) return false;
            if (!canSweepSimplePose(vehicle, previous, next, profile)) return false;
            previous = next;
        }
        return true;
    }

    private static int yawBin(float yaw) {
        return wrapYawBin(Mth.floor((Mth.wrapDegrees(yaw) + 180.0F) / AVOIDANCE_YAW_STEP));
    }

    private static int wrapYawBin(int yaw) {
        int wrapped = yaw % AVOIDANCE_YAW_BINS;
        return wrapped < 0 ? wrapped + AVOIDANCE_YAW_BINS : wrapped;
    }

    private static float yawFromBin(int yaw) {
        return (float) Mth.wrapDegrees(wrapYawBin(yaw) * AVOIDANCE_YAW_STEP - 180.0D + AVOIDANCE_YAW_STEP * 0.5D);
    }

    private record SafeCandidate(Vec3 position, double score) {}
    private record AvoidNode(int x, int z, int yaw) {}
    private record AvoidStep(AvoidNode node, double cost) {}
    private record AvoidItem(AvoidNode node, double g, double f) {}
    private record FlowItem(AvoidNode node, double cost) {}
    private record ActiveFlowField(ResourceKey<Level> level, Vec3 target, int limit, Map<AvoidNode, Double> cost, long expiresAt) {
        ActiveFlowField withExpiresAt(long expiresAt) { return new ActiveFlowField(level, target, limit, cost, expiresAt); }
        List<Vec3> routeFrom(Entity vehicle, Vec3 start, VehicleProfile profile) { return routeFromFlowField(vehicle, start, profile, this); }
    }
    private record CachedWaypoint(Vec3 target, Vec3 waypoint, long expiresAt) {}
    private static boolean isTrackedVehicle(Entity vehicle) {
        Object computed = invokeNoArg(vehicle, "computed");
        Object engineType = computed != null ? readMember(computed, "engineType") : null;
        if (engineType == null) engineType = readMember(vehicle, "engineType");
        if (engineType == null) {
            Object engineInfo = readMember(vehicle, "engineInfo");
            if (engineInfo == null && computed != null) engineInfo = readMember(computed, "engineInfo");
            engineType = engineInfo;
        }
        return engineType != null && engineType.toString().toUpperCase(Locale.ROOT).contains("TRACK");
    }

    private static boolean canWideTurn(Entity vehicle, float yawDelta, VehicleProfile profile) {
        Vec3 forward = Vec3.directionFromRotation(0.0F, vehicle.getYRot()).multiply(1.0D, 0.0D, 1.0D).normalize();
        if (forward.lengthSqr() < 1.0E-6D) return false;
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 turnSide = yawDelta > 0.0F ? right : right.scale(-1.0D);
        for (int i = 1; i <= 5; i++) {
            double forwardDistance = i * profile.wideForwardStep;
            double sideDistance = i * i * profile.wideSideStep;
            Vec3 offset = forward.scale(forwardDistance).add(turnSide.scale(sideDistance));
            float predictedYaw = Mth.wrapDegrees(vehicle.getYRot() + yawDelta * (i / 5.0F));
            if (!canOccupyVehicleSpace(vehicle, vehicle.position().add(offset), predictedYaw, profile)) return false;
        }
        return true;
    }

    private static boolean canExitThreePoint(Entity vehicle, float yawDelta, float absYawDelta, double distance, VehicleProfile profile) {
        double turnRadius = estimatedTurnRadius(profile);
        double usableDistance = Math.max(1.0D, distance - Math.max(1.0D, profile.length * 0.35D));
        double ratio = Mth.clamp(usableDistance / Math.max(usableDistance, turnRadius * 2.0D), 0.0D, 1.0D);
        double dynamicExitArc = Mth.clamp(Math.toDegrees(Math.asin(ratio)) * 0.35D, 6.0D, 22.0D);
        return absYawDelta <= dynamicExitArc;
    }

    private static boolean canThreePointTurn(Entity vehicle, float yawDelta, VehicleProfile profile) {
        int steer = yawDelta > 0.0F ? 1 : -1;
        Vec3 pos = vehicle.position();
        float yaw = vehicle.getYRot();
        double movedThisStroke = 0.0D;
        float yawAtStrokeStart = yaw;
        int totalTicks = 0;

        for (int step = 0; step < THREE_POINT_DURATIONS.length; step++) {
            boolean forward = step % 2 == 1;
            int stepSteer = forward ? steer : -steer;
            double tickDistance = forward ? profile.forwardStep : -profile.reverseStep;
            movedThisStroke = 0.0D;
            yawAtStrokeStart = yaw;

            for (int tick = 0; tick < THREE_POINT_DURATIONS[step]; tick++) {
                totalTicks++;
                if (totalTicks > MAX_THREE_POINT_TICKS) return false;
                yaw = Mth.wrapDegrees(yaw + stepSteer * profile.yawStep * (forward ? 1.0F : -1.0F));
                Vec3 forwardVec = Vec3.directionFromRotation(0.0F, yaw).multiply(1.0D, 0.0D, 1.0D).normalize();
                pos = pos.add(forwardVec.scale(tickDistance));
                movedThisStroke += Math.abs(tickDistance);
                if (!canOccupyVehicleSpace(vehicle, pos, yaw, profile)) {
                    return false;
                }
            }

            if (movedThisStroke < 1.0D) return false;
            if (Math.abs(Mth.wrapDegrees(yaw - yawAtStrokeStart)) < 10.0F) return false;
            if (Math.abs(Mth.wrapDegrees(yaw - vehicle.getYRot())) >= 120.0F) return true;
        }
        return Math.abs(Mth.wrapDegrees(yaw - vehicle.getYRot())) >= 80.0F;
    }

    private static boolean canOccupyVehicleSpace(Entity vehicle, Vec3 predictedPosition, float predictedYaw, VehicleProfile profile) {
        if (!profile.hasCollisionObb()) {
            AABB fallback = vehicle.getBoundingBox()
                    .move(predictedPosition.subtract(vehicle.position()))
                    .inflate(profile.padding, 0.15D, profile.padding);
            return canOccupyAabbSpace(vehicle, fallback, profile);
        }

        for (double stepUp : profile.stepUpCandidates) {
            OrientedBox obb = profile.predictedCollisionObb(predictedPosition.add(0.0D, stepUp, 0.0D), predictedYaw);
            if (collidesWithWorld(vehicle, obb)) continue;
            if (hasStepSupport(vehicle, obb.worldAabb())) return true;
        }
        return false;
    }

    private static boolean canOccupySimpleVehicleSpace(Entity vehicle, Vec3 predictedPosition, VehicleProfile profile) {
        return canOccupyVoxelVehicleSpace(vehicle, predictedPosition, profile)
                && !collidesWithOtherVehicleSimple(vehicle, predictedPosition, profile);
    }

    private static boolean canOccupyVoxelVehicleSpace(Entity vehicle, Vec3 predictedPosition, VehicleProfile profile) {
        int radius = profile.voxelRadius;
        int height = profile.voxelHeight;
        BlockPos center = BlockPos.containing(predictedPosition.x, predictedPosition.y, predictedPosition.z);
        boolean supported = false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = center.offset(dx, -1, dz);
                BlockState support = vehicle.level().getBlockState(floor);
                if (!support.getCollisionShape(vehicle.level(), floor).isEmpty()) supported = true;
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = vehicle.level().getBlockState(pos);
                    if (!state.getCollisionShape(vehicle.level(), pos).isEmpty()) return false;
                }
            }
        }
        return supported;
    }

    private static boolean canOccupyAabbSpace(Entity vehicle, AABB predictedBox, VehicleProfile profile) {
        for (double stepUp : profile.stepUpCandidates) {
            AABB stepped = predictedBox.move(0.0D, stepUp, 0.0D);
            if (!vehicle.level().noCollision(vehicle, stepped)) continue;
            if (hasStepSupport(vehicle, stepped)) return true;
        }
        return false;
    }

    private static boolean collidesWithWorld(Entity vehicle, OrientedBox obb) {
        AABB search = obb.worldAabb().inflate(1.0E-4D);
        for (VoxelShape shape : vehicle.level().getBlockCollisions(vehicle, search)) {
            if (!shape.isEmpty() && obb.intersects(shape.bounds())) return true;
        }
        for (Entity other : vehicle.level().getEntities(vehicle, search)) {
            if (other == vehicle || !isSuperbWarfareVehicle(other)) continue;
            if (isIgnoredVehicle(other)) continue;
            if (obb.worldAabb().intersects(otherVehicleBlockAabb(other))) return true;
        }
        return false;
    }

    private static boolean isIgnoredVehicle(Entity vehicle) {
        return IGNORED_VEHICLES.get().contains(vehicle.getUUID());
    }

    private static AABB otherVehicleBlockAabb(Entity otherVehicle) {
        AABB box = otherVehicle.getBoundingBox();
        double radius = Math.max(box.getXsize(), box.getZsize()) * 0.5D + 0.6D;
        Vec3 center = box.getCenter();
        return new AABB(center.x - radius, box.minY - 0.1D, center.z - radius, center.x + radius, box.maxY + 0.1D, center.z + radius);
    }

    private static boolean voxelFootprintsOverlap(Vec3 predictedPosition, VehicleProfile profile, Entity otherVehicle) {
        BlockPos a = BlockPos.containing(predictedPosition.x, predictedPosition.y, predictedPosition.z);
        BlockPos b = otherVehicle.blockPosition();
        int otherRadius = Math.max(1, Mth.ceil(Math.max(otherVehicle.getBbWidth(), otherVehicle.getBbHeight()) * 0.5F + 0.6F));
        return Math.abs(a.getX() - b.getX()) <= profile.voxelRadius + otherRadius
                && Math.abs(a.getZ() - b.getZ()) <= profile.voxelRadius + otherRadius
                && Math.abs(a.getY() - b.getY()) <= Math.max(profile.voxelHeight, Mth.ceil(otherVehicle.getBbHeight() + 0.5F));
    }

    private static boolean collidesWithOtherVehicleSimple(Entity vehicle, Vec3 predictedPosition, VehicleProfile profile) {
        AABB search = profile.predictedSimpleCollisionAabb(vehicle, predictedPosition).inflate(1.0E-4D);
        for (Entity other : vehicle.level().getEntities(vehicle, search)) {
            if (other == vehicle || !isSuperbWarfareVehicle(other)) continue;
            if (isIgnoredVehicle(other)) continue;
            double predictedOverlap = simpleVehicleOverlapDepth(predictedPosition, profile, other);
            if (predictedOverlap <= 0.0D) continue;
            double currentOverlap = simpleVehicleOverlapDepth(vehicle.position(), profile, other);
            if (currentOverlap > 0.0D && predictedOverlap < currentOverlap - 0.25D) continue;
            return true;
        }
        return false;
    }

    private static double simpleVehicleOverlapDepth(Vec3 position, VehicleProfile profile, Entity otherVehicle) {
        BlockPos a = BlockPos.containing(position.x, position.y, position.z);
        BlockPos b = otherVehicle.blockPosition();
        int otherRadius = Math.max(1, Mth.ceil(Math.max(otherVehicle.getBbWidth(), otherVehicle.getBbHeight()) * 0.5F + 0.6F));
        int yLimit = Math.max(profile.voxelHeight, Mth.ceil(otherVehicle.getBbHeight() + 0.5F));
        if (Math.abs(a.getY() - b.getY()) > yLimit) return 0.0D;
        double xOverlap = profile.voxelRadius + otherRadius + 1.0D - Math.abs(a.getX() - b.getX());
        double zOverlap = profile.voxelRadius + otherRadius + 1.0D - Math.abs(a.getZ() - b.getZ());
        return Math.min(xOverlap, zOverlap);
    }

    private static boolean hasForeignReservationOnPath(Entity vehicle, Vec3 from, Vec3 to, VehicleProfile profile, double maxDistance) {
        Vec3 flat = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(flat.lengthSqr());
        if (distance < 1.0E-6D) return false;
        Vec3 dir = flat.normalize();
        double checked = Math.min(distance, maxDistance);
        double step = Math.max(2.0D, Math.min(4.0D, profile.length * 0.75D));
        for (double d = step; d <= checked + 0.01D; d += step) {
            Vec3 sample = from.add(dir.scale(Math.min(d, checked)));
            if (foreignReservationPenalty(vehicle, sample, profile) > 0.0D) return true;
        }
        return false;
    }

    private static double foreignReservationPenalty(Entity vehicle, Vec3 position, VehicleProfile profile) {
        return VehicleGridManager.foreignReservationPenalty(vehicle, position, profile.voxelRadius, RESERVATION_PATH_PENALTY);
    }

    private static double foreignReservationMaxOwnerSpeedOnPath(Entity vehicle, Vec3 from, Vec3 to, VehicleProfile profile, double maxDistance) {
        Vec3 flat = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(flat.lengthSqr());
        if (distance < 1.0E-6D) return 0.0D;
        Vec3 dir = flat.normalize();
        double checked = Math.min(distance, maxDistance);
        double step = Math.max(2.0D, Math.min(4.0D, profile.length * 0.75D));
        double speed = 0.0D;
        for (double d = step; d <= checked + 0.01D; d += step) {
            Vec3 sample = from.add(dir.scale(Math.min(d, checked)));
            speed = Math.max(speed, VehicleGridManager.foreignReservationMaxOwnerSpeed(vehicle, sample, profile.voxelRadius));
        }
        return speed;
    }

    private static List<OrientedBox> liveCollisionBoxes(Entity vehicle) {
        List<OrientedBox> result = new ArrayList<>();
        Object collisionObb = invokeNoArg(vehicle, "getCollisionOBB");
        OrientedBox single = liveObbToBox(collisionObb);
        if (single != null) {
            result.add(single);
            return result;
        }

        Object rawObbs = invokeNoArg(vehicle, "getOBBs");
        List<?> obbs = asList(rawObbs);
        if (obbs == null) return result;
        for (Object obb : obbs) {
            Object part = readMember(obb, "part");
            String partName = part instanceof Enum<?> enumPart ? enumPart.name() : String.valueOf(part);
            if (!"COLLISION".equals(partName)) continue;
            OrientedBox box = liveObbToBox(obb);
            if (box != null) result.add(box);
        }
        return result;
    }

    private static OrientedBox liveObbToBox(Object liveObb) {
        if (liveObb == null) return null;
        Vec3 center = readVec3(readMember(liveObb, "center"));
        Vec3 extents = readVec3(readMember(liveObb, "extents"));
        Object rotation = readMember(liveObb, "rotation");
        if (center == null || extents == null || rotation == null) return null;
        if (extents.x <= 0.0D || extents.y <= 0.0D || extents.z <= 0.0D) return null;
        QuaternionValue quaternion = readQuaternion(rotation);
        if (quaternion == null) return null;
        return new OrientedBox(
                center,
                quaternion.rotate(new Vec3(1.0D, 0.0D, 0.0D)).normalize(),
                quaternion.rotate(new Vec3(0.0D, 1.0D, 0.0D)).normalize(),
                quaternion.rotate(new Vec3(0.0D, 0.0D, 1.0D)).normalize(),
                extents
        );
    }

    private static boolean hasStepSupport(Entity vehicle, AABB steppedBox) {
        AABB supportProbe = new AABB(
                steppedBox.minX + 0.12D,
                steppedBox.minY - SUPPORT_PROBE_DEPTH,
                steppedBox.minZ + 0.12D,
                steppedBox.maxX - 0.12D,
                steppedBox.minY - 0.02D,
                steppedBox.maxZ - 0.12D
        );
        return !vehicle.level().noCollision(vehicle, supportProbe);
    }

    private record VehicleProfile(
            double length,
            double width,
            double padding,
            double forwardStep,
            double reverseStep,
            float yawStep,
            double wideForwardStep,
            double wideSideStep,
            double maxStepUp,
            double[] stepUpCandidates,
            double[] simpleStepCandidates,
            int voxelRadius,
            int voxelHeight,
            Vec3 collisionLocalCenter,
            Vec3 collisionExtents,
            double collisionCustomYaw
    ) {
        static VehicleProfile from(Entity vehicle) {
            AABB box = vehicle.getBoundingBox();
            double aabbLength = Math.max(box.getXsize(), box.getZsize());
            double aabbWidth = Math.min(box.getXsize(), box.getZsize());
            double length = aabbLength;
            double width = aabbWidth;
            Vec3 collisionLocalCenter = null;
            Vec3 collisionExtents = null;
            double collisionCustomYaw = 0.0D;

            Object liveCollisionObb = invokeNoArg(vehicle, "getCollisionOBB");
            if (liveCollisionObb != null) {
                Vec3 liveCenter = readVec3(readMember(liveCollisionObb, "center"));
                Vec3 liveExtents = readVec3(readMember(liveCollisionObb, "extents"));
                if (liveCenter != null && liveExtents != null && liveExtents.x > 0.0D && liveExtents.y > 0.0D && liveExtents.z > 0.0D) {
                    Vec3 forward = Vec3.directionFromRotation(0.0F, vehicle.getYRot()).multiply(1.0D, 0.0D, 1.0D).normalize();
                    if (forward.lengthSqr() < 1.0E-6D) forward = new Vec3(0.0D, 0.0D, 1.0D);
                    Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
                    Vec3 rel = liveCenter.subtract(vehicle.position());
                    collisionLocalCenter = new Vec3(rel.dot(right), rel.y, rel.dot(forward));
                    collisionExtents = liveExtents;
                    length = Math.max(length, Math.max(liveExtents.x, liveExtents.z) * 2.0D);
                    width = Math.max(width, Math.min(liveExtents.x, liveExtents.z) * 2.0D);
                }
            }

            Object computed = invokeNoArg(vehicle, "computed");
            if (computed != null) {
                List<?> obbs = asList(readMember(computed, "obb"));
                if (obbs != null && !obbs.isEmpty()) {
                    double maxX = 0.0D;
                    double maxZ = 0.0D;
                    for (Object info : obbs) {
                        Object size = readMember(info, "size");
                        maxX = Math.max(maxX, Math.abs(readDouble(size, "x", 0.0D)));
                        maxZ = Math.max(maxZ, Math.abs(readDouble(size, "z", 0.0D)));
                    }
                    if (maxX > 0.0D || maxZ > 0.0D) {
                        length = Math.max(Math.max(maxX, maxZ) * 2.0D, aabbLength);
                        width = Math.max(Math.min(maxX, maxZ) * 2.0D, aabbWidth);
                    }

                    Object collisionInfo = findCollisionObbInfo(obbs);
                    if (collisionInfo != null) {
                        Object position = readMember(collisionInfo, "position");
                        Object size = readMember(collisionInfo, "size");
                        Object customRotate = readMember(collisionInfo, "customRotate");
                        Vec3 local = readVec3(position);
                        Vec3 extents = readVec3(size);
                        if (collisionLocalCenter == null && local != null && extents != null && extents.x > 0.0D && extents.y > 0.0D && extents.z > 0.0D) {
                            collisionLocalCenter = local;
                            collisionExtents = extents;
                            collisionCustomYaw = customRotate != null ? readDouble(customRotate, "y", 0.0D) : 0.0D;
                        }
                        if (customRotate != null) collisionCustomYaw = readDouble(customRotate, "y", collisionCustomYaw);
                        if (extents != null) {
                            length = Math.max(length, Math.max(extents.x, extents.z) * 2.0D);
                            width = Math.max(width, Math.min(extents.x, extents.z) * 2.0D);
                        }
                    }
                }
            }

            Object engineInfo = readMember(vehicle, "engineInfo");
            if (engineInfo == null && computed != null) engineInfo = readMember(computed, "engineInfo");
            double steeringSpeed = readEngineDouble(engineInfo, "steeringSpeed", "SteeringSpeed", 0.1D);
            double forwardRate = Math.abs(readEngineDouble(engineInfo, "maxForwardSpeedRate", "MaxForwardSpeedRate", 0.2D));
            double backwardRate = Math.abs(readEngineDouble(engineInfo, "maxBackwardSpeedRate", "MaxBackwardSpeedRate", 0.1D));
            double upStep = computed != null ? readDouble(computed, "upStep", 0.0D) : readDouble(vehicle, "maxUpStep", 0.0D);

            double normalizedLength = Mth.clamp(length / 4.0D, 0.7D, 2.4D);
            float yawStep = (float) Mth.clamp(steeringSpeed * 32.0D / normalizedLength, 1.2D, 5.8D);
            double forwardStep = Mth.clamp(0.14D + forwardRate * 0.22D, 0.12D, 0.32D);
            double reverseStep = Mth.clamp(0.10D + backwardRate * 0.22D, 0.08D, 0.24D);
            double basePadding = Mth.clamp(width * 0.08D + length * 0.025D, 0.42D, 0.85D);
            double heavyWallClearance = Mth.clamp((Math.max(length, width) - 6.0D) * 0.08D, 0.0D, 0.85D);
            double padding = Mth.clamp(basePadding + heavyWallClearance, 0.30D, 1.35D);
            double wideForwardStep = Mth.clamp(length * 0.34D, 1.0D, 2.4D);
            double wideSideStep = Mth.clamp((width + length * 0.25D) * 0.055D * (0.1D / Math.max(steeringSpeed, 0.03D)), 0.12D, 0.42D);
            double[] stepUpCandidates = makeStepUpCandidates(upStep);
            double[] simpleStepCandidates = makeSimpleStepCandidates(upStep);
            int voxelRadius = Math.max(1, Mth.ceil((Math.max(length, width) * 0.5D + padding + 0.35D)));
            int voxelHeight = Math.max(2, Mth.ceil((collisionExtents != null ? collisionExtents.y * 2.0D : box.getYsize()) + 0.5D));
            return new VehicleProfile(length, width, padding, forwardStep, reverseStep, yawStep, wideForwardStep, wideSideStep,
                    Mth.clamp(upStep, 0.0D, 1.25D), stepUpCandidates, simpleStepCandidates, voxelRadius, voxelHeight, collisionLocalCenter, collisionExtents, collisionCustomYaw);
        }

        boolean hasCollisionObb() {
            return collisionLocalCenter != null && collisionExtents != null;
        }

        OrientedBox predictedCollisionObb(Vec3 vehiclePosition, float vehicleYaw) {
            Vec3 forward = Vec3.directionFromRotation(0.0F, vehicleYaw).multiply(1.0D, 0.0D, 1.0D).normalize();
            if (forward.lengthSqr() < 1.0E-6D) forward = new Vec3(0.0D, 0.0D, 1.0D);
            Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
            Vec3 center = vehiclePosition
                    .add(right.scale(collisionLocalCenter.x))
                    .add(0.0D, collisionLocalCenter.y, 0.0D)
                    .add(forward.scale(collisionLocalCenter.z));
            double yaw = Math.toRadians(vehicleYaw + collisionCustomYaw);
            Vec3 obbForward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw)).normalize();
            Vec3 obbRight = new Vec3(-obbForward.z, 0.0D, obbForward.x);
            Vec3 obbUp = new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 paddedExtents = new Vec3(
                    collisionExtents.x + padding,
                    collisionExtents.y + 0.15D,
                    collisionExtents.z + padding
            );
            return new OrientedBox(center, obbRight, obbUp, obbForward, paddedExtents);
        }

        AABB predictedSimpleCollisionAabb(Entity vehicle, Vec3 vehiclePosition) {
            AABB live = vehicle.getBoundingBox();
            double radius = Math.max(length, width) * 0.5D + padding + 0.35D;
            double minY;
            double maxY;
            if (hasCollisionObb()) {
                minY = vehiclePosition.y + collisionLocalCenter.y - collisionExtents.y - 0.15D;
                maxY = vehiclePosition.y + collisionLocalCenter.y + collisionExtents.y + 0.20D;
            } else {
                double dy = vehiclePosition.y - vehicle.position().y;
                minY = live.minY + dy - 0.15D;
                maxY = live.maxY + dy + 0.20D;
            }
            return new AABB(vehiclePosition.x - radius, minY, vehiclePosition.z - radius, vehiclePosition.x + radius, maxY, vehiclePosition.z + radius);
        }

        VehicleProfile withFleetRadius(int fleetRadius) {
            int radius = Math.max(voxelRadius, fleetRadius);
            double fleetWidth = Math.max(width, radius * 2.0D);
            double fleetLength = Math.max(length, radius * 2.0D);
            return new VehicleProfile(fleetLength, fleetWidth, padding, forwardStep, reverseStep, yawStep, wideForwardStep, wideSideStep,
                    maxStepUp, stepUpCandidates, simpleStepCandidates, radius, voxelHeight, collisionLocalCenter, collisionExtents, collisionCustomYaw);
        }
    }

    private record OrientedBox(Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ, Vec3 extents) {
        boolean intersects(OrientedBox other) {
            Vec3[] axes = new Vec3[]{
                    axisX, axisY, axisZ,
                    other.axisX, other.axisY, other.axisZ,
                    axisX.cross(other.axisX), axisX.cross(other.axisY), axisX.cross(other.axisZ),
                    axisY.cross(other.axisX), axisY.cross(other.axisY), axisY.cross(other.axisZ),
                    axisZ.cross(other.axisX), axisZ.cross(other.axisY), axisZ.cross(other.axisZ)
            };
            for (Vec3 axis : axes) {
                if (axis.lengthSqr() < 1.0E-8D) continue;
                Vec3 normal = axis.normalize();
                double distance = Math.abs(center.subtract(other.center).dot(normal));
                double radius = projectionRadius(normal) + other.projectionRadius(normal);
                if (distance > radius + 1.0E-7D) return false;
            }
            return true;
        }

        boolean intersects(AABB aabb) {
            return intersects(fromAabb(aabb));
        }

        AABB worldAabb() {
            double rx = Math.abs(axisX.x * extents.x) + Math.abs(axisY.x * extents.y) + Math.abs(axisZ.x * extents.z);
            double ry = Math.abs(axisX.y * extents.x) + Math.abs(axisY.y * extents.y) + Math.abs(axisZ.y * extents.z);
            double rz = Math.abs(axisX.z * extents.x) + Math.abs(axisY.z * extents.y) + Math.abs(axisZ.z * extents.z);
            return new AABB(center.x - rx, center.y - ry, center.z - rz, center.x + rx, center.y + ry, center.z + rz);
        }

        private double projectionRadius(Vec3 axis) {
            return Math.abs(axis.dot(axisX)) * extents.x
                    + Math.abs(axis.dot(axisY)) * extents.y
                    + Math.abs(axis.dot(axisZ)) * extents.z;
        }

        private static OrientedBox fromAabb(AABB aabb) {
            return new OrientedBox(
                    aabb.getCenter(),
                    new Vec3(1.0D, 0.0D, 0.0D),
                    new Vec3(0.0D, 1.0D, 0.0D),
                    new Vec3(0.0D, 0.0D, 1.0D),
                    new Vec3(aabb.getXsize() * 0.5D, aabb.getYsize() * 0.5D, aabb.getZsize() * 0.5D)
            );
        }
    }

    private static final class AsyncRouteBuild {
        final UUID pilot;
        final Vec3 start, safe;
        final VehicleProfile profile;
        final long generation;
        final int goalX, goalZ;
        final List<DominionAsyncGridPlanner.Point> points = new ArrayList<>();
        final Map<Long, DominionAsyncGridPlanner.Cell> cells = new HashMap<>();
        int cursor;
        CompletableFuture<DominionAsyncGridPlanner.Result> future;

        AsyncRouteBuild(UUID pilot, Vec3 start, Vec3 safe, VehicleProfile profile, long generation, int radius, int goalX, int goalZ) {
            this.pilot = pilot; this.start = start; this.safe = safe; this.profile = profile; this.generation = generation; this.goalX = goalX; this.goalZ = goalZ;
            points.add(new DominionAsyncGridPlanner.Point(0, 0));
            points.add(new DominionAsyncGridPlanner.Point(goalX, goalZ));
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if ((x == 0 && z == 0) || (x == goalX && z == goalZ)) continue;
                points.add(new DominionAsyncGridPlanner.Point(x, z));
            }
        }
    }

    private record QuaternionValue(double x, double y, double z, double w) {
        Vec3 rotate(Vec3 vector) {
            double tx = 2.0D * (y * vector.z - z * vector.y);
            double ty = 2.0D * (z * vector.x - x * vector.z);
            double tz = 2.0D * (x * vector.y - y * vector.x);
            return new Vec3(
                    vector.x + w * tx + (y * tz - z * ty),
                    vector.y + w * ty + (z * tx - x * tz),
                    vector.z + w * tz + (x * ty - y * tx)
            );
        }
    }

    private static Object findCollisionObbInfo(List<?> obbs) {
        Object firstUsable = null;
        for (Object info : obbs) {
            Object size = readMember(info, "size");
            Vec3 extents = readVec3(size);
            if (extents == null || extents.x <= 0.0D || extents.y <= 0.0D || extents.z <= 0.0D) continue;
            if (firstUsable == null) firstUsable = info;
            Object part = readMember(info, "part");
            String partName = part instanceof Enum<?> enumPart ? enumPart.name() : String.valueOf(part);
            if ("COLLISION".equals(partName)) return info;
        }
        return firstUsable;
    }

    private static Vec3 readVec3(Object value) {
        if (value == null) return null;
        double x = readDouble(value, "x", Double.NaN);
        double y = readDouble(value, "y", Double.NaN);
        double z = readDouble(value, "z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;
        return new Vec3(x, y, z);
    }

    private static QuaternionValue readQuaternion(Object value) {
        if (value == null) return null;
        double x = readDouble(value, "x", Double.NaN);
        double y = readDouble(value, "y", Double.NaN);
        double z = readDouble(value, "z", Double.NaN);
        double w = readDouble(value, "w", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || Double.isNaN(w)) return null;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1.0E-8D) return null;
        return new QuaternionValue(x / length, y / length, z / length, w / length);
    }

    private static double[] makeStepUpCandidates(double upStep) {
        if (upStep <= 0.05D) return new double[]{0.0D};
        double capped = Mth.clamp(upStep, 0.0D, 1.25D);
        double half = capped * 0.5D;
        if (half < 0.1D) return new double[]{0.0D, capped};
        return new double[]{0.0D, half, capped};
    }

    private static double[] makeSimpleStepCandidates(double upStep) {
        double capped = Mth.clamp(upStep, 0.0D, 1.25D);
        if (capped <= 0.05D) return new double[]{0.0D, -0.5D, -1.0D};
        return new double[]{0.0D, capped, -0.5D, -1.0D};
    }

    private static double readEngineDouble(Object engineInfo, String fieldName, String jsonName, double fallback) {
        if (engineInfo == null) return fallback;
        double direct = readDouble(engineInfo, fieldName, Double.NaN);
        if (!Double.isNaN(direct)) return direct;
        try {
            Method get = engineInfo.getClass().getMethod("get", String.class);
            Object primitive = get.invoke(engineInfo, jsonName);
            if (primitive == null) return fallback;
            return readDouble(primitive, "getAsDouble",
                    readDouble(primitive, "getAsFloat",
                            readDouble(primitive, "asDouble",
                                    readDouble(primitive, "asFloat", fallback))));
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        MethodKey key = MethodKey.of(target.getClass(), name);
        if (BROKEN_METHODS.contains(key)) return null;
        try {
            Method method = findMethod(target.getClass(), name);
            return method != null ? method.invoke(target) : null;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException exception) {
            tripBrokenMethod(key, exception);
            return null;
        }
    }

    private static Object invokeWithArgs(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return null;
        MethodKey key = MethodKey.of(target.getClass(), name, parameterTypes);
        if (BROKEN_METHODS.contains(key)) return null;
        try {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            return method != null ? method.invoke(target, args) : null;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException exception) {
            tripBrokenMethod(key, exception);
            return null;
        }
    }

    private static void tripBrokenMethod(MethodKey key, Exception exception) {
        if (BROKEN_METHODS.add(key)) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : exception;
            LOGGER.error("SuperbWarfare unit reflective bridge method {}#{} failed and has been disabled.", key.type().getName(), key.name(), cause);
        }
    }

    private static Object readMember(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) return field.get(target);
        } catch (IllegalAccessException ignored) {
        }
        Object methodValue = invokeNoArg(target, name);
        if (methodValue != null) return methodValue;
        return invokeNoArg(target, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    private static double readDouble(Object target, String name, double fallback) {
        Object value = readMember(target, name);
        if (value instanceof Number number) return number.doubleValue();
        return fallback;
    }

    private static int readInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private static void pathDebugThrottled(Mob mob, Entity vehicle, String phase, String format, Object... args) {
        CompoundTag data = mob.getPersistentData();
        long now = vehicle.level().getGameTime();
        if (data.getLong(PILOT_LAST_PATH_DEBUG_TICK) + 20L > now) return;
        data.putLong(PILOT_LAST_PATH_DEBUG_TICK, now);
        pathDebug(mob, vehicle, phase, format, args);
    }

    private static void pathDebug(Mob mob, Entity vehicle, String phase, String format, Object... args) {
        CompoundTag data = mob.getPersistentData();
        String routeMode = data.getString(PILOT_ROUTE_MODE);
        String driveMode = data.getString(PILOT_DRIVE_MODE);
        int pathIndex = data.getInt(PILOT_PATH_INDEX);
        int pathSize = data.contains(PILOT_PATH_POINTS, Tag.TAG_LIST) ? data.getList(PILOT_PATH_POINTS, Tag.TAG_COMPOUND).size() : 0;
        long effectiveSince = data.getLong(PILOT_EFFECTIVE_ARRIVE_SINCE);
        String prefix = String.format(Locale.ROOT, "routeMode=%s driveMode=%s path=%d/%d effectiveSince=%d ",
                routeMode.isBlank() ? "NONE" : routeMode,
                driveMode.isBlank() ? "NONE" : driveMode,
                pathIndex, pathSize, effectiveSince);
        pathDebug(vehicle, phase, prefix + format, args);
    }

    private static void logDecision(Entity vehicle, DriveMode mode, boolean activeThreePoint, boolean finalApproach, boolean intermediateWaypoint,
                                    Vec3 target, Vec3 finalTarget, float yawDelta, float absYawDelta, double horizontalDistance,
                                    double speed, double turnRadius, boolean wideTurnAvailable, boolean shortReverse, boolean cannotArc, short keys) {
        LOGGER.info("[DS-SW-DECISION] vehicle={} pos={} yaw={} mode={} activeThreePoint={} final={} intermediate={} target={} finalTarget={} yawDiff={} absYaw={} dist={} speed={} turnRadius={} wideTurn={} shortReverse={} cannotArc={} keys={}",
                vehicle == null ? "null" : vehicle.getStringUUID(),
                fmt(vehicle == null ? Vec3.ZERO : vehicle.position()),
                vehicle == null ? "null" : String.format(Locale.ROOT, "%.1f", vehicle.getYRot()),
                mode,
                activeThreePoint,
                finalApproach,
                intermediateWaypoint,
                fmt(target),
                fmt(finalTarget),
                String.format(Locale.ROOT, "%.1f", yawDelta),
                String.format(Locale.ROOT, "%.1f", absYawDelta),
                String.format(Locale.ROOT, "%.2f", horizontalDistance),
                String.format(Locale.ROOT, "%.3f", speed),
                String.format(Locale.ROOT, "%.2f", turnRadius),
                wideTurnAvailable,
                shortReverse,
                cannotArc,
                (int) keys);
    }

    private static void pathDebug(Entity vehicle, String phase, String format, Object... args) {
        if (!PATH_LOGGING) return;
        String message;
        try {
            message = String.format(Locale.ROOT, format, args);
        } catch (RuntimeException ex) {
            message = format;
        }
        Vec3 pos = vehicle == null ? Vec3.ZERO : vehicle.position();
        Vec3 vel = vehicle == null ? Vec3.ZERO : vehicle.getDeltaMovement();
        LOGGER.info("[DS-SW-PATH] phase={} vehicle={} type={} pos={} yaw={} speed={} {}",
                phase,
                vehicle == null ? "null" : vehicle.getStringUUID(),
                vehicle == null ? "null" : vehicle.getType().toString(),
                fmt(pos),
                vehicle == null ? "null" : String.format(Locale.ROOT, "%.1f", vehicle.getYRot()),
                String.format(Locale.ROOT, "%.3f", Math.sqrt(vel.x * vel.x + vel.z * vel.z)),
                message);
    }

    private static String fmt(Vec3 vec) {
        if (vec == null) return "null";
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", vec.x, vec.y, vec.z);
    }

    private static String fmtPath(List<Vec3> points) {
        if (points == null) return "null";
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) builder.append(" -> ");
            builder.append(fmt(points.get(i)));
        }
        return builder.append(']').toString();
    }

    private static String profileSummary(VehicleProfile profile) {
        if (profile == null) return "null";
        return String.format(Locale.ROOT, "len=%.2f width=%.2f pad=%.2f voxR=%d voxH=%d yawStep=%.2f obb=%s",
                profile.length, profile.width, profile.padding, profile.voxelRadius, profile.voxelHeight, profile.yawStep, profile.hasCollisionObb());
    }

    private static double flatDistance(Vec3 a, Vec3 b) {
        if (a == null || b == null) return Double.NaN;
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private record FieldKey(Class<?> type, String name) {}

    private record HelicopterTranslation(boolean active, double desiredPitch, double desiredRoll) {
        private static final HelicopterTranslation INACTIVE = new HelicopterTranslation(false, 0.0D, 0.0D);
    }
    private record HelicopterVectorControl(double desiredPitch, double desiredRoll, double yawInput) {}

    private record MethodKey(Class<?> type, String name, List<Class<?>> parameterTypes) {
        private static MethodKey of(Class<?> type, String name, Class<?>... parameterTypes) {
            return new MethodKey(type, name, List.of(parameterTypes));
        }
    }

    private static Field findField(Class<?> type, String name) {
        if (type == null || name == null) return null;
        return FIELD_CACHE.computeIfAbsent(new FieldKey(type, name), key -> Optional.ofNullable(findFieldUncached(key.type(), key.name()))).orElse(null);
    }

    private static Field findFieldUncached(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void startThreePointTurn(CompoundTag data, float yawDelta) {
        data.putBoolean(PILOT_THREE_POINT_ACTIVE, true);
        data.putInt(PILOT_THREE_POINT_STEP, 0);
        data.putInt(PILOT_THREE_POINT_STEP_TICKS, THREE_POINT_DURATIONS[0]);
        data.putInt(PILOT_THREE_POINT_TOTAL_TICKS, 0);
        data.putInt(PILOT_THREE_POINT_STEER, yawDelta > 0.0F ? 1 : -1);
        data.remove(PILOT_REVERSE_NAV_TICKS);
    }

    private static short threePointKeys(Entity vehicle, CompoundTag data, float absYaw) {
        int phase = data.getInt(PILOT_THREE_POINT_STEP);
        int ticks = data.getInt(PILOT_THREE_POINT_STEP_TICKS);
        int total = data.getInt(PILOT_THREE_POINT_TOTAL_TICKS);
        if (total >= MAX_THREE_POINT_TICKS) {
            clearThreePointState(data);
            return KEY_BRAKE_OR_UP;
        }
        updateThreePointProgress(vehicle, data, absYaw);
        phase = data.getInt(PILOT_THREE_POINT_STEP);
        ticks = data.getInt(PILOT_THREE_POINT_STEP_TICKS);
        if (SuperbWarfareCompatConfig.multiStageKTurnEnabled() && phase == THREE_POINT_FORWARD_PHASE && ticks >= THREE_POINT_FORWARD_PHASE_TICKS) {
            setThreePointPhase(data, THREE_POINT_REVERSE_PHASE, vehicle, absYaw);
            phase = THREE_POINT_REVERSE_PHASE;
        }
        int steer = data.getInt(PILOT_THREE_POINT_STEER);
        int appliedSteer = phase == THREE_POINT_FORWARD_PHASE ? steer : -steer;
        short keys = phase == THREE_POINT_FORWARD_PHASE ? KEY_FORWARD : KEY_BACK;
        if (appliedSteer > 0) keys |= KEY_RIGHT;
        else if (appliedSteer < 0) keys |= KEY_LEFT;
        data.putInt(PILOT_THREE_POINT_STEP_TICKS, ticks + 1);
        data.putInt(PILOT_THREE_POINT_TOTAL_TICKS, total + 1);
        return keys;
    }

    private static void updateThreePointProgress(Entity vehicle, CompoundTag data, float absYaw) {
        if (!data.contains(PILOT_THREE_POINT_LAST_X)) {
            setThreePointPhase(data, data.getInt(PILOT_THREE_POINT_STEP), vehicle, absYaw);
            return;
        }
        double dx = vehicle.getX() - data.getDouble(PILOT_THREE_POINT_LAST_X);
        double dz = vehicle.getZ() - data.getDouble(PILOT_THREE_POINT_LAST_Z);
        double movedSqr = dx * dx + dz * dz;
        double lastAbsYaw = data.getDouble(PILOT_THREE_POINT_LAST_ABS_YAW);
        boolean yawImproved = absYaw < lastAbsYaw - 2.0D;
        boolean stalled = movedSqr < 0.0025D && !yawImproved;
        int stuck = stalled ? data.getInt(PILOT_THREE_POINT_STUCK_TICKS) + 1 : 0;
        data.putInt(PILOT_THREE_POINT_STUCK_TICKS, stuck);
        if (SuperbWarfareCompatConfig.multiStageKTurnEnabled() && stuck >= THREE_POINT_PHASE_STUCK_TICKS) {
            int next = data.getInt(PILOT_THREE_POINT_STEP) == THREE_POINT_REVERSE_PHASE ? THREE_POINT_FORWARD_PHASE : THREE_POINT_REVERSE_PHASE;
            setThreePointPhase(data, next, vehicle, absYaw);
            return;
        }
        data.putDouble(PILOT_THREE_POINT_LAST_X, vehicle.getX());
        data.putDouble(PILOT_THREE_POINT_LAST_Z, vehicle.getZ());
        data.putDouble(PILOT_THREE_POINT_LAST_ABS_YAW, absYaw);
    }

    private static void setThreePointPhase(CompoundTag data, int phase, Entity vehicle, float absYaw) {
        data.putInt(PILOT_THREE_POINT_STEP, phase);
        data.putInt(PILOT_THREE_POINT_STEP_TICKS, 0);
        data.putInt(PILOT_THREE_POINT_STUCK_TICKS, 0);
        data.putDouble(PILOT_THREE_POINT_LAST_X, vehicle.getX());
        data.putDouble(PILOT_THREE_POINT_LAST_Z, vehicle.getZ());
        data.putDouble(PILOT_THREE_POINT_LAST_ABS_YAW, absYaw);
    }

    private static void updatePilotProgress(CompoundTag data, double horizontalDistance) {
        if (!data.contains(PILOT_LAST_DISTANCE)) {
            data.putDouble(PILOT_LAST_DISTANCE, horizontalDistance);
            data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
            return;
        }
        double lastDistance = data.getDouble(PILOT_LAST_DISTANCE);
        if (horizontalDistance < lastDistance - STUCK_EPSILON) {
            data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
        } else {
            data.putInt(PILOT_NO_PROGRESS_TICKS, data.getInt(PILOT_NO_PROGRESS_TICKS) + 1);
        }
        data.putDouble(PILOT_LAST_DISTANCE, horizontalDistance);
    }

    private static void clearPilotState(Mob mob) {
        Entity vehicle = vehicleOf(mob);
        if (vehicle != null) WAYPOINT_CACHE.remove(vehicle.getUUID());
        CompoundTag data = mob.getPersistentData();
        data.remove(PILOT_LAST_DISTANCE);
        data.remove(PILOT_NO_PROGRESS_TICKS);
        data.remove(PILOT_REVERSE_TICKS);
        data.remove(PILOT_REVERSE_STEER);
        data.remove(PILOT_REVERSE_NAV_TICKS);
        data.remove(PILOT_REVERSE_NAV_STEER);
        data.remove(PILOT_TARGET_X);
        data.remove(PILOT_TARGET_Z);
        data.remove(PILOT_CAPTURED_TARGET_X);
        data.remove(PILOT_CAPTURED_TARGET_Z);
        data.remove(PILOT_FINAL_TARGET_X);
        data.remove(PILOT_FINAL_TARGET_Z);
        data.remove(PILOT_SAFE_TARGET_X);
        data.remove(PILOT_SAFE_TARGET_Y);
        data.remove(PILOT_SAFE_TARGET_Z);
        data.remove(PILOT_LAST_INPUT_TICK);
        data.remove(PILOT_LAST_INPUT_KEYS);
        clearAvoidanceState(data);
        clearThreePointState(data);
    }

    private static void resetPilotStateIfTargetChanged(Mob mob, Vec3 target) {
        CompoundTag data = mob.getPersistentData();
        if (data.contains(PILOT_TARGET_X) && data.contains(PILOT_TARGET_Z)) {
            Vec3 oldTarget = new Vec3(data.getDouble(PILOT_TARGET_X), target.y, data.getDouble(PILOT_TARGET_Z));
            double dx = target.x - data.getDouble(PILOT_TARGET_X);
            double dz = target.z - data.getDouble(PILOT_TARGET_Z);
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr > PILOT_TARGET_HARD_RESET_DISTANCE * PILOT_TARGET_HARD_RESET_DISTANCE) {
                Entity vehicle = vehicleOf(mob);
                if (vehicle != null && sameTargetCorridor(vehicle.position(), oldTarget, target)) {
                    data.remove(PILOT_LAST_DISTANCE);
                    data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
                    data.remove(PILOT_CAPTURED_TARGET_X);
                    data.remove(PILOT_CAPTURED_TARGET_Z);
                } else {
                    clearPilotState(mob);
                }
            } else if (distanceSqr > PILOT_TARGET_SOFT_CHANGE_DISTANCE * PILOT_TARGET_SOFT_CHANGE_DISTANCE) {
                data.remove(PILOT_LAST_DISTANCE);
                data.putInt(PILOT_NO_PROGRESS_TICKS, 0);
            }
        }
        data.putDouble(PILOT_TARGET_X, target.x);
        data.putDouble(PILOT_TARGET_Z, target.z);
    }

    private static boolean sameTargetCorridor(Vec3 position, Vec3 oldTarget, Vec3 newTarget) {
        Vec3 oldDir = oldTarget.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        Vec3 newDir = newTarget.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        if (oldDir.lengthSqr() < 1.0E-6D || newDir.lengthSqr() < 1.0E-6D) return false;
        Vec3 oldNorm = oldDir.normalize();
        Vec3 newNorm = newDir.normalize();
        if (oldNorm.dot(newNorm) < PILOT_TARGET_SAME_CORRIDOR_DOT) return false;
        Vec3 right = new Vec3(-oldNorm.z, 0.0D, oldNorm.x);
        double side = Math.abs(newTarget.subtract(oldTarget).multiply(1.0D, 0.0D, 1.0D).dot(right));
        return side <= PILOT_TARGET_SAME_CORRIDOR_SIDE;
    }

    private static void clearThreePointState(CompoundTag data) {
        data.remove(PILOT_THREE_POINT_ACTIVE);
        data.remove(PILOT_THREE_POINT_STEP);
        data.remove(PILOT_THREE_POINT_STEP_TICKS);
        data.remove(PILOT_THREE_POINT_TOTAL_TICKS);
        data.remove(PILOT_THREE_POINT_STEER);
    }

    private static void clearManeuverState(CompoundTag data) {
        data.remove(PILOT_NO_PROGRESS_TICKS);
        data.remove(PILOT_REVERSE_TICKS);
        data.remove(PILOT_REVERSE_STEER);
        data.remove(PILOT_REVERSE_NAV_TICKS);
        data.remove(PILOT_REVERSE_NAV_STEER);
        clearThreePointState(data);
    }

    private static void stopVehicle(Entity vehicle) {
        processInput(vehicle, (short) 0);
        mouseInput(vehicle, 0.0D, 0.0D);
    }

    private static boolean moveHelicopter(Mob pilot, Entity vehicle, Vec3 target) {
        if (pilot == null || !pilot.isAlive() || driver(vehicle) != pilot || target == null) {
            stopVehicle(vehicle);
            return true;
        }
        CompoundTag data = pilot.getPersistentData();
        String mode = data.getString(HELI_MODE);
        if (mode.isBlank() || "LANDED".equals(mode)) {
            /*
             * moveHelicopter is only entered for a real command or an existing
             * flight task. The background task runner skips blank/LANDED modes, so
             * a ground command can safely become TAKEOFF while retaining its remote
             * target. A menu takeoff already carries the vehicle's current position
             * and consequently remains an in-place takeoff.
             */
            mode = vehicle.onGround() ? "TAKEOFF" : "LOW_HOVER";
            data.putString(HELI_MODE, mode);
            if ("TAKEOFF".equals(mode)) {
                data.remove(HELI_HOLD_ALTITUDE);
                data.remove(HELI_LOCKED_ALTITUDE);
            }
        }
        // LOW_HOVER on the ground used the normal pulsed altitude controller and
        // repeatedly reset native holdPowerTick. A grounded non-landing aircraft
        // must always pass through the continuous-collective TAKEOFF phase.
        if (vehicle.onGround() && !"LANDING".equals(mode) && !"TAKEOFF".equals(mode)) {
            mode = "TAKEOFF";
            data.putString(HELI_MODE, mode);
            data.remove(HELI_HOLD_ALTITUDE);
            data.remove(HELI_LOCKED_ALTITUDE);
        }
        if (!"LANDING".equals(mode)) {
            rememberHelicopterNavigationTarget(data, target);
        } else {
            target = new Vec3(
                    data.contains(HELI_NAV_X) ? data.getDouble(HELI_NAV_X) : vehicle.getX(),
                    data.contains(HELI_NAV_Y) ? data.getDouble(HELI_NAV_Y) : vehicle.getY(),
                    data.contains(HELI_NAV_Z) ? data.getDouble(HELI_NAV_Z) : vehicle.getZ());
        }
        boolean takeoffMode = "TAKEOFF".equals(mode);
        Vec3 altitudeReference = takeoffMode ? vehicle.position() : target;
        double terrainY = landingTerrainY(vehicle, altitudeReference);
        if ("TAKEOFF".equals(mode) && vehicle.getY() > terrainY + 4.0D) {
            mode = "LOW_HOVER";
            data.putString(HELI_MODE, mode);
        }
        double cruiseAltitude = data.getBoolean(HELI_HOLD_ALTITUDE) && data.contains(HELI_LOCKED_ALTITUDE)
                ? boundedAbsoluteHelicopterAltitude(vehicle, data.getDouble(HELI_LOCKED_ALTITUDE))
                : helicopterCruiseAltitude(mode, flightClearanceGroundY(vehicle, altitudeReference));
        boolean lockedAltitude = data.getBoolean(HELI_HOLD_ALTITUDE) && data.contains(HELI_LOCKED_ALTITUDE);
        Vec3 navigationTarget = lockedAltitude && !"LANDING".equals(mode)
                ? helicopterObstacleAvoidanceTarget(vehicle, target) : target;
        Vec3 flatDelta = navigationTarget.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        double horizontalDistance = Math.sqrt(flatDelta.lengthSqr());
        boolean landing = "LANDING".equals(mode);
        double targetY = landing ? terrainY + 1.5D : cruiseAltitude;
        double altitudeError = targetY - vehicle.getY();

        Vec3 velocity = vehicle.getDeltaMovement();
        double horizontalSpeed = velocity.horizontalDistance();
        double stoppingDistance = Math.max(1.5D, horizontalSpeed * horizontalSpeed * 22.0D + horizontalSpeed * 5.0D);
        boolean brakeLatch = data.getBoolean(HELI_BRAKE_LATCH);
        if (brakeLatch && !landing && horizontalDistance > Math.max(8.0D, stoppingDistance * 2.5D)) brakeLatch = false;
        if (!landing && !"TAKEOFF".equals(mode)
                && horizontalDistance <= stoppingDistance + 1.5D && horizontalSpeed > 0.09D) brakeLatch = true;
        if (brakeLatch && horizontalSpeed < 0.055D) brakeLatch = false;
        data.putBoolean(HELI_BRAKE_LATCH, brakeLatch);
        boolean stationKeeping = !landing && !"TAKEOFF".equals(mode) && horizontalDistance <= HELI_HORIZONTAL_ARRIVAL;
        boolean emergencyBrake = brakeLatch || stationKeeping;
        setHelicopterHover(vehicle, emergencyBrake);

        short keys = helicopterAltitudeKeys(vehicle, altitudeError, landing, "TAKEOFF".equals(mode));
        if ("TAKEOFF".equals(mode)) {
            keys |= helicopterRollKeys(vehicle, 0.0D);
            mouseInput(vehicle, 0.0D, attitudePitchInput(vehicle, 0.0D));
        } else if (landing) {
            if (horizontalDistance > HELI_LANDING_HORIZONTAL) {
                HelicopterVectorControl control = helicopterVectorControl(vehicle, flatDelta, horizontalDistance, 0.18D);
                keys |= helicopterRollKeys(vehicle, control.desiredRoll());
                mouseInput(vehicle, control.yawInput(), helicopterPitchInput(vehicle, control.desiredPitch()));
            } else {
                keys |= helicopterRollKeys(vehicle, 0.0D);
                mouseInput(vehicle, 0.0D, attitudePitchInput(vehicle, 0.0D));
            }
            data.putString(HELI_MODE, "LANDING");
        } else if (horizontalDistance > HELI_HORIZONTAL_ARRIVAL && !emergencyBrake) {
            HelicopterVectorControl control = helicopterVectorControl(vehicle, flatDelta, horizontalDistance, 0.38D);
            keys |= helicopterRollKeys(vehicle, control.desiredRoll());
            mouseInput(vehicle, control.yawInput(), helicopterPitchInput(vehicle, control.desiredPitch()));
        } else {
            keys |= helicopterRollKeys(vehicle, 0.0D);
            mouseInput(vehicle, 0.0D, attitudePitchInput(vehicle, 0.0D));
        }
        if (landing && horizontalDistance <= HELI_LANDING_HORIZONTAL
                && (vehicle.onGround() || vehicle.getY() <= terrainY + 2.0D)
                && vehicle.getDeltaMovement().y <= 0.08D) {
            shutdownHelicopterPower(vehicle);
            processInput(vehicle, (short) 0);
            mouseInput(vehicle, 0.0D, 0.0D);
            data.putString(HELI_MODE, "LANDED");
            com.arxyt.dominionsword.api.VehicleDismounts.dismount(vehicle, pilot);
            return true;
        }
        traceHelicopterControl(pilot, vehicle, mode, target, navigationTarget, terrainY, targetY,
                altitudeError, horizontalDistance, horizontalSpeed, stoppingDistance, emergencyBrake, keys);
        processInput(vehicle, keys);
        return true;
    }

    private static void traceHelicopterControl(Mob pilot, Entity vehicle, String mode, Vec3 target, Vec3 navigationTarget,
                                               double terrainY, double targetY, double altitudeError,
                                               double horizontalDistance, double horizontalSpeed,
                                               double stoppingDistance, boolean emergencyBrake, short keys) {
        if (!SuperbWarfareCompatConfig.flightControlTraceEnabled() || vehicle == null || vehicle.level() == null) return;
        CompoundTag data = pilot.getPersistentData();
        long now = vehicle.level().getGameTime();
        if (data.getLong(HELI_LAST_CONTROL_TRACE_TICK) + SuperbWarfareCompatConfig.flightControlTraceIntervalTicks() > now) return;
        data.putLong(HELI_LAST_CONTROL_TRACE_TICK, now);
        Vec3 velocity = vehicle.getDeltaMovement();
        Object liftValue = invokeWithArgs(vehicle, "getUpVec", new Class<?>[]{float.class}, 1.0F);
        Vec3 liftDirection = liftValue instanceof Vec3 vec ? vec : Vec3.ZERO;
        Vec3 targetDirection = navigationTarget == null ? Vec3.ZERO
                : navigationTarget.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 horizontalLift = liftDirection.multiply(1.0D, 0.0D, 1.0D);
        double liftAlignment = horizontalLift.lengthSqr() < 1.0E-8D || targetDirection.lengthSqr() < 1.0E-8D
                ? 0.0D : horizontalLift.normalize().dot(targetDirection);
        double desiredYaw = navigationTarget == null ? vehicle.getYRot()
                : Mth.wrapDegrees(-(Mth.atan2(navigationTarget.x - vehicle.getX(), navigationTarget.z - vehicle.getZ()) * Mth.RAD_TO_DEG));
        LOGGER.info("[DS-SW-HELI] tick={} vehicle={} class={} name={} mode={} pos={} target={} nav={} terrainY={} targetY={} altErr={} velocity={} vY={} desiredVY={} hSpeed={} distance={} stopDistance={} yaw={} desiredYaw={} yawErr={} pitch={} roll={} liftDir={} liftAlign={} power={} rotor={} mouseX={} mouseY={} hover={} engineStarted={} engineReady={} emergencyBrake={} keys={}",
                now, vehicle.getId(), vehicle.getClass().getName(), vehicle.getDisplayName().getString(), mode,
                fmt(vehicle.position()), fmt(target), fmt(navigationTarget),
                decimal(terrainY), decimal(targetY), decimal(altitudeError), fmt(velocity), decimal(velocity.y),
                decimal(vehicle.getPersistentData().getDouble(HELI_DEBUG_DESIRED_VERTICAL_SPEED)),
                decimal(horizontalSpeed), decimal(horizontalDistance), decimal(stoppingDistance),
                decimal(vehicle.getYRot()), decimal(desiredYaw), decimal(Mth.wrapDegrees(desiredYaw - vehicle.getYRot())),
                decimal(vehicle.getXRot()), decimal(readDouble(vehicle, "roll", 0.0D)), fmt(liftDirection), decimal(liftAlignment),
                decimal(readDouble(vehicle, "power", 0.0D)), decimal(readDouble(vehicle, "synchedPropellerRot", 0.0D)),
                decimal(readDouble(vehicle, "mouseMoveSpeedX", 0.0D)), decimal(readDouble(vehicle, "mouseMoveSpeedY", 0.0D)),
                Boolean.TRUE.equals(readMember(vehicle, "hoverMode")), Boolean.TRUE.equals(readMember(vehicle, "engineStart")),
                Boolean.TRUE.equals(readMember(vehicle, "engineStartOver")), emergencyBrake, keys);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static short helicopterAltitudeKeys(Entity vehicle, double altitudeError, boolean landing, boolean takingOff) {
        double verticalSpeed = vehicle.getDeltaMovement().y();
        if (!Boolean.TRUE.equals(readMember(vehicle, "engineStartOver"))) return KEY_FORWARD;
        double desiredVerticalSpeed = landing
                ? Mth.clamp(altitudeError * 0.045D, -0.14D, 0.035D)
                : takingOff
                ? Mth.clamp(altitudeError * HELI_ALTITUDE_SPEED_GAIN, 0.0D, HELI_ALTITUDE_SPEED_LIMIT)
                : Mth.clamp(altitudeError * 0.04D, -0.17D, 0.17D);
        vehicle.getPersistentData().putDouble(HELI_DEBUG_DESIRED_VERTICAL_SPEED, desiredVerticalSpeed);

        // Superb Warfare already owns the hover-power loop. With no up/down input it subtracts a
        // value proportional to vertical velocity from power (gain 0.002, or 0.01 in hover mode).
        // We only inject isolated one-tick pulses to request a different vertical velocity; every
        // neutral tick resets holdPowerTick and gives the native stabilizer control again.
        double speedError = desiredVerticalSpeed - verticalSpeed;
        if (Math.abs(speedError) <= HELI_VERTICAL_SPEED_DEAD_ZONE) return 0;
        /*
         * Native helicopter power acceleration is hold-duration based. Pulsing the
         * key during takeoff reset holdPowerTick on every neutral tick, leaving the
         * rotor just above its start threshold for tens of seconds. Hold collective
         * continuously until the requested climb rate is reached; once it is, the
         * native vertical stabilizer resumes on the neutral tick.
         */
        if (takingOff && speedError > 0.0D) return KEY_FORWARD;
        double magnitude = Math.abs(speedError);
        int pulseInterval = magnitude > 0.24D ? 2 : magnitude > 0.12D ? 3 : 5;
        if (vehicle.level().getGameTime() % pulseInterval != 0L) return 0;
        return speedError > 0.0D ? KEY_FORWARD : KEY_DOWN;
    }

    private static void rememberHelicopterNavigationTarget(CompoundTag data, Vec3 target) {
        data.putDouble(HELI_NAV_X, target.x);
        data.putDouble(HELI_NAV_Y, target.y);
        data.putDouble(HELI_NAV_Z, target.z);
    }

    private static double helicopterDesiredCruisePitch(Entity vehicle, Vec3 flatDelta, double horizontalDistance, double stoppingDistance) {
        float desiredYaw = (float) Mth.wrapDegrees(-(Mth.atan2(flatDelta.x, flatDelta.z) * Mth.RAD_TO_DEG));
        double yawError = Math.abs(Mth.wrapDegrees(desiredYaw - vehicle.getYRot()));
        if (yawError > 24.0D) return 0.0D;
        double speed = vehicle.getDeltaMovement().horizontalDistance();
        double pitch = Mth.clamp(1.5D + horizontalDistance * 0.10D - speed * 10.0D,
                1.5D, HELI_MAX_CRUISE_PITCH_DEGREES);
        if (horizontalDistance < stoppingDistance * 1.35D) {
            pitch = Mth.clamp((horizontalDistance - stoppingDistance) * 2.0D, -7.0D, pitch);
        }
        return pitch * Mth.clamp(1.0D - yawError / 75.0D, 0.0D, 1.0D);
    }

    /**
     * Converts a world-space velocity error into the exact pitch/roll needed to point rotor lift
     * toward the destination. This avoids assuming that nose direction alone equals movement direction.
     */
    private static HelicopterVectorControl helicopterVectorControl(Entity vehicle, Vec3 flatDelta,
                                                                   double horizontalDistance, double maxSpeed) {
        Vec3 direction = horizontalDistance < 1.0E-6D ? Vec3.ZERO : flatDelta.scale(1.0D / horizontalDistance);
        double brakingSpeed = Math.sqrt(Math.max(0.0D, 2.0D * 0.008D * Math.max(0.0D, horizontalDistance - 1.0D)));
        double desiredSpeed = Math.min(maxSpeed, brakingSpeed);
        Vec3 desiredVelocity = direction.scale(desiredSpeed);
        Vec3 currentVelocity = vehicle.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        Vec3 acceleration = desiredVelocity.subtract(currentVelocity).scale(0.16D);
        double accelerationLength = acceleration.horizontalDistance();
        if (accelerationLength > 0.010D) acceleration = acceleration.scale(0.010D / accelerationLength);

        Vec3 forward = horizontalForward(vehicle);
        Vec3 right = horizontalRight(vehicle);
        double desiredPitch = Math.toDegrees(Math.atan2(acceleration.dot(forward), 0.08D));
        double desiredRoll = Math.toDegrees(Math.atan2(acceleration.dot(right), 0.08D));
        desiredPitch = Mth.clamp(desiredPitch, -HELI_MAX_TRANSLATION_PITCH_DEGREES, HELI_MAX_TRANSLATION_PITCH_DEGREES);
        desiredRoll = Mth.clamp(desiredRoll, -HELI_MAX_TRANSLATION_ROLL_DEGREES, HELI_MAX_TRANSLATION_ROLL_DEGREES);

        // Within 40 blocks a helicopter translates backward/sideways without turning its nose.
        double yawInput = horizontalDistance <= HELI_DIRECT_TRANSLATION_RANGE
                ? 0.0D : helicopterYawInput(vehicle, flatDelta);
        return new HelicopterVectorControl(desiredPitch, desiredRoll, yawInput);
    }

    private static HelicopterTranslation directHelicopterTranslation(Entity vehicle, Vec3 flatDelta, double horizontalDistance) {
        if (horizontalDistance > HELI_DIRECT_TRANSLATION_RANGE) return HelicopterTranslation.INACTIVE;
        double localForward = flatDelta.dot(horizontalForward(vehicle));
        double localRight = flatDelta.dot(horizontalRight(vehicle));
        if (Math.abs(localForward) < HELI_DIRECT_TRANSLATION_DEAD_ZONE
                && Math.abs(localRight) < HELI_DIRECT_TRANSLATION_DEAD_ZONE) return HelicopterTranslation.INACTIVE;

        // In Superb Warfare, positive pitch creates forward rotor-disk tilt and positive roll creates right tilt.
        double desiredPitch = Mth.clamp(localForward * 0.55D,
                -HELI_MAX_TRANSLATION_PITCH_DEGREES, HELI_MAX_TRANSLATION_PITCH_DEGREES);
        double desiredRoll = Mth.clamp(localRight * 0.70D,
                -HELI_MAX_TRANSLATION_ROLL_DEGREES, HELI_MAX_TRANSLATION_ROLL_DEGREES);
        return new HelicopterTranslation(true, desiredPitch, desiredRoll);
    }

    private static short helicopterRollKeys(Entity vehicle, double desiredRoll) {
        CompoundTag data = vehicle.getPersistentData();
        double previousTarget = data.contains(HELI_FILTERED_ROLL)
                ? data.getDouble(HELI_FILTERED_ROLL) : desiredRoll;
        // A route/yaw update may rotate the local lateral axis abruptly. Limit the
        // bank target slew instead of commanding the opposite key in one tick.
        double filteredTarget = previousTarget + Mth.clamp(desiredRoll - previousTarget, -0.8D, 0.8D);
        data.putDouble(HELI_FILTERED_ROLL, filteredTarget);

        double currentRoll = readDouble(vehicle, "roll", 0.0D);
        double propeller = Math.max(0.035D, readDouble(vehicle, "synchedPropellerRot", 0.05D));
        double rollGain = Math.max(0.2D, readDouble(readMember(vehicle, "engineInfo"), "rollSpeed", 1.0D));
        if (Boolean.TRUE.equals(readMember(vehicle, "hoverMode"))) rollGain *= 0.05D;
        double residualSteer = readDouble(vehicle, "deltaRot", 0.0D);
        double yawRollCoupling = 0.25D * readDouble(vehicle, "mouseMoveSpeedX", 0.0D) * propeller;
        // This is the exact next-tick roll contribution used by Superb Warfare:
        // roll -= rollSpeed * (deltaRot + 0.25 * mouseX * rotor).
        double predictedRollRate = -rollGain * (residualSteer + yawRollCoupling);
        double rollError = Mth.wrapDegrees(filteredTarget - currentRoll);
        double command = rollError - predictedRollRate * 3.5D;
        if (Math.abs(currentRoll) > 12.0D) command = -currentRoll - predictedRollRate * 4.5D;
        if (command > 0.55D) return KEY_RIGHT;
        if (command < -0.55D) return KEY_LEFT;
        return 0;
    }

    private static double helicopterYawInput(Entity vehicle, Vec3 flatDelta) {
        if (flatDelta.lengthSqr() < 1.0E-6D) return 0.0D;
        float desiredYaw = (float) Mth.wrapDegrees(-(Mth.atan2(flatDelta.x, flatDelta.z) * Mth.RAD_TO_DEG));
        double yawError = Mth.wrapDegrees(desiredYaw - vehicle.getYRot());
        double propeller = Math.max(0.035D, readDouble(vehicle, "synchedPropellerRot", 0.05D));
        double yawSpeed = Math.max(0.2D, readDouble(readMember(vehicle, "engineInfo"), "yawSpeed", 1.0D));
        double yawInput = yawError * 0.32D / Math.max(0.10D, 2.0D * yawSpeed * propeller);
        return Mth.clamp(yawInput, -HELI_MAX_YAW_INPUT, HELI_MAX_YAW_INPUT);
    }

    private static double attitudeYawInput(Entity vehicle) {
        double roll = readDouble(vehicle, "roll", 0.0D);
        double propeller = Math.max(0.035D, readDouble(vehicle, "synchedPropellerRot", 0.05D));
        return Mth.clamp(roll * 0.16D / Math.max(0.04D, 0.25D * propeller), -HELI_MAX_YAW_INPUT, HELI_MAX_YAW_INPUT);
    }

    private static double helicopterPitchInput(Entity vehicle, double desiredPitch) {
        double forwardSpeed = vehicle.getDeltaMovement().dot(horizontalForward(vehicle));
        double dampedPitch = desiredPitch - Mth.clamp(forwardSpeed * 9.0D, -3.0D, 3.0D);
        return attitudePitchInput(vehicle, dampedPitch);
    }

    private static double attitudePitchInput(Entity vehicle, double desiredPitch) {
        double pitch = vehicle.getXRot();
        double propeller = Math.max(0.035D, readDouble(vehicle, "synchedPropellerRot", 0.05D));
        double pitchSpeed = Math.max(0.2D, readDouble(readMember(vehicle, "engineInfo"), "pitchSpeed", 1.0D));
        double response = Math.max(0.06D, 1.5D * pitchSpeed * propeller);
        return Mth.clamp((desiredPitch - pitch) * 0.42D / response, -HELI_MAX_PITCH_INPUT, HELI_MAX_PITCH_INPUT);
    }

    private static Vec3 horizontalForward(Entity vehicle) {
        float yaw = vehicle.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    private static Vec3 horizontalRight(Entity vehicle) {
        Vec3 forward = horizontalForward(vehicle);
        // Minecraft yaw 0 faces +Z, whose screen/right-hand side is -X.
        return new Vec3(-forward.z, 0.0D, forward.x);
    }

    private static void setHelicopterHover(Entity vehicle, boolean enabled) {
        boolean current = Boolean.TRUE.equals(readMember(vehicle, "hoverMode"));
        if (current == enabled) return;
        invokeWithArgs(vehicle, "setHoverMode", new Class<?>[]{boolean.class}, enabled);
    }

    private static void shutdownHelicopterPower(Entity vehicle) {
        setHelicopterHover(vehicle, false);
        vehicle.getPersistentData().remove(HELI_FILTERED_ROLL);
        invokeWithArgs(vehicle, "setEngineStart", new Class<?>[]{boolean.class}, false);
        invokeWithArgs(vehicle, "setEngineStartOver", new Class<?>[]{boolean.class}, false);
        setHelicopterPower(vehicle, 0.0D);
        writeBooleanField(vehicle, "engineStart", false);
        writeBooleanField(vehicle, "engineStartOver", false);
        writeNumberField(vehicle, "power", 0.0D);
        writeNumberField(vehicle, "synchedPropellerRot", 0.0D);
        writeNumberField(vehicle, "propellerRot", 0.0D);
        writeNumberField(vehicle, "holdPowerTick", 0.0D);
        vehicle.setDeltaMovement(Vec3.ZERO);
    }

    private static void setHelicopterPower(Entity vehicle, double power) {
        float value = (float) Mth.clamp(power, 0.0D, 0.12D);
        invokeWithArgs(vehicle, "setPower", new Class<?>[]{float.class}, value);
        writeNumberField(vehicle, "power", value);
    }

    private static boolean writeBooleanField(Object target, String name, boolean value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean writeNumberField(Object target, String name, double value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Class<?> fieldType = field.getType();
                if (fieldType == float.class) field.setFloat(target, (float) value);
                else if (fieldType == double.class) field.setDouble(target, value);
                else if (fieldType == int.class) field.setInt(target, (int) value);
                else if (fieldType == long.class) field.setLong(target, (long) value);
                else return false;
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException ignored) {
                return false;
            }
        }
        return false;
    }

    private static double helicopterCruiseAltitude(String mode, double terrainY) {
        double offset = switch (mode) {
            case "HIGH_HOVER" -> HELI_HIGH_HOVER_ALTITUDE;
            case "MEDIUM_HOVER" -> HELI_MEDIUM_HOVER_ALTITUDE;
            default -> HELI_LOW_HOVER_ALTITUDE;
        };
        return terrainY + offset;
    }

    /** Landing needs the exact ground at the destination; cruise also clears every cliff in front of the aircraft. */
    private static double landingTerrainY(Entity vehicle, Vec3 target) {
        if (!(vehicle.level() instanceof ServerLevel level)) return target.y;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(target.x), Mth.floor(target.z));
    }

    private static double flightClearanceGroundY(Entity vehicle, Vec3 target) {
        if (!(vehicle.level() instanceof ServerLevel level)) return target.y;
        CompoundTag data = vehicle.getPersistentData();
        long now = level.getGameTime();
        double dx = target.x - data.getDouble(HELI_CLEARANCE_SCAN_TARGET_X);
        double dz = target.z - data.getDouble(HELI_CLEARANCE_SCAN_TARGET_Z);
        if (data.contains(HELI_CLEARANCE_SCAN_GROUND_Y)
                && now - data.getLong(HELI_CLEARANCE_SCAN_TICK) < HELI_CLEARANCE_SCAN_INTERVAL
                && dx * dx + dz * dz <= 1.0D) return data.getDouble(HELI_CLEARANCE_SCAN_GROUND_Y);
        Vec3 horizontal = target.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.min(HELI_CLEARANCE_SCAN_RANGE, horizontal.length());
        int samples = Math.max(1, Mth.ceil(distance / HELI_CLEARANCE_SCAN_STEP));
        double highest = landingTerrainY(vehicle, vehicle.position());
        for (int index = 1; index <= samples; index++) {
            double t = (double) index / samples;
            double x = vehicle.getX() + horizontal.x * t;
            double z = vehicle.getZ() + horizontal.z * t;
            highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z)));
        }
        data.putLong(HELI_CLEARANCE_SCAN_TICK, now);
        data.putDouble(HELI_CLEARANCE_SCAN_TARGET_X, target.x);
        data.putDouble(HELI_CLEARANCE_SCAN_TARGET_Z, target.z);
        data.putDouble(HELI_CLEARANCE_SCAN_GROUND_Y, highest);
        return highest;
    }

    private static double boundedAbsoluteHelicopterAltitude(Entity vehicle, double requestedY) {
        if (!(vehicle.level() instanceof ServerLevel level)) return Mth.clamp(requestedY, -64.0D, 500.0D);
        double floorY = landingTerrainY(vehicle, vehicle.position());
        double minimum = floorY + 3.0D;
        int upperLimit = Math.min(500, level.getMaxBuildHeight() - 4);
        double maximum = upperLimit;
        HitResult ceiling = level.clip(new ClipContext(new Vec3(vehicle.getX(), minimum, vehicle.getZ()),
                new Vec3(vehicle.getX(), upperLimit, vehicle.getZ()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        if (ceiling.getType() != HitResult.Type.MISS) maximum = Math.floor(ceiling.getLocation().y) - 3.0D;
        if (maximum < minimum) return maximum;
        return Mth.clamp(requestedY, minimum, maximum);
    }

    /** At an explicitly locked altitude, route around a building instead of treating its roof as terrain to climb. */
    private static Vec3 helicopterObstacleAvoidanceTarget(Entity vehicle, Vec3 finalTarget) {
        if (!(vehicle.level() instanceof ServerLevel level)) return finalTarget;
        CompoundTag data = vehicle.getPersistentData();
        double safetyRadius = helicopterSafetyRadius(vehicle);
        double safetyHalfHeight = helicopterSafetyHalfHeight(vehicle);
        if (helicopterCorridorClear(vehicle, vehicle.position(), finalTarget, safetyRadius, safetyHalfHeight)) {
            data.remove(HELI_AVOID_EXPIRES);
            return finalTarget;
        }
        long now = level.getGameTime();
        double targetDx = finalTarget.x - data.getDouble(HELI_AVOID_TARGET_X);
        double targetDz = finalTarget.z - data.getDouble(HELI_AVOID_TARGET_Z);
        if (data.contains(HELI_AVOID_EXPIRES) && now <= data.getLong(HELI_AVOID_EXPIRES)
                && targetDx * targetDx + targetDz * targetDz <= 1.0D) {
            Vec3 waypoint = new Vec3(data.getDouble(HELI_AVOID_WAYPOINT_X), finalTarget.y, data.getDouble(HELI_AVOID_WAYPOINT_Z));
            if (vehicle.position().distanceToSqr(waypoint) > 4.0D && helicopterCorridorClear(vehicle, vehicle.position(), waypoint, safetyRadius, safetyHalfHeight)) return waypoint;
        }
        Vec3 flat = finalTarget.subtract(vehicle.position()).multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSqr() < 1.0E-4D) return finalTarget;
        Vec3 forward = flat.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double offset = safetyRadius + 3.0D;
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int side : new int[]{-1, 1}) {
            for (double distance : new double[]{offset, offset * 1.75D, offset * 2.5D}) {
                Vec3 candidate = vehicle.position().add(forward.scale(Math.max(4.0D, offset * 0.5D))).add(right.scale(side * distance));
                if (!helicopterCorridorClear(vehicle, vehicle.position(), candidate, safetyRadius, safetyHalfHeight)) continue;
                double score = candidate.distanceToSqr(finalTarget);
                if (score < bestScore) { best = candidate; bestScore = score; }
            }
        }
        if (best == null) return finalTarget;
        data.putDouble(HELI_AVOID_TARGET_X, finalTarget.x);
        data.putDouble(HELI_AVOID_TARGET_Z, finalTarget.z);
        data.putDouble(HELI_AVOID_WAYPOINT_X, best.x);
        data.putDouble(HELI_AVOID_WAYPOINT_Z, best.z);
        data.putLong(HELI_AVOID_EXPIRES, now + HELI_AVOID_TTL);
        return best;
    }

    private static boolean helicopterCorridorClear(Entity vehicle, Vec3 from, Vec3 to, double safetyRadius, double safetyHalfHeight) {
        if (!(vehicle.level() instanceof ServerLevel level)) return true;
        Vec3 flat = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSqr() < 1.0E-5D) return true;
        Vec3 right = new Vec3(-flat.z, 0.0D, flat.x).normalize();
        for (double lateral : HELI_OBSTACLE_LATERAL_SAMPLES) for (double vertical : HELI_OBSTACLE_VERTICAL_SAMPLES) {
            Vec3 offset = right.scale(lateral * safetyRadius).add(0.0D, vertical * safetyHalfHeight, 0.0D);
            HitResult hit = level.clip(new ClipContext(from.add(offset), to.add(offset), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
            if (hit.getType() != HitResult.Type.MISS) return false;
        }
        return true;
    }

    private static double helicopterSafetyRadius(Entity vehicle) {
        double body = Math.max(vehicle.getBbWidth(), vehicle.getBbHeight());
        for (OrientedBox box : liveCollisionBoxes(vehicle)) body = Math.max(body, Math.max(box.extents().x, box.extents().z) * 2.0D);
        return Math.max(8.0D, body + 6.0D);
    }

    private static double helicopterSafetyHalfHeight(Entity vehicle) {
        double halfHeight = vehicle.getBbHeight() * 0.5D;
        for (OrientedBox box : liveCollisionBoxes(vehicle)) halfHeight = Math.max(halfHeight, box.extents().y);
        return Math.max(3.0D, halfHeight + 2.5D);
    }

    private static boolean isHelicopter(Entity vehicle) {
        return engineTypeName(vehicle).contains("HELICOPTER");
    }

    private static String engineTypeName(Entity vehicle) {
        Object computed = invokeNoArg(vehicle, "computed");
        Object engineType = computed != null ? readMember(computed, "engineType") : null;
        if (engineType == null) engineType = readMember(vehicle, "engineType");
        if (engineType == null) {
            Object engineInfo = readMember(vehicle, "engineInfo");
            if (engineInfo == null && computed != null) engineInfo = readMember(computed, "engineInfo");
            engineType = engineInfo;
        }
        return engineType == null ? "" : engineType.toString().toUpperCase(Locale.ROOT);
    }

    private static void processInput(Entity vehicle, short keys) {
        CompoundTag data = vehicle.getPersistentData();
        long now = vehicle.level().getGameTime();
        if (data.getLong(PILOT_LAST_INPUT_TICK) == now && (keys & KEY_BRAKE_OR_UP) == 0 && keys != 0) return;
        data.putLong(PILOT_LAST_INPUT_TICK, now);
        data.putShort(PILOT_LAST_INPUT_KEYS, keys);
        try {
            Method method = findMethod(vehicle.getClass(), "processInput", short.class);
            if (method != null) method.invoke(vehicle, keys);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private static void mouseInput(Entity vehicle, double x, double y) {
        try {
            Method method = findMethod(vehicle.getClass(), "mouseInput", double.class, double.class);
            if (method != null) method.invoke(vehicle, x, y);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private static void autoAimForSeat(Entity vehicle, Mob mob, LivingEntity target) {
        String uuid = target.getUUID().toString();
        Object computed = invokeWithArgs(vehicle, "computed", new Class<?>[0]);
        int turretIndex = readInt(readMember(computed, "turretControllerIndex"), readInt(readMember(vehicle, "turretControllerIndex"), -1));
        int passengerIndex = readInt(readMember(computed, "passengerWeaponStationControllerIndex"),
                readInt(readMember(vehicle, "passengerWeaponStationControllerIndex"), -1));
        Entity turretController = turretIndex < 0 ? null : entityValue(invokeWithArgs(vehicle, "getNthEntity",
                new Class<?>[]{int.class}, turretIndex));
        Entity passengerController = passengerIndex < 0 ? null : entityValue(invokeWithArgs(vehicle, "getNthEntity",
                new Class<?>[]{int.class}, passengerIndex));
        if (turretController == mob) {
            invokeIfPresent(vehicle, "turretAutoAimFromUuid", new Class<?>[]{String.class, LivingEntity.class}, uuid, mob);
        }
        if (passengerController == mob) {
            invokeIfPresent(vehicle, "passengerWeaponAutoAimFormUuid",
                    new Class<?>[]{String.class, LivingEntity.class}, uuid, mob);
        }
    }

    private static void aimHelicopterBodyWeapon(Entity vehicle, LivingEntity target) {
        Vec3 aimDelta = target.getBoundingBox().getCenter()
                .subtract(vehicle.position().add(0.0D, vehicle.getBbHeight() * 0.55D, 0.0D));
        Vec3 flat = aimDelta.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSqr() < 1.0E-6D) return;
        double desiredPitch = Mth.clamp(
                -Math.toDegrees(Math.atan2(aimDelta.y, flat.length())), -10.0D, 12.0D);
        setHelicopterHover(vehicle, true);
        mouseInput(vehicle, helicopterYawInput(vehicle, flat), attitudePitchInput(vehicle, desiredPitch));
    }

    /** Keeps the helicopter airframe pointed at its assigned combat target. */
    public boolean faceHelicopterTarget(Entity vehicle, LivingEntity target) {
        if (vehicle == null || target == null || !target.isAlive() || !isHelicopter(vehicle)) return false;
        Vec3 aimDelta = target.getBoundingBox().getCenter()
                .subtract(vehicle.position().add(0.0D, vehicle.getBbHeight() * 0.55D, 0.0D));
        Vec3 flat = aimDelta.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSqr() < 1.0E-6D) return false;
        double desiredPitch = hasClearWeaponLine(vehicle, target)
                ? Mth.clamp(-Math.toDegrees(Math.atan2(aimDelta.y, flat.length())), -10.0D, 12.0D)
                : 0.0D;
        setHelicopterHover(vehicle, true);
        mouseInput(vehicle, helicopterYawInput(vehicle, flat), attitudePitchInput(vehicle, desiredPitch));
        return true;
    }

    private static boolean hasClearWeaponLine(Entity vehicle, LivingEntity target) {
        Vec3 from = vehicle.position().add(0.0D, Math.max(1.0D, vehicle.getBbHeight() * 0.6D), 0.0D);
        Vec3 to = target.getBoundingBox().getCenter();
        HitResult hit = vehicle.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) <= 2.25D;
    }

    private static void traceHelicopterWeapon(Mob operator, Entity vehicle, LivingEntity target) {
        if (!isHelicopter(vehicle) || !SuperbWarfareCompatConfig.flightControlTraceEnabled()) return;
        long now = vehicle.level().getGameTime();
        CompoundTag data = operator.getPersistentData();
        if (data.getLong(HELI_LAST_WEAPON_TRACE_TICK)
                + SuperbWarfareCompatConfig.flightControlTraceIntervalTicks() > now) return;
        data.putLong(HELI_LAST_WEAPON_TRACE_TICK, now);
        Object canShoot = invokeWithArgs(vehicle, "canShoot", new Class<?>[]{LivingEntity.class}, operator);
        Object gunData = invokeWithArgs(vehicle, "getGunData", new Class<?>[]{LivingEntity.class}, operator);
        Object directionValue = invokeWithArgs(vehicle, "getShootDirectionForHud",
                new Class<?>[]{LivingEntity.class, float.class}, operator, 1.0F);
        Object shootPosValue = invokeWithArgs(vehicle, "getShootPos",
                new Class<?>[]{LivingEntity.class, float.class}, operator, 1.0F);
        double aimError = Double.NaN;
        if (directionValue instanceof Vec3 direction && shootPosValue instanceof Vec3 shootPos) {
            Vec3 toTarget = target.getBoundingBox().getCenter().subtract(shootPos);
            if (direction.lengthSqr() > 1.0E-8D && toTarget.lengthSqr() > 1.0E-8D) {
                double dot = Mth.clamp(direction.normalize().dot(toTarget.normalize()), -1.0D, 1.0D);
                aimError = Math.toDegrees(Math.acos(dot));
            }
        }
        LOGGER.info("[DS-SW-HELI-WEAPON] tick={} vehicle={} operator={} target={} driver={} turretTarget={} stationTarget={} canShoot={} gun={} aimError={}",
                now, vehicle.getId(), operator.getId(), target.getId(), driver(vehicle) == operator,
                String.valueOf(readMember(vehicle, "aiTurretTargetUUID")),
                String.valueOf(readMember(vehicle, "aiPassengerWeaponTargetUUID")),
                canShoot, gunData == null ? "none" : gunData.getClass().getSimpleName(), decimal(aimError));
    }

    private static Entity entityValue(Object value) {
        return value instanceof Entity entity ? entity : null;
    }

    private static void invokeIfPresent(Entity vehicle, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = findMethod(vehicle.getClass(), name, parameterTypes);
            if (method != null) method.invoke(vehicle, args);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        if (type == null || name == null) return null;
        return METHOD_CACHE.computeIfAbsent(MethodKey.of(type, name, parameterTypes), key -> Optional.ofNullable(findMethodUncached(key.type(), key.name(), key.parameterTypes().toArray(Class<?>[]::new)))).orElse(null);
    }

    private static Method findMethodUncached(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static boolean isSuperbWarfareVehicle(Entity entity) {
        if (entity == null) return false;
        return VEHICLE_CLASS_CACHE.computeIfAbsent(entity.getClass(), SuperbWarfareUnitAdapter::isSuperbWarfareVehicleClass);
    }

    private static boolean isSuperbWarfareVehicleClass(Class<?> entityClass) {
        for (Class<?> type = entityClass; type != null; type = type.getSuperclass()) {
            if (VEHICLE_CLASS_NAME.equals(type.getName())) return true;
        }
        return false;
    }
}
