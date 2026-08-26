package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainFollowingPolicyTest {
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
}
