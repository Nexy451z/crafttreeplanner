package com.nexy451z.nexcrafttree.integration.refinedstorage;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.nexy451z.nexcrafttree.integration.ModIntegration;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.AbstractCraftingGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.CraftingGrid;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Field;
import java.util.Collection;

/**
 * サーバー側での Refined Storage 2 ストレージ直接抽出・返却ヘルパー。
 * Refined Storage 2.0.9 の実API（デコンパイル実物で確認済み）:
 *  - AbstractGridContainerMenu 内の private Grid grid だけリフレクション（RS本体にpublicアクセサがないため）
 *  - Grid#getItemStorage() -> Storage（= StorageNetworkComponent / ネットワークRootStorage）
 *  - StorageView#getAll() -> Collection&lt;ResourceAmount&gt;
 *  - Storage#extract / insert(ResourceKey, long, Action, Actor)（Actorは PlayerActor(Player)）
 * プレイヤーがグリッド画面を開いている場合にのみ使用する。
 */
public class RefinedStorageServerHelper {

    public static boolean isRsContainerOpen(ServerPlayer player) {
        if (!ModIntegration.isRefinedStorageLoaded() || player == null) return false;
        return player.containerMenu instanceof AbstractGridContainerMenu;
    }

    /** RS本体の AbstractGridContainerMenu.grid は private かつ publicアクセサが存在しないため、ここだけ型付きリフレクション */
    @SuppressWarnings("unchecked")
    private static Grid getGrid(AbstractGridContainerMenu menu) {
        try {
            Field gridField = AbstractGridContainerMenu.class.getDeclaredField("grid");
            gridField.setAccessible(true);
            Object grid = gridField.get(menu);
            return grid instanceof Grid g ? g : null;
        } catch (Throwable t) {
            NexCraftTree.LOGGER.debug("[NexCraftTree] failed to access RS grid field: {}", t.toString());
            return null;
        }
    }

    /** プレイヤーが開いているRSクラフトグリッドのネットワークストレージを取得する */
    public static com.refinedmods.refinedstorage.api.storage.Storage getGridStorage(ServerPlayer player) {
        if (!isRsContainerOpen(player)) return null;
        try {
            AbstractGridContainerMenu menu = (AbstractGridContainerMenu) player.containerMenu;
            Grid grid = getGrid(menu);
            return grid != null ? grid.getItemStorage() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** プレイヤーが開いているRSクラフトグリッド（マトリクス・結果スロット管理）を取得する */
    public static CraftingGrid getCraftingGrid(AbstractCraftingGridContainerMenu menu) {
        try {
            Field craftingGridField = AbstractCraftingGridContainerMenu.class
                    .getDeclaredField("craftingGrid");
            craftingGridField.setAccessible(true);
            Object craftingGrid = craftingGridField.get(menu);
            return craftingGrid instanceof CraftingGrid g ? g : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * RSグリッドのネットワークストレージから指定 Ingredient に一致するアイテムを1個抽出する
     */
    public static ItemStack extractSingle(ServerPlayer player, Ingredient ingredient) {
        if (!isRsContainerOpen(player) || ingredient == null || ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try {
            AbstractGridContainerMenu menu = (AbstractGridContainerMenu) player.containerMenu;
            Grid grid = getGrid(menu);
            if (grid == null) return ItemStack.EMPTY;

            Storage storage = grid.getItemStorage();
            if (storage == null) return ItemStack.EMPTY;

            PlayerActor actor = new PlayerActor(player);
            Collection<ResourceAmount> all = storage.getAll();
            if (all == null) return ItemStack.EMPTY;

            ItemStack[] ingOptions = ingredient.getItems();

            for (ResourceAmount resourceAmount : all) {
                if (resourceAmount == null || resourceAmount.resource() == null) continue;
                if (!(resourceAmount.resource() instanceof ItemResource itemResource)) continue;
                if (resourceAmount.amount() <= 0) continue;

                ItemStack candidate = itemResource.toItemStack();
                if (candidate == null || candidate.isEmpty()) continue;

                boolean matches = ingredient.test(candidate);
                if (!matches && ingOptions != null) {
                    for (ItemStack opt : ingOptions) {
                        if (ItemMatchHelper.isStockMatch(candidate, opt)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (matches) {
                    long extracted = storage.extract(itemResource, 1L, Action.EXECUTE, actor);
                    if (extracted > 0) {
                        return candidate.copyWithCount((int) extracted);
                    }
                }
            }
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] Failed to extract item from RS storage", t);
        }

        return ItemStack.EMPTY;
    }

    /**
     * ロールバック用：抽出したアイテムを RS ストレージに戻す
     */
    public static boolean returnToStorage(ServerPlayer player, ItemStack stack) {
        if (!isRsContainerOpen(player) || stack == null || stack.isEmpty()) return false;

        try {
            AbstractGridContainerMenu menu = (AbstractGridContainerMenu) player.containerMenu;
            Grid grid = getGrid(menu);
            if (grid == null) return false;

            Storage storage = grid.getItemStorage();
            if (storage == null) return false;

            ItemResource resource = ItemResource.ofItemStack(stack);
            PlayerActor actor = new PlayerActor(player);
            long inserted = storage.insert(resource, (long) stack.getCount(), Action.EXECUTE, actor);
            return inserted == stack.getCount();
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] Failed to return item to RS storage", t);
            return false;
        }
    }

    /**
     * RSグリッドのネットワークストレージ内に指定アイテムが保管されているか確認する（引き出しは行わない）
     */
    public static boolean hasItem(ServerPlayer player, ItemStack target) {
        if (!isRsContainerOpen(player) || target == null || target.isEmpty()) return false;
        try {
            AbstractGridContainerMenu menu = (AbstractGridContainerMenu) player.containerMenu;
            Grid grid = getGrid(menu);
            if (grid == null) return false;

            Storage storage = grid.getItemStorage();
            if (storage == null) return false;

            Collection<ResourceAmount> all = storage.getAll();
            if (all == null) return false;

            for (ResourceAmount resourceAmount : all) {
                if (resourceAmount == null || resourceAmount.resource() == null) continue;
                if (!(resourceAmount.resource() instanceof ItemResource itemResource)) continue;

                ItemStack candidate = itemResource.toItemStack();
                if (candidate != null && !candidate.isEmpty()
                        && (ItemStack.isSameItem(candidate, target) || ItemMatchHelper.isStockMatch(candidate, target))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            NexCraftTree.LOGGER.debug("[NexCraftTree] RS hasItem check failed", t);
        }
        return false;
    }
}


