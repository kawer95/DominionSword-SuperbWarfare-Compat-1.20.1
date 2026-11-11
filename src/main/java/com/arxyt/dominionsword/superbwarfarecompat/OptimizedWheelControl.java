package com.arxyt.dominionsword.superbwarfarecompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import java.util.*;
import java.util.concurrent.*;

/** Game-side numeric binding only. The private controller is separately installed; native motion stays in SW. */
final class OptimizedWheelControl implements AutoCloseable {
    private final Vec3 goal;private final double[] physical;
    private CompletableFuture<Object> pending;private Object controller;private volatile boolean closed;private boolean failed;
    OptimizedWheelControl(Entity vehicle,Vec3 goal,double cap,double reverse,double wheelbase,double gravity){
        vehicle.getPersistentData().remove("DominionWheelFailure");this.goal=goal;double[] hull=SuperbWarfareUnitAdapter.controlHull(vehicle);
        wheelbase=hull[6]!=0?Math.max(2,hull[2]*2*.65):wheelbase;
        physical=new double[]{hull[0],hull[1],hull[2],hull[3],hull[4],hull[5],Math.max(1,BodyNative.number(vehicle,"Mass")),cap,reverse,BodyNative.number(vehicle,"StepHeight"),gravity,wheelbase,hull[6],BodyNative.number(vehicle,"RotateOffsetHeight")};
    }
    private static double[] state(Entity e){Vec3 v=e.getDeltaMovement();return new double[]{e.getX(),e.getY(),e.getZ(),e.getYRot(),v.x,v.y,v.z,e.onGround()?1:0,e.horizontalCollision?1:0,e.getXRot(),BodyNative.number(e,"Roll")};}
    private void plan(Entity e,double[] requested){
        double margin=Math.max(12,Math.max(physical[11]*4,e.position().subtract(goal).horizontalDistance()*.5));
        AABB area=requested!=null?new AABB(requested[0],requested[1],requested[2],requested[3],requested[4],requested[5]):new AABB(Math.min(e.getX(),goal.x)-margin,Math.min(e.getY(),goal.y)-4,Math.min(e.getZ(),goal.z)-margin,Math.max(e.getX(),goal.x)+margin,Math.max(e.getY(),goal.y)+physical[1]*2+physical[9]+3,Math.max(e.getZ(),goal.z)+margin);
        List<double[]> shapes=new ArrayList<>();
        var context=CollisionContext.of(e);
        for(var block:BlockPos.betweenClosed(Mth.floor(area.minX)-1,Mth.floor(area.minY)-1,Mth.floor(area.minZ)-1,
                Mth.floor(area.maxX)+1,Mth.floor(area.maxY)+1,Mth.floor(area.maxZ)+1)){
            for(var b:e.level().getBlockState(block).getCollisionShape(e.level(),block,context).toAabbs())
                shapes.add(new double[]{block.getX(),block.getY(),block.getZ(),b.minX+block.getX(),b.minY+block.getY(),b.minZ+block.getZ(),b.maxX+block.getX(),b.maxY+block.getY(),b.maxZ+block.getZ(),e.level().getBlockState(block).canOcclude()?1:0});
        }
        for(var shape:e.level().getEntityCollisions(e,area))for(var b:shape.toAabbs())shapes.add(box(b));
        double[] start=state(e),target={goal.x,goal.y,goal.z},bounds=box(area);double[][] boxes=shapes.toArray(double[][]::new);
        com.mojang.logging.LogUtils.getLogger().info("[DS-SW-OPTIMIZED] request goal={} physical={}",goal,java.util.Arrays.toString(physical));
        pending=CompletableFuture.supplyAsync(()->{
            Thread thread=Thread.currentThread();ClassLoader previous=thread.getContextClassLoader();
            thread.setContextClassLoader(OptimizedWheelControl.class.getClassLoader());
            try{if(controller!=null){controller.getClass().getMethod("provideSnapshot",double[].class,double[][].class,double[].class).invoke(controller,start,boxes,bounds);return controller;}return Class.forName("io.vehiclepathsim.VehicleRuntime").getConstructor(double[].class,double[].class,double[].class,double[][].class,double[].class).newInstance(physical,start,target,boxes,bounds);}
            catch(ReflectiveOperationException failure){throw new CompletionException(failure);}
            finally{thread.setContextClassLoader(previous);}
        });
        pending.thenAccept(value->{if(closed)dispose(value);});
    }
    double[] tick(Entity e,boolean stop){
        if(failed)return braking(e);
        try{return tickControlled(e,stop);}catch(RuntimeException failure){failed=true;e.getPersistentData().putString("DominionWheelFailure",failure.toString());com.mojang.logging.LogUtils.getLogger().error("[DS-SW-OPTIMIZED] FAILED; retaining command and braking, no legacy fallback",failure);return braking(e);}
    }
    private double[] braking(Entity e){Vec3 v=e.getDeltaMovement();double speed=v.horizontalDistance(),brake=.025*Math.sqrt(13.6/physical[6]),scale=!e.onGround()?1:speed<1e-9?0:Math.max(0,1-brake/speed);return new double[]{v.x*scale,v.y,v.z*scale,e.getYRot(),0,0};}
    private double[] tickControlled(Entity e,boolean stop){
        Vec3 v=e.getDeltaMovement();double brake=.025*Math.sqrt(13.6/physical[6]);
        if(!stop&&pending==null&&controller==null&&v.horizontalDistance()<.005&&e.onGround())plan(e,null);
        if(pending!=null&&pending.isDone()){controller=pending.join();pending=null;com.mojang.logging.LogUtils.getLogger().info("[DS-SW-OPTIMIZED] vehicle={} candidate=g363-c05-v23 ready goal={}",e.getUUID(),goal);}
        if(controller!=null&&pending==null){try{String reason=(String)controller.getClass().getMethod("failureReason").invoke(controller);if(reason!=null){failed=true;e.getPersistentData().putString("DominionWheelFailure",reason);com.mojang.logging.LogUtils.getLogger().error("[DS-SW-OPTIMIZED] REJECTED: {}; no legacy fallback",reason);if("DIFFICULT_TO_REACH".equals(reason)){var owner=BodyControl.commander(e,null);if(owner!=null)owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.dominionsword.sw_body.difficult_to_reach"),false);}return braking(e);}double[] request=(double[])controller.getClass().getMethod("requestedBounds").invoke(controller);if(!stop&&request!=null)plan(e,request);}catch(ReflectiveOperationException failure){throw new IllegalStateException("snapshot request",failure);}}
        if(controller==null||pending!=null){double speed=v.horizontalDistance(),scale=!e.onGround()?1:speed<1e-9?0:Math.max(0,1-brake/speed);return new double[]{v.x*scale,v.y,v.z*scale,e.getYRot(),0,0};}
        try{return (double[])controller.getClass().getMethod("tick",double[].class,boolean.class).invoke(controller,state(e),stop);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("private vehicle controller tick",failure);}
    }
    boolean waiting(){return controller==null||pending!=null||failed;}
    private static double[] box(AABB b){return new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ};}
    private static void dispose(Object value){try{((AutoCloseable)value).close();}catch(Exception e){throw new IllegalStateException(e);}}
    public void close(){closed=true;if(controller!=null){dispose(controller);controller=null;}}
}
