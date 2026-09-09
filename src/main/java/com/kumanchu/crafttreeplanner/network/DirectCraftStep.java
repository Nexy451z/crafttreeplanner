package com.kumanchu.crafttreeplanner.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record DirectCraftStep(ResourceLocation recipeId, int count, ItemStack stationIcon) {
    public static final StreamCodec<RegistryFriendlyByteBuf, DirectCraftStep> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, DirectCraftStep::recipeId,
            ByteBufCodecs.VAR_INT, DirectCraftStep::count,
            ItemStack.OPTIONAL_STREAM_CODEC, DirectCraftStep::stationIcon,
            DirectCraftStep::new
    );
}
