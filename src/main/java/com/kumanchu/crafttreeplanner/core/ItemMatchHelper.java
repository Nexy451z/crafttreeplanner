package com.kumanchu.crafttreeplanner.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * アイテム・レシピ・在庫のマッチング判定ユーティリティ。
 * 
 * 耐久値減ツール（クワ、ツルハシ、ハサミ等）の柔軟判定：
 * - バニラやJEIにおいて、耐久値が1でも減っているツールやエンチャント・名付けされたツールが
 *   「素材不足」として誤判定されるのを防ぐ。
 * - ツール・防具・武器等の消耗品は、耐久値（DataComponents.DAMAGE）やエンチャント・名前の違いを
 *   完全に無視し、ベースアイテム単位で柔軟に一致判定を行う。
 * - 一方でポーション（効果違い）、エンチャント本（エンチャント違い）、怪しいシチュー等の
 *   機能的に厳格なコンポーネントは厳密に比較する。
 */
public final class ItemMatchHelper {
    private ItemMatchHelper() {
    }

    /**
     * 在庫引き当て用のマッチング判定。
     * @param inventoryStack プレイヤー所持またはストレージ内の現物アイテム
     * @param neededStack レシピ側で要求されているアイテム
     */
    public static boolean isStockMatch(ItemStack inventoryStack, ItemStack neededStack) {
        if (inventoryStack == null || neededStack == null || inventoryStack.isEmpty() || neededStack.isEmpty()) {
            return false;
        }
        // 1. ベースアイテムIDが一致するか（異なるアイテムは即座に除外）
        if (!ItemStack.isSameItem(inventoryStack, neededStack)) {
            return false;
        }

        // 2. ツール・耐久値消費アイテム（クワ、斧、ツルハシ、剣、ハサミ、防具など）の判定
        // 耐久値（DAMAGE）、エンチャント、修繕コスト、名付け等はすべて無視し、
        // 同種ツールであれば手持ちの使用済み品（耐久減）を素材として充当可能とする
        if (isToolOrDamageable(inventoryStack) || isToolOrDamageable(neededStack)) {
            return true;
        }

        // 3. 機能的に厳格なコンポーネント（ポーション、エンチャント本、怪しいシチュー等）の判定
        if (hasStrictFunctionalComponents(neededStack)) {
            return matchesStrictComponents(inventoryStack, neededStack);
        }

        // 4. バックパック、コンテナ、一般素材（インゴット等）
        // レシピ側の要求に特段の制限がない限り、ベースアイテム一致で同一素材とみなす
        return true;
    }

    /**
     * ツールや耐久値を持つアイテム（または消耗可能なアイテム）か判定する。
     */
    public static boolean isToolOrDamageable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            if (stack.isDamageableItem() || stack.getMaxDamage() > 0) {
                return true;
            }
            if (stack.has(DataComponents.DAMAGE) || stack.has(DataComponents.MAX_DAMAGE)) {
                return true;
            }

            net.minecraft.world.item.Item item = stack.getItem();
            if (item instanceof net.minecraft.world.item.TieredItem
                    || item instanceof net.minecraft.world.item.DiggerItem
                    || item instanceof net.minecraft.world.item.SwordItem
                    || item instanceof net.minecraft.world.item.ArmorItem
                    || item instanceof net.minecraft.world.item.ShearsItem
                    || item instanceof net.minecraft.world.item.BowItem
                    || item instanceof net.minecraft.world.item.CrossbowItem
                    || item instanceof net.minecraft.world.item.FishingRodItem
                    || item instanceof net.minecraft.world.item.TridentItem
                    || item instanceof net.minecraft.world.item.FlintAndSteelItem
                    || item instanceof net.minecraft.world.item.ShieldItem) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * ポーションやエンチャント本など、クラフトにおいてコンポーネントが必須の意味を持つか確認する。
     */
    private static boolean hasStrictFunctionalComponents(ItemStack stack) {
        try {
            if (stack.has(DataComponents.POTION_CONTENTS)) return true;
            if (stack.has(DataComponents.STORED_ENCHANTMENTS)) return true;
            if (stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) return true;
            if (stack.has(DataComponents.OMINOUS_BOTTLE_AMPLIFIER)) return true;
            if (stack.has(DataComponents.INSTRUMENT)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 機能的コンポーネントの厳密一致確認。
     */
    private static boolean matchesStrictComponents(ItemStack inventoryStack, ItemStack neededStack) {
        try {
            if (neededStack.has(DataComponents.POTION_CONTENTS)) {
                if (!Objects.equals(inventoryStack.get(DataComponents.POTION_CONTENTS), neededStack.get(DataComponents.POTION_CONTENTS))) {
                    return false;
                }
            }
            if (neededStack.has(DataComponents.STORED_ENCHANTMENTS)) {
                if (!Objects.equals(inventoryStack.get(DataComponents.STORED_ENCHANTMENTS), neededStack.get(DataComponents.STORED_ENCHANTMENTS))) {
                    return false;
                }
            }
            if (neededStack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) {
                if (!Objects.equals(inventoryStack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS), neededStack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS))) {
                    return false;
                }
            }
            if (neededStack.has(DataComponents.OMINOUS_BOTTLE_AMPLIFIER)) {
                if (!Objects.equals(inventoryStack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER), neededStack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER))) {
                    return false;
                }
            }
            if (neededStack.has(DataComponents.INSTRUMENT)) {
                if (!Objects.equals(inventoryStack.get(DataComponents.INSTRUMENT), neededStack.get(DataComponents.INSTRUMENT))) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * レシピ成果物と目的アイテムのマッチング判定。
     * レシピ成果物は通常デフォルトコンポーネントのため、base Itemが一致するか確認する。
     */
    public static boolean isRecipeOutputMatch(ItemStack recipeOutput, ItemStack target) {
        if (recipeOutput == null || target == null || recipeOutput.isEmpty() || target.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItem(recipeOutput, target);
    }
}

