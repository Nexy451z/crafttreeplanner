package com.nexy451z.nexcrafttree.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/** C→S: プレイヤーの全バックパックの中身を集計して送り返すよう要求する */
public record ServerboundRequestBackpackStockPayload() implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nexcrafttree", "request_backpack_stock");
    public static final Type<ServerboundRequestBackpackStockPayload> TYPE = new Type<>(ID);
    public static final ServerboundRequestBackpackStockPayload INSTANCE = new ServerboundRequestBackpackStockPayload();
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundRequestBackpackStockPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    @NotNull
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
