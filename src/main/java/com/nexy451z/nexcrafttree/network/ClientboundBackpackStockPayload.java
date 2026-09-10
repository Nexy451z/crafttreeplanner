package com.nexy451z.nexcrafttree.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** S→C: プレイヤーのバックパック群の中身（集計済みスタック一覧） */
public record ClientboundBackpackStockPayload(List<ItemStack> items) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nexcrafttree", "backpack_stock");
    public static final Type<ClientboundBackpackStockPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundBackpackStockPayload> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(2048)), ClientboundBackpackStockPayload::items,
            ClientboundBackpackStockPayload::new
    );

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
