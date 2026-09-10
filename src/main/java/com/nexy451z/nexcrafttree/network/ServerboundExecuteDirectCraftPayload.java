package com.nexy451z.nexcrafttree.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ServerboundExecuteDirectCraftPayload(
        ItemStack targetItem,
        int quantity,
        List<DirectCraftStep> steps,
        ItemStack slottedWorkstation
) implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("nexcrafttree", "execute_direct_craft");
    public static final Type<ServerboundExecuteDirectCraftPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundExecuteDirectCraftPayload> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, ServerboundExecuteDirectCraftPayload::targetItem,
            ByteBufCodecs.VAR_INT, ServerboundExecuteDirectCraftPayload::quantity,
            DirectCraftStep.STREAM_CODEC.apply(ByteBufCodecs.list(512)), ServerboundExecuteDirectCraftPayload::steps,
            ItemStack.OPTIONAL_STREAM_CODEC, ServerboundExecuteDirectCraftPayload::slottedWorkstation,
            ServerboundExecuteDirectCraftPayload::new
    );

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}


