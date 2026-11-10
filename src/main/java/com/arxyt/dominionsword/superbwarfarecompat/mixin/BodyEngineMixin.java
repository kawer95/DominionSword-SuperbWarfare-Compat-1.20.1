package com.arxyt.dominionsword.superbwarfarecompat.mixin;

import com.arxyt.dominionsword.superbwarfarecompat.BodyControl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;

/** Exclusive engine dispatch; movement, gravity and weapon ticks remain native. */
@Pseudo
@Mixin(targets="com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",remap=false)
public abstract class BodyEngineMixin {
    @Redirect(method="travel",at=@At(value="INVOKE",target="Lcom/atsuishio/superbwarfare/data/vehicle/subdata/EngineInfo;work(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;)V"),remap=false)
    private void dominion$engine(@Coerce Object engine,@Coerce Object vehicle){if(!BodyControl.engine(engine,(Entity)vehicle))BodyControl.nativeEngine(engine,vehicle);}
    @Redirect(method="vCollide",at=@At(value="INVOKE",target="Lcom/atsuishio/superbwarfare/entity/vehicle/utils/VehicleMotionUtils;resolveObbWorldCollision(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/phys/Vec3;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"),remap=false)
    private Vec3 dominion$landing(@Coerce Object vehicle,Vec3 motion,List<?> obbs){return BodyControl.landing(vehicle,motion,obbs);}
}
