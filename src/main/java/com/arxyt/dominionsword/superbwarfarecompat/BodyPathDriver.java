package com.arxyt.dominionsword.superbwarfarecompat;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Server-tick trajectory execution. World access belongs to PlanSource, never a worker callback. */
final class BodyPathDriver implements AutoCloseable {
    record Vector(double x,double y,double z) {
        Vector add(Vector b){return new Vector(x+b.x,y+b.y,z+b.z);}
        Vector scale(double scale){return new Vector(x*scale,y*scale,z*scale);}
        double dot(Vector b){return x*b.x+y*b.y+z*b.z;}
        double horizontal(){return Math.hypot(x,z);}
    }
    record Parameters(double mass,double forwardCap,double reverseCap,double wheelbase,double gravity) {}
    record State(BodyPathPlanner.Pose pose,Vector velocity,boolean grounded,boolean horizontalCollision) {}
    record Motion(Vector velocity,double yaw,double steer,boolean arrived) {}
    record Job(CompletableFuture<List<BodyPathPlanner.Point>> result,Runnable cancel) {}
    interface PlanSource { Job request(BodyPathPlanner.Pose start,BodyPathPlanner.Pose goal,Parameters parameters); }

    private BodyPathPlanner.Pose goal,requestedGoal;
    private final PlanSource source;
    private Parameters parameters;
    private List<BodyPathPlanner.Point> path=List.of();
    private double[] limits=new double[0];
    private Job pending;
    private int index,legEnd,tick,retryAt,blocked;
    private boolean airborne,replan,legStopping,arrived,closed,goalDirty;
    private Vector expected=new Vector(0,0,0);

    BodyPathDriver(State state,BodyPathPlanner.Pose goal,Parameters parameters,PlanSource source){
        this.goal=goal;this.parameters=parameters;this.source=source;request(state);
    }

    boolean arrived(){return arrived;}
    boolean planning(){return pending!=null;}
    int index(){return index;}
    int legEnd(){return legEnd;}
    boolean waiting(){return pending!=null||path.isEmpty();}
    List<BodyPathPlanner.Point> path(){return path;}

    private void request(State state){
        if(pending!=null)pending.cancel.run();
        legStopping=false;path=List.of();limits=new double[0];index=0;blocked=0;
        requestedGoal=goal;goalDirty=false;
        pending=source.request(state.pose,goal,parameters);
    }

    /** Moving combat approaches coalesce while a valid prefix keeps running; explicit orders create a new driver. */
    void retarget(BodyPathPlanner.Pose updated){
        goal=updated;
        if(requestedGoal==null||distance(updated,requestedGoal)>.3||Math.abs(updated.y()-requestedGoal.y())>.3)goalDirty=true;
    }
    private void replaceRoute(State state){
        requestedGoal=goal;goalDirty=false;
        pending=source.request(state.pose,requestedGoal,parameters);
    }

    private void speedProfile(){
        limits=new double[path.size()];double braking=braking();
        for(int i=path.size()-2;i>=0;i--){
            var point=path.get(i);
            double corner=Math.sqrt(.015/Math.max(1e-6,Math.abs(Math.tan(point.steer())/parameters.wheelbase)));
            double cap=point.gear()<0?parameters.reverseCap:parameters.forwardCap;
            limits[i]=point.gear()!=path.get(i+1).gear()?0:Math.min(Math.min(cap,corner),
                    Math.sqrt(limits[i+1]*limits[i+1]+2*braking*distance(point.pose(),path.get(i+1).pose())));
        }
    }

    private void findEnd(){
        legEnd=Math.min(index,path.size()-1);int gear=path.get(legEnd).gear();
        while(legEnd+1<path.size()&&path.get(legEnd+1).gear()==gear)legEnd++;
    }

