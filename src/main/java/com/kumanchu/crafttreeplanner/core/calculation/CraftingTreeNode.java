package com.kumanchu.crafttreeplanner.core.calculation;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * AE2のCraftingTreeNodeを参考にしたツリーノード。
 * stored / toCraft / missing をAE2の計画画面と同じ見せ方にする。
 */
public class CraftingTreeNode {
    public final ItemStack item;
    public long requiredAmount;
    /** 今回のクラフト要求に対して在庫から充当された数量 */
    public long storedAmount;
    /** プレイヤー手持ち・RS・AE2に存在する実際の在庫総数 */
    public long totalStockAmount;
    public long toCraftAmount;
    public long missingAmount;
    /** RSパターン or AE2パターンで自動発注できるか */
    public boolean autocraftable;
    /** 循環参照などで打ち切られたか */
    public boolean cutByCycle;
    public RecipeHolder<?> recipe;
    public ProcessingStation station = ProcessingStation.CRAFTING_TABLE;
    public final List<PlannedRecipe> alternativeRecipes = new ArrayList<>();
    public int selectedRecipeIndex = 0;
    public final List<CraftingTreeNode> children = new ArrayList<>();

    public CraftingTreeNode(ItemStack item, long requiredAmount) {
        this.item = item.copy();
        this.item.setCount(1);
        this.requiredAmount = requiredAmount;
    }

    public boolean isMissing() {
        return missingAmount > 0;
    }

    public boolean isFullyStored() {
        return missingAmount == 0 && toCraftAmount == 0;
    }
}
