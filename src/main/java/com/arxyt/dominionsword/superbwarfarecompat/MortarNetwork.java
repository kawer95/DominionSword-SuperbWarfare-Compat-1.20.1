package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

final class MortarNetwork {
    private static final String PROTOCOL = "1";
    static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(DominionSwordSuperbWarfareCompatMod.MODID, "mortar"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static int id;

    private MortarNetwork() {}

    static void register() {
        CHANNEL.registerMessage(id++, BeginMortarAimPacket.class, BeginMortarAimPacket::encode,
                BeginMortarAimPacket::decode, BeginMortarAimPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, OpenMortarAmmoPacket.class, OpenMortarAmmoPacket::encode,
                OpenMortarAmmoPacket::decode, OpenMortarAmmoPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MortarTargetStatePacket.class, MortarTargetStatePacket::encode,
                MortarTargetStatePacket::decode, MortarTargetStatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MortarAimTargetPacket.class, MortarAimTargetPacket::encode,
                MortarAimTargetPacket::decode, MortarAimTargetPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MortarAmmoChoicePacket.class, MortarAmmoChoicePacket::encode,
                MortarAmmoChoicePacket::decode, MortarAmmoChoicePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
