package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

record BeginMortarAimPacket(UUID mortarId) {
    static void encode(BeginMortarAimPacket packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.mortarId); }
    static BeginMortarAimPacket decode(FriendlyByteBuf buffer) { return new BeginMortarAimPacket(buffer.readUUID()); }
    static void handle(BeginMortarAimPacket packet, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MortarTargetingClient.begin(packet.mortarId)));
        supplier.get().setPacketHandled(true);
    }
}
