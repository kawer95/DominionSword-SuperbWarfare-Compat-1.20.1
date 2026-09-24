package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundCollisionEnvelopeTest {
    private static final Vec3 M2_LOCAL_CENTER = new Vec3(0.0D, 1.0938D, 0.25D);
    private static final Vec3 M2_EXTENTS = new Vec3(1.75D, 1.0938D, 3.875D);
    private static final double ROOF_CLEARANCE = 0.15D;
    private static final double SUPPORT_PROBE_DEPTH = 0.18D;

    @Test
    void m2EnvelopeKeepsGroundContactAndRoofClearanceAtDifferentHeadings() throws Exception {
        Object profile = m2Profile();
        for (float yaw : new float[]{0.0F, 45.0F, 170.0F}) {
            Vec3 pose = new Vec3(614.0D, 62.04D, 311.0D);
            AABB body = predictedWorldAabb(profile, pose, yaw);

            assertEquals(pose.y, body.minY, 1.0E-9D, "yaw=" + yaw);
            assertTrue(body.minY > 62.0D, "body must stay above the full-block floor at yaw=" + yaw);
            assertEquals(pose.y + M2_LOCAL_CENTER.y + M2_EXTENTS.y + ROOF_CLEARANCE,
                    body.maxY, 1.0E-9D, "yaw=" + yaw);
        }
    }

    @Test
    void halfSlabSurfaceRemainsInsideSupportProbeBelowTheBody() throws Exception {
        Object profile = m2Profile();
        double slabTop = 62.5D;
        for (float yaw : new float[]{0.0F, 45.0F, 170.0F}) {
            AABB body = predictedWorldAabb(profile, new Vec3(614.0D, 62.54D, 311.0D), yaw);

            assertEquals(62.54D, body.minY, 1.0E-9D, "yaw=" + yaw);
            assertTrue(body.minY > slabTop, "body must not intersect the slab at yaw=" + yaw);
            assertTrue(body.minY - SUPPORT_PROBE_DEPTH <= slabTop
                    && slabTop < body.minY - 0.02D,
                    "the existing support probe must still include the slab top at yaw=" + yaw);
        }
    }

    private static Object m2Profile() throws Exception {
        Class<?> type = Class.forName(SuperbWarfareUnitAdapter.class.getName() + "$VehicleProfile");
        Constructor<?> constructor = type.getDeclaredConstructor(
                double.class, double.class, double.class, double.class, double.class, float.class,
                double.class, double.class, double.class, double[].class, double[].class,
                int.class, int.class, Vec3.class, Vec3.class, double.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                7.75D, 3.5D, 0.5D, 1.0D, 0.2D, 15.0F,
                1.0D, 0.2D, 1.0D, new double[]{0.0D}, new double[]{0.0D},
                3, 3, M2_LOCAL_CENTER, M2_EXTENTS, 0.0D);
    }

    private static AABB predictedWorldAabb(Object profile, Vec3 pose, float yaw) throws Exception {
        Method predicted = profile.getClass().getDeclaredMethod("predictedCollisionObb", Vec3.class, float.class);
        predicted.setAccessible(true);
        Object obb = predicted.invoke(profile, pose, yaw);
        Method worldAabb = obb.getClass().getDeclaredMethod("worldAabb");
        worldAabb.setAccessible(true);
        return (AABB) worldAabb.invoke(obb);
    }
}
