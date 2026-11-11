package com.arxyt.dominionsword.superbwarfarecompat;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pose-space routes: every edge is a driveable forward/reverse steering arc. */
final class BodyPathPlanner {
 record Pose(double x,double y,double z,double yaw) {}
 record Point(Pose pose,int gear,double steer,double clearance) {
  Point(Pose pose,int gear,double steer){this(pose,gear,steer,.25);}
 }
 interface Ground { double height(Pose from,double x,double z); default double clearance(Pose pose){return Double.POSITIVE_INFINITY;} }
 private record Node(Pose pose,int gear,double g,double score,Node parent,List<Point> edge,boolean finished) {}
 private record Arc(Pose end,List<Point> points,double climb,double proximity) {}
 private static String key(Pose p,int gear){return Math.round(p.x/.5)+":"+Math.round(p.y*4)+":"+Math.round(p.z/.5)+":"+Math.round(BodyDriveDynamics.wrap(p.yaw)/15)+":"+gear;}
 static List<Point> plan(Pose start,Pose goal,double wheelbase,Ground ground){return plan(start,goal,wheelbase,ground,()->false);}
 static List<Point> plan(Pose start,Pose goal,double wheelbase,Ground ground,BooleanSupplier cancelled) {
  long began=System.nanoTime();
  List<Point> level=search(start,goal,wheelbase,ground,Math.max(start.y,goal.y)+.02,cancelled);
  if(cancelled.getAsBoolean())return List.of();
  List<Point> raw=level.isEmpty()?search(start,goal,wheelbase,ground,Double.POSITIVE_INFINITY,cancelled):level;
  long searched=System.nanoTime();List<Point> result=smooth(raw,wheelbase,ground,cancelled);
  if(Boolean.getBoolean("dominionsword.superbwarfare.pathLog"))System.out.println("[DS-SW-BODY-PLAN] start="+start+" goal="+goal+" points="+result.size()+" searchMs="+(searched-began)/1e6+" smoothMs="+(System.nanoTime()-searched)/1e6);
  return result;
 }
 private static List<Point> search(Pose start,Pose goal,double wheelbase,Ground ground,double ceiling,BooleanSupplier cancelled) {
  double distance=Math.hypot(goal.x-start.x,goal.z-start.z);
  double reverseCost=distance<=10?1.05:4;
  // The level pass already establishes flat-route preference. Once it has no
  // route, use traversal cost rather than penalising a necessary climb again.
  double climbCost=Double.isFinite(ceiling)?wheelbase*4:3;
  boolean forwardFinish=distance>BodyDriveDynamics.reverseApproachDistance(wheelbase)&&Math.abs(BodyDriveDynamics.wrap(Math.toDegrees(Math.atan2(start.x-goal.x,goal.z-start.z))-start.yaw))>100;
  List<Pose> guide=distance>24?guide(start,goal,ground,ceiling,cancelled):List.of();
  PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score));Map<String,Double> costs=new HashMap<>();
  open.add(new Node(start,1,0,distance,null,List.of(),false));costs.put(key(start,1),0d);
  int expanded=0;
  while(!open.isEmpty()&&expanded++<20000&&!cancelled.getAsBoolean()){
   Node n=open.remove();if(n.finished)return unpack(n,List.of());if(n.g>costs.getOrDefault(key(n.pose,n.gear),Double.POSITIVE_INFINITY)+1e-8)continue;
   double remaining=Math.hypot(goal.x-n.pose.x,goal.z-n.pose.z);
   // Try a continuous approach from the initial pose as well as near the goal.
   // Quantized one-metre turns cannot represent a small heading correction.
   if(n.parent==null||remaining<4){
    for(int gear:new int[]{1,-1}){
     if(forwardFinish&&gear<0)continue;
     double bearing=Math.toDegrees(Math.atan2(n.pose.x-goal.x,goal.z-n.pose.z));
     double error=Math.toRadians(BodyDriveDynamics.wrap(bearing-n.pose.yaw+(gear<0?180:0)));
     double curvature=2*Math.sin(error)/Math.max(.001,remaining);
     if(Math.abs(error)>Math.PI*.5||Math.abs(curvature)>Math.tan(.6)/wheelbase)continue;
     double length=Math.abs(curvature)<1e-8?remaining:Math.abs(2*error/curvature);
     Arc end=arc(n.pose,gear,Math.atan(curvature*wheelbase)*gear,length,wheelbase,ground,ceiling);
     if(end!=null&&Math.hypot(end.end.x-goal.x,end.end.z-goal.z)<.03&&Math.abs(end.end.y-goal.y)<.2){
      // Prefer a clear, gentle forward approach over a lattice zigzag.
      // Every sample still checks chassis clearance, support and the level-pass ceiling.
      if(n.parent==null&&gear>0&&Math.abs(Math.atan(curvature*wheelbase))<=.1
              &&end.climb<.02&&end.proximity<1e-8)return unpack(n,end.points);
      double cost=n.g+length*(gear<0?reverseCost:1)+(gear==n.gear?0:wheelbase*.5)+end.climb*climbCost+end.proximity+length*Math.max(0,Math.abs(Math.atan(curvature*wheelbase))-.5)*3;
      open.add(new Node(end.end,gear,cost,cost,n,end.points,true));
     }
    }
    if(remaining<.18&&Math.abs(n.pose.y-goal.y)<.2&&(!forwardFinish||n.gear>0))return unpack(n,List.of());
   }
   for(int gear:new int[]{1,-1})for(double steer:steeringChoices(n.pose,goal,gear,wheelbase,guide))for(double stepLength:ground.clearance(n.pose)<.3?new double[]{.5,1}:new double[]{1}){
    Arc edge=arc(n.pose,gear,steer,stepLength,wheelbase,ground,ceiling);if(edge==null)continue;
    double g=n.g+stepLength*(gear<0?reverseCost:1)+stepLength*Math.max(0,Math.abs(steer)-.5)*3+(gear==n.gear?0:wheelbase*.5)+edge.climb*climbCost+edge.proximity;
    String k=key(edge.end,gear);if(g>=costs.getOrDefault(k,Double.POSITIVE_INFINITY))continue;
    costs.put(k,g);double h=estimate(edge.end,goal,guide);
    open.add(new Node(edge.end,gear,g,g+h*(guide.isEmpty()?1:2),n,edge.points,false));
   }
  }
  return List.of();
 }
 /** Add the actual gentle curvature towards the route, rather than quantizing every correction to 29 degrees. */
 private static double[] steeringChoices(Pose pose,Pose goal,int gear,double wheelbase,List<Pose> guide){
  Pose target=goal;
  if(!guide.isEmpty()){
   int nearest=0;double best=Double.POSITIVE_INFINITY;
   for(int i=0;i<guide.size();i++){double d=Math.hypot(pose.x-guide.get(i).x,pose.z-guide.get(i).z);if(d<best){best=d;nearest=i;}}
   int next=nearest;while(next<guide.size()-1&&Math.hypot(pose.x-guide.get(next).x,pose.z-guide.get(next).z)<6)next++;target=guide.get(next);
  }
  double bearing=Math.toDegrees(Math.atan2(pose.x-target.x,target.z-pose.z));
  double error=Math.toRadians(BodyDriveDynamics.wrap(bearing-pose.yaw+(gear<0?180:0)));
  double steer=Math.max(-.5,Math.min(.5,Math.atan2(2*wheelbase*Math.sin(error),Math.max(1,Math.hypot(pose.x-target.x,pose.z-target.z)))))*gear;
  return new double[]{steer,0,-.5,.5,-.6,.6};
 }
 /** Holonomic surface route guides long searches around buildings; pose edges still validate every turn. */
 private record GuideNode(Pose pose,double g,double score,GuideNode parent) {}
 private static List<Pose> guide(Pose start,Pose goal,Ground ground,double ceiling,BooleanSupplier cancelled){
  var open=new PriorityQueue<GuideNode>(Comparator.comparingDouble(GuideNode::score));var costs=new HashMap<String,Double>();
  open.add(new GuideNode(start,0,Math.hypot(start.x-goal.x,start.z-goal.z),null));
  for(int count=0;!open.isEmpty()&&count<12000&&!cancelled.getAsBoolean();count++){
   var n=open.remove();String key=Math.round((n.pose.x-start.x)/2)+":"+Math.round(n.pose.y*4)+":"+Math.round((n.pose.z-start.z)/2);
   if(n.g>costs.getOrDefault(key,Double.POSITIVE_INFINITY)+1e-6)continue;
   if(Math.hypot(n.pose.x-goal.x,n.pose.z-goal.z)<3&&line(n.pose,goal,ground,ceiling)){
    List<Pose> result=new ArrayList<>();result.add(goal);for(var c=n;c!=null;c=c.parent)result.add(c.pose);Collections.reverse(result);return result;
   }
   for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
    double x=n.pose.x+dx*2,z=n.pose.z+dz*2,y=ground.height(n.pose,x,z);if(!Double.isFinite(y)||y>ceiling)continue;
    var next=new Pose(x,y,z,0);if(!line(n.pose,next,ground,ceiling))continue;
    double g=n.g+Math.hypot(dx*2,dz*2)+Math.max(0,y-n.pose.y)*12;
    String k=Math.round((x-start.x)/2)+":"+Math.round(y*4)+":"+Math.round((z-start.z)/2);
    if(g>=costs.getOrDefault(k,Double.POSITIVE_INFINITY))continue;costs.put(k,g);
    open.add(new GuideNode(next,g,g+Math.hypot(x-goal.x,z-goal.z),n));
   }
  }
  return List.of();
 }
 private static boolean line(Pose a,Pose b,Ground ground,double ceiling){
  int steps=Math.max(1,(int)Math.ceil(Math.hypot(a.x-b.x,a.z-b.z)/.25));Pose prev=a;
  for(int i=1;i<=steps;i++){double t=i/(double)steps,x=a.x+(b.x-a.x)*t,z=a.z+(b.z-a.z)*t,y=ground.height(prev,x,z);if(!Double.isFinite(y)||y>ceiling)return false;prev=new Pose(x,y,z,0);}
  return Math.abs(prev.y-b.y)<.2;
 }
 private static double estimate(Pose pose,Pose goal,List<Pose> guide){
  if(guide.isEmpty())return Math.hypot(pose.x-goal.x,pose.z-goal.z);
  double remaining=0,best=Double.POSITIVE_INFINITY,result=0;
  for(int i=guide.size()-1;i>=0;i--){Pose p=guide.get(i);double d=Math.hypot(pose.x-p.x,pose.z-p.z);if(d<best){best=d;result=d+remaining;}if(i>0)remaining+=Math.hypot(p.x-guide.get(i-1).x,p.z-guide.get(i-1).z);}
  return result;
 }
 /** Replace lattice steering chatter by collision-checked curves, never crossing a gear change. */
 private static List<Point> smooth(List<Point> path,double wheelbase,Ground ground,BooleanSupplier cancelled){
  if(path.size()<3)return path;List<Point> result=new ArrayList<>();result.add(path.get(0));int i=0;
  while(i<path.size()-1&&!cancelled.getAsBoolean()){
   int end=i+1;while(end+1<path.size()&&path.get(end+1).gear==path.get(i+1).gear&&Math.abs(path.get(end+1).pose.y-path.get(i).pose.y)<.02&&Math.hypot(path.get(end+1).pose.x-path.get(i).pose.x,path.get(end+1).pose.z-path.get(i).pose.z)<40)end++;
   List<Point> curve=null;int chosen=i+1;
   for(int j=end;j>i+8;j-=8){curve=curve(path.get(i).pose,path.get(j).pose,path.get(i+1).gear,wheelbase,ground);if(curve!=null){chosen=j;break;}}
   if(curve==null)result.add(path.get(i+1));else result.addAll(curve);i=chosen;
  }
  return cancelled.getAsBoolean()?List.of():result;
 }
 private static List<Point> curve(Pose a,Pose b,int gear,double wheelbase,Ground ground){
  double length=Math.hypot(b.x-a.x,b.z-a.z);if(length<.5||Math.abs(a.y-b.y)>.02)return null;
  double ax=-Math.sin(Math.toRadians(a.yaw))*gear*length/3,az=Math.cos(Math.toRadians(a.yaw))*gear*length/3;
  double bx=-Math.sin(Math.toRadians(b.yaw))*gear*length/3,bz=Math.cos(Math.toRadians(b.yaw))*gear*length/3;
  double[] xs={a.x,a.x+ax,b.x-bx,b.x},zs={a.z,a.z+az,b.z-bz,b.z};
  int samples=(int)Math.ceil(length*8);List<Point> points=new ArrayList<>();Pose previous=a;
  for(int k=1;k<=samples;k++){
   double t=k/(double)samples,u=1-t;
   double x=u*u*u*xs[0]+3*u*u*t*xs[1]+3*u*t*t*xs[2]+t*t*t*xs[3],z=u*u*u*zs[0]+3*u*u*t*zs[1]+3*u*t*t*zs[2]+t*t*t*zs[3];
   double dx=3*u*u*(xs[1]-xs[0])+6*u*t*(xs[2]-xs[1])+3*t*t*(xs[3]-xs[2]),dz=3*u*u*(zs[1]-zs[0])+6*u*t*(zs[2]-zs[1])+3*t*t*(zs[3]-zs[2]);
   double ddx=6*u*(xs[2]-2*xs[1]+xs[0])+6*t*(xs[3]-2*xs[2]+xs[1]),ddz=6*u*(zs[2]-2*zs[1]+zs[0])+6*t*(zs[3]-2*zs[2]+zs[1]);
   double speed=Math.hypot(dx,dz);if(speed<1e-6)return null;
   double steer=Math.atan((dz*ddx-dx*ddz)*-gear*wheelbase/(speed*speed*speed));if(Math.abs(steer)>.5)return null;
   double y=ground.height(previous,x,z);if(!Double.isFinite(y)||Math.abs(y-a.y)>.02)return null;
   double yaw=BodyDriveDynamics.wrap(Math.toDegrees(Math.atan2(-dx,dz))+(gear<0?180:0));Pose p=new Pose(x,y,z,yaw);
   double clearance=ground.clearance(p);if(clearance<.15)return null;points.add(new Point(p,gear,steer,clearance));previous=p;
  }
  return points;
 }

 private static Arc arc(Pose start,int gear,double steer,double length,double wheelbase,Ground ground,double ceiling){
  Pose p=start;List<Point> points=new ArrayList<>();double travelled=0,climb=0,proximity=0;
  while(travelled<length-1e-7){double step=Math.min(.125,length-travelled);double delta=step*gear*Math.tan(steer)/wheelbase;double yaw=Math.toRadians(p.yaw);
   double x,z;
   if(Math.abs(delta)<1e-10){x=p.x-Math.sin(yaw)*step*gear;z=p.z+Math.cos(yaw)*step*gear;}
   else{double radius=wheelbase/Math.tan(steer);x=p.x+radius*(Math.cos(yaw+delta)-Math.cos(yaw));z=p.z+radius*(Math.sin(yaw+delta)-Math.sin(yaw));}
   double y=ground.height(p,x,z);if(!Double.isFinite(y)||y>ceiling)return null;
   climb+=Math.max(0,y-p.y);p=new Pose(x,y,z,BodyDriveDynamics.wrap(p.yaw+Math.toDegrees(delta)));double clearance=ground.clearance(p);points.add(new Point(p,gear,steer,clearance));proximity+=step*3*Math.max(0,1-clearance/.25);travelled+=step;
  }
  return new Arc(p,points,climb,proximity);
 }
 private static List<Point> unpack(Node end,List<Point> finish){
  List<Node> chain=new ArrayList<>();for(Node n=end;n!=null;n=n.parent)chain.add(n);Collections.reverse(chain);
  List<Point> path=new ArrayList<>();path.add(new Point(chain.get(0).pose,chain.size()>1?chain.get(1).gear:1,0));for(Node n:chain)path.addAll(n.edge);path.addAll(finish);return path;
 }
}
