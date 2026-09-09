package com.nexy451z.nexcrafttree.mixin;

import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Refined StorageクラフトグリッドのJEI転送ハンドラ(+ボタン)で、
 * 耐久値が減ったツール（クワ・ツルハシ等）が「正規の新品スタック」とのコンポーネント差異で
 * 不足扱いされる問題を緩和する。
 * 既存の完全一致removeが失敗した場合に限り、同一アイテムかつ耐久値系の違いだけの候補を
 * 引き当て直す（ItemMatchHelperのfuzzy判定と同じ粒度）。
 * 対象クラスが存在しない/変更された場合は設定 required:false により静かにスキップされる。
 */
@Mixin(targets = "com.refinedmods.refinedstorage.jei.common.CraftingGridRecipeTransferHandler", remap = false)
public abstract class CraftingGridRecipeTransferHandlerMixin {

    @Redirect(
            method = "toTransferInput",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/api/resource/list/MutableResourceList;remove(Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J)Lcom/refinedmods/refinedstorage/api/resource/list/MutableResourceList$OperationResult;"
            ),
            remap = false
    )
    private static MutableResourceList.OperationResult nex$fuzzyRemove(MutableResourceList available, ResourceKey key, long amount) {
        MutableResourceList.OperationResult result = available.remove(key, amount);
        if (result != null) {
            return result;
        }
        try {
            if (key instanceof ItemResource itemKey) {
                ItemStack needed = itemKey.toItemStack();
                if (needed != null && !needed.isEmpty() && ItemMatchHelper.isToolOrDamageable(needed)) {
                    for (ResourceKey candidate : available.getAll()) {
                        if (!(candidate instanceof ItemResource candidateItem)) continue;
                        ItemStack held = candidateItem.toItemStack();
                        if (held == null || held.isEmpty()) continue;
                        if (!ItemStack.isSameItem(held, needed)) continue;
                        // 耐久値系アイテム同士のみ緩和（ポーション等の厳格コンポーネントは触らない）
                        if (!ItemMatchHelper.isToolOrDamageable(held)) continue;
                        MutableResourceList.OperationResult fuzzy = available.remove(candidate, amount);
                        if (fuzzy != null) {
                            return fuzzy;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
