package com.arxyt.dominionsword.superbwarfarecompat;

import java.util.*;

/** Immutable, indexed collision snapshot for AABB chassis planning on a worker thread. */
final class BodyCollisionScene implements BodyPathPlanner.Ground {
    private static final double EPS=1e-6;
    record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        Bounds move(double x,double y,double z){return new Bounds(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
        boolean overlaps(Bounds b){return maxX>b.minX+EPS&&minX<b.maxX-EPS&&maxY>b.minY+EPS&&minY<b.maxY-EPS&&maxZ>b.minZ+EPS&&minZ<b.maxZ-EPS;}
        boolean horizontal(Bounds b){return maxX>b.minX+EPS&&minX<b.maxX-EPS&&maxZ>b.minZ+EPS&&minZ<b.maxZ-EPS;}
        boolean contains(Bounds b){return b.minX>=minX-EPS&&b.minY>=minY-EPS&&b.minZ>=minZ-EPS&&b.maxX<=maxX+EPS&&b.maxY<=maxY+EPS&&b.maxZ<=maxZ+EPS;}
    }
    private record Cell(int x,int y,int z) {}
    static final class World {
        final Bounds bounds;
        private final Map<Cell,List<Bounds>> bins;
        World(List<Bounds> source,Bounds bounds){
            this.bounds=bounds;Map<Cell,List<Bounds>> index=new HashMap<>();
            for(Bounds box:source)for(int x=cell(box.minX);x<=cell(box.maxX);x++)for(int y=cell(box.minY);y<=cell(box.maxY);y++)for(int z=cell(box.minZ);z<=cell(box.maxZ);z++)index.computeIfAbsent(new Cell(x,y,z),ignored->new ArrayList<>()).add(box);
            Map<Cell,List<Bounds>> frozen=new HashMap<>();index.forEach((key,value)->frozen.put(key,List.copyOf(value)));bins=Collections.unmodifiableMap(frozen);
        }
        List<Bounds> query(Bounds area){
            List<Bounds> result=new ArrayList<>();
            for(int x=cell(area.minX);x<=cell(area.maxX);x++)for(int y=cell(area.minY);y<=cell(area.maxY);y++)for(int z=cell(area.minZ);z<=cell(area.maxZ);z++){
                List<Bounds> bucket=bins.get(new Cell(x,y,z));if(bucket==null)continue;
                for(Bounds b:bucket)if(b.maxX>=area.minX&&b.minX<=area.maxX&&b.maxY>=area.minY&&b.minY<=area.maxY&&b.maxZ>=area.minZ&&b.minZ<=area.maxZ)result.add(b);
            }
            return result;
        }
        private static int cell(double value){return (int)Math.floor(value/8);}
    }

    private final World world;
    private final Bounds chassis;
    private final double step;
    BodyCollisionScene(World world,Bounds chassis,double step){this.world=world;this.chassis=chassis;this.step=step;}

    @Override public double height(BodyPathPlanner.Pose from,double x,double z){
        Bounds footprint=chassis.move(x,from.y(),z);
        Bounds area=new Bounds(footprint.minX+EPS,footprint.minY-3,footprint.minZ+EPS,footprint.maxX-EPS,footprint.minY+step+.001,footprint.maxZ-EPS);
        NavigableSet<Double> heights=new TreeSet<>(Comparator.<Double>comparingDouble(y->Math.abs(y-from.y())).thenComparingDouble(y->y));
        for(Bounds b:world.query(area))if(b.horizontal(area)&&b.maxY>=area.minY&&b.maxY<=area.maxY)heights.add(b.maxY-chassis.minY);
        for(double y:heights){
            Bounds target=chassis.move(x,y,z);if(!world.bounds.contains(target)||collides(target))continue;
            double raised=Math.max(y,from.y());Bounds initial=chassis.move(from.x(),from.y(),from.z());
            if(y>from.y()&&!clear(initial,0,y-from.y(),0))continue;
            Bounds across=chassis.move(from.x(),raised,from.z());
            if(!clear(across,x-from.x(),0,z-from.z()))continue;
            if(y<from.y()&&!clear(chassis.move(x,from.y(),z),0,y-from.y(),0))continue;
            return y;
        }
        return Double.NaN;
    }

    @Override public double clearance(BodyPathPlanner.Pose pose){
        Bounds body=chassis.move(pose.x(),pose.y(),pose.z());double distance=.5;
        Bounds area=new Bounds(body.minX-.5,body.minY,body.minZ-.5,body.maxX+.5,body.maxY,body.maxZ+.5);
        for(Bounds obstacle:world.query(area)){
            if(obstacle.maxY<=body.minY+EPS||obstacle.minY>=body.maxY-EPS)continue;
            double dx=Math.max(0,Math.max(obstacle.minX-body.maxX,body.minX-obstacle.maxX));
            double dz=Math.max(0,Math.max(obstacle.minZ-body.maxZ,body.minZ-obstacle.maxZ));distance=Math.min(distance,Math.hypot(dx,dz));
        }
        return distance;
    }

    private boolean collides(Bounds body){for(Bounds obstacle:world.query(body))if(body.overlaps(obstacle))return true;return false;}
    private boolean clear(Bounds from,double dx,double dy,double dz){
        Bounds area=new Bounds(from.minX+Math.min(0,dx),from.minY+Math.min(0,dy),from.minZ+Math.min(0,dz),from.maxX+Math.max(0,dx),from.maxY+Math.max(0,dy),from.maxZ+Math.max(0,dz));
        if(!world.bounds.contains(area))return false;
        double[] delta={dx,dy,dz},lo={from.minX,from.minY,from.minZ},hi={from.maxX,from.maxY,from.maxZ};
        for(Bounds b:world.query(area)){
            double[] min={b.minX,b.minY,b.minZ},max={b.maxX,b.maxY,b.maxZ};double enter=0,exit=1;boolean separate=false;
            for(int axis=0;axis<3;axis++){
                if(Math.abs(delta[axis])<1e-12){if(hi[axis]<=min[axis]+EPS||lo[axis]>=max[axis]-EPS){separate=true;break;}continue;}
                double a=(min[axis]-hi[axis])/delta[axis],c=(max[axis]-lo[axis])/delta[axis];if(a>c){double swap=a;a=c;c=swap;}
                enter=Math.max(enter,a);exit=Math.min(exit,c);if(enter>=exit-EPS){separate=true;break;}
            }
            if(!separate&&enter<1-EPS&&exit>EPS)return false;
        }
        return true;
    }
}
