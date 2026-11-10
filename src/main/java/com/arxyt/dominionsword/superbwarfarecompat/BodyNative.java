package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** SW's public JVM properties, isolated from the core mod and cached by runtime class. */
final class BodyNative {
    private record Key(Class<?> owner, String name, List<Class<?>> parameters) {}
    private static final Map<Key, Method> METHODS = new ConcurrentHashMap<>();
    static Object invoke(Object target, String name, Class<?>[] parameters, Object... args) {
        try {
            Key key = new Key(target.getClass(), name, List.of(parameters));
            Method method = METHODS.computeIfAbsent(key, k -> {
                try { return k.owner.getMethod(k.name, parameters); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
    static Object get(Object target, String name) { return invoke(target, "get" + name, new Class<?>[0]); }
    static double number(Object target, String name) { return ((Number)get(target,name)).doubleValue(); }
    static boolean flag(Object target, String name) { return (Boolean)get(target,name); }
    static void set(Object target, String name, float value) { invoke(target,"set"+name,new Class<?>[]{float.class},value); }
    static void input(Entity entity, short keys) { invoke(entity,"processInput",new Class<?>[]{short.class},keys); }
    static void work(Object engine, Object vehicle) {
        try { invoke(engine,"work",new Class<?>[]{Class.forName("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")},vehicle); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }
    static Vec3 resolve(Object vehicle, Vec3 motion, List<?> obbs) {
        try {
            Class<?> base=Class.forName("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
            Class<?> utils=Class.forName("com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils");
            return (Vec3)utils.getMethod("resolveObbWorldCollision",base,Vec3.class,List.class).invoke(null,vehicle,motion,obbs);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    /** Sweep the step-down trial instead of testing only its deeply penetrating endpoint. */
    static Vec3 landing(Object vehicle, Vec3 motion, List<?> obbs) {
        if(motion.y>=-.125 || motion.x!=0 || motion.z!=0) return resolve(vehicle,motion,obbs);
        double descent=0;
        for(int i=0;i<256 && descent>motion.y+1e-7;i++) {
            double step=Math.max(-.0625,motion.y-descent);
            List<Object> moved=new ArrayList<>(obbs.size());
            for(Object obb:obbs)moved.add(invoke(obb,"move",new Class<?>[]{Vec3.class},new Vec3(0,descent,0)));
            double clipped=resolve(vehicle,new Vec3(0,step,0),moved).y;
            descent+=clipped;
            if(clipped>step+1e-7)break;
        }
        return new Vec3(0,descent,0);
    }
}
