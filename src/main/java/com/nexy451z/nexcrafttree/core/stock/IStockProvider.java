package com.nexy451z.nexcrafttree.core.stock;

import net.minecraft.world.item.ItemStack;

/**
 * 在庫取得の抽象化。AE2 / RS / 手持ちなど全てここに寄せる。
 * 実装は必ず例外を投げないこと（呼び出し側でもtry-catchする）。
 */
public interface IStockProvider {
    String getSourceName();

    /** 利用可能ならtrue。MOD未導入や画面未オープンならfalse。 */
    default boolean isAvailable() {
        return true;
    }

    /** 指定スタックと同一アイテムの所持数を返す。失敗時は0。 */
    long getAmount(ItemStack stack);

    /** 自動クラフト（RSパターン / AE2パターン）で作れるならtrue。分からなければfalse。 */
    default boolean isAutocraftable(ItemStack stack) {
        return false;
    }
}


