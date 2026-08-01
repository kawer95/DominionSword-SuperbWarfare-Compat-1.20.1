package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;

public final class SuperbWarfareVehicleAdapter implements DominionVehicleAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VEHICLE_CLASS_NAME = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static final double FACE_HUG_DISTANCE = 6.0D;
    private static final double FACE_HUG_BREAK_DISTANCE = 12.0D;
    private static final Map<FieldKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<MethodKey> BROKEN_METHODS = ConcurrentHashMap.newKeySet();
    private static final Map<Entity, CachedObb> OBB_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<FleetRouteKey, CachedFleetRoute> FLEET_ROUTE_CACHE = new ConcurrentHashMap<>();
    private static final long FLEET_ROUTE_CACHE_TICKS = 8L;
    private static final double FLEET_ROUTE_NODE_SPACING = 3.0D;
    private static final double FLEET_ROUTE_LOOKAHEAD = 12.0D;
    private static final double FLEET_ROUTE_LOOKAHEAD_MIN = 8.0D;
    private static final double FLEET_ROUTE_LOOKAHEAD_MAX = 32.0D;
    private static final double FLEET_TERMINAL_HANDOFF_DISTANCE = 24.0D;
    private static final double FLEET_FORMATION_CORRECTION_LIMIT = 8.0D;
    private static final double FLEET_COMMAND_JITTER_DISTANCE = 5.0D;
    private static final double FLEET_COMMAND_HARD_REPLAN_DISTANCE = 18.0D;
    private static final double FLEET_COMMAND_HARD_REPLAN_DOT = 0.25D;
    private static final double FLEET_COMMAND_SMOOTH_ALPHA = 0.42D;
    private static final double FLEET_TRACK_FOLLOW_COMPRESSION = 0.38D;
    private static final double FLEET_TRACK_BASE_SPACING = 8.0D;
    private static final double FLEET_TRACK_ROAD_ACCESS_BASE = 18.0D;
    private static final double FLEET_TRACK_ROAD_LOOKAHEAD_BASE = 18.0D;
    private static final long FLEET_CONVOY_STUCK_TRACK_TICKS = 45L;
    private static final long FLEET_TRACK_MAX_AGE_TICKS = 140L;
    private static final int FLEET_TRACK_MAX_POINTS = 160;
    private static final String PATHING_NBT = "DominionFleetPathing";
    private static final String PATHING_INDEPENDENT_ROUTE = "independent_route";
    private static final String PATHING_MASTER_PATH = "master_path";
    private static final String PATHING_FLOW_FIELD = "flow_field";
    private static final String ACTION_HELI_TAKEOFF = "superb_helicopter_takeoff";
    private static final String ACTION_HELI_LAND = "superb_helicopter_land";
    private static final String ACTION_HELI_LOW = "superb_helicopter_low_hover";
    private static final String ACTION_HELI_MEDIUM = "superb_helicopter_medium_hover";
    private static final String ACTION_HELI_HIGH = "superb_helicopter_high_hover";
    private static final String ACTION_HELI_HOLD_ALTITUDE = "superb_helicopter_hold_altitude";
    private static final String ACTION_HELI_SET_ABSOLUTE_ALTITUDE = "superb_helicopter_set_absolute_altitude";
    private static final String ACTION_MORTAR_AIM = "superb_mortar_aim";
    private static final String ACTION_MORTAR_FIRE = "superb_mortar_fire_menu";
    private static final String ACTION_MORTAR_CANCEL = "superb_mortar_cancel";
    private static final String HELI_MODE = "DominionSwordSuperbHeliMode";
    private static final String HELI_HOLD_ALTITUDE = "DominionSwordSuperbHeliHoldAltitude";
    private static final String HELI_LOCKED_ALTITUDE = "DominionSwordSuperbHeliLockedAltitude";
    private static final String HELI_NAV_X = "DominionSwordSuperbHeliNavX";
    private static final String HELI_NAV_Y = "DominionSwordSuperbHeliNavY";
    private static final String HELI_NAV_Z = "DominionSwordSuperbHeliNavZ";
    private static final String VEHICLE_WEAPON_LAST_SHOT = "DominionSwordVehicleWeaponLastShot";
    private static final double VEHICLE_WEAPON_MAX_AIM_ERROR_DEGREES = 15.0D;
    private final SuperbWarfareUnitAdapter unitAdapter = new SuperbWarfareUnitAdapter();
    private static final Map<UUID, CachedFleetCommand> FLEET_COMMAND_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HELICOPTER_SELECTION_TRACE_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<TrackPoint>> LEADER_TRACKS = new ConcurrentHashMap<>();
    private static final Map<TrackRoadKey, TrackRoadSegment> TRACK_ROADS = new ConcurrentHashMap<>();
    private static final Map<TrackSafetyKey, CachedTrackSafety> TRACK_SAFETY_CACHE = new ConcurrentHashMap<>();
    private static final long TRACK_SAFETY_CACHE_TICKS = 12L;
    private static volatile long lastCacheCleanupTick = Long.MIN_VALUE;

    public static void cleanupExpiredCaches(long now) {
        if (now - lastCacheCleanupTick < 20L) return;
        lastCacheCleanupTick = now;
        TRACK_ROADS.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        TRACK_SAFETY_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        FLEET_ROUTE_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        FLEET_COMMAND_CACHE.clear();
        LEADER_TRACKS.entrySet().removeIf(entry -> {
            Deque<TrackPoint> track = entry.getValue();
            if (track == null) return true;
            track.removeIf(point -> point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now);
            return track.isEmpty();
        });
        synchronized (OBB_CACHE) {
            OBB_CACHE.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue().tick() + 40L < now);
        }
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public float portraitPitchDegrees(Entity vehicle) {
        // Superb Warfare's vehicle model axis is opposite to the portrait-space X
        // rotation: negative pitch exposes the roof; positive pitch looks upward
        // through the chassis from below.
        return -16.0F;
    }

    @Override
    public float portraitYawDegrees(Entity vehicle) {
        // The renderer's model front is opposite the generic portrait front.
        // Preserve the three-quarter angle while rotating the view to the front.
        return 215.0F;
    }

    @Override
    public boolean supports(Entity vehicle) {
        return isSuperbWarfareVehicle(vehicle);
    }

    @Override
    public boolean selectable(Entity vehicle) {
        return supports(vehicle) && (isHelicopter(vehicle) || hasAnyPassenger(vehicle));
    }

    @Override
    public AABB selectionBounds(Entity vehicle) {
        OrientedBox obb = boardingObb(vehicle);
        AABB footprint = obb == null ? DominionVehicleAdapter.super.selectionBounds(vehicle) : obb.worldAabb().inflate(0.75D, 0.25D, 0.75D);
        if (!isHelicopter(vehicle)) return footprint;
        footprint = wholeVehicleObbBounds(vehicle, footprint).inflate(1.0D, 0.0D, 1.0D);
        double groundY = groundProjectionY(vehicle, footprint.getCenter().x, footprint.getCenter().z);
        AABB projected = new AABB(footprint.minX, groundY + 0.02D, footprint.minZ, footprint.maxX, groundY + 0.18D, footprint.maxZ);
        traceHelicopterSelection(vehicle, projected, groundY);
        return projected;
    }

    @Override
    public AABB portraitBounds(Entity vehicle) {
        // Portrait framing needs the complete physical model, but none of the
        // boarding/selection padding and never the helicopter's ground projection.
        return wholeVehicleObbBounds(vehicle, vehicle.getBoundingBox());
    }

    @Override
    public List<Vec3> selectionCorners(Entity vehicle) {
        if (isHelicopter(vehicle)) {
            AABB footprint = selectionBounds(vehicle);
            double y = footprint.maxY + 0.03D;
            return List.of(new Vec3(footprint.minX, y, footprint.minZ), new Vec3(footprint.maxX, y, footprint.minZ),
                    new Vec3(footprint.maxX, y, footprint.maxZ), new Vec3(footprint.minX, y, footprint.maxZ));
        }
        OrientedBox obb = boardingObb(vehicle);
        return obb == null ? DominionVehicleAdapter.super.selectionCorners(vehicle) : obb.inflate(0.75D, 0.25D, 0.75D).topCorners();
    }

    @Override
    public boolean groundProjectedSelection(Entity vehicle) {
        return isHelicopter(vehicle);
    }

    @Override
    public HealthView health(Entity vehicle) {
        Object current = invokeNoArg(vehicle, "getHealth");
        Object max = invokeNoArg(vehicle, "getMaxHealth");
        if (current instanceof Number health && max instanceof Number maxHealth && maxHealth.floatValue() > 0.0F) {
            return new HealthView(health.floatValue(), maxHealth.floatValue());
        }
        return null;
    }

    @Override
    public List<SeatView> seats(Entity vehicle) {
        Object computed = invokeNoArg(vehicle, "computed");
        List<?> seatInfos = asList(readMember(computed, "seats"));
        if (seatInfos == null) seatInfos = asList(invokeNoArg(computed, "seats"));
        int count = seatInfos != null ? seatInfos.size() : Math.max(0, readInt(vehicle, "maxPassengers", vehicle.getPassengers().size()));
        List<SeatView> result = new ArrayList<>();
        int turret = readInt(computed, "turretControllerIndex", -1);
        int passengerWeapon = readInt(computed, "passengerWeaponStationControllerIndex", -1);
        for (int i = 0; i < count; i++) {
            Object seat = seatInfos != null && i < seatInfos.size() ? seatInfos.get(i) : null;
            String type = seatType(vehicle, seat, i, turret, passengerWeapon);
            Entity passenger = readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, i));
            result.add(new SeatView(i, type, passenger));
        }
        return result;
    }

    @Override
    public boolean hasDriver(Entity vehicle) {
        return driver(vehicle) != null;
    }

    @Override
    public boolean select(ServerPlayer player, Entity vehicle) {
        Entity driver = driver(vehicle);
        if (driver instanceof Mob mob) unitAdapter.select(player, mob);
        return true;
    }

    @Override
    public boolean release(ServerPlayer player, Entity vehicle) {
        Entity driver = driver(vehicle);
        if (driver instanceof Mob mob) return unitAdapter.release(player, mob);
        return true;
    }

    @Override
    public boolean board(ServerPlayer player, Mob unit, Entity vehicle, int seat, boolean force) {
        if (!supports(vehicle) || seat < 0) return false;
        Entity occupant = readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, seat));
        if (occupant != null) {
            if (!force) return false;
            com.arxyt.dominionsword.api.VehicleDismounts.dismount(vehicle, occupant);
        }
        Object previousOverride = readMember(vehicle, "entityIndexOverride");
        boolean overrideSet = writeMember(vehicle, "entityIndexOverride", (Function<Entity, Integer>) entity -> entity == unit ? seat : -1);
        boolean ridden;
        try {
            ridden = unit.getVehicle() == vehicle || unit.startRiding(vehicle, true);
        } finally {
            if (overrideSet) writeMember(vehicle, "entityIndexOverride", previousOverride);
        }
        if (!ridden) return false;
        Entity seated = readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, seat));
        if (seated == unit) return true;
        return Boolean.TRUE.equals(invoke(vehicle, "changeSeat", new Class<?>[]{Entity.class, int.class}, unit, seat));
    }

    @Override
    public Vec3 boardingPosition(Mob unit, Entity vehicle) {
        OrientedBox obb = boardingObb(vehicle);
        if (obb == null) return DominionVehicleAdapter.super.boardingPosition(unit, vehicle);
        Vec3 point = obb.inflate(1.0D, 0.25D, 1.0D).closestPoint(unit.position());
        return new Vec3(point.x, vehicle.getY(), point.z);
    }

    @Override
    public boolean canBoardFrom(Mob unit, Entity vehicle) {
        OrientedBox obb = boardingObb(vehicle);
        if (obb == null) return DominionVehicleAdapter.super.canBoardFrom(unit, vehicle);
        OrientedBox access = obb.inflate(1.75D, 1.0D, 1.75D);
        return access.intersects(unit.getBoundingBox()) || access.horizontalDistanceSqr(unit.position()) <= 9.0D;
    }

    @Override
    public boolean dismount(ServerPlayer player, Entity vehicle, int seat) {
        Entity passenger = readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, seat));
        if (passenger == null) return false;
        return com.arxyt.dominionsword.api.VehicleDismounts.dismount(vehicle, passenger);
    }

    @Override
    public boolean dismountAll(ServerPlayer player, Entity vehicle) {
        boolean any = false;
        for (SeatView seat : seats(vehicle)) {
            if (seat.passenger() != null) {
                any |= com.arxyt.dominionsword.api.VehicleDismounts.dismount(vehicle, seat.passenger());
            }
        }
        return any;
    }

    @Override
    public boolean move(ServerPlayer player, Entity vehicle, Vec3 target) {
        Entity driver = driver(vehicle);
        if (driver instanceof Mob mob) return unitAdapter.move(player, mob, target);
        return false;
    }

    @Override
    public List<ActionView> actions(Entity vehicle) {
        if (vehicle instanceof MortarEntity) {
            return List.of(
                    new ActionView(ACTION_MORTAR_AIM, "@menu.dominionsword_superbwarfare_compat.mortar.aim"),
                    new ActionView(ACTION_MORTAR_FIRE, "@menu.dominionsword_superbwarfare_compat.mortar.fire"),
                    new ActionView(ACTION_MORTAR_CANCEL, "@menu.dominionsword_superbwarfare_compat.mortar.cancel")
            );
        }
        if (!isHelicopter(vehicle) || !(driver(vehicle) instanceof Mob mob)) return List.of();
        String mode = mob.getPersistentData().getString(HELI_MODE);
        if (!isHelicopterFlying(vehicle) || "LANDING".equals(mode) || "LANDED".equals(mode) || mode.isBlank()) {
            return List.of(new ActionView(ACTION_HELI_TAKEOFF, "起飞"));
        }
        return List.of(
                new ActionView(ACTION_HELI_LAND, "降落"),
                new ActionView(ACTION_HELI_LOW, "低空悬停（10格）"),
                new ActionView(ACTION_HELI_MEDIUM, "中低空悬停（20格）"),
                new ActionView(ACTION_HELI_HIGH, "高空悬停（30格）"),
                ActionView.toggle(ACTION_HELI_HOLD_ALTITUDE, "维持高度", mob.getPersistentData().getBoolean(HELI_HOLD_ALTITUDE)),
                ActionView.numberInput(ACTION_HELI_SET_ABSOLUTE_ALTITUDE, "手动设置绝对高度")
        );
    }

    @Override
    public boolean performAction(ServerPlayer player, Entity vehicle, String actionId) {
        if (vehicle instanceof MortarEntity mortar) {
            if (ACTION_MORTAR_AIM.equals(actionId)) { MortarCommands.beginAim(player, mortar); return true; }
            if (ACTION_MORTAR_FIRE.equals(actionId)) { MortarCommands.requestFire(player, mortar); return true; }
            return ACTION_MORTAR_CANCEL.equals(actionId);
        }
        if (!isHelicopter(vehicle) || !(driver(vehicle) instanceof Mob mob)) return false;
        if (ACTION_HELI_TAKEOFF.equals(actionId)) {
            setHelicopterTask(mob, vehicle, "TAKEOFF");
            return true;
        }
        if (ACTION_HELI_LAND.equals(actionId)) {
            setHelicopterTask(mob, vehicle, "LANDING");
            return true;
        }
        if (ACTION_HELI_HIGH.equals(actionId)) {
            setHelicopterTask(mob, vehicle, "HIGH_HOVER");
            return true;
        }
        if (ACTION_HELI_LOW.equals(actionId)) {
            setHelicopterTask(mob, vehicle, "LOW_HOVER");
            return true;
        }
        if (ACTION_HELI_MEDIUM.equals(actionId)) {
            setHelicopterTask(mob, vehicle, "MEDIUM_HOVER");
            return true;
        }
        if (ACTION_HELI_HOLD_ALTITUDE.equals(actionId)) {
            boolean hold = !mob.getPersistentData().getBoolean(HELI_HOLD_ALTITUDE);
            mob.getPersistentData().putBoolean(HELI_HOLD_ALTITUDE, hold);
            if (hold) mob.getPersistentData().putDouble(HELI_LOCKED_ALTITUDE, vehicle.getY());
            else mob.getPersistentData().remove(HELI_LOCKED_ALTITUDE);
            return true;
        }
        return false;
    }

    @Override
    public boolean performAction(ServerPlayer player, Entity vehicle, String actionId, String value) {
        if (ACTION_HELI_SET_ABSOLUTE_ALTITUDE.equals(actionId) && isHelicopter(vehicle) && driver(vehicle) instanceof Mob mob) {
            try {
                int altitude = Mth.clamp(Integer.parseInt(value), -64, 500);
                mob.getPersistentData().putBoolean(HELI_HOLD_ALTITUDE, true);
                mob.getPersistentData().putDouble(HELI_LOCKED_ALTITUDE, altitude);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return performAction(player, vehicle, actionId);
    }

    @Override
    public void prepareMoveRoute(ServerPlayer player, Entity vehicle, Vec3 target) {
        Entity driver = driver(vehicle);
        if (driver instanceof Mob mob) {
            int radius = Math.max(1, (int) Math.ceil(Math.max(selectionBoundsStatic(vehicle).getXsize(), selectionBoundsStatic(vehicle).getZsize()) * 0.5D + 0.75D));
            unitAdapter.prepareMoveRoute(mob, target, radius, Set.of(vehicle.getUUID()));
        }
    }

    @Override
    public List<Vec3> plannedMoveRoute(ServerPlayer player, Entity vehicle, Vec3 target) {
        if (vehicle == null || target == null || !supports(vehicle)) return List.of();
        Entity driver = driver(vehicle);
        if (!(driver instanceof Mob mob)) return List.of(vehicle.position(), target);
        List<Vec3> route = unitAdapter.currentPreparedRoute(mob, target, Math.max(1, (int) Math.ceil(Math.max(selectionBoundsStatic(vehicle).getXsize(), selectionBoundsStatic(vehicle).getZsize()) * 0.5D + 0.75D)), Set.of(vehicle.getUUID()));
        return normalizedPlannedRoute(vehicle.position(), target, route);
    }

    @Override
    public boolean moveFleet(ServerPlayer player, List<Entity> vehicles, List<Vec3> targets) {
        List<FleetMove> moves = assignedFleetMoves(vehicles, targets);
        if (moves.isEmpty()) return false;
        moves.sort(Comparator.comparingDouble(FleetMove::distanceSqr));
        String mode = pathingMode(moves);
        if (PATHING_FLOW_FIELD.equals(mode)) {
            int fleetRadius = fleetVoxelRadius(moves);
            Set<UUID> ignored = fleetVehicleIdSet(moves);
            unitAdapter.primeFlowField(centerOfTargets(moves), fleetRadius, ignored, moves.stream().map(FleetMove::driver).toList());
            boolean any = false;
            for (FleetMove move : moves) any |= unitAdapter.move(player, move.driver(), move.target());
            return any;
        }
        if (!PATHING_MASTER_PATH.equals(mode)) {
            List<Vec3> commandTargets = opportunisticConvoyTargets(moves);
            SuperbWarfareUnitAdapter.primeFleetReservations(moves.stream().map(FleetMove::vehicle).toList(), commandTargets);
            boolean any = false;
            for (int i = 0; i < moves.size(); i++) any |= unitAdapter.move(player, moves.get(i).driver(), commandTargets.get(i));
            return any;
        }
        List<Vec3> commandTargets = virtualLeaderTargets(moves);
        StableFleetCommands stableCommands = smoothFleetCommandTargets(moves, commandTargets);
        if (stableCommands.brakeForReplan()) {
            for (FleetMove move : moves) stopVehicle(move.vehicle());
            return true;
        }
        commandTargets = stableCommands.targets();
        SuperbWarfareUnitAdapter.primeFleetReservations(moves.stream().map(FleetMove::vehicle).toList(), commandTargets);
        boolean any = false;
        for (int i = 0; i < moves.size(); i++) any |= unitAdapter.move(player, moves.get(i).driver(), commandTargets.get(i));
        return any;
    }

    public void tickHelicopterAutopilot(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (Entity vehicle : level.getAllEntities()) {
                if (!isHelicopter(vehicle)) continue;
                Entity driver = driver(vehicle);
                if (!(driver instanceof Mob mob)) {
                    if (isHelicopterFlying(vehicle)) stopVehicle(vehicle);
                    continue;
                }
                String mode = mob.getPersistentData().getString(HELI_MODE);
                if (mode.isBlank() || "LANDED".equals(mode)) continue;
                unitAdapter.move(null, mob, helicopterTaskTarget(mob, vehicle));
            }
        }
    }

    private static void setHelicopterTask(Mob mob, Entity vehicle, String mode) {
        mob.getPersistentData().putString(HELI_MODE, mode);
        if ("TAKEOFF".equals(mode)) {
            mob.getPersistentData().remove(HELI_HOLD_ALTITUDE);
            mob.getPersistentData().remove(HELI_LOCKED_ALTITUDE);
        }
        mob.getPersistentData().putDouble(HELI_NAV_X, vehicle.getX());
        mob.getPersistentData().putDouble(HELI_NAV_Y, vehicle.getY());
        mob.getPersistentData().putDouble(HELI_NAV_Z, vehicle.getZ());
    }

    private static Vec3 helicopterTaskTarget(Mob mob, Entity vehicle) {
        double x = mob.getPersistentData().contains(HELI_NAV_X) ? mob.getPersistentData().getDouble(HELI_NAV_X) : vehicle.getX();
        double y = mob.getPersistentData().contains(HELI_NAV_Y) ? mob.getPersistentData().getDouble(HELI_NAV_Y) : vehicle.getY();
        double z = mob.getPersistentData().contains(HELI_NAV_Z) ? mob.getPersistentData().getDouble(HELI_NAV_Z) : vehicle.getZ();
        return new Vec3(x, y, z);
    }

    @Override
    public void prepareFleetMoveRoutes(ServerPlayer player, List<Entity> vehicles, List<Vec3> targets) {
        List<FleetMove> moves = assignedFleetMoves(vehicles, targets);
        if (moves.isEmpty()) return;
        int fleetRadius = fleetVoxelRadius(moves);
        Set<UUID> ignored = fleetVehicleIdSet(moves);
        for (FleetMove move : moves) unitAdapter.prepareMoveRoute(move.driver(), move.target(), fleetRadius, ignored);
    }

    @Override
    public List<List<Vec3>> plannedFleetMoveRoutes(ServerPlayer player, List<Entity> vehicles, List<Vec3> targets) {
        List<FleetMove> moves = assignedFleetMoves(vehicles, targets);
        if (moves.isEmpty()) return List.of();
        String mode = pathingMode(moves);
        int fleetRadius = fleetVoxelRadius(moves);
        Set<UUID> ignored = fleetVehicleIdSet(moves);
        List<List<Vec3>> routes = new ArrayList<>(moves.size());
        for (FleetMove move : moves) {
            List<Vec3> route = unitAdapter.currentPreparedRoute(move.driver(), move.target(), fleetRadius, ignored);
            routes.add(normalizedPlannedRoute(move.vehicle().position(), move.target(), route));
        }
        return routes;
    }

    @Override
    public boolean attack(ServerPlayer player, Entity vehicle, LivingEntity target) {
        List<WeaponOperator> weaponOperators = assignPassengerTargets(player, vehicle, target);
        assignVehicleTarget(vehicle, target);
        aimOfficialControllers(vehicle, target);
        fireOccupiedVehicleWeapons(vehicle, target, weaponOperators);

        /*
         * A visible target used to enter the stationary-fire branch before the
         * helicopter flight controller was called. Grounded helicopters therefore
         * received an attack task but never started. Let the flight controller
         * perform its TAKEOFF -> LOW_HOVER transition first while preserving the
         * combat target.
         */
        Entity helicopterDriver = driver(vehicle);
        String helicopterMode = helicopterDriver instanceof Mob mob
                ? mob.getPersistentData().getString(HELI_MODE) : "";
        if (isHelicopter(vehicle) && (!isHelicopterFlying(vehicle)
                || helicopterMode.isBlank()
                || "LANDED".equals(helicopterMode)
                || "TAKEOFF".equals(helicopterMode))) {
            boolean moved = move(player, vehicle, target.position());
            unitAdapter.faceHelicopterTarget(vehicle, target);
            return moved || !weaponOperators.isEmpty();
        }

        if (canSee(vehicle, target)) {
            Vec3 away = vehicle.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() <= 1.0E-4D) {
                stopVehicle(vehicle);
                unitAdapter.faceHelicopterTarget(vehicle, target);
                return true;
            }
            double distance = Math.sqrt(away.lengthSqr());
            if (distance >= FACE_HUG_DISTANCE) {
                stopVehicle(vehicle);
                unitAdapter.faceHelicopterTarget(vehicle, target);
                return true;
            }
            boolean moved = move(player, vehicle, target.position().add(away.normalize().scale(FACE_HUG_BREAK_DISTANCE)));
            weaponOperators = assignPassengerTargets(player, vehicle, target);
            assignVehicleTarget(vehicle, target);
            aimOfficialControllers(vehicle, target);
            fireOccupiedVehicleWeapons(vehicle, target, weaponOperators);
            unitAdapter.faceHelicopterTarget(vehicle, target);
            return moved;
        }
        boolean moved = move(player, vehicle, target.position());
        if (isHelicopter(vehicle)) unitAdapter.faceHelicopterTarget(vehicle, target);
        return moved;
    }

    private List<WeaponOperator> assignPassengerTargets(ServerPlayer player, Entity vehicle, LivingEntity target) {
        List<WeaponOperator> weaponOperators = new ArrayList<>();
        for (SeatView seat : seats(vehicle)) {
            if (seat.passenger() instanceof Mob mob) {
                mob.setTarget(target);
                mob.lookAt(target, 180.0F, 180.0F);
                mob.getLookControl().setLookAt(target, 180.0F, 180.0F);
                List<String> weapons = seatWeaponNames(vehicle, seat.index());
                if (weapons.isEmpty()) {
                    unitAdapter.attack(player, mob, target);
                } else {
                    // Let the unit adapter perform body/turret aiming, then release the
                    // Mob target so Superb Warfare's own tick cannot also fire the same
                    // selected weapon and the passenger's handheld AI cannot mask it.
                    unitAdapter.attack(player, mob, target);
                    mob.setTarget(null);
                    weaponOperators.add(new WeaponOperator(seat.index(), mob, weapons));
                }
            }
        }
        return weaponOperators;
    }

    private static List<String> seatWeaponNames(Entity vehicle, int seatIndex) {
        Object computed = invokeNoArg(vehicle, "computed");
        List<?> seatInfos = asList(readMember(computed, "seats"));
        if (seatInfos == null) seatInfos = asList(invokeNoArg(computed, "seats"));
        if (seatInfos == null || seatIndex < 0 || seatIndex >= seatInfos.size()) return List.of();
        List<?> rawWeapons = asList(invokeNoArg(seatInfos.get(seatIndex), "weapons"));
        if (rawWeapons == null || rawWeapons.isEmpty()) return List.of();
        List<String> weapons = new ArrayList<>(rawWeapons.size());
        for (Object raw : rawWeapons) {
            if (raw != null && !raw.toString().isBlank()) weapons.add(raw.toString());
        }
        return weapons;
    }

    private static void fireOccupiedVehicleWeapons(Entity vehicle, LivingEntity target, List<WeaponOperator> operators) {
        long now = vehicle.level().getGameTime();
        for (WeaponOperator operator : operators) {
            int selected = numberValue(invoke(vehicle, "getWeaponIndex", new Class<?>[]{int.class}, operator.seatIndex()), 0);
            if (selected < 0 || selected >= operator.weapons().size()) continue;
            if (!Boolean.TRUE.equals(invoke(vehicle, "canShoot", new Class<?>[]{LivingEntity.class}, operator.mob()))) continue;

            int rpm = Math.max(1, numberValue(invoke(vehicle, "vehicleWeaponRpm",
                    new Class<?>[]{LivingEntity.class}, operator.mob()), 60));
            long interval = Math.max(1L, (long) Math.ceil(1200.0D / rpm));
            String lastShotKey = VEHICLE_WEAPON_LAST_SHOT + operator.seatIndex();
            if (now - vehicle.getPersistentData().getLong(lastShotKey) < interval) continue;

            Object rawDirection = invoke(vehicle, "getShootDirectionForHud",
                    new Class<?>[]{Entity.class, float.class}, operator.mob(), 1.0F);
            Object rawShootPos = invoke(vehicle, "getShootPos",
                    new Class<?>[]{Entity.class, float.class}, operator.mob(), 1.0F);
            if (!(rawDirection instanceof Vec3 direction) || !(rawShootPos instanceof Vec3 shootPos)) continue;
            Vec3 toTarget = target.getBoundingBox().getCenter().subtract(shootPos);
            if (direction.lengthSqr() < 1.0E-8D || toTarget.lengthSqr() < 1.0E-8D) continue;
            double dot = Mth.clamp(direction.normalize().dot(toTarget.normalize()), -1.0D, 1.0D);
            double aimError = Math.toDegrees(Math.acos(dot));
            if (aimError > VEHICLE_WEAPON_MAX_AIM_ERROR_DEGREES || !hasWeaponLine(vehicle, shootPos, target)) continue;

            if (invokeVoid(vehicle, "vehicleShoot",
                    new Class<?>[]{LivingEntity.class, UUID.class, Vec3.class},
                    operator.mob(), target.getUUID(), null)) {
                vehicle.getPersistentData().putLong(lastShotKey, now);
            }
        }
    }

    private static boolean hasWeaponLine(Entity vehicle, Vec3 shootPos, LivingEntity target) {
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        BlockHitResult hit = vehicle.level().clip(new ClipContext(
                shootPos, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(targetCenter) <= 2.25D;
    }

    private static void assignVehicleTarget(Entity vehicle, LivingEntity target) {
        String uuid = target.getStringUUID();
        writeMember(vehicle, "aiTurretTargetUUID", uuid);
        writeMember(vehicle, "aiPassengerWeaponTargetUUID", uuid);
    }

    private static void aimOfficialControllers(Entity vehicle, LivingEntity target) {
        String uuid = target.getStringUUID();
        Object computed = invokeNoArg(vehicle, "computed");
        int turret = readInt(computed, "turretControllerIndex", readInt(vehicle, "turretControllerIndex", -1));
        int passengerWeapon = readInt(computed, "passengerWeaponStationControllerIndex", readInt(vehicle, "passengerWeaponStationControllerIndex", -1));
        Entity turretController = turret >= 0 ? readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, turret)) : null;
        Entity passengerWeaponController = passengerWeapon >= 0 ? readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, passengerWeapon)) : null;
        if (turretController instanceof LivingEntity living) invoke(vehicle, "turretAutoAimFromUuid", new Class<?>[]{String.class, LivingEntity.class}, uuid, living);
        if (passengerWeaponController instanceof LivingEntity living) invoke(vehicle, "passengerWeaponAutoAimFormUuid", new Class<?>[]{String.class, LivingEntity.class}, uuid, living);
    }

    private static int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static void stopVehicle(Entity vehicle) {
        invoke(vehicle, "processInput", new Class<?>[]{short.class}, (short) 0);
    }

    private static boolean canSee(Entity vehicle, LivingEntity target) {
        Vec3 from = vehicle.position().add(0.0D, vehicle.getBbHeight() * 0.65D, 0.0D);
        Vec3 to = target.getEyePosition();
        BlockHitResult hit = vehicle.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(BlockPos.containing(to));
    }

    private record FleetMove(Entity vehicle, Mob driver, Vec3 target, double distanceSqr) {}
    private record FleetCandidate(Entity vehicle, Mob driver) {}
    private record WeaponOperator(int seatIndex, Mob mob, List<String> weapons) {}

    private static List<Vec3> normalizedPlannedRoute(Vec3 start, Vec3 target, List<Vec3> route) {
        List<Vec3> result = new ArrayList<>();
        if (start != null) result.add(start);
        if (route != null) {
            for (Vec3 point : route) {
                if (point == null) continue;
                if (result.isEmpty() || flatDistanceSqr(result.get(result.size() - 1), point) > 0.25D) result.add(point);
            }
        }
        if (target != null && (result.isEmpty() || flatDistanceSqr(result.get(result.size() - 1), target) > 0.25D)) result.add(target);
        return result;
    }

    private static List<FleetMove> assignedFleetMoves(List<Entity> vehicles, List<Vec3> targets) {
        int count = Math.min(vehicles.size(), targets.size());
        List<FleetCandidate> candidates = new ArrayList<>(count);
        List<Vec3> validTargets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Entity vehicle = vehicles.get(i);
            Vec3 target = targets.get(i);
            Entity driver = driver(vehicle);
            if (driver instanceof Mob mob && isSuperbWarfareVehicle(vehicle) && target != null) {
                candidates.add(new FleetCandidate(vehicle, mob));
                validTargets.add(target);
            }
        }
        int n = Math.min(candidates.size(), validTargets.size());
        if (n <= 0) return List.of();
        List<FleetMove> moves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            FleetCandidate candidate = candidates.get(i);
            Vec3 target = validTargets.get(i);
            moves.add(new FleetMove(candidate.vehicle(), candidate.driver(), target, candidate.vehicle().position().distanceToSqr(target)));
        }
        return moves;
    }

    private static int[] optimalTargetAssignment(List<FleetCandidate> candidates, List<Vec3> targets, int n) {
        int masks = 1 << n;
        double[] dp = new double[masks];
        int[] choice = new int[masks];
        java.util.Arrays.fill(dp, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(choice, -1);
        dp[0] = 0.0D;
        for (int mask = 0; mask < masks; mask++) {
            int vehicleIndex = Integer.bitCount(mask);
            if (vehicleIndex >= n || Double.isInfinite(dp[mask])) continue;
            Entity vehicle = candidates.get(vehicleIndex).vehicle();
            for (int targetIndex = 0; targetIndex < n; targetIndex++) {
                int bit = 1 << targetIndex;
                if ((mask & bit) != 0) continue;
                int next = mask | bit;
                double cost = dp[mask] + flatDistanceSqr(vehicle.position(), targets.get(targetIndex));
                if (cost < dp[next]) {
                    dp[next] = cost;
                    choice[mask] = targetIndex;
                }
            }
        }
        int[] result = new int[n];
        int mask = 0;
        for (int i = 0; i < n; i++) {
            int targetIndex = choice[mask];
            if (targetIndex < 0) return greedyTargetAssignment(candidates, targets, n);
            result[i] = targetIndex;
            mask |= 1 << targetIndex;
        }
        return result;
    }

    private static int[] relativeTargetAssignment(List<FleetCandidate> candidates, List<Vec3> targets, int n) {
        Vec3 currentCenter = centerOfCandidates(candidates, n);
        Vec3 targetCenter = centerOfTargetList(targets, n);
        Vec3 forward = flatOrFallback(targetCenter.subtract(currentCenter), targetSpreadAxis(targets, n));
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double targetSideSpread = projectionSpread(targets, n, targetCenter, right);
        double targetForwardSpread = projectionSpread(targets, n, targetCenter, forward);
        Vec3 primary = targetSideSpread >= targetForwardSpread * 0.75D ? right : forward;
        Vec3 secondary = primary == right ? forward : right;

        List<Integer> vehicleOrder = new ArrayList<>(n);
        List<Integer> targetOrder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            vehicleOrder.add(i);
            targetOrder.add(i);
        }
        vehicleOrder.sort((a, b) -> compareRelative(candidates.get(a).vehicle().position(), candidates.get(b).vehicle().position(), currentCenter, primary, secondary));
        targetOrder.sort((a, b) -> compareRelative(targets.get(a), targets.get(b), targetCenter, primary, secondary));

        int[] result = new int[n];
        for (int rank = 0; rank < n; rank++) {
            result[vehicleOrder.get(rank)] = targetOrder.get(rank);
        }
        return result;
    }

    private static int compareRelative(Vec3 a, Vec3 b, Vec3 center, Vec3 primary, Vec3 secondary) {
        double pa = dot2d(a.subtract(center), primary);
        double pb = dot2d(b.subtract(center), primary);
        int primaryCompare = Double.compare(pa, pb);
        if (primaryCompare != 0) return primaryCompare;
        double sa = dot2d(a.subtract(center), secondary);
        double sb = dot2d(b.subtract(center), secondary);
        return Double.compare(sa, sb);
    }

    private static Vec3 centerOfCandidates(List<FleetCandidate> candidates, int n) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (int i = 0; i < n; i++) {
            Vec3 position = candidates.get(i).vehicle().position();
            x += position.x;
            y += position.y;
            z += position.z;
        }
        double count = Math.max(1, n);
        return new Vec3(x / count, y / count, z / count);
    }

    private static Vec3 centerOfTargetList(List<Vec3> targets, int n) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (int i = 0; i < n; i++) {
            Vec3 target = targets.get(i);
            x += target.x;
            y += target.y;
            z += target.z;
        }
        double count = Math.max(1, n);
        return new Vec3(x / count, y / count, z / count);
    }

    private static Vec3 targetSpreadAxis(List<Vec3> targets, int n) {
        Vec3 best = Vec3.ZERO;
        double bestDistance = 0.0D;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Vec3 delta = targets.get(j).subtract(targets.get(i)).multiply(1.0D, 0.0D, 1.0D);
                double distance = delta.lengthSqr();
                if (distance > bestDistance) {
                    bestDistance = distance;
                    best = delta;
                }
            }
        }
        return bestDistance > 1.0E-6D ? best.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
    }

    private static double projectionSpread(List<Vec3> points, int n, Vec3 center, Vec3 axis) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double value = dot2d(points.get(i).subtract(center), axis);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return Double.isInfinite(min) ? 0.0D : max - min;
    }

    private static int[] greedyTargetAssignment(List<FleetCandidate> candidates, List<Vec3> targets, int n) {
        int[] result = new int[n];
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            Entity vehicle = candidates.get(i).vehicle();
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (used[j]) continue;
                double distance = flatDistanceSqr(vehicle.position(), targets.get(j));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = j;
                }
            }
            if (best < 0) best = i;
            used[best] = true;
            result[i] = best;
        }
        return result;
    }

    private List<Vec3> virtualLeaderTargets(List<FleetMove> moves) {
        if (moves.size() <= 1) return moves.stream().map(FleetMove::target).toList();
        Vec3 currentCenter = centerOfVehicles(moves);
        Vec3 targetCenter = centerOfTargets(moves);
        FleetMove leader = representativeLeader(moves, currentCenter);
        if (leader == null) return moves.stream().map(FleetMove::target).toList();
        recordLeaderTrack(leader.vehicle());
        int fleetRadius = fleetVoxelRadius(moves);

        List<Vec3> route = fleetRoute(moves, leader, currentCenter, targetCenter);
        Vec3 leaderWaypoint = routeWaypoint(route, currentCenter, targetCenter, leader.vehicle(), fleetRadius);
        if (leaderWaypoint == null || leaderWaypoint.distanceToSqr(targetCenter) <= 9.0D) {
            return moves.stream().map(FleetMove::target).toList();
        }

        Vec3 referenceDirection = flatOrFallback(targetCenter.subtract(currentCenter), leaderWaypoint.subtract(currentCenter));
        Vec3 routeDirection = flatOrFallback(leaderWaypoint.subtract(currentCenter), referenceDirection);
        double formationRadius = formationRadius(moves, targetCenter);
        double terminalHandoff = FLEET_TERMINAL_HANDOFF_DISTANCE + formationRadius;
        if (flatDistanceSqr(currentCenter, targetCenter) <= terminalHandoff * terminalHandoff
                || flatDistanceSqr(leaderWaypoint, targetCenter) <= terminalHandoff * terminalHandoff) {
            return moves.stream().map(FleetMove::target).toList();
        }
        Set<UUID> ignored = fleetVehicleIdSet(moves);
        List<Vec3> result = new ArrayList<>(moves.size());
        Map<TrackRoadKey, Integer> roadUsers = new ConcurrentHashMap<>();
        for (FleetMove move : moves) {
            if (flatDistanceSqr(move.vehicle().position(), move.target()) <= terminalHandoff * terminalHandoff) {
                result.add(move.target());
                continue;
            }
            Vec3 offset = move.target().subtract(targetCenter);
            Vec3 rotatedOffset = rotateHorizontalOffset(offset, referenceDirection, routeDirection);
            Vec3 shifted = leaderWaypoint.add(rotatedOffset.x, 0.0D, rotatedOffset.z);
            shifted = applyFormationErrorCorrection(shifted, move.vehicle().position(), currentCenter, rotatedOffset, routeDirection);
            if (shifted.distanceToSqr(move.target()) <= 36.0D) result.add(move.target());
            else {
                FormationCommand command = elasticFormationCommand(move, shifted, leaderWaypoint, rotatedOffset, fleetRadius, ignored);
                Vec3 trackTarget = command.compression() <= FLEET_TRACK_FOLLOW_COMPRESSION && move.vehicle() != leader.vehicle() && !unitAdapter.canDriveDirect(move.driver(), move.target(), fleetRadius, ignored)
                        ? trackRoadTarget(move, fleetRadius, ignored, roadUsers)
                        : null;
                result.add(isSafeTrackTarget(move, trackTarget, fleetRadius, ignored) ? trackTarget : command.target());
            }
        }
        return result;
    }

    private FormationCommand elasticFormationCommand(FleetMove move, Vec3 desired, Vec3 anchor, Vec3 desiredOffset, int fleetRadius, Set<UUID> ignored) {
        Vec3 resolved = unitAdapter.fleetCommandTarget(move.driver(), desired, anchor, fleetRadius, ignored);
        double wanted = flatLength(desiredOffset);
        double actual = flatDistance(resolved, anchor);
        double compression = wanted < 1.0E-6D ? 1.0D : Mth.clamp(actual / wanted, 0.0D, 1.0D);
        return new FormationCommand(resolved, compression);
    }

    private static void recordLeaderTrack(Entity leader) {
        long now = leader.level().getGameTime();
        Deque<TrackPoint> track = LEADER_TRACKS.computeIfAbsent(leader.getUUID(), ignored -> new ArrayDeque<>());
        Vec3 pos = leader.position();
        TrackPoint last = track.peekLast();
        if (last == null || flatDistanceSqr(last.position(), pos) >= 1.0D) {
            track.addLast(new TrackPoint(pos, now));
            refreshTrackRoad(track, now);
        }
        while (track.size() > FLEET_TRACK_MAX_POINTS) track.removeFirst();
        track.removeIf(point -> point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now);
        TRACK_ROADS.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private static void refreshTrackRoad(Deque<TrackPoint> track, long now) {
        if (track.size() < 3) return;
        List<TrackPoint> points = new ArrayList<>(track);
        int index = points.size() - 2;
        TrackRoadKey key = trackRoadKey(points, index);
        TRACK_ROADS.compute(key, (ignored, old) -> {
            if (old == null || old.points().size() < 2) {
                return new TrackRoadSegment(trimTrackRoadPoints(points), now + FLEET_TRACK_MAX_AGE_TICKS);
            }
            List<TrackPoint> merged = old.points();
            TrackPoint last = merged.get(merged.size() - 1);
            for (int i = Math.max(0, index - 2); i < points.size(); i++) {
                TrackPoint point = points.get(i);
                if (flatDistanceSqr(last.position(), point.position()) < 1.0D) continue;
                merged.add(point);
                last = point;
            }
            while (merged.size() > FLEET_TRACK_MAX_POINTS) merged.remove(0);
            return new TrackRoadSegment(merged, now + FLEET_TRACK_MAX_AGE_TICKS);
        });
    }

    private static List<TrackPoint> trimTrackRoadPoints(List<TrackPoint> points) {
        int from = Math.max(0, points.size() - 24);
        return new ArrayList<>(points.subList(from, points.size()));
    }

    private List<Vec3> opportunisticConvoyTargets(List<FleetMove> moves) {
        if (moves.size() <= 1) return moves.stream().map(FleetMove::target).toList();
        for (FleetMove move : moves) recordLeaderTrack(move.vehicle());
        int fleetRadius = fleetVoxelRadius(moves);
        Set<UUID> ignored = fleetVehicleIdSet(moves);

        List<Vec3> result = new ArrayList<>(moves.size());
        Map<TrackRoadKey, Integer> roadUsers = new ConcurrentHashMap<>();
        for (FleetMove follower : moves) {
            if (unitAdapter.canDriveDirect(follower.driver(), follower.target(), fleetRadius, ignored)) {
                result.add(follower.target());
                continue;
            }
            Vec3 target = follower.target();
            Vec3 trackTarget = trackRoadTarget(follower, fleetRadius, ignored, roadUsers);
            if (isUsefulTrackRoadTarget(follower, trackTarget) && isSafeTrackTarget(follower, trackTarget, fleetRadius, ignored)) target = trackTarget;
            result.add(target);
        }
        return result;
    }

    private Vec3 trackRoadTarget(FleetMove follower, int fleetRadius, Set<UUID> ignored, Map<TrackRoadKey, Integer> roadUsers) {
        Entity vehicle = follower.vehicle();
        Vec3 position = vehicle.position();
        Vec3 toFinal = follower.target().subtract(position).multiply(1.0D, 0.0D, 1.0D);
        if (toFinal.lengthSqr() < 4.0D) return null;
        toFinal = toFinal.normalize();
        double footprint = vehicleFootprintSize(vehicle);
        double accessRadiusSqr = Math.pow(Math.max(FLEET_TRACK_ROAD_ACCESS_BASE, footprint * 1.8D), 2.0D);
        double lookahead = Math.max(FLEET_TRACK_ROAD_LOOKAHEAD_BASE, footprint * 1.35D);
        long now = vehicle.level().getGameTime();
        TrackRoadMatch best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Map.Entry<TrackRoadKey, TrackRoadSegment> entry : TRACK_ROADS.entrySet()) {
            TrackRoadSegment segment = entry.getValue();
            if (segment == null || segment.expiresAt() < now || segment.points().size() < 3) continue;
            List<TrackPoint> points = segment.points();
            int closest = -1;
            double closestDistanceSqr = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                TrackPoint point = points.get(i);
                if (point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now) continue;
                double distanceSqr = flatDistanceSqr(position, point.position());
                if (distanceSqr < closestDistanceSqr) {
                    closestDistanceSqr = distanceSqr;
                    closest = i;
                }
            }
            if (closest < 0 || closestDistanceSqr > accessRadiusSqr) continue;

            Vec3 candidate = stringPulledTrackTarget(follower, points, closest, lookahead, now, fleetRadius, ignored);
            if (candidate == null) continue;
            TrackRoadKey roadKey = entry.getKey();
            Vec3 toCandidate = candidate.subtract(position).multiply(1.0D, 0.0D, 1.0D);
            if (toCandidate.lengthSqr() < 9.0D) continue;
            if (dot2d(toCandidate.normalize(), toFinal) < 0.12D) continue;
            double progress = flatDistance(position, follower.target()) - flatDistance(candidate, follower.target());
            if (progress < -footprint * 0.35D) continue;
            double score = progress * 1.8D - Math.sqrt(closestDistanceSqr) * 0.8D + dot2d(toCandidate.normalize(), toFinal) * 8.0D;
            if (score > bestScore) {
                bestScore = score;
                best = new TrackRoadMatch(points, closest, roadKey);
            }
        }
        if (best == null) return null;
        int users = roadUsers.merge(best.key(), 1, Integer::sum);
        double spacing = Math.max(6.0D, footprint + 2.0D);
        return stringPulledTrackTarget(follower, best.points(), best.closestIndex(), lookahead + (users - 1) * spacing, now, fleetRadius, ignored);
    }

    private static TrackRoadKey trackRoadKey(List<TrackPoint> points, int index) {
        Vec3 point = points.get(index).position();
        Vec3 before = points.get(Math.max(0, index - 1)).position();
        Vec3 after = points.get(Math.min(points.size() - 1, index + 1)).position();
        Vec3 dir = after.subtract(before).multiply(1.0D, 0.0D, 1.0D);
        int sector = directionSector8(dir);
        return new TrackRoadKey(Mth.floor(point.x / 6.0D), Mth.floor(point.z / 6.0D), sector);
    }

    private static int directionSector8(Vec3 dir) {
        if (dir == null || dir.lengthSqr() < 1.0E-6D) return 2;
        double angle = Math.atan2(dir.z, dir.x);
        return Mth.floor((angle + Math.PI + Math.PI / 8.0D) / (Math.PI / 4.0D)) & 7;
    }

    private static Vec3 advanceOnTrack(List<TrackPoint> points, int startIndex, double lookahead, long now) {
        Vec3 previous = points.get(startIndex).position();
        double walked = 0.0D;
        for (int i = startIndex + 1; i < points.size(); i++) {
            TrackPoint point = points.get(i);
            if (point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now) continue;
            Vec3 current = point.position();
            walked += flatDistance(previous, current);
            if (walked >= lookahead) return current;
            previous = current;
        }
        TrackPoint last = points.get(points.size() - 1);
        return last.tick() + FLEET_TRACK_MAX_AGE_TICKS < now ? null : last.position();
    }

    private Vec3 stringPulledTrackTarget(FleetMove follower, List<TrackPoint> points, int startIndex, double lookahead, long now, int fleetRadius, Set<UUID> ignored) {
        TrackRoadAdvance base = advanceOnTrackIndexed(points, startIndex, lookahead, now);
        if (base == null) return null;
        int last = Math.min(points.size() - 1, base.index() + 8);
        Vec3 best = base.position();
        for (int i = last; i >= base.index(); i--) {
            TrackPoint point = points.get(i);
            if (point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now) continue;
            Vec3 candidate = point.position();
            if (!isUsefulTrackRoadTarget(follower, candidate)) continue;
            if (!isSafeTrackTarget(follower, candidate, fleetRadius, ignored)) continue;
            return candidate;
        }
        return best;
    }

    private static TrackRoadAdvance advanceOnTrackIndexed(List<TrackPoint> points, int startIndex, double lookahead, long now) {
        Vec3 previous = points.get(startIndex).position();
        double walked = 0.0D;
        for (int i = startIndex + 1; i < points.size(); i++) {
            TrackPoint point = points.get(i);
            if (point.tick() + FLEET_TRACK_MAX_AGE_TICKS < now) continue;
            Vec3 current = point.position();
            walked += flatDistance(previous, current);
            if (walked >= lookahead) return new TrackRoadAdvance(current, i);
            previous = current;
        }
        TrackPoint last = points.get(points.size() - 1);
        return last.tick() + FLEET_TRACK_MAX_AGE_TICKS < now ? null : new TrackRoadAdvance(last.position(), points.size() - 1);
    }

    private boolean isSafeTrackTarget(FleetMove follower, Vec3 trackTarget, int fleetRadius, Set<UUID> ignored) {
        if (trackTarget == null) return false;
        long now = follower.vehicle().level().getGameTime();
        TrackSafetyKey key = new TrackSafetyKey(follower.vehicle().getUUID(), quantizeTrack(trackTarget.x), quantizeTrack(trackTarget.z), fleetRadius);
        CachedTrackSafety cached = TRACK_SAFETY_CACHE.get(key);
        if (cached != null && cached.expiresAt() >= now) return cached.safe();
        boolean safe = unitAdapter.canDriveDirect(follower.driver(), trackTarget, fleetRadius, ignored);
        TRACK_SAFETY_CACHE.put(key, new CachedTrackSafety(safe, now + TRACK_SAFETY_CACHE_TICKS));
        if (TRACK_SAFETY_CACHE.size() > 512) TRACK_SAFETY_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        return safe;
    }

    private static int quantizeTrack(double value) {
        return Mth.floor(value * 0.5D);
    }

    private static boolean isUsefulTrackRoadTarget(FleetMove follower, Vec3 trackTarget) {
        if (trackTarget == null) return false;
        Vec3 followerPos = follower.vehicle().position();
        Vec3 toFinal = follower.target().subtract(followerPos).multiply(1.0D, 0.0D, 1.0D);
        if (toFinal.lengthSqr() < 4.0D) return false;
        toFinal = toFinal.normalize();
        Vec3 toTrack = trackTarget.subtract(followerPos).multiply(1.0D, 0.0D, 1.0D);
        double distanceSqr = toTrack.lengthSqr();
        if (distanceSqr < 12.25D) return false;
        if (dot2d(toTrack, toFinal) <= 1.0D) return false;
        return flatDistanceSqr(trackTarget, follower.target()) > 9.0D;
    }

    private static boolean convoyTrackStuck(Entity vehicle) {
        Deque<TrackPoint> track = LEADER_TRACKS.get(vehicle.getUUID());
        if (track == null || track.isEmpty()) return false;
        long now = vehicle.level().getGameTime();
        TrackPoint last = track.peekLast();
        return horizontalSpeed(vehicle) < 0.025D && last != null && now - last.tick() >= FLEET_CONVOY_STUCK_TRACK_TICKS;
    }

    private static double vehicleFootprintSize(Entity vehicle) {
        AABB bounds = selectionBoundsStatic(vehicle);
        return Math.max(bounds.getXsize(), bounds.getZsize()) + 2.0D;
    }

    private static double horizontalSpeed(Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static StableFleetCommands smoothFleetCommandTargets(List<FleetMove> moves, List<Vec3> requestedTargets) {
        int count = Math.min(moves.size(), requestedTargets.size());
        if (count <= 0) return new StableFleetCommands(requestedTargets, false);
        List<Vec3> result = new ArrayList<>(requestedTargets.size());
        double jitterSqr = FLEET_COMMAND_JITTER_DISTANCE * FLEET_COMMAND_JITTER_DISTANCE;
        double hardSqr = FLEET_COMMAND_HARD_REPLAN_DISTANCE * FLEET_COMMAND_HARD_REPLAN_DISTANCE;
        boolean brakeForReplan = false;
        for (int i = 0; i < count; i++) {
            FleetMove move = moves.get(i);
            Vec3 requested = requestedTargets.get(i);
            CachedFleetCommand cached = FLEET_COMMAND_CACHE.get(move.vehicle().getUUID());
            Vec3 chosen = requested;
            if (cached != null) {
                double changeSqr = flatDistanceSqr(cached.target(), requested);
                if (changeSqr > hardSqr && !sameCommandDirection(move.vehicle().position(), cached.target(), requested, FLEET_COMMAND_HARD_REPLAN_DOT)) {
                    brakeForReplan = true;
                    FLEET_COMMAND_CACHE.remove(move.vehicle().getUUID());
                } else if (changeSqr <= jitterSqr) {
                    chosen = requested;
                    FLEET_COMMAND_CACHE.put(move.vehicle().getUUID(), new CachedFleetCommand(chosen));
                } else {
                    chosen = lerpHorizontal(cached.target(), requested, FLEET_COMMAND_SMOOTH_ALPHA);
                    FLEET_COMMAND_CACHE.put(move.vehicle().getUUID(), new CachedFleetCommand(chosen));
                }
            } else {
                FLEET_COMMAND_CACHE.put(move.vehicle().getUUID(), new CachedFleetCommand(requested));
            }
            result.add(chosen);
        }
        for (int i = count; i < requestedTargets.size(); i++) result.add(requestedTargets.get(i));
        if (brakeForReplan) {
            for (FleetMove move : moves) FLEET_COMMAND_CACHE.remove(move.vehicle().getUUID());
            return new StableFleetCommands(requestedTargets, true);
        }
        return new StableFleetCommands(result, false);
    }

    private static boolean sameCommandDirection(Vec3 position, Vec3 previousCommand, Vec3 requestedCommand, double dotThreshold) {
        Vec3 previous = previousCommand.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        Vec3 requested = requestedCommand.subtract(position).multiply(1.0D, 0.0D, 1.0D);
        if (previous.lengthSqr() < 1.0E-6D || requested.lengthSqr() < 1.0E-6D) return false;
        return previous.normalize().dot(requested.normalize()) >= dotThreshold;
    }

    private static Vec3 lerpHorizontal(Vec3 from, Vec3 to, double alpha) {
        return new Vec3(Mth.lerp(alpha, from.x, to.x), to.y, Mth.lerp(alpha, from.z, to.z));
    }

    private static Vec3 flatOrFallback(Vec3 vector, Vec3 fallback) {
        Vec3 flat = vector == null ? Vec3.ZERO : vector.multiply(1.0D, 0.0D, 1.0D);
        if (flat.lengthSqr() > 1.0E-6D) return flat.normalize();
        Vec3 fallbackFlat = fallback == null ? Vec3.ZERO : fallback.multiply(1.0D, 0.0D, 1.0D);
        return fallbackFlat.lengthSqr() > 1.0E-6D ? fallbackFlat.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
    }

    private static Vec3 rotateHorizontalOffset(Vec3 offset, Vec3 fromDirection, Vec3 toDirection) {
        Vec3 flatOffset = offset.multiply(1.0D, 0.0D, 1.0D);
        if (flatOffset.lengthSqr() < 1.0E-6D) return flatOffset;
        Vec3 from = flatOrFallback(fromDirection, toDirection);
        Vec3 to = flatOrFallback(toDirection, fromDirection);
        double cos = Mth.clamp(from.x * to.x + from.z * to.z, -1.0D, 1.0D);
        double sin = from.x * to.z - from.z * to.x;
        double x = flatOffset.x * cos - flatOffset.z * sin;
        double z = flatOffset.x * sin + flatOffset.z * cos;
        return new Vec3(x, 0.0D, z);
    }

    private static Vec3 applyFormationErrorCorrection(Vec3 command, Vec3 vehiclePosition, Vec3 currentCenter, Vec3 desiredOffset, Vec3 routeDirection) {
        Vec3 forward = flatOrFallback(routeDirection, desiredOffset);
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 actualOffset = vehiclePosition.subtract(currentCenter).multiply(1.0D, 0.0D, 1.0D);
        Vec3 desiredFlat = desiredOffset.multiply(1.0D, 0.0D, 1.0D);
        double sideError = dot2d(desiredFlat, right) - dot2d(actualOffset, right);
        double forwardError = dot2d(desiredFlat, forward) - dot2d(actualOffset, forward);
        double sideCorrection = Mth.clamp(sideError * 0.65D, -FLEET_FORMATION_CORRECTION_LIMIT, FLEET_FORMATION_CORRECTION_LIMIT);
        double forwardCorrection = Mth.clamp(forwardError * 0.35D, -FLEET_FORMATION_CORRECTION_LIMIT * 0.5D, FLEET_FORMATION_CORRECTION_LIMIT * 0.5D);
        return command.add(right.scale(sideCorrection)).add(forward.scale(forwardCorrection));
    }

    private static double dot2d(Vec3 a, Vec3 b) {
        return a.x * b.x + a.z * b.z;
    }

    private static double flatDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static double flatDistance(Vec3 a, Vec3 b) {
        return Math.sqrt(flatDistanceSqr(a, b));
    }

    private static double flatLength(Vec3 vector) {
        return Math.sqrt(vector.x * vector.x + vector.z * vector.z);
    }

    private static double formationRadius(List<FleetMove> moves, Vec3 targetCenter) {
        double radius = 0.0D;
        for (FleetMove move : moves) radius = Math.max(radius, Math.sqrt(flatDistanceSqr(move.target(), targetCenter)));
        return radius;
    }

    private List<Vec3> fleetRoute(List<FleetMove> moves, FleetMove leader, Vec3 currentCenter, Vec3 targetCenter) {
        long now = leader.vehicle().level().getGameTime();
        FLEET_ROUTE_CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        String mode = pathingMode(moves);
        FleetRouteKey key = fleetRouteKey(moves, targetCenter, mode);
        CachedFleetRoute cached = FLEET_ROUTE_CACHE.get(key);
        if (cached != null && cached.usableFrom(currentCenter, targetCenter, now)) return cached.route();

        int fleetRadius = fleetVoxelRadius(moves);
        List<Vec3> route = unitAdapter.fleetRoute(leader.driver(), currentCenter, targetCenter, fleetRadius, fleetVehicleIdSet(moves));
        if (route == null || route.isEmpty()) route = List.of(targetCenter);
        FLEET_ROUTE_CACHE.put(key, new CachedFleetRoute(route, targetCenter, now + FLEET_ROUTE_CACHE_TICKS));
        return route;
    }

    private static FleetRouteKey fleetRouteKey(List<FleetMove> moves, Vec3 targetCenter, String mode) {
        List<UUID> ids = fleetVehicleIdSet(moves).stream().sorted().toList();
        ResourceKey<Level> level = moves.get(0).vehicle().level().dimension();
        int x = gridKey(targetCenter.x);
        int z = gridKey(targetCenter.z);
        return new FleetRouteKey(level, ids, x, z, mode);
    }

    private static String pathingMode(List<FleetMove> moves) {
        return PATHING_INDEPENDENT_ROUTE;
    }

    private static Set<UUID> fleetVehicleIdSet(List<FleetMove> moves) {
        return moves.stream().map(move -> move.vehicle().getUUID()).collect(java.util.stream.Collectors.toSet());
    }

    private static int gridKey(double value) {
        return (int) Math.floor(value / FLEET_ROUTE_NODE_SPACING);
    }

    private static int fleetVoxelRadius(List<FleetMove> moves) {
        double radius = 1.0D;
        for (FleetMove move : moves) {
            AABB bounds = selectionBoundsStatic(move.vehicle());
            radius = Math.max(radius, Math.max(bounds.getXsize(), bounds.getZsize()) * 0.5D + 0.75D);
        }
        return Math.max(1, (int) Math.ceil(radius));
    }

    private static AABB selectionBoundsStatic(Entity vehicle) {
        OrientedBox obb = boardingObb(vehicle);
        return obb == null ? vehicle.getBoundingBox().inflate(0.75D, 0.25D, 0.75D) : obb.worldAabb().inflate(0.75D, 0.25D, 0.75D);
    }

    private static Vec3 routeWaypoint(List<Vec3> route, Vec3 currentCenter, Vec3 targetCenter, Entity leaderVehicle, int fleetRadius) {
        if (route == null || route.isEmpty()) return targetCenter;
        double lookahead = fleetDynamicLookahead(leaderVehicle, fleetRadius);
        if (currentCenter.distanceToSqr(targetCenter) <= lookahead * lookahead) return targetCenter;
        int nearest = nearestRouteIndex(route, currentCenter);
        double walked = 0.0D;
        Vec3 previous = currentCenter;
        for (int i = nearest; i < route.size(); i++) {
            Vec3 point = route.get(i);
            walked += previous.multiply(1.0D, 0.0D, 1.0D).distanceTo(point.multiply(1.0D, 0.0D, 1.0D));
            if (walked >= lookahead) return point;
            previous = point;
        }
        return targetCenter;
    }

    private static double fleetDynamicLookahead(Entity leaderVehicle, int fleetRadius) {
        Vec3 velocity = leaderVehicle == null ? Vec3.ZERO : leaderVehicle.getDeltaMovement();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return Mth.clamp(FLEET_ROUTE_LOOKAHEAD_MIN + speed * 20.0D + fleetRadius * 0.75D, FLEET_ROUTE_LOOKAHEAD_MIN, FLEET_ROUTE_LOOKAHEAD_MAX);
    }

    private static int nearestRouteIndex(List<Vec3> route, Vec3 currentCenter) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double distance = route.get(i).multiply(1.0D, 0.0D, 1.0D).distanceToSqr(currentCenter.multiply(1.0D, 0.0D, 1.0D));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static Vec3 centerOfVehicles(List<FleetMove> moves) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (FleetMove move : moves) {
            Vec3 position = move.vehicle().position();
            x += position.x;
            y += position.y;
            z += position.z;
        }
        double count = Math.max(1, moves.size());
        return new Vec3(x / count, y / count, z / count);
    }

    private static Vec3 centerOfTargets(List<FleetMove> moves) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (FleetMove move : moves) {
            Vec3 target = move.target();
            x += target.x;
            y += target.y;
            z += target.z;
        }
        double count = Math.max(1, moves.size());
        return new Vec3(x / count, y / count, z / count);
    }

    private static FleetMove representativeLeader(List<FleetMove> moves, Vec3 currentCenter) {
        FleetMove best = null;
        double bestScore = Double.MAX_VALUE;
        for (FleetMove move : moves) {
            double score = move.distanceSqr();
            if (score < bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    private record FleetRouteKey(ResourceKey<Level> level, List<UUID> vehicles, int targetX, int targetZ, String mode) {}

    private record StableFleetCommands(List<Vec3> targets, boolean brakeForReplan) {}

    private record CachedFleetCommand(Vec3 target) {}
    private record TrackSafetyKey(UUID vehicle, int x, int z, int radius) {}
    private record CachedTrackSafety(boolean safe, long expiresAt) {}
    private record TrackRoadKey(int x, int z, int sector) {}
    private record TrackRoadSegment(List<TrackPoint> points, long expiresAt) {}
    private record TrackRoadMatch(List<TrackPoint> points, int closestIndex, TrackRoadKey key) {}
    private record TrackRoadAdvance(Vec3 position, int index) {}

    private record FormationCommand(Vec3 target, double compression) {}

    private record TrackPoint(Vec3 position, long tick) {}

    private record CachedFleetRoute(List<Vec3> route, Vec3 targetCenter, long expiresAt) {
        private boolean usableFrom(Vec3 currentCenter, Vec3 currentTargetCenter, long now) {
            if (expiresAt < now || targetCenter.distanceToSqr(currentTargetCenter) > 4.0D) return false;
            if (route.isEmpty()) return false;
            return route.stream().mapToDouble(point -> point.multiply(1.0D, 0.0D, 1.0D).distanceToSqr(currentCenter.multiply(1.0D, 0.0D, 1.0D))).min().orElse(Double.MAX_VALUE) <= 144.0D;
        }
    }

    private static Entity driver(Entity vehicle) {
        return readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, 0));
    }

    private static String seatType(Entity vehicle, Object seat, int index, int turret, int passengerWeapon) {
        if (index == 0) return "驾驶";
        if (index == turret) return "炮位";
        if (index == passengerWeapon) return "武器站";
        Object weapons = seat == null ? null : invokeNoArg(seat, "weapons");
        if (asList(weapons) != null && !asList(weapons).isEmpty()) return "武器位";
        String pose = seat == null ? "" : String.valueOf(readMember(seat, "pose"));
        if (pose != null && !pose.isBlank() && !"Default".equals(pose) && !"null".equals(pose)) return pose;
        return "座位 " + (index + 1);
    }

    private static boolean isSuperbWarfareVehicle(Entity entity) {
        for (Class<?> type = entity == null ? null : entity.getClass(); type != null; type = type.getSuperclass()) {
            if (VEHICLE_CLASS_NAME.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isHelicopter(Entity vehicle) {
        if (!isSuperbWarfareVehicle(vehicle)) return false;
        Object computed = invokeNoArg(vehicle, "computed");
        Object vehicleType = readMember(vehicle, "vehicleType");
        if (vehicleType == null) vehicleType = readMember(computed, "type");
        if (isHelicopterType(vehicleType)) return true;
        Object engineType = computed == null ? null : readMember(computed, "engineType");
        if (engineType == null) engineType = readMember(vehicle, "engineType");
        return isHelicopterType(engineType);
    }

    private static boolean isHelicopterType(Object type) {
        if (type == null) return false;
        String name = type instanceof Enum<?> enumValue ? enumValue.name() : type.toString();
        String normalized = name.toUpperCase(Locale.ROOT);
        return normalized.contains("HELICOPTER") || normalized.contains("ROTARY_WING");
    }

    private static boolean hasAnyPassenger(Entity vehicle) {
        if (vehicle == null) return false;
        if (!vehicle.getPassengers().isEmpty()) return true;
        Object computed = invokeNoArg(vehicle, "computed");
        List<?> seatInfos = asList(readMember(computed, "seats"));
        if (seatInfos == null) seatInfos = asList(invokeNoArg(computed, "seats"));
        int seatCount = seatInfos != null ? seatInfos.size() : Math.max(0, readInt(vehicle, "maxPassengers", 0));
        for (int seat = 0; seat < seatCount; seat++) {
            if (readEntity(invoke(vehicle, "getNthEntity", new Class<?>[]{int.class}, seat)) != null) return true;
        }
        return false;
    }

    private static boolean isHelicopterFlying(Entity vehicle) {
        return vehicle != null && (!vehicle.onGround() || Boolean.TRUE.equals(readMember(vehicle, "engineStartOver")));
    }

    private static double groundProjectionY(Entity vehicle, double x, double z) {
        if (vehicle == null || vehicle.level() == null) return 0.0D;
        Level level = vehicle.level();
        BlockPos.MutableBlockPos cursor = BlockPos.containing(x, vehicle.getY(), z).mutable();
        for (int y = cursor.getY(); y >= level.getMinBuildHeight(); y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (!state.is(Blocks.BARRIER) && !shape.isEmpty()) return y + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return level.getMinBuildHeight();
    }

    private static Entity readEntity(Object value) {
        return value instanceof Entity entity ? entity : null;
    }

    private static int readInt(Object target, String name, int fallback) {
        Object value = readMember(target, name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Object invokeNoArg(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return null;
        MethodKey key = MethodKey.of(target.getClass(), name, parameterTypes);
        if (BROKEN_METHODS.contains(key)) return null;
        try {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            return method == null ? null : method.invoke(target, args);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException exception) {
            tripBrokenMethod(key, exception);
            return null;
        }
    }

    private static boolean invokeVoid(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return false;
        MethodKey key = MethodKey.of(target.getClass(), name, parameterTypes);
        if (BROKEN_METHODS.contains(key)) return false;
        try {
            Method method = findMethod(target.getClass(), name, parameterTypes);
            if (method == null) return false;
            method.invoke(target, args);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException exception) {
            tripBrokenMethod(key, exception);
            return false;
        }
    }

    private static void tripBrokenMethod(MethodKey key, Exception exception) {
        if (BROKEN_METHODS.add(key)) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : exception;
            LOGGER.error("SuperbWarfare reflective bridge method {}#{} failed and has been disabled.", key.type().getName(), key.name(), cause);
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

    private static boolean writeMember(Object target, String name, Object value) {
        if (target == null) return false;
        try {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                field.set(target, value);
                return true;
            }
            Method setter = findMethod(target.getClass(), "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1), value == null ? Object.class : value.getClass());
            if (setter != null) {
                setter.invoke(target, value);
                return true;
            }
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException exception) {
            tripBrokenMethod(MethodKey.of(target.getClass(), "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1), value == null ? Object.class : value.getClass()), exception);
        }
        return false;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private static OrientedBox boardingObb(Entity vehicle) {
        if (vehicle == null) return null;
        long tick = vehicle.level().getGameTime();
        CachedObb cached = OBB_CACHE.get(vehicle);
        if (cached != null && cached.tick() == tick) return cached.box().orElse(null);
        OrientedBox box = boardingObbUncached(vehicle);
        OBB_CACHE.put(vehicle, new CachedObb(tick, Optional.ofNullable(box)));
        return box;
    }

    private static OrientedBox boardingObbUncached(Entity vehicle) {
        OrientedBox collision = liveObbToBox(invokeNoArg(vehicle, "getCollisionOBB"));
        if (collision != null) return collision;

        List<?> obbs = asList(invokeNoArg(vehicle, "getOBBs"));
        if (obbs == null || obbs.isEmpty()) return null;
        OrientedBox first = null;
        for (Object raw : obbs) {
            OrientedBox box = liveObbToBox(raw);
            if (box == null) continue;
            if (first == null) first = box;
            Object part = readMember(raw, "part");
            String name = part instanceof Enum<?> enumPart ? enumPart.name() : String.valueOf(part);
            if ("COLLISION".equals(name)) return box;
        }
        return first;
    }

    private static AABB wholeVehicleObbBounds(Entity vehicle, AABB fallback) {
        AABB result = fallback == null ? vehicle.getBoundingBox() : fallback;
        List<?> obbs = asList(invokeNoArg(vehicle, "getOBBs"));
        if (obbs == null) return result;
        for (Object raw : obbs) {
            OrientedBox box = liveObbToBox(raw);
            if (box != null) result = unionAabb(result, box.worldAabb());
        }
        return result;
    }

    private static void traceHelicopterSelection(Entity vehicle, AABB projected, double groundY) {
        if (!SuperbWarfareCompatConfig.flightControlTraceEnabled() || vehicle == null || vehicle.level() == null || !vehicle.level().isClientSide) return;
        long now = vehicle.level().getGameTime();
        Long previous = HELICOPTER_SELECTION_TRACE_TICKS.get(vehicle.getUUID());
        if (previous != null && previous + 20L > now) return;
        HELICOPTER_SELECTION_TRACE_TICKS.put(vehicle.getUUID(), now);
        List<?> obbs = asList(invokeNoArg(vehicle, "getOBBs"));
        Object computed = invokeNoArg(vehicle, "computed");
        LOGGER.info("[DS-SW-HELI-SELECT] tick={} vehicle={} class={} name={} vehicleType={} engineType={} passengers={} obbs={} groundY={} projected={}",
                now, vehicle.getId(), vehicle.getClass().getName(), vehicle.getDisplayName().getString(),
                readMember(vehicle, "vehicleType"), readMember(computed, "engineType"), vehicle.getPassengers().size(),
                obbs == null ? -1 : obbs.size(), groundY, projected);
    }

    private static AABB unionAabb(AABB a, AABB b) {
        if (a == null) return b;
        if (b == null) return a;
        return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
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

    private static Vec3 readVec3(Object value) {
        if (value == null) return null;
        double x = readDouble(value, "x", Double.NaN);
        double y = readDouble(value, "y", Double.NaN);
        double z = readDouble(value, "z", Double.NaN);
        return Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) ? null : new Vec3(x, y, z);
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

    private static double readDouble(Object target, String name, double fallback) {
        Object value = readMember(target, name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record OrientedBox(Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ, Vec3 extents) {
        OrientedBox inflate(double x, double y, double z) {
            return new OrientedBox(center, axisX, axisY, axisZ, new Vec3(extents.x + x, extents.y + y, extents.z + z));
        }

        Vec3 closestPoint(Vec3 point) {
            Vec3 relative = point.subtract(center);
            double x = clamp(relative.dot(axisX), -extents.x, extents.x);
            double y = clamp(relative.dot(axisY), -extents.y, extents.y);
            double z = clamp(relative.dot(axisZ), -extents.z, extents.z);
            return center.add(axisX.scale(x)).add(axisY.scale(y)).add(axisZ.scale(z));
        }

        double horizontalDistanceSqr(Vec3 point) {
            Vec3 closest = closestPoint(point);
            double dx = closest.x - point.x;
            double dz = closest.z - point.z;
            return dx * dx + dz * dz;
        }

        boolean intersects(AABB aabb) {
            return intersects(fromAabb(aabb));
        }

        AABB worldAabb() {
            double rx = Math.abs(axisX.x) * extents.x + Math.abs(axisY.x) * extents.y + Math.abs(axisZ.x) * extents.z;
            double ry = Math.abs(axisX.y) * extents.x + Math.abs(axisY.y) * extents.y + Math.abs(axisZ.y) * extents.z;
            double rz = Math.abs(axisX.z) * extents.x + Math.abs(axisY.z) * extents.y + Math.abs(axisZ.z) * extents.z;
            return new AABB(center.x - rx, center.y - ry, center.z - rz, center.x + rx, center.y + ry, center.z + rz);
        }

        List<Vec3> topCorners() {
            Vec3 top = axisY.scale(extents.y + 0.08D);
            return List.of(
                    center.subtract(axisX.scale(extents.x)).subtract(axisZ.scale(extents.z)).add(top),
                    center.add(axisX.scale(extents.x)).subtract(axisZ.scale(extents.z)).add(top),
                    center.add(axisX.scale(extents.x)).add(axisZ.scale(extents.z)).add(top),
                    center.subtract(axisX.scale(extents.x)).add(axisZ.scale(extents.z)).add(top)
            );
        }

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

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
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

    private record CachedObb(long tick, Optional<OrientedBox> box) {}

    private record FieldKey(Class<?> type, String name) {}

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
}
