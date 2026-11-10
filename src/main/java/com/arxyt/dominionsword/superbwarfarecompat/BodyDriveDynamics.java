package com.arxyt.dominionsword.superbwarfarecompat;

/** Deterministic chassis demand. Measured velocity, not a hidden speed accumulator, is authoritative. */
final class BodyDriveDynamics {
    private int approachGear = 1;
    private boolean initialized, maneuver, forwardTurning;
    private int hold;
    private double angular;
    void resetTarget(){initialized=false;maneuver=false;forwardTurning=false;hold=0;}
    record Output(double speed, double yawStep, double steer) {}
    Output step(boolean tracked,double error,double distance,double measured,double wheelbase,double cap,double acceleration,boolean stop) {
        return step(tracked,error,distance,measured,wheelbase,cap,acceleration,stop,true);
    }
    static double reverseApproachDistance(double wheelbase){return Math.max(10,2*wheelbase);}
    Output step(boolean tracked,double error,double distance,double measured,double wheelbase,double cap,double acceleration,boolean stop,boolean allowReverseApproach) {
        return step(tracked,error,distance,measured,wheelbase,cap,acceleration,stop,allowReverseApproach,cap,acceleration);
    }
    Output step(boolean tracked,double error,double distance,double measured,double wheelbase,double cap,double acceleration,boolean stop,boolean allowReverseApproach,double reverseCap,double braking) {
        double radius=Math.max(1,wheelbase)/Math.tan(.6);
        if(hold>0)hold--;
        if(!initialized){approachGear=allowReverseApproach && Math.abs(error)>100 && !tracked?-1:1;initialized=true;hold=20;}
        if(!allowReverseApproach && approachGear<0){approachGear=1;maneuver=false;hold=20;}
        double heading=wrap(error+(approachGear<0?180:0));
        double needed=2*Math.abs(Math.sin(Math.toRadians(heading)))/Math.max(.01,distance);
        if(!tracked && !stop) {
            if(maneuver) {
                if(hold==0 && ((Math.abs(heading)<15 && needed<.8/radius) || distance>2*radius)){maneuver=false;hold=20;}
            } else if(hold==0) {
                if(allowReverseApproach && Math.abs(heading)>110){approachGear=-approachGear;heading=wrap(heading+180);hold=20;}
                else if(needed>1/radius && Math.abs(heading)>20 && distance<2*radius){maneuver=true;hold=25;}
            }
        }
        int gear=maneuver?-approachGear:approachGear;
        if(tracked || allowReverseApproach || Math.abs(heading)<30)forwardTurning=false;
        else if(Math.abs(heading)>110)forwardTurning=true;
        boolean forwardTurn=forwardTurning && !maneuver;
        double demand=maneuver?Math.toRadians(heading):Math.atan2(2*wheelbase*Math.sin(Math.toRadians(heading)),Math.max(.1,distance));
        // Pure pursuit has zero curvature at exactly 180 degrees: explicitly choose
        // a forward turning arc rather than driving straight away or selecting reverse.
        if(forwardTurn)demand=Math.copySign(.6,heading);
        double steer=Math.max(-.6,Math.min(.6,demand))*gear;
        double directionalCap=gear<0?Math.min(cap,reverseCap):cap;
        double curvature=Math.abs(Math.tan(steer)/Math.max(1,wheelbase));
        double cornerCap=tracked?(Math.abs(heading)>10?.38:directionalCap)
                :Math.sqrt(.015/Math.max(1e-6,curvature));
        double target=Math.min(directionalCap,cornerCap);
        if(maneuver)target=Math.min(target,.24);
        target=Math.min(target,Math.sqrt(2*braking*Math.max(0,distance-.15)))*gear;
        if(stop || tracked && Math.abs(heading)>15)target=0;
        if(Math.signum(measured)!=Math.signum(target) && Math.abs(measured)>.005)target=0;
        boolean accelerating=measured==0 || Math.signum(measured)==Math.signum(target) && Math.abs(target)>Math.abs(measured);
        double speed=approach(measured,target,accelerating?acceleration:braking);
        double turn=tracked?Math.copySign(Math.min(2,Math.sqrt(.36*Math.abs(heading))),heading)
                :Math.toDegrees(speed*Math.tan(steer)/Math.max(1,wheelbase));
        if(stop || Math.abs(heading)<.2)turn=0;
        if(tracked && Math.abs(heading)>15 && Math.abs(measured)>.005)turn=0;
        angular=approach(angular,turn,.18);
        if(!tracked){double bound=Math.toDegrees(Math.abs(speed)*Math.tan(.6)/Math.max(1,wheelbase));angular=Math.max(-bound,Math.min(bound,angular));}
        if(Math.abs(angular)>Math.abs(heading))angular=heading;
        return new Output(speed,angular,steer);
    }
    static double approach(double value,double target,double amount){return value+Math.max(-amount,Math.min(amount,target-value));}
    static double wrap(double degrees){double r=degrees%360;return r>=180?r-360:r< -180?r+360:r;}
}
