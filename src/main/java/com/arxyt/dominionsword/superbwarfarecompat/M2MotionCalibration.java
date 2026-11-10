package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionVehicleAdapter;
import com.arxyt.dominionsword.api.DominionVehicleAdapters;
import com.arxyt.dominionsword.vehicle.navigation.VehicleMotion;
import com.arxyt.dominionsword.vehicle.navigation.VehicleMotionSession;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import java.util.*;
import static com.arxyt.dominionsword.vehicle.navigation.VehicleMotion.*;

/** Explicit, bounded M0 test harness. Never invoked by ordinary move/pathfinding orders. */
final class M2MotionCalibration {
    private static final Logger LOG=LogUtils.getLogger();
    private static final String CAPABILITY="superbwarfare:bradley/flat-calibration-v1";
    private static final Map<UUID,Run> RUNS=new HashMap<>();
    // Initial hypotheses, not measured physics constants. Telemetry must validate them.
    private static final M2MotionInput.Calibration CALIBRATION=new M2MotionInput.Calibration(.12,.01,.4,2);
    private static final Tolerance TOLERANCE=new Tolerance(.3,4,.025,.3);
    private M2MotionCalibration() {}

    static void register(RegisterCommandsEvent event) {
        var root=Commands.literal("dominion_m2_test").requires(source->source.hasPermission(2));
        for(Action action:List.of(Action.FORWARD,Action.REVERSE,Action.PIVOT)) {
            String name=action==Action.PIVOT?"turn":action.name().toLowerCase(Locale.ROOT);
            root.then(Commands.literal(name).then(Commands.argument("vehicle",EntityArgument.entity())
                    .then(Commands.argument("amount",DoubleArgumentType.doubleArg(action==Action.PIVOT?-90:1,action==Action.PIVOT?90:4))
                    .executes(context->begin(context.getSource().getPlayerOrException(),EntityArgument.getEntity(context,"vehicle"),
                            action,DoubleArgumentType.getDouble(context,"amount"))))));
        }
        root.then(Commands.literal("cancel").then(Commands.argument("vehicle",EntityArgument.entity()).executes(context->{
            ServerPlayer player=context.getSource().getPlayerOrException();
            Run run=RUNS.get(EntityArgument.getEntity(context,"vehicle").getUUID());
            if(run==null || !run.owner.equals(player.getUUID())) return 0;
            finish(run,"cancelled",true);return 1;
        })));
        event.getDispatcher().register(root);
    }
    static boolean owns(Entity vehicle) { return vehicle!=null && RUNS.containsKey(vehicle.getUUID()); }

