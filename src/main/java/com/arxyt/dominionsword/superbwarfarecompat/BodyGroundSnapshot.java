package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures native shapes on the server thread; background searches retain no Entity or Level. */
final class BodyGroundSnapshot {
    private BodyGroundSnapshot() {}
    static BodyPathDriver.PlanSource source(Entity vehicle){
        WeakReference<Entity> reference=new WeakReference<>(vehicle);
        return (start,goal,parameters)->{
            Entity current=reference.get();
            if(current==null||!current.isAlive())return new BodyPathDriver.Job(CompletableFuture.completedFuture(List.of()),()->{});
            BodyCollisionScene scene=capture(current,start,goal,parameters.wheelbase());
            AtomicBoolean cancelled=new AtomicBoolean();
            CompletableFuture<List<BodyPathPlanner.Point>> result=CompletableFuture.supplyAsync(
                    ()->BodyPathPlanner.plan(start,goal,parameters.wheelbase(),scene,cancelled::get));
            return new BodyPathDriver.Job(result,()->{cancelled.set(true);result.cancel(false);});
        };
    }
    private static BodyCollisionScene capture(Entity vehicle,BodyPathPlanner.Pose start,BodyPathPlanner.Pose goal,double wheelbase){
        AABB actual=vehicle.getBoundingBox();
        BodyCollisionScene.Bounds chassis=new BodyCollisionScene.Bounds(actual.minX-vehicle.getX(),actual.minY-vehicle.getY(),actual.minZ-vehicle.getZ(),actual.maxX-vehicle.getX(),actual.maxY-vehicle.getY(),actual.maxZ-vehicle.getZ());
        double step=BodyNative.number(vehicle,"StepHeight"),margin=Math.max(Math.max(12,wheelbase*4),Math.hypot(goal.x()-start.x(),goal.z()-start.z())*.5);
        AABB area=new AABB(Math.min(start.x(),goal.x())-margin,Math.min(start.y(),goal.y())-4,Math.min(start.z(),goal.z())-margin,
                Math.max(start.x(),goal.x())+margin,Math.max(start.y(),goal.y())+actual.getYsize()+step+3,Math.max(start.z(),goal.z())+margin);
        List<BodyCollisionScene.Bounds> boxes=new ArrayList<>();
        for(VoxelShape shape:vehicle.level().getBlockCollisions(vehicle,area))for(AABB box:shape.toAabbs())boxes.add(bounds(box));
        for(VoxelShape shape:vehicle.level().getEntityCollisions(vehicle,area))for(AABB box:shape.toAabbs())boxes.add(bounds(box));
        return new BodyCollisionScene(new BodyCollisionScene.World(boxes,bounds(area)),chassis,step);
    }
    private static BodyCollisionScene.Bounds bounds(AABB b){return new BodyCollisionScene.Bounds(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
}
