package com.nexy451z.nexcrafttree.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

public record ServerboundTakeOutputPayload() implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nexcrafttree", "take_output");
    public static final Type<ServerboundTakeOutputPayload> TYPE = new Type<>(ID);
    public static final ServerboundTakeOutputPayload INSTANCE = new ServerboundTakeOutputPayload();
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundTakeOutputPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}


