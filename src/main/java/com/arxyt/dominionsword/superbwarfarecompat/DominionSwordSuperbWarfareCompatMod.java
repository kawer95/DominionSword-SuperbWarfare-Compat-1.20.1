package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionAdapters;
import com.arxyt.dominionsword.api.DominionEntityInteractions;
import com.arxyt.dominionsword.api.DominionVehicleAdapters;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(DominionSwordSuperbWarfareCompatMod.MODID)
public final class DominionSwordSuperbWarfareCompatMod {
    public static final String MODID = "dominionsword_superbwarfare_compat";
    private final SuperbWarfareVehicleAdapter vehicleAdapter = new SuperbWarfareVehicleAdapter();

    public DominionSwordSuperbWarfareCompatMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SuperbWarfareCompatConfig.SPEC);
        MortarNetwork.register();
        DominionAdapters.register(new SuperbWarfareUnitAdapter());
        DominionVehicleAdapters.register(vehicleAdapter);
        DominionEntityInteractions.register(new MortarEntityInteractionAdapter());
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(M2MotionCalibration::register);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent event) -> M2MotionCalibration.clear());
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent event) -> SuperbWarfareUnitAdapter.clearPlanning());
        MinecraftForge.EVENT_BUS.addListener(this::onEntityJoin);
        MinecraftForge.EVENT_BUS.addListener(this::onEntityLeave);
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) { M2MotionCalibration.tick(event.getServer()); return; }
        if (event.phase != TickEvent.Phase.END) return;
        SuperbWarfareVehicleAdapter.cleanupExpiredCaches(event.getServer().overworld().getGameTime());
        vehicleAdapter.tickHelicopterAutopilot(event.getServer());
    }

    private void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) vehicleAdapter.onEntityLoaded(event.getEntity());
    }

    private void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            M2MotionCalibration.unloaded(event.getEntity());
            vehicleAdapter.onEntityUnloaded(event.getEntity());
        }
    }
}
