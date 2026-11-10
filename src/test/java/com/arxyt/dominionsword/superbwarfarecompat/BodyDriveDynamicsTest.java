package com.arxyt.dominionsword.superbwarfarecompat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Chassis constraints independent of Minecraft integration and native collision handling. */
class BodyDriveDynamicsTest {
    @Test void directCruiseIsNotLimitedToTheOldPedalSpeed(){
        var state=new BodyDriveDynamics();double speed=0;
        for(int i=0;i<100;i++)speed=state.step(false,0,64,speed,4.15,.8,.012,false,false,.48,.025).speed();
        assertEquals(.8,speed,1e-9);
    }
    @Test void turnsKeepRollingButBrakingStillControlsTheLastMeter(){
        var state=new BodyDriveDynamics();double speed=0;
        for(int i=0;i<100;i++)speed=state.step(false,150,30,speed,4.15,.8,.012,false,false,.48,.025).speed();
        assertTrue(speed>.25 && speed<.4);
        var out=state.step(false,0,.2,.2,4.15,.8,.012,true,false,.48,.025);
        assertEquals(.175,out.speed(),1e-9);
    }
    @Test void distantRearGoalUsesAForwardTurnEvenWithANearbyPathPoint(){
        for(double distance:new double[]{3,64,256}) {
            var state=new BodyDriveDynamics();
            var first=state.step(false,180,distance,0,4,.25,.003,false,false);
            assertTrue(first.speed()>0);assertTrue(Math.abs(first.yawStep())>0);
            double speed=first.speed();
            for(int i=0;i<80;i++)speed=state.step(false,170,distance,speed,4,.25,.003,false,false).speed();
            assertTrue(speed>=0,"angle-only gear switching must remain disabled");
        }
    }
    @Test void distantRearJourneyTurnsAroundAndDrivesForward(){
        var state=new BodyDriveDynamics();double x=0,z=0,yaw=-90,speed=0,reverseTravel=0,travel=0;
        for(int i=0;i<1200;i++) {
            double distance=Math.hypot(-64-x,z);
            var out=state.step(false,BodyDriveDynamics.wrap(Math.toDegrees(Math.atan2(x+64,-z))-yaw),distance,speed,4.15,.25,.00325,distance<=.22,distance<=10);
            speed=out.speed();yaw+=out.yawStep();reverseTravel+=Math.max(0,-speed);travel+=Math.abs(speed);
            x-=Math.sin(Math.toRadians(yaw))*speed;z+=Math.cos(Math.toRadians(yaw))*speed;
        }
        assertTrue(Math.hypot(x+64,z)<.35,"far goal reached");assertTrue(reverseTravel<5,"must not reverse for the journey");
        assertTrue(travel<100,"finish the turn before chasing the far target, rather than a wide orbit");
    }
    @Test void aNewTargetDoesNotKeepTheOldReverseDemand(){
        var state=new BodyDriveDynamics();
        state.step(false,170,3,0,3,.12,.003,false);
        state.resetTarget();
        assertTrue(state.step(false,0,3,0,3,.12,.003,false).speed()>0);
    }
    @Test void wheelReachesNearbyRearPointWithoutOrbiting(){
        for(double wheelbase:new double[]{2,3,4,5,6,7,8}) for(double acceleration:new double[]{.001,.002,.003,.004,.006,.012}) {
        var state=new BodyDriveDynamics();
        double x=0,z=0,yaw=-90,speed=0;
        for(int i=0;i<1200;i++) {
            double distance=Math.hypot(-8-x,4-z);
            double desired=Math.toDegrees(Math.atan2(x+8,4-z));
            var out=state.step(false,BodyDriveDynamics.wrap(desired-yaw),distance,speed,wheelbase,.12,acceleration,distance<=.22);
            speed=out.speed();yaw+=out.yawStep();
            x-=Math.sin(Math.toRadians(yaw))*speed;z+=Math.cos(Math.toRadians(yaw))*speed;
        }
        assertTrue(Math.hypot(x+8,z-4)<.35,"wheelbase="+wheelbase+" a="+acceleration+" final="+x+","+z+" yaw="+yaw+" speed="+speed);
        }
    }
    @Test void wheelDoesNotPivotWhenStationary(){
        var state=new BodyDriveDynamics();
        var out=state.step(false,90,3,0,3,0,.012,false);
        assertEquals(0,out.speed());assertEquals(0,out.yawStep());
    }
    @Test void measuredVelocityBoundsBrakingAndAcceleration(){
        var state=new BodyDriveDynamics();
        var out=state.step(false,0,10,.4,3,.12,.012,false);
        assertEquals(.388,out.speed(),1e-9);
        out=state.step(false,0,10,0,3,.12,.012,false);
        assertEquals(.012,out.speed(),1e-9);
    }
    @Test void tracksCanPivotWithoutForwardDrive(){
        var out=new BodyDriveDynamics().step(true,170,3,0,3,.12,.012,false);
        assertEquals(0,out.speed());assertTrue(out.yawStep()>0 && out.yawStep()<=.18);
    }
    @Test void tracksBrakeBeforeSweepingCorners(){
        var out=new BodyDriveDynamics().step(true,120,5,.12,5,.12,.003,false);
        assertEquals(.117,out.speed(),1e-9);assertEquals(0,out.yawStep());
    }
    @Test void reverseDoesNotFlipOnEveryBearingUpdate(){
        var state=new BodyDriveDynamics();
        double speed=state.step(false,170,3,0,3,.12,.012,false).speed();
        assertTrue(speed<0);
        for(int i=0;i<10;i++)speed=state.step(false,100,3,speed,3,.12,.012,false).speed();
        assertTrue(speed<0);
    }
}