    private static int begin(ServerPlayer player,Entity vehicle,Action action,double amount) {
        if(!SuperbWarfareCompatConfig.M2_MOTION_CALIBRATION.get()) return reject(player,"请先在附属配置中开启 debug.m2MotionCalibration。");
        if(!RUNS.isEmpty()) return reject(player,"校准一次只允许一辆 M2；请等待当前动作结束或取消。");
        if(!BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString().equals("superbwarfare:bradley")
                || vehicle.level()!=player.level() || vehicle.distanceToSqr(player)>64*64)
            return reject(player,"仅支持同维度、64格内的 M2 布拉德利。");
        Entity driver=SuperbWarfareUnitAdapter.calibrationDriver(vehicle);
        if(!(driver instanceof Mob pilot) || !pilot.isAlive() || pilot.isRemoved() || vehicle.getFirstPassenger()!=pilot || !DominionControlApi.canControl(player,pilot)
                || !DominionVehicleAdapters.canOperate(player,vehicle,DominionVehicleAdapter.Operation.MOVE))
            return reject(player,"需要你有指挥权限的 NPC 驾驶员；不接管玩家驾驶。");
        if(!SuperbWarfareUnitAdapter.calibrationVehicleReady(vehicle))
            return reject(player,"需要非残骸、有足够能量、未涉水且姿态平稳的 M2。");
        if(!idle(vehicle,pilot) || !vehicle.onGround() || horizontalSpeed(vehicle)>.025
                || Math.abs(Mth.wrapDegrees(vehicle.getYRot()-vehicle.yRotO))>.3)
            return reject(player,"请先取消移动/战斗指令，让 M2 在平地完全停稳。");
        if(Math.abs(vehicle.getXRot())>3 || (action==Action.PIVOT && Math.abs(amount)<5))
            return reject(player,"当前仅校准平地动作，转向幅度需至少5度。");
        try {
            Pose start=pose(vehicle), end;
            double radians=Math.toRadians(start.yaw());
            double distance=action==Action.REVERSE?-amount:amount;
            if(action==Action.PIVOT) end=new Pose(start.x(),start.y(),start.z(),start.yaw()+amount,start.pitch(),start.roll());
            else end=new Pose(start.x()-Math.sin(radians)*distance,start.y(),start.z()+Math.cos(radians)*distance,start.yaw(),start.pitch(),start.roll());
            String surface="calibration:"+vehicle.getUUID();
            Segment segment=new Segment(UUID.randomUUID(),action,surface,surface,List.of(start,end),TOLERANCE,
                    action==Action.PIVOT?0:.35,8,List.of(new Dependency("local-corridor",1)));
            if(!SuperbWarfareUnitAdapter.calibrationPathClear(vehicle,segment)) return reject(player,"动作扫掠或支撑检查未通过，未接管车辆。");
            Snapshot snapshot=new Snapshot((ServerLevel)vehicle.level(),SuperbWarfareUnitAdapter.calibrationBounds(vehicle,segment));
            Request request=new Request(UUID.randomUUID(),1,vehicle.getUUID(),vehicle.level().dimension().location().toString(),
                    new Goal(end,surface,.3,.3,true,4,.025,.3));
            VehicleMotionSession session=new VehicleMotionSession(vehicle.getUUID(),request.dimension());
            var lease=session.begin(request);
            Plan plan=new Plan(UUID.randomUUID(),request.id(),request.revision(),1,CAPABILITY,List.of(segment),Terminal.FINAL_GOAL);
            Observation initial=new Observation(start,surface,horizontalSpeed(vehicle),0,true);
            if(!session.offer(lease,plan,initial,snapshot)) return reject(player,"会话拒绝了校准计划。");
            // Resolve the custom native method before acquiring ownership; do not silently accept a missing bridge.
            var nativeInput=vehicle.getClass().getMethod("processInput",short.class);
            Run run=new Run(vehicle,pilot,player.getUUID(),session,lease,request,plan,snapshot,nativeInput);
            RUNS.put(vehicle.getUUID(),run);
            nativeInput.invoke(vehicle,(short)16);
            LOG.info("[DS-M2-CAL] start action={} vehicle={} start={} end={} model={}",action,vehicle.getUUID(),start,end,CALIBRATION);
            player.sendSystemMessage(Component.literal("M2 校准开始："+action+"；仅测试单动作，最多400个服务器tick。"));
            return 1;
        } catch(ReflectiveOperationException | RuntimeException error) {
            Run run=RUNS.get(vehicle.getUUID());if(run!=null)finish(run,"bridge_error",true);
            LOG.warn("[DS-M2-CAL] cannot start calibration",error);
            return reject(player,"校准入口异常，已停止；请查看日志。");
        }
    }
    static void tick(MinecraftServer server) {
        for(Run run:List.copyOf(RUNS.values())) {
            if(run.vehicle.getServer()!=server)continue;
            try { tick(run,server); }
            catch(ReflectiveOperationException | RuntimeException error) {
                LOG.warn("[DS-M2-CAL] tick failed",error);finish(run,"bridge_error",true);
            }
        }
    }
    private static void tick(Run run,MinecraftServer server) throws ReflectiveOperationException {
        Entity vehicle=run.vehicle;
        ServerPlayer owner=server.getPlayerList().getPlayer(run.owner);
        if(!SuperbWarfareCompatConfig.M2_MOTION_CALIBRATION.get() || !vehicle.isAlive() || vehicle.isRemoved()
                || !run.pilot.isAlive() || run.pilot.isRemoved()
                || owner==null || owner.level()!=vehicle.level() || vehicle.distanceToSqr(owner)>64*64
                || SuperbWarfareUnitAdapter.calibrationDriver(vehicle)!=run.pilot || vehicle.getFirstPassenger()!=run.pilot
                || !DominionControlApi.canControl(owner,run.pilot)
                || !DominionVehicleAdapters.canOperate(owner,vehicle,DominionVehicleAdapter.Operation.MOVE)
                || DominionControlApi.commandView(run.pilot).commandRevision()!=run.pilotRevision || !idle(vehicle,run.pilot)) {
            finish(run,"authority_or_order_changed",SuperbWarfareUnitAdapter.calibrationDriver(vehicle)==run.pilot && vehicle.getFirstPassenger()==run.pilot);return;
        }
        if(!SuperbWarfareUnitAdapter.calibrationVehicleReady(vehicle)) { finish(run,"native_not_ready",true);return; }
        long tick=server.getTickCount();
        if(tick-run.started>=400) { finish(run,"timeout",true);return; }
        Pose pose=pose(vehicle);
        double angular=Math.abs(VehicleMotion.angle(pose.yaw()-run.previousYaw));run.previousYaw=pose.yaw();
        Observation observation=new Observation(pose,run.segment.entrySurface(),horizontalSpeed(vehicle),angular,vehicle.onGround());
        var command=run.session.command(run.lease,observation,run.snapshot);
        if(command.isEmpty()) { finish(run,run.session.reason().name(),true);return; }
        // This harness rechecks the short native-volume corridor, including dynamic vehicles, every tick.
        if(!SuperbWarfareUnitAdapter.calibrationPathClear(vehicle,run.segment)) { finish(run,"corridor_changed",true);return; }
        M2MotionInput.Decision decision=M2MotionInput.decide(run.segment,observation,CALIBRATION);
        // Require several supported, stopped observations before releasing the native brake.
        run.settledTicks=decision.complete()?run.settledTicks+1:0;
        short keys=decision.complete()?(short)16:decision.keys();
        run.nativeInput.invoke(vehicle,keys);
        if((tick-run.started)%5==0) LOG.info("[DS-M2-CAL] tick={} action={} pose={} speed={} angular={} keys={} decision={}",
                tick-run.started,run.segment.action(),pose,observation.speed(),angular,keys,decision.reason());
        if(decision.complete() && run.settledTicks>=5) {
            boolean accepted=run.session.feedback(run.lease,new Feedback(run.request.id(),run.request.revision(),run.plan.id(),
                    run.segment.id(),++run.feedbackSequence,Outcome.SEGMENT_FINISHED,observation),run.snapshot);
            finish(run,accepted && run.session.state()==VehicleMotionSession.State.ARRIVED?"arrived":"completion_rejected",true);
        }
    }
    private static boolean idle(Entity vehicle,Mob pilot) {
        var tag=vehicle.getPersistentData();var view=DominionControlApi.commandView(pilot);
        return !tag.getBoolean("DominionOfflineVehicleMove") && !tag.getBoolean("DominionOfflineVehicleAttack")
                && pilot.getTarget()==null && (view.order().isBlank() || view.order().equals("hold"));
    }
    private static double horizontalSpeed(Entity vehicle) {
        var v=vehicle.getDeltaMovement();return Math.hypot(v.x,v.z);
    }
    private static Pose pose(Entity vehicle) {
        double roll=vehicle instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity nativeVehicle
                ? nativeVehicle.getRoll():0;
        return new Pose(vehicle.getX(),vehicle.getY(),vehicle.getZ(),vehicle.getYRot(),vehicle.getXRot(),roll);
    }
    private static int reject(ServerPlayer player,String message) { player.sendSystemMessage(Component.literal(message));return 0; }
    static void unloaded(Entity entity) {
        for(Run run:List.copyOf(RUNS.values()))
            if(run.vehicle==entity || run.pilot==entity)finish(run,"unloaded",run.vehicle!=entity
                    && run.vehicle.getFirstPassenger()==run.pilot);
    }
    static void clear() { for(Run run:List.copyOf(RUNS.values()))finish(run,"server_stopped",false); }
    private static void finish(Run run,String reason,boolean brake) {
        // No input on a new player's driver seat. New commands regain ownership on the following normal tick.
        try { if(brake && !run.vehicle.isRemoved() && run.vehicle.getFirstPassenger()==run.pilot
                    && SuperbWarfareUnitAdapter.calibrationDriver(run.vehicle)==run.pilot)run.nativeInput.invoke(run.vehicle,(short)16); }
        catch(ReflectiveOperationException error) { LOG.warn("[DS-M2-CAL] final brake failed",error); }
        finally { run.session.cancel(run.lease);RUNS.remove(run.vehicle.getUUID(),run); }
        LOG.info("[DS-M2-CAL] end vehicle={} reason={} pose={}",run.vehicle.getUUID(),reason,pose(run.vehicle));
        if(run.vehicle.getServer()!=null) {
            ServerPlayer owner=run.vehicle.getServer().getPlayerList().getPlayer(run.owner);
            if(owner!=null)owner.sendSystemMessage(Component.literal("M2 校准结束："+reason+"（结果已记录日志）"));
        }
    }
    private static final class Snapshot implements VehicleMotionSession.WorldView {
        final ServerLevel level;final Map<BlockPos,BlockState> blocks=new HashMap<>();
        Snapshot(ServerLevel level,AABB bounds) {
            this.level=level;
            long count=(long)(Mth.ceil(bounds.maxX)-Mth.floor(bounds.minX)+1)
                    *(Mth.ceil(bounds.maxY)-Mth.floor(bounds.minY)+1)*(Mth.ceil(bounds.maxZ)-Mth.floor(bounds.minZ)+1);
            if(count>4096)throw new IllegalArgumentException("calibration snapshot too large");
            for(BlockPos p:BlockPos.betweenClosed(Mth.floor(bounds.minX),Mth.floor(bounds.minY),Mth.floor(bounds.minZ),
                    Mth.ceil(bounds.maxX),Mth.ceil(bounds.maxY),Mth.ceil(bounds.maxZ))) {
                if(!level.hasChunkAt(p))throw new IllegalArgumentException("unloaded calibration corridor");
                blocks.put(p.immutable(),level.getBlockState(p));
            }
        }
        public String capabilitySignature() { return CAPABILITY; }
        public boolean current(Dependency dependency) {
            if(!dependency.region().equals("local-corridor") || dependency.version()!=1)return false;
            for(var e:blocks.entrySet())if(!level.hasChunkAt(e.getKey()) || !level.getBlockState(e.getKey()).equals(e.getValue()))return false;
            return true;
        }
    }
    private static final class Run {
        final Entity vehicle;final Mob pilot;final UUID owner;final VehicleMotionSession session;
        final VehicleMotionSession.Lease lease;final Request request;final Plan plan;final Segment segment;
        final Snapshot snapshot;final java.lang.reflect.Method nativeInput;final long started,pilotRevision;
        double previousYaw;long feedbackSequence;int settledTicks;
        Run(Entity vehicle,Mob pilot,UUID owner,VehicleMotionSession session,VehicleMotionSession.Lease lease,
            Request request,Plan plan,Snapshot snapshot,java.lang.reflect.Method nativeInput) {
            this.vehicle=vehicle;this.pilot=pilot;this.owner=owner;this.session=session;this.lease=lease;this.request=request;
            this.plan=plan;this.segment=plan.segments().get(0);this.snapshot=snapshot;this.nativeInput=nativeInput;
            this.started=vehicle.getServer().getTickCount();this.pilotRevision=DominionControlApi.commandView(pilot).commandRevision();
            this.previousYaw=vehicle.getYRot();
        }
    }
}
