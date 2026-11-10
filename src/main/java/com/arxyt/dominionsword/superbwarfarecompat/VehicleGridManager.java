package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Level-scoped coarse vehicle traffic grid. This is intentionally voxel-only. */
final class VehicleGridManager {
    private static final Map<ResourceKey<Level>, Map<Long, Reservation>> RESERVATIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<UUID>> IGNORED_VEHICLES = ThreadLocal.withInitial(Set::of);

    private VehicleGridManager() {}

    static Set<UUID> setIgnoredVehicles(Set<UUID> ignoredVehicles) {
        Set<UUID> previous = IGNORED_VEHICLES.get();
        IGNORED_VEHICLES.set(ignoredVehicles == null ? Set.of() : ignoredVehicles);
        return previous;
    }

    static boolean hasForeignReservationOnPath(Entity vehicle, Vec3 from, Vec3 to, int radius, double maxDistance, double step) {
        Vec3 flat = to.subtract(from).multiply(1.0D, 0.0D, 1.0D);
        double distance = Math.sqrt(flat.lengthSqr());
        if (distance < 1.0E-6D) return false;
        Vec3 dir = flat.normalize();
        double checked = Math.min(distance, maxDistance);
        for (double d = step; d <= checked + 0.01D; d += step) {
            Vec3 sample = from.add(dir.scale(Math.min(d, checked)));
            if (foreignReservationPenalty(vehicle, sample, radius, 1.0D) > 0.0D) return true;
        }
        return false;
    }

    static double foreignReservationPenalty(Entity vehicle, Vec3 position, int radius, double perSamplePenalty) {
        Map<Long, Reservation> reservations = reservations(vehicle);
        if (reservations.isEmpty()) return 0.0D;
        long now = vehicle.level().getGameTime();
        UUID self = vehicle.getUUID();
        double penalty = 0.0D;
        BlockPos center = BlockPos.containing(position.x, position.y, position.z);
        int[][] samples = {
                {0, 0},
                {radius, 0},
                {-radius, 0},
                {0, radius},
                {0, -radius},
                {radius, radius},
                {radius, -radius},
                {-radius, radius},
                {-radius, -radius}
        };
        for (int[] sample : samples) {
            Reservation reservation = reservations.get(center.offset(sample[0], 0, sample[1]).asLong());
            if (reservation != null && !reservation.owner().equals(self) && !IGNORED_VEHICLES.get().contains(reservation.owner()) && reservation.expiresAt() >= now) {
                penalty += perSamplePenalty * velocityFactor(reservation.ownerSpeed());
            }
        }
        return penalty;
    }

    static double foreignReservationMaxOwnerSpeed(Entity vehicle, Vec3 position, int radius) {
        Map<Long, Reservation> reservations = reservations(vehicle);
        if (reservations.isEmpty()) return 0.0D;
        long now = vehicle.level().getGameTime();
        UUID self = vehicle.getUUID();
        double speed = 0.0D;
        BlockPos center = BlockPos.containing(position.x, position.y, position.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Reservation reservation = reservations.get(center.offset(dx, 0, dz).asLong());
                if (reservation != null && !reservation.owner().equals(self) && !IGNORED_VEHICLES.get().contains(reservation.owner()) && reservation.expiresAt() >= now) {
                    speed = Math.max(speed, reservation.ownerSpeed());
                }
            }
        }
        return speed;
    }

    private static double velocityFactor(double ownerSpeed) {
        if (ownerSpeed >= 0.32D) return 0.45D;
        if (ownerSpeed >= 0.18D) return 0.70D;
        if (ownerSpeed >= 0.08D) return 1.05D;
        return 1.85D;
    }

    static void rememberFutureFootprint(Entity vehicle, Vec3 target, int radius, double length, long ttlTicks) {
        rememberFutureFootprints(vehicle, List.of(target), radius, length, ttlTicks);
    }

    static void rememberFutureFootprints(Entity vehicle, List<Vec3> path, int radius, double length, long ttlTicks) {
        if (path == null || path.isEmpty()) return;
        Map<Long, Reservation> reservations = reservations(vehicle);
        long now = vehicle.level().getGameTime();
        UUID self = vehicle.getUUID();
        double ownerSpeed = horizontalSpeed(vehicle);
        reservations.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now || entry.getValue().owner().equals(self));
        long expires = now + ttlTicks;
        Vec3 previous = vehicle.position();
        double remaining = Math.max(8.0D, length * 1.75D);
        for (Vec3 waypoint : path) {
            if (waypoint == null || remaining <= 0.0D) break;
            Vec3 flat = waypoint.subtract(previous).multiply(1.0D, 0.0D, 1.0D);
            double distance = Math.sqrt(flat.lengthSqr());
            if (distance < 1.0E-6D) continue;
            Vec3 dir = flat.normalize();
            double segment = Math.min(distance, remaining);
            int samples = Math.max(1, Mth.ceil(segment / 3.0D));
            for (int i = 1; i <= samples; i++) {
                Vec3 sample = previous.add(dir.scale(segment * i / samples));
                rememberFootprint(reservations, sample, radius, self, ownerSpeed, expires);
            }
            previous = waypoint;
            remaining -= segment;
        }
    }

    private static void rememberFootprint(Map<Long, Reservation> reservations, Vec3 sample, int radius, UUID self, double ownerSpeed, long expires) {
            BlockPos center = BlockPos.containing(sample.x, sample.y, sample.z);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    reservations.put(center.offset(dx, 0, dz).asLong(), new Reservation(self, expires, ownerSpeed));
                }
            }
    }

    private static double horizontalSpeed(Entity vehicle) {
        Vec3 velocity = vehicle.getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static Map<Long, Reservation> reservations(Entity vehicle) {
        return RESERVATIONS.computeIfAbsent(vehicle.level().dimension(), ignored -> new ConcurrentHashMap<>());
    }

    private record Reservation(UUID owner, long expiresAt, double ownerSpeed) {}
}