    Motion tick(State state,Parameters current,boolean stopRequested){
        tick++;
        if(!parameters.equals(current)){parameters=current;if(!path.isEmpty())speedProfile();}
        if(closed||stopRequested||arrived)return brake(state);
        if(!state.grounded)airborne=true;
        else if(airborne){airborne=false;replan=!alignedWithPath(state);}
        if(replan){
            Motion motion=brake(state);
            if(state.grounded&&motion.velocity.horizontal()<.005){request(state);replan=false;}
            return motion;
        }
        if(goalDirty&&pending==null&&!path.isEmpty())replaceRoute(state);
        if(pending!=null&&pending.result.isDone()){
            path=List.copyOf(pending.result.join());pending=null;
            legStopping=false;
            index=Math.min(1,Math.max(0,path.size()-1));
            if(path.isEmpty()){retryAt=tick+40;return brake(state);}
            findEnd();speedProfile();
            double speed=state.velocity.horizontal(),measured=state.velocity.dot(forward(state.pose.yaw()));
            double closest=Double.POSITIVE_INFINITY;
            for(int i=0;i<=legEnd;i++)closest=Math.min(closest,distance(state.pose,path.get(i).pose()));
            if(closest>.5||speed>.005&&(measured*path.get(index).gear()<0
                    ||distance(state.pose,path.get(legEnd).pose())<speed*speed/(2*braking())+.2)){
                replan=true;return brake(state);
            }
        }
        if(pending!=null&&path.isEmpty())return brake(state);
        if(path.isEmpty()){
            if(tick>=retryAt)request(state);
            return brake(state);
        }
        double measured=state.velocity.dot(forward(state.pose.yaw()));
        var end=path.get(legEnd).pose();
        Vector tangent=forward(end.yaw()).scale(path.get(legEnd).gear());
        boolean passed=new Vector(state.pose.x()-end.x(),0,state.pose.z()-end.z()).dot(tangent)>0&&distance(state.pose,end)<1;
        if(distance(state.pose,end)<.18||passed)legStopping=true;
        if(legStopping&&Math.abs(measured)<.005){
            if(legEnd<path.size()-1&&distance(state.pose,end)<=.35){index=legEnd+1;findEnd();end=path.get(legEnd).pose();legStopping=false;}
            else if(legEnd==path.size()-1&&state.grounded&&distance(state.pose,goal)<=.3&&Math.abs(state.pose.y()-goal.y())<.3)arrived=true;
            else if(pending!=null)return brake(state);
            else{replan=true;return brake(state);}
        }
        int nearest=index;double nearestDistance=distance(state.pose,path.get(index).pose());
        for(int i=index+1;i<=legEnd;i++){double d=distance(state.pose,path.get(i).pose());if(d<nearestDistance){nearest=i;nearestDistance=d;}}
        if(nearestDistance<1)index=nearest;
        while(index<legEnd&&distance(state.pose,path.get(index).pose())<.05)index++;
        int carrot=index;double walked=distance(state.pose,path.get(carrot).pose()),look=Math.min(2.5,1+Math.abs(measured)*4);
        while(carrot<legEnd&&walked<look&&Math.abs(path.get(carrot+1).steer()-path.get(index).steer())<.12){
            walked+=distance(path.get(carrot).pose(),path.get(carrot+1).pose());carrot++;
        }
        var target=path.get(carrot).pose();int gear=path.get(index).gear();
        double bearing=Math.toDegrees(Math.atan2(state.pose.x()-target.x(),target.z()-state.pose.z()));
        double error=BodyDriveDynamics.wrap(bearing-state.pose.yaw()+(gear<0?180:0));
        double steer=Math.max(-.6,Math.min(.6,Math.atan2(2*parameters.wheelbase*Math.sin(Math.toRadians(error)),Math.max(.1,distance(state.pose,target)))))*gear;
        double brake=braking(),acceleration=.012*Math.sqrt(13.6/parameters.mass);
        double cap=Math.min(gear<0?parameters.reverseCap:parameters.forwardCap,
                Math.sqrt(limits[index]*limits[index]+2*brake*distance(state.pose,path.get(index).pose())));
        cap=Math.min(cap,Math.sqrt(.015/Math.max(1e-6,Math.abs(Math.tan(steer)/parameters.wheelbase))));
        double margin=legEnd==path.size()-1?Math.max(0,.12-distance(end,goal)):.12;
        double remaining=Math.max(0,distance(state.pose,end)-margin);
        double fall=parameters.gravity>0?Math.sqrt(2*Math.max(0,state.pose.y()-end.y())/parameters.gravity):0;
        cap=Math.min(cap,Math.sqrt(brake*brake*fall*fall+2*brake*remaining)-brake*fall);
        if(state.grounded&&distance(state.pose,goal)<.22&&Math.abs(state.pose.y()-goal.y())<.3&&Math.abs(measured)<.05)arrived=true;
        double demand=arrived||legStopping?0:cap*gear;
        if(demand*measured<0&&Math.abs(measured)>.005)demand=0;
        double speed=BodyDriveDynamics.approach(measured,demand,Math.abs(demand)>Math.abs(measured)?acceleration:brake);
        if(!legStopping&&state.horizontalCollision&&Math.abs(measured)<.015)blocked++;else blocked=0;
        if(blocked>=20){replan=true;return brake(state);}
        if(!state.grounded){expected=state.velocity;return new Motion(state.velocity,state.pose.yaw(),steer,arrived);}
        double oldYaw=Math.toRadians(state.pose.yaw()),curvature=Math.tan(steer)/parameters.wheelbase,angle=speed*curvature;
        Vector residual=state.velocity.add(expected.scale(-1)),direction=forward(state.pose.yaw());
        double along=residual.dot(direction);Vector lateral=new Vector(residual.x-direction.x*along,0,residual.z-direction.z*along);
        double lateralSpeed=lateral.horizontal();if(lateralSpeed>1e-8)lateral=lateral.scale(Math.max(0,1-.012/lateralSpeed));
        double dx=Math.abs(curvature)<1e-9?-Math.sin(oldYaw)*speed:(Math.cos(oldYaw+angle)-Math.cos(oldYaw))/curvature;
        double dz=Math.abs(curvature)<1e-9?Math.cos(oldYaw)*speed:(Math.sin(oldYaw+angle)-Math.sin(oldYaw))/curvature;
        expected=new Vector(dx+lateral.x,state.velocity.y,dz+lateral.z);
        return new Motion(expected,BodyDriveDynamics.wrap(state.pose.yaw()+Math.toDegrees(angle)),steer,arrived);
    }

