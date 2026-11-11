package com.arxyt.dominionsword.superbwarfarecompat;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Native AABB support and head clearance must agree when entering and leaving obstacles. */
class BodyCollisionSceneTest {
    @Test void rearSupportIsRetainedUntilTheWholeChassisLeavesTheFence(){
        var floor=new BodyCollisionScene.Bounds(-10,-1,-10,10,0,10);
        var fence=new BodyCollisionScene.Bounds(-.125,0,-.125,.125,1.5,.125);
        var world=new BodyCollisionScene.World(List.of(floor,fence),new BodyCollisionScene.Bounds(-10,-2,-10,10,10,10));
        var scene=new BodyCollisionScene(world,new BodyCollisionScene.Bounds(-2,0,-2,2,2.5,2),2.25);
        assertEquals(1.5,scene.height(new BodyPathPlanner.Pose(0,1.5,0,0),1,0),1e-8);
        assertEquals(0,scene.height(new BodyPathPlanner.Pose(1,1.5,0,0),3,0),1e-8);
    }
    @Test void aClearDestinationDoesNotPermitSteppingThroughAStartingCeiling(){
        var floor=new BodyCollisionScene.Bounds(-5,-1,-5,5,0,5);
        var step=new BodyCollisionScene.Bounds(1,0,-1,3,.5,1);
        var ceiling=new BodyCollisionScene.Bounds(-1,1.2,-1,.75,3,1);
        var bounds=new BodyCollisionScene.Bounds(-5,-2,-5,5,5,5);
        var chassis=new BodyCollisionScene.Bounds(-.3,0,-.3,.3,1,.3);
        var open=new BodyCollisionScene(new BodyCollisionScene.World(List.of(floor,step),bounds),chassis,1);
        var covered=new BodyCollisionScene(new BodyCollisionScene.World(List.of(floor,step,ceiling),bounds),chassis,1);
        var start=new BodyPathPlanner.Pose(0,0,0,0);
        assertEquals(.5,open.height(start,1.5,0),1e-8);
        assertTrue(Double.isNaN(covered.height(start,1.5,0)));
    }
}
