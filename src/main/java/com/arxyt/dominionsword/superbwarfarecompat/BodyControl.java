package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.SuperbVehicleControlMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import java.util.*;

/** Server-thread leases for SW chassis execution. Client state is presentation-only and expires. */
public final class BodyControl {
    private static final Map<Entity, Session> SESSIONS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Entity, ClientState> CLIENT=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Entity,String> FALLBACK=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Entity> FAILED=Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final org.slf4j.Logger LOG=com.mojang.logging.LogUtils.getLogger();
    private record ClientState(BodyControlStatePacket packet,long expires) {}
    private static final class Session {
        final UUID driver,owner;
        final BodyDriveDynamics dynamics=new BodyDriveDynamics();
        BodyPathDriver pathDriver;
        OptimizedWheelControl optimized;
        Vec3 target,goal,commandGoal;
        long refreshed,stopUntil,waypointUntil;
        int settled, blocked, recoveries;
        long recoveryUntil, waitUntil;
        Vec3 recoveryStart;
        long commandRevision,vehicleCommandRevision;
        float motorPower,lastDeltaRot,lastRudderRot;
        boolean unreachable,nearCommand;
        double bestDistance=Double.POSITIVE_INFINITY;
        long progressTick,lastRouteTick;
        boolean pivotLock;
        Vec3 pivotStart;
        boolean stopping, arrived;
        Session(Mob driver,ServerPlayer owner){this.driver=driver.getUUID();this.owner=owner.getUUID();}
    }
    private BodyControl() {}
    static ServerPlayer commander(Entity vehicle,ServerPlayer supplied){
        if(supplied!=null)return supplied;
        UUID owner=DominionControlApi.controller(vehicle);
        if(owner==null){Session lease=SESSIONS.get(vehicle);if(lease!=null)owner=lease.owner;}
        return owner==null||vehicle.getServer()==null?null:vehicle.getServer().getPlayerList().getPlayer(owner);
    }
    static boolean usesPathPlanner(Entity vehicle) {
        Object engine=BodyNative.get(vehicle,"EngineInfo");
        return com.arxyt.dominionsword.api.SuperbVehicleControlMode.current()==com.arxyt.dominionsword.api.SuperbVehicleControlMode.BODY_DIRECT
                &&engine!=null&&engine.getClass().getSimpleName().equals("Wheel");
    }
    static String unsupported(Entity vehicle) {
        Object engine=BodyNative.get(vehicle,"EngineInfo");
        if(engine==null || !(engine.getClass().getName().equals("com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo$Wheel")
                || engine.getClass().getName().equals("com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo$Track")))return "unvalidated_type";
        // Native ground vehicles may use either VehicleEntity.vMove (OBB) or
        // Entity.move (AABB). Both execute the same engine hook and retain native
        // collision; missing COLLISION metadata must not silently select pedals.
        if(vehicle.getBoundingBox().getSize()<=0)return "unvalidated_type";
        try {
            if(!vehicle.getClass().getMethod("travel").getDeclaringClass().getName().equals("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity"))return "unvalidated_type";
        } catch(NoSuchMethodException e){return "unvalidated_type";}
        if(vehicle.isInFluidType())return "in_fluid";
        if(BodyNative.get(vehicle,"TowingEntity")!=null || BodyNative.get(vehicle,"TowedByEntity")!=null)return "towing";
        return "";
    }
    static boolean requested(Entity vehicle,ServerPlayer player,Mob driver) {
        if(SuperbVehicleControlMode.current()!=SuperbVehicleControlMode.BODY_DIRECT){FALLBACK.remove(vehicle);FAILED.remove(vehicle);return false;}
        if(FAILED.contains(vehicle))return false;
        String reason=unsupported(vehicle);
        if(!reason.isEmpty()) {
            String notice=(player==null?"":player.getUUID().toString())+":"+reason;
            if(player!=null && !notice.equals(FALLBACK.put(vehicle,notice)))player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.dominionsword.sw_body_fallback."+reason),true);
            return false;
        }
        FALLBACK.remove(vehicle);
        return player!=null && vehicle.getFirstPassenger()==driver && DominionControlApi.canControl(player,driver);
    }
    static void request(Entity vehicle,Mob driver,ServerPlayer player,Vec3 target,Vec3 goal,boolean validRoute) {
        Session s=SESSIONS.get(vehicle);
        if(s==null || !s.driver.equals(driver.getUUID()) || !s.owner.equals(player.getUUID())) {
            release(vehicle);s=new Session(driver,player);SESSIONS.put(vehicle,s);
            BodyNative.input(vehicle,(short)0);BodyNative.set(vehicle,"Power",0);BodyNative.set(vehicle,"DeltaRot",0);BodyNative.set(vehicle,"RudderRot",0);
        }
        boolean changed=s.goal==null || s.goal.distanceToSqr(goal)>.01;
        long revision=DominionControlApi.commandView(driver).commandRevision();
        // Core persists this sequence on the actual vehicle for explicit vehicle orders.
        long vehicleRevision=vehicle.getPersistentData().getLong("DominionCommandRevision");
        var order=vehicle.getPersistentData();
        Vec3 commandGoal=order.getBoolean("DominionOfflineVehicleMove")?new Vec3(order.getDouble("DominionOfflineVehicleX"),order.getDouble("DominionOfflineVehicleY"),order.getDouble("DominionOfflineVehicleZ")):null;
        boolean explicitOrder=revision!=s.commandRevision||vehicleRevision!=s.vehicleCommandRevision
                ||commandGoal!=null&&(s.commandGoal==null||s.commandGoal.distanceToSqr(commandGoal)>1e-8);
        s.commandGoal=commandGoal;
        if((explicitOrder||changed)&&s.optimized!=null){s.optimized.close();s.optimized=null;}
        if(explicitOrder&&s.pathDriver!=null){s.pathDriver.close();s.pathDriver=null;s.arrived=false;s.settled=0;}
        // Install the new waypoint before evaluating a pivot lock. Otherwise a new
        // goal can immediately re-lock the previous command's waypoint below.
        if(changed){
            s.target=target;s.blocked=0;s.arrived=false;
            if(s.pathDriver!=null){
                if(explicitOrder||s.pathDriver.arrived()){s.pathDriver.close();s.pathDriver=null;}
                else s.pathDriver.retarget(new BodyPathPlanner.Pose(goal.x,goal.y,goal.z,0));
            }
        }
        if(changed){s.dynamics.resetTarget();s.settled=0;s.recoveries=0;s.waitUntil=0;s.recoveryUntil=0;s.waypointUntil=0;s.pivotLock=false;s.unreachable=false;s.bestDistance=Double.POSITIVE_INFINITY;s.lastRouteTick=vehicle.level().getGameTime();s.nearCommand=vehicle.position().subtract(goal).horizontalDistance()<=10;}
        long now=vehicle.level().getGameTime();
        Object engine=BodyNative.get(vehicle,"EngineInfo");
        boolean tracked=engine!=null && engine.getClass().getSimpleName().equals("Track");
        // A turning hull changes the planner's starting orientation. Do not chase a fresh
        // route's first node every tick while still executing the previous safe pivot.
        double oldBearing=s.target==null?0:Math.toDegrees(Math.atan2(vehicle.getX()-s.target.x,s.target.z-vehicle.getZ()));
        double oldError=Math.abs(BodyDriveDynamics.wrap(oldBearing-vehicle.getYRot()));
        if(tracked && !s.pivotLock && s.target!=null && oldError>55 && vehicle.getDeltaMovement().horizontalDistance()<.02){s.pivotLock=true;s.pivotStart=vehicle.position();s.waypointUntil=now+200;}
        if(s.pivotLock && (now>=s.waypointUntil || s.target==null || vehicle.position().subtract(s.target).horizontalDistance()<2
                || oldError<15 && vehicle.position().subtract(s.pivotStart).horizontalDistance()>=1))s.pivotLock=false;
        if(!s.pivotLock)s.target=target;
        s.goal=goal;s.stopping=false;s.refreshed=now;
        if(validRoute){s.unreachable=false;s.lastRouteTick=now;}
        s.commandRevision=revision;s.vehicleCommandRevision=vehicleRevision;
    }
    static boolean stop(Entity vehicle){
        Session s=SESSIONS.get(vehicle);if(s==null)return false;
        if(!s.stopping){s.stopping=true;s.stopUntil=vehicle.level().getGameTime()+100;}
        return true;
    }
    static boolean pending(Entity vehicle,Vec3 goal) {
        Session s=SESSIONS.get(vehicle);
        return s!=null && (!vehicle.onGround() || vehicle.position().subtract(goal).horizontalDistance()>.3 || vehicle.getDeltaMovement().horizontalDistance()>.005 || s.settled<20);
    }
    static void release(Entity vehicle){
        if(vehicle.level().isClientSide){CLIENT.remove(vehicle);return;}
        Session old=SESSIONS.remove(vehicle);
        if(old==null)return;
        if(old.pathDriver!=null)old.pathDriver.close();
        if(old.optimized!=null)old.optimized.close();
        // Only remove our own residual engine load; never clear a new player's input flags.
        if(Math.abs(BodyNative.number(vehicle,"Power")-old.motorPower)<1e-6)BodyNative.set(vehicle,"Power",0);
        if(Math.abs(BodyNative.number(vehicle,"DeltaRot")-old.lastDeltaRot)<1e-6)BodyNative.set(vehicle,"DeltaRot",0);
        if(Math.abs(BodyNative.number(vehicle,"RudderRot")-old.lastRudderRot)<1e-6)BodyNative.set(vehicle,"RudderRot",0);
        if(vehicle.getFirstPassenger() instanceof Mob mob && old.driver.equals(mob.getUUID())) {
            BodyNative.input(vehicle,(short)0);BodyNative.set(vehicle,"Power",0);BodyNative.set(vehicle,"DeltaRot",0);BodyNative.set(vehicle,"RudderRot",0);
        }
        if(!vehicle.level().isClientSide)send(vehicle,false,0,0,0);
    }
    static void clear(){synchronized(SESSIONS){for(Session s:SESSIONS.values()){if(s.pathDriver!=null)s.pathDriver.close();if(s.optimized!=null)s.optimized.close();}SESSIONS.clear();}CLIENT.clear();FALLBACK.clear();FAILED.clear();}
    static void clientState(Entity entity,BodyControlStatePacket packet){
        if(packet.active())CLIENT.put(entity,new ClientState(packet,entity.level().getGameTime()+20));else CLIENT.remove(entity);
    }
    public static boolean owns(Entity entity){
        if(entity.level().isClientSide){ClientState s=CLIENT.get(entity);return s!=null && s.expires>=entity.level().getGameTime();}
        return SESSIONS.containsKey(entity);
    }
    /** Called only at the native engine invocation, before the one native move. */
    public static boolean engine(Object engine,Entity vehicle){
        if(vehicle.level().isClientSide){
            ClientState s=CLIENT.get(vehicle);
            if(s==null)return false;
            if(vehicle.getFirstPassenger() instanceof net.minecraft.world.entity.player.Player){CLIENT.remove(vehicle);return false;}
            if(s.expires<vehicle.level().getGameTime()){
                CLIENT.remove(vehicle);BodyNative.input(vehicle,(short)0);BodyNative.set(vehicle,"Power",0);BodyNative.set(vehicle,"DeltaRot",0);return false;
            }
            animate(vehicle,engine,s.packet.speed(),s.packet.turn(),s.packet.steer());return true;
        }
        Session s=SESSIONS.get(vehicle);
        if(SuperbVehicleControlMode.current()!=SuperbVehicleControlMode.BODY_DIRECT){FAILED.remove(vehicle);if(s!=null)release(vehicle);return false;}
        if(s==null)return false;
        try {
            var driver=vehicle.getFirstPassenger();
            var player=vehicle.getServer().getPlayerList().getPlayer(s.owner);
            long now=vehicle.level().getGameTime();
            UUID currentOwner=DominionControlApi.controller(vehicle);
            if(!vehicle.isAlive() || !(driver instanceof Mob mob) || !s.driver.equals(driver.getUUID()) || player==null
                    || !DominionControlApi.canControl(player,mob) || SuperbVehicleControlMode.current()!=SuperbVehicleControlMode.BODY_DIRECT
                    || currentOwner!=null && !currentOwner.equals(s.owner)
                    || !unsupported(vehicle).isEmpty()) {release(vehicle);return false;}
            if(DominionControlApi.commandView(mob).commandRevision()!=s.commandRevision
                    ||vehicle.getPersistentData().getLong("DominionCommandRevision")!=s.vehicleCommandRevision){stop(vehicle);}
            if(!s.stopping && now-s.refreshed>20)stop(vehicle);
            if(s.stopping && (now>s.stopUntil || vehicle.getDeltaMovement().horizontalDistance()<.001)){release(vehicle);return false;}
            Vec3 velocity=vehicle.getDeltaMovement();
            Vec3 forward=Vec3.directionFromRotation(0,vehicle.getYRot());
            double measured=velocity.dot(forward);
            if(engine.getClass().getSimpleName().equals("Wheel"))
                return plannedWheel(engine,vehicle,s,measured);
            double goalDistance=vehicle.position().subtract(s.goal).horizontalDistance();
            if(goalDistance<s.bestDistance-1){s.bestDistance=goalDistance;s.progressTick=now;}
            if(!s.pivotLock && now-s.lastRouteTick>=120 && now-s.progressTick>=120)s.unreachable=true;
            // Capture completion for this command. Residual native motion must
            // not reopen pursuit and select a fresh gear after reaching the goal.
            if(goalDistance<=.22 && Math.abs(measured)<=.05)s.arrived=true;
            boolean stop=s.stopping || s.target==null || s.arrived || goalDistance<=.22 || s.unreachable;
            if(now<s.waitUntil)stop=true;
            boolean track=engine.getClass().getSimpleName().equals("Track");
            double distance=s.target==null?0:vehicle.position().subtract(s.target).horizontalDistance();
            double yaw=s.target==null?vehicle.getYRot():Math.toDegrees(Math.atan2(vehicle.getX()-s.target.x,s.target.z-vehicle.getZ()));
            double cap=.8*Math.max(0,BodyNative.number(engine,"MaxForwardSpeedRate"));
            double reverseCap=.8*Math.max(0,BodyNative.number(engine,"MaxBackwardSpeedRate"));
            double damage=1;
            if(BodyNative.flag(vehicle,"MainEngineDamaged"))damage*=track?.5:.25;
            if(BodyNative.flag(vehicle,"LeftWheelDamaged"))damage*=.5;
            if(BodyNative.flag(vehicle,"RightWheelDamaged"))damage*=.5;
            double rate=BodyNative.number(engine,"EnergyCostRate");
            double energy=BodyNative.number(vehicle,"Energy");
            if(BodyNative.number(vehicle,"MaxEnergy")>0 && energy<=0)stop=true;
            double massScale=Math.sqrt(13.6/Math.max(1,BodyNative.number(vehicle,"Mass")));
            double acceleration=.012*massScale;
            double braking=.025*massScale;
            double length=SuperbWarfareUnitAdapter.bodyLength(vehicle);
            double wheelbase=Math.max(2,length*.65);
            // A nearby navigation carrot must not turn a long journey into reverse travel.
            boolean reverseApproach=s.nearCommand || goalDistance<=BodyDriveDynamics.reverseApproachDistance(wheelbase);
            var output=s.dynamics.step(track,BodyDriveDynamics.wrap(yaw-vehicle.getYRot()),distance,measured,wheelbase,cap*damage,acceleration,stop,reverseApproach,reverseCap*damage,braking);
            double yawStep=output.yawStep();
            float nextYaw=(float)BodyDriveDynamics.wrap(vehicle.getYRot()+yawStep);
            boolean rotationBlocked=Math.abs(yawStep)>1e-6 && !SuperbWarfareUnitAdapter.bodyRotationClear(vehicle,nextYaw);
            if(!vehicle.onGround() || rotationBlocked){yawStep=0;nextYaw=vehicle.getYRot();}
            if(!stop && (rotationBlocked || vehicle.horizontalCollision) && Math.abs(measured)<.015)s.blocked++;else s.blocked=0;
            if(s.recoveryUntil==0 && s.blocked>=20) {
                s.blocked=0;
                if(++s.recoveries>2){s.waitUntil=now+200;s.recoveries=0;}
                else {s.recoveryUntil=now+80;s.recoveryStart=vehicle.position();}
            }
            double motorSpeed=output.speed();
            if(s.recoveryUntil>0) {
                if(now>=s.recoveryUntil || vehicle.position().subtract(s.recoveryStart).horizontalDistance()>=2 || stop){
                    s.recoveryUntil=0;s.waypointUntil=0;s.pivotLock=false;SuperbWarfareUnitAdapter.resetBodyRoute(mob);
                } else {
                    motorSpeed=BodyDriveDynamics.approach(measured,-Math.min(.18,reverseCap*damage),measured>0?braking:acceleration);
                    yawStep=0;nextYaw=vehicle.getYRot();
                }
            }
            if(now<s.waitUntil)motorSpeed=BodyDriveDynamics.approach(measured,0,braking);
            if(vehicle.onGround()){
                vehicle.setYRot(nextYaw);
                Vec3 nextForward=Vec3.directionFromRotation(0,nextYaw);
                // Bound the motor correction; retain external lateral momentum instead of erasing impulses.
                Vec3 lateral=velocity.subtract(forward.scale(measured)).multiply(1,0,1);
                double friction=Math.min(.012,lateral.length());
                if(lateral.length()>1e-8)lateral=lateral.scale(Math.max(0,1-friction/lateral.length()));
                Vec3 horizontal=nextForward.scale(motorSpeed).add(lateral);
                vehicle.setDeltaMovement(horizontal.x,velocity.y,horizontal.z);
            }
            double load=stop?0:Math.min(1,Math.max(Math.abs(motorSpeed)/Math.max(.01,motorSpeed<0?reverseCap:cap),Math.abs(yawStep)/2));
            s.motorPower=(float)Math.copySign(load,motorSpeed==0?1:motorSpeed);
            BodyNative.set(vehicle,"Power",s.motorPower);
            s.lastDeltaRot=(float)((track?-1:Math.signum(motorSpeed))*yawStep/8);
            BodyNative.set(vehicle,"DeltaRot",s.lastDeltaRot);
            int cost=(int)Math.min(energy,rate*load);
            if(cost>0)BodyNative.invoke(vehicle,"consumeEnergy",new Class<?>[]{int.class},cost);
            if(goalDistance<=.3 && vehicle.getDeltaMovement().horizontalDistance()<=.005)s.settled++;else s.settled=0;
            animate(vehicle,engine,measured,yawStep,output.steer());
            s.lastRudderRot=track?0:(float)-output.steer();
            send(vehicle,true,(float)measured,(float)yawStep,(float)output.steer());
            return true;
        }catch(RuntimeException failure){
            LOG.error("SW body control released after failure: {}",vehicle.getUUID(),failure);
            FAILED.add(vehicle);
            var owner=vehicle.getServer().getPlayerList().getPlayer(s.owner);
            if(owner!=null)owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.dominionsword.sw_body_fallback.runtime_failure"),true);
            release(vehicle);
            // A partial direct step must not also run the native engine in this tick.
            // Following ticks use the original input route until the mode is switched off.
            return true;
        }
    }
    /** AABB wheel vehicles use the same pose-space controller as the offline verifier. */
    private static boolean plannedWheel(Object engine,Entity vehicle,Session session,double measured){
        double cap=.8*Math.max(0,BodyNative.number(engine,"MaxForwardSpeedRate"));
        double reverse=.8*Math.max(0,BodyNative.number(engine,"MaxBackwardSpeedRate"));
        double damage=1;
        if(BodyNative.flag(vehicle,"MainEngineDamaged"))damage*=.25;
        if(BodyNative.flag(vehicle,"LeftWheelDamaged"))damage*=.5;
        if(BodyNative.flag(vehicle,"RightWheelDamaged"))damage*=.5;
        Object computed=BodyNative.invoke(vehicle,"computed",new Class<?>[0]);
        var parameters=new BodyPathDriver.Parameters(Math.max(1,BodyNative.number(vehicle,"Mass")),cap*damage,reverse*damage,
                Math.max(2,SuperbWarfareUnitAdapter.bodyLength(vehicle)*.65),BodyNative.number(computed,"Gravity"));
        Vec3 velocity=vehicle.getDeltaMovement();
        var pose=new BodyPathPlanner.Pose(vehicle.getX(),vehicle.getY(),vehicle.getZ(),vehicle.getYRot());
        var state=new BodyPathDriver.State(pose,new BodyPathDriver.Vector(velocity.x,velocity.y,velocity.z),vehicle.onGround(),vehicle.horizontalCollision);
        if(session.optimized==null)session.optimized=new OptimizedWheelControl(vehicle,session.goal,cap*damage,reverse*damage,parameters.wheelbase(),parameters.gravity());
        double energy=BodyNative.number(vehicle,"Energy");
        boolean stop=session.stopping || BodyNative.number(vehicle,"MaxEnergy")>0&&energy<=0;
        double[] result=session.optimized.tick(vehicle,stop);
        var motion=new BodyPathDriver.Motion(new BodyPathDriver.Vector(result[0],result[1],result[2]),result[3],result[4],result[5]!=0);
        float turn=(float)BodyDriveDynamics.wrap(motion.yaw()-vehicle.getYRot());
        vehicle.setYRot((float)motion.yaw());
        vehicle.setDeltaMovement(motion.velocity().x(),motion.velocity().y(),motion.velocity().z());
        double speed=motion.velocity().dot(new BodyPathDriver.Vector(-Math.sin(Math.toRadians(motion.yaw())),0,Math.cos(Math.toRadians(motion.yaw()))));
        double load=stop||motion.arrived()||session.optimized.waiting()?0:Math.min(1,Math.abs(speed)/Math.max(.01,speed<0?reverse:cap));
        session.motorPower=(float)Math.copySign(load,speed==0?1:speed);
        session.lastDeltaRot=(float)(Math.signum(speed)*turn/8);
        session.lastRudderRot=(float)-motion.steer();
        BodyNative.set(vehicle,"Power",session.motorPower);
        BodyNative.set(vehicle,"DeltaRot",session.lastDeltaRot);
        int cost=(int)Math.min(energy,BodyNative.number(engine,"EnergyCostRate")*load);
        if(cost>0)BodyNative.invoke(vehicle,"consumeEnergy",new Class<?>[]{int.class},cost);
        session.arrived=motion.arrived();
        if(motion.arrived()&&vehicle.onGround()&&vehicle.position().subtract(session.goal).horizontalDistance()<=.3
                &&motion.velocity().horizontal()<=.005)session.settled++;else session.settled=0;
        animate(vehicle,engine,measured,turn,motion.steer());
        send(vehicle,true,(float)measured,turn,(float)motion.steer());
        return true;
    }
    private static void send(Entity e,boolean active,float speed,float turn,float steer){MortarNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(()->e),new BodyControlStatePacket(e.getId(),e.getUUID(),active,speed,turn,steer));}
    private static void animate(Entity e,Object engine,double speed,double turn,double steer){
        double wheel=BodyNative.number(engine,"WheelRotSpeed");
        boolean trackEngine=engine.getClass().getSimpleName().equals("Track");
        double nativeTurn=(trackEngine?-1:Math.signum(speed))*turn/8;
        double differential=Math.max(-5,Math.min(5,BodyNative.number(engine,"WheelDifferential")*nativeTurn));
        if(!trackEngine)differential*=-Math.abs(speed);
        BodyNative.set(e,"LeftWheelRot",(float)(BodyNative.number(e,"LeftWheelRot")-wheel*speed+differential));
        BodyNative.set(e,"RightWheelRot",(float)(BodyNative.number(e,"RightWheelRot")-wheel*speed-differential));
        // Native wheel rudder is opposite to chassis curvature; tracks do not steer wheel bones.
        BodyNative.set(e,"RudderRot",trackEngine?0:(float)-steer);
        if(trackEngine){
            double track=BodyNative.number(engine,"TrackRotSpeed");
            double trackDifferential=BodyNative.number(engine,"TrackDifferential");
            BodyNative.set(e,"LeftTrack",(float)(BodyNative.number(e,"LeftTrack")-track*Math.PI*speed-trackDifferential*Math.PI*turn/8));
            BodyNative.set(e,"RightTrack",(float)(BodyNative.number(e,"RightTrack")-track*Math.PI*speed+trackDifferential*Math.PI*turn/8));
        }
    }
    public static void nativeEngine(Object engine,Object vehicle){BodyNative.work(engine,vehicle);}
    public static Vec3 landing(Object vehicle,Vec3 motion,List<?> obbs){return owns((Entity)vehicle)?BodyNative.landing(vehicle,motion,obbs):BodyNative.resolve(vehicle,motion,obbs);}
}
