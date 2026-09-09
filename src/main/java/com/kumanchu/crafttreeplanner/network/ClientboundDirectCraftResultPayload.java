package com.kumanchu.crafttreeplanner.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public record ClientboundDirectCraftResultPayload(
        boolean success,
        Component message,
        ItemStack resultStack,
        int count
) implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("crafttreeplanner", "direct_craft_result");
    public static final Type<ClientboundDirectCraftResultPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundDirectCraftResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ClientboundDirectCraftResultPayload::success,
            ComponentSerialization.STREAM_CODEC, ClientboundDirectCraftResultPayload::message,
            ItemStack.OPTIONAL_STREAM_CODEC, ClientboundDirectCraftResultPayload::resultStack,
            ByteBufCodecs.VAR_INT, ClientboundDirectCraftResultPayload::count,
            ClientboundDirectCraftResultPayload::new
    );

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
