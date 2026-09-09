package com.kumanchu.crafttreeplanner.integration.refinedstorage;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.ItemMatchHelper;
import com.kumanchu.crafttreeplanner.core.stock.IStockProvider;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.view.ItemGridResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * RS在庫取得。コンパイル時依存あり（Refined Storage 2.0.9 の実API）。
 * 実行時は ModIntegration.isRefinedStorageLoaded() でガードされ、RS未導入環境ではこのクラスの
 * メソッド本体が実行されないため NoClassDefFoundError は発生しない。
 * 経路（デコンパイル実物で確認済み）:
 *  - AbstractGridContainerMenu#getRepository() -> ResourceRepository&lt;GridResource&gt;
 *  - ResourceRepository#getAmount / isSticky / getViewList
 *  - ItemGridResource#getItemStack / getItemResource / getAmount / isAutocraftable
 */
public class RefinedStorageStock implements IStockProvider {

    @Override
    public String getSourceName() {
        return "refinedstorage";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (!ModIntegration.isRefinedStorageLoaded()) return false;
            if (Minecraft.getInstance().player == null) return false;
            return Minecraft.getInstance().player.containerMenu instanceof AbstractGridContainerMenu;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ResourceRepository<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> repoOrNull() {
        try {
            if (!ModIntegration.isRefinedStorageLoaded()) return null;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !(mc.player.containerMenu instanceof AbstractGridContainerMenu menu)) return null;
            return menu.getRepository();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public long getAmount(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return 0;
            ResourceRepository<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> repo = repoOrNull();
            if (repo == null) return 0;

            // 1) O(1)直接取得（耐久値持ちツールは同一IDでもバリエーションがあるため走査に任せる）
            if (!ItemMatchHelper.isToolOrDamageable(stack)) {
                ItemResource direct = ItemResource.ofItemStack(stack);
                long amount = repo.getAmount(direct);
                if (amount > 0) return amount;
            }

            // 2) ビュー走査（耐久値違い・コンポーネント違いの合算）
            long total = 0;
            List<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> viewList = repo.getViewList();
            for (com.refinedmods.refinedstorage.common.api.grid.view.GridResource gridResource : viewList) {
                if (!(gridResource instanceof ItemGridResource itemGridResource)) continue;
                ItemStack item = itemGridResource.getItemStack();
                if (item == null || item.isEmpty()) continue;
                if (ItemMatchHelper.isStockMatch(item, stack)) {
                    total += gridResource.getAmount(repo);
                }
            }
            return total;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] RS stock query failed: {}", t.toString());
            return 0;
        }
    }

    @Override
    public boolean isAutocraftable(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            ResourceRepository<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> repo = repoOrNull();
            if (repo == null) return false;

            if (repo.isSticky(ItemResource.ofItemStack(stack))) return true;

            List<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> viewList = repo.getViewList();
            for (com.refinedmods.refinedstorage.common.api.grid.view.GridResource gridResource : viewList) {
                if (!(gridResource instanceof ItemGridResource itemGridResource)) continue;
                ItemStack item = itemGridResource.getItemStack();
                if (item == null || item.isEmpty()) continue;
                if (ItemMatchHelper.isStockMatch(item, stack) && gridResource.isAutocraftable(repo)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * RS Crafting Grid（クラフトグリッド）が開いている場合、レシピの材料を3x3マトリクスに配置する。
     * 正式経路: AbstractCraftingGridContainerMenu#transferRecipe(List&lt;List&lt;ItemResource&gt;&gt;)
     */
    public static boolean transferRecipeToCraftingGrid(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                       net.minecraft.world.item.crafting.RecipeHolder<?> recipe) {
        if (menu == null || recipe == null) return false;
        try {
            if (!(menu instanceof com.refinedmods.refinedstorage.common.grid.AbstractCraftingGridContainerMenu craftingGridMenu)) {
                return false;
            }
            ResourceRepository<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> repo = craftingGridMenu.getRepository();

            List<List<ItemResource>> slots = new java.util.ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                slots.add(new java.util.ArrayList<>());
            }

            if (recipe.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                List<net.minecraft.world.item.crafting.Ingredient> ingredients = shaped.getIngredients();
                for (int r = 0; r < height && r < 3; r++) {
                    for (int c = 0; c < width && c < 3; c++) {
                        int idx = r * width + c;
                        if (idx < ingredients.size()) {
                            net.minecraft.world.item.crafting.Ingredient ing = ingredients.get(idx);
                            if (ing != null && !ing.isEmpty()) {
                                int gridSlot = r * 3 + c;
                                populateSlotPossibilities(slots.get(gridSlot), ing, repo);
                            }
                        }
                    }
                }
            } else {
                List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.value().getIngredients();
                for (int i = 0; i < ingredients.size() && i < 9; i++) {
                    net.minecraft.world.item.crafting.Ingredient ing = ingredients.get(i);
                    if (ing != null && !ing.isEmpty()) {
                        populateSlotPossibilities(slots.get(i), ing, repo);
                    }
                }
            }

            craftingGridMenu.transferRecipe(slots);
            return true;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] RS transferRecipe failed", t);
            return false;
        }
    }

    private static void populateSlotPossibilities(List<ItemResource> slotList,
                                                  net.minecraft.world.item.crafting.Ingredient ing,
                                                  ResourceRepository<com.refinedmods.refinedstorage.common.api.grid.view.GridResource> repo) {
        if (ing == null || ing.isEmpty()) return;

        // 1. プレイヤー手持ちの現物アイテム（耐久値減ツール、コンポーネント付きアイテム等）を最優先で候補に追加
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                    ItemStack invStack = mc.player.getInventory().getItem(i);
                    if (!invStack.isEmpty() && isIngredientMatch(ing, invStack)) {
                        ItemResource res = ItemResource.ofItemStack(invStack);
                        if (!slotList.contains(res)) {
                            slotList.add(res);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. RSストレージ内の現物アイテムを追加
        if (repo != null) {
            try {
                for (com.refinedmods.refinedstorage.common.api.grid.view.GridResource gridResource : repo.getViewList()) {
                    if (!(gridResource instanceof ItemGridResource itemGridResource)) continue;
                    ItemStack item = itemGridResource.getItemStack();
                    if (item != null && isIngredientMatch(ing, item)) {
                        ItemResource res = itemGridResource.getItemResource();
                        if (res != null && !slotList.contains(res)) {
                            slotList.add(res);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. レシピのデフォルトテンプレートアイテムをフォールバック候補として追加
        for (ItemStack st : ing.getItems()) {
            try {
                ItemResource res = ItemResource.ofItemStack(st);
                if (!slotList.contains(res)) {
                    slotList.add(res);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isIngredientMatch(net.minecraft.world.item.crafting.Ingredient ing, ItemStack stack) {
        if (stack == null || stack.isEmpty() || ing == null || ing.isEmpty()) return false;
        try {
            if (ing.test(stack)) return true;
        } catch (Throwable ignored) {
        }
        for (ItemStack tmpl : ing.getItems()) {
            if (ItemMatchHelper.isStockMatch(stack, tmpl)) {
                return true;
            }
        }
        return false;
    }
}
