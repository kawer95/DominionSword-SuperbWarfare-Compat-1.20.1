package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

record OpenMortarAmmoPacket(UUID mortarId, UUID unitId, String mortarName, List<AmmoEntry> entries) {
    record AmmoEntry(ItemStack sample, int count) {}

    static void encode(OpenMortarAmmoPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.mortarId);
        buffer.writeUUID(packet.unitId);
        buffer.writeUtf(packet.mortarName);
        buffer.writeVarInt(packet.entries.size());
        for (AmmoEntry entry : packet.entries) {
            buffer.writeItem(entry.sample.copyWithCount(1));
            buffer.writeVarInt(entry.count);
        }
    }

    static OpenMortarAmmoPacket decode(FriendlyByteBuf buffer) {
        UUID mortar = buffer.readUUID(), unit = buffer.readUUID();
        String name = buffer.readUtf();
        int size = Math.min(256, buffer.readVarInt());
        List<AmmoEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(new AmmoEntry(buffer.readItem(), buffer.readVarInt()));
        return new OpenMortarAmmoPacket(mortar, unit, name, entries);
    }

    static void handle(OpenMortarAmmoPacket packet, Supplier<NetworkEvent.Context> supplier) {
        supplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MortarAmmoScreen.open(packet)));
        supplier.get().setPacketHandled(true);
    }
}
