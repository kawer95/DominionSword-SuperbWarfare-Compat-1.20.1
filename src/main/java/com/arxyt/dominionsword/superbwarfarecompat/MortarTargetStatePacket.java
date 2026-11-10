package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

record MortarTargetStatePacket(UUID mortarId, boolean valid, BlockPos target) {
    static void encode(MortarTargetStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.mortarId);
        buffer.writeBoolean(packet.valid);
        if (packet.valid) buffer.writeBlockPos(packet.target);
    }

    static MortarTargetStatePacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        boolean valid = buffer.readBoolean();
        return new MortarTargetStatePacket(id, valid, valid ? buffer.readBlockPos() : BlockPos.ZERO);
    }

    static void handle(MortarTargetStatePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MortarTargetingClient.setMarkedTarget(packet.mortarId, packet.valid, packet.target)));
        context.get().setPacketHandled(true);
    }
}
