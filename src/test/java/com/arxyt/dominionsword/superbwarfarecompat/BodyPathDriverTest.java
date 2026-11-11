package com.arxyt.dominionsword.superbwarfarecompat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** User-visible contracts: pending jobs brake, cancelled jobs cannot regain control, and parking stays latched. */
class BodyPathDriverTest {
    private static final BodyPathDriver.Parameters PARAMETERS=new BodyPathDriver.Parameters(22.9,1.2,.4,2.965625,.06);
    @Test void smallHeadingErrorUsesOneGentleApproachInsteadOfAlternatingHardTurns(){
        var route=BodyPathPlanner.plan(new BodyPathPlanner.Pose(0,0,0,2),new BodyPathPlanner.Pose(0,0,64,0),2.965625,(from,x,z)->0);
        assertFalse(route.isEmpty());
        double previous=0;
        for(var point:route){
            assertTrue(Math.abs(point.steer())<Math.toRadians(1));
            if(Math.abs(point.steer())>1e-8){assertTrue(previous==0||previous*point.steer()>0);previous=point.steer();}
        }
        var end=route.get(route.size()-1).pose();assertTrue(Math.hypot(end.x(),end.z()-64)<.03);
    }
    @Test void pendingAndCancelledSearchesDoNotAccelerateTheVehicle(){
        AtomicBoolean cancelled=new AtomicBoolean();var future=new CompletableFuture<List<BodyPathPlanner.Point>>();
        var state=new BodyPathDriver.State(new BodyPathPlanner.Pose(0,0,0,0),new BodyPathDriver.Vector(0,0,.3),true,false);
        var driver=new BodyPathDriver(state,new BodyPathPlanner.Pose(0,0,16,0),PARAMETERS,(start,goal,p)->new BodyPathDriver.Job(future,()->cancelled.set(true)));
        assertTrue(driver.tick(state,PARAMETERS,false).velocity().horizontal()<.3);
        driver.close();assertTrue(cancelled.get());future.complete(List.of());
        assertTrue(driver.tick(state,PARAMETERS,false).velocity().horizontal()<.3);
    }
    @Test void passingThePathEndpointDoesNotRestartPowerBeforeSettling(){
        var start=new BodyPathPlanner.Pose(0,0,0,180);var goal=new BodyPathPlanner.Pose(0,0,16,0);
        var points=List.of(new BodyPathPlanner.Point(start,-1,0),new BodyPathPlanner.Point(new BodyPathPlanner.Pose(0,0,15.83,180),-1,0));
        var driver=new BodyPathDriver(new BodyPathDriver.State(start,new BodyPathDriver.Vector(0,0,0),true,false),goal,PARAMETERS,
                (from,to,p)->new BodyPathDriver.Job(CompletableFuture.completedFuture(points),()->{}));
        driver.tick(new BodyPathDriver.State(start,new BodyPathDriver.Vector(0,0,0),true,false),PARAMETERS,false);
        double z=15.84;var velocity=new BodyPathDriver.Vector(0,0,.08);double previous=.08;
        for(int tick=0;tick<30;tick++){
            var motion=driver.tick(new BodyPathDriver.State(new BodyPathPlanner.Pose(0,0,z,180),velocity,true,false),PARAMETERS,false);
            assertTrue(motion.velocity().horizontal()<=previous+1e-8,"parking must continue braking after crossing the endpoint");
            velocity=motion.velocity();previous=velocity.horizontal();z+=velocity.z();
        }
        assertTrue(driver.arrived());assertTrue(Math.abs(z-16)<.3);assertEquals(0,velocity.horizontal(),1e-8);
    }
    @Test void movingTargetsKeepAValidPrefixWhileOneReplacementIsPending(){
        var replacement=new CompletableFuture<List<BodyPathPlanner.Point>>();int[] requests={0};
        var start=new BodyPathPlanner.Pose(0,0,0,0);
        var driver=new BodyPathDriver(new BodyPathDriver.State(start,new BodyPathDriver.Vector(0,0,.3),true,false),new BodyPathPlanner.Pose(0,0,20,0),PARAMETERS,(from,to,p)->{
            requests[0]++;
            var result=requests[0]==1?CompletableFuture.completedFuture(List.of(new BodyPathPlanner.Point(start,1,0),new BodyPathPlanner.Point(to,1,0))):replacement;
            return new BodyPathDriver.Job(result,()->{});
        });
        double z=0;var velocity=new BodyPathDriver.Vector(0,0,.3);
        for(int tick=0;tick<10;tick++){
            driver.retarget(new BodyPathPlanner.Pose(0,0,20+tick*.1,0));
            var motion=driver.tick(new BodyPathDriver.State(new BodyPathPlanner.Pose(0,0,z,0),velocity,true,false),PARAMETERS,false);
            assertTrue(motion.velocity().z()>=.3,"a pending moving-target update must not repeatedly stop a usable route");
            velocity=motion.velocity();z+=velocity.z();
        }
        assertEquals(2,requests[0]);assertFalse(driver.arrived());
    }
}
