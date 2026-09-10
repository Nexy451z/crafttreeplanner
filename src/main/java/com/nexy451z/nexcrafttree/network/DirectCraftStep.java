package com.nexy451z.nexcrafttree.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 直接作成の1工程。
 * expectedOutput: クライアント側ツリー計算で判明したレシピの期待出力。
 *   サーバー側でレシピ出力を直接取得できない機械レシピ（Mekanism/特殊レシピ等）の場合にのみ採用され、
 *   取得できる場合はサーバー側のレシピ出力を優先して改ざんを防ぐ。
 * inputs: クライアント側で判明した材料リスト。サーバー側でレシピの正規入力が取得できない
 *   （getIngredientsが空のMODレシピ等）場合にのみ使用される。
 */
public record DirectCraftStep(Identifier recipeId, int count, ItemStack stationIcon,
                              ItemStack expectedOutput, List<ItemStack> inputs) {
    /** 1工程あたりの材料リスト上限（コーデックとクライアント検査で共用） */
    public static final int MAX_INPUTS = 16;

    public static final StreamCodec<RegistryFriendlyByteBuf, DirectCraftStep> STREAM_CODEC = StreamCodec.composite(
            Identifier.STREAM_CODEC, DirectCraftStep::recipeId,
            ByteBufCodecs.VAR_INT, DirectCraftStep::count,
            ItemStack.OPTIONAL_STREAM_CODEC, DirectCraftStep::stationIcon,
            ItemStack.OPTIONAL_STREAM_CODEC, DirectCraftStep::expectedOutput,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_INPUTS)), DirectCraftStep::inputs,
            DirectCraftStep::new
    );
}


