package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** Tracking-only chassis presentation; entity UUID prevents ID reuse from transferring control. */
record BodyControlStatePacket(int id, UUID uuid, boolean active, float speed, float turn, float steer) {
    static void encode(BodyControlStatePacket p,FriendlyByteBuf b){b.writeVarInt(p.id);b.writeUUID(p.uuid);b.writeBoolean(p.active);b.writeFloat(p.speed);b.writeFloat(p.turn);b.writeFloat(p.steer);}
    static BodyControlStatePacket decode(FriendlyByteBuf b){return new BodyControlStatePacket(b.readVarInt(),b.readUUID(),b.readBoolean(),b.readFloat(),b.readFloat(),b.readFloat());}
    static void handle(BodyControlStatePacket p,Supplier<NetworkEvent.Context> c){
        c.get().enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->{
            var level=net.minecraft.client.Minecraft.getInstance().level;
            if(level==null)return;
            var entity=level.getEntity(p.id);
            if(entity!=null && entity.getUUID().equals(p.uuid)) BodyControl.clientState(entity,p);
        }));c.get().setPacketHandled(true);
    }
}
