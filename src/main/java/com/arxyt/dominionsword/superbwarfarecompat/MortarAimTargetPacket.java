package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

record MortarAimTargetPacket(UUID mortarId, BlockPos target) {
    static void encode(MortarAimTargetPacket packet, FriendlyByteBuf buffer) { buffer.writeUUID(packet.mortarId); buffer.writeBlockPos(packet.target); }
    static MortarAimTargetPacket decode(FriendlyByteBuf buffer) { return new MortarAimTargetPacket(buffer.readUUID(), buffer.readBlockPos()); }
    static void handle(MortarAimTargetPacket packet, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            ServerPlayer player = supplier.get().getSender();
            if (player != null) MortarCommands.aim(player, packet.mortarId, packet.target);
        });
        supplier.get().setPacketHandled(true);
    }
}
