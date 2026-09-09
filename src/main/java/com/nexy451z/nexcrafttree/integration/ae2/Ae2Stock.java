package com.nexy451z.nexcrafttree.integration.ae2;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.nexy451z.nexcrafttree.core.stock.IStockProvider;
import com.nexy451z.nexcrafttree.integration.ModIntegration;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * AE2在庫取得。コンパイル時依存あり（Applied Energistics 2 19.2.17 の正規クラス）。
 * 実行時は ModIntegration.isAe2Loaded() でガードされ、AE2未導入環境ではこのクラスの
 * メソッド本体が実行されないため NoClassDefFoundError は発生しない。
 * 経路（デコンパイル実物で確認済み）:
 *  - MEStorageMenu#getClientRepo() -> IClientRepo
 *  - IClientRepo#getAllEntries() -> Set&lt;GridInventoryEntry&gt;
 *  - GridInventoryEntry#getWhat/getStoredAmount/isCraftable
 *  - AEItemKey#toStack()
 */
public class Ae2Stock implements IStockProvider {

    @Override
    public String getSourceName() {
        return "ae2";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (!ModIntegration.isAe2Loaded()) return false;
            if (Minecraft.getInstance().player == null) return false;
            return Minecraft.getInstance().player.containerMenu instanceof MEStorageMenu;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IClientRepo clientRepoOrNull() {
        try {
            if (!ModIntegration.isAe2Loaded()) return null;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !(mc.player.containerMenu instanceof MEStorageMenu menu)) return null;
            return menu.getClientRepo();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public long getAmount(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return 0;
            IClientRepo repo = clientRepoOrNull();
            if (repo == null) return 0;
            Set<GridInventoryEntry> entries = repo.getAllEntries();
            if (entries == null) return 0;
            long total = 0;
            for (GridInventoryEntry entry : entries) {
                if (entry == null) continue;
                ItemStack what = entryToStack(entry);
                if (what == null || what.isEmpty()) continue;
                if (ItemMatchHelper.isStockMatch(what, stack)) {
                    total += Math.max(0, entry.getStoredAmount());
                }
            }
            return total;
        } catch (Throwable t) {
            NexCraftTree.LOGGER.debug("[NexCraftTree] AE2 stock failed: {}", t.toString());
            return 0;
        }
    }

    @Override
    public boolean isAutocraftable(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            IClientRepo repo = clientRepoOrNull();
            if (repo == null) return false;
            Set<GridInventoryEntry> entries = repo.getAllEntries();
            if (entries == null) return false;
            for (GridInventoryEntry entry : entries) {
                if (entry == null) continue;
                ItemStack what = entryToStack(entry);
                if (what == null || what.isEmpty()) continue;
                if (ItemMatchHelper.isStockMatch(what, stack) && entry.isCraftable()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * メインスレッドから呼ぶこと。AE2端末のクライアントリポジトリを不変スナップショットとして取り込む。
     */
    public static java.util.List<com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.Entry> captureEntries() {
        try {
            IClientRepo repo = clientRepoOrNull();
            if (repo == null) return java.util.List.of();
            Set<GridInventoryEntry> entries = repo.getAllEntries();
            if (entries == null) return java.util.List.of();
            java.util.List<com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.Entry> out = new java.util.ArrayList<>();
            for (GridInventoryEntry entry : entries) {
                try {
                    ItemStack what = entryToStack(entry);
                    if (what == null || what.isEmpty()) continue;
                    out.add(new com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.Entry(
                            what.copy(), entry.getStoredAmount(), entry.isCraftable()));
                } catch (Throwable ignored) {
                }
            }
            return java.util.List.copyOf(out);
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    private static ItemStack entryToStack(GridInventoryEntry entry) {
        AEKey what = entry.getWhat();
        if (what instanceof AEItemKey itemKey) {
            return itemKey.toStack();
        }
        return null;
    }
}