    private Motion brake(State state){
        double speed=state.velocity.horizontal(),scale=speed<1e-9?0:Math.max(0,speed-braking())/speed;
        expected=state.grounded?new Vector(state.velocity.x*scale,state.velocity.y,state.velocity.z*scale):state.velocity;
        return new Motion(expected,state.pose.yaw(),0,arrived);
    }
    /** Straight descents keep their route; an airborne chassis cannot execute a planned ground turn. */
    private boolean alignedWithPath(State state){
        if(path.isEmpty())return false;
        BodyPathPlanner.Point nearest=path.get(Math.min(index,path.size()-1));double error=distance(state.pose,nearest.pose());
        for(int i=index;i<=legEnd;i++){double next=distance(state.pose,path.get(i).pose());if(next<error){error=next;nearest=path.get(i);}}
        double positionAllowance=Math.min(.25,Math.max(.03,nearest.clearance()*.5));
        double reserve=Math.toDegrees((Math.tan(.6)-Math.abs(Math.tan(nearest.steer())))*Math.min(2.5,parameters.wheelbase)/parameters.wheelbase);
        double yawAllowance=Math.max(.5,Math.min(5,reserve));
        return error<=positionAllowance&&Math.abs(BodyDriveDynamics.wrap(state.pose.yaw()-nearest.pose().yaw()))<=yawAllowance;
    }
    private double braking(){return .025*Math.sqrt(13.6/Math.max(1,parameters.mass));}
    private static double distance(BodyPathPlanner.Pose a,BodyPathPlanner.Pose b){return Math.hypot(a.x()-b.x(),a.z()-b.z());}
    private static Vector forward(double yaw){double angle=Math.toRadians(yaw);return new Vector(-Math.sin(angle),0,Math.cos(angle));}
    @Override public void close(){closed=true;if(pending!=null)pending.cancel.run();path=List.of();}
}
