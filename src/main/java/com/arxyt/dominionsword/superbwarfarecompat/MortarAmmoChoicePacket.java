package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

record MortarAmmoChoicePacket(UUID mortarId, UUID unitId, ItemStack sample) {
    static void encode(MortarAmmoChoicePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.mortarId); buffer.writeUUID(packet.unitId); buffer.writeItem(packet.sample.copyWithCount(1));
    }
    static MortarAmmoChoicePacket decode(FriendlyByteBuf buffer) {
        return new MortarAmmoChoicePacket(buffer.readUUID(), buffer.readUUID(), buffer.readItem());
    }
    static void handle(MortarAmmoChoicePacket packet, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> {
            ServerPlayer player = supplier.get().getSender();
            if (player != null) MortarCommands.chooseAmmo(player, packet.mortarId, packet.unitId, packet.sample);
        });
        supplier.get().setPacketHandled(true);
    }
}
