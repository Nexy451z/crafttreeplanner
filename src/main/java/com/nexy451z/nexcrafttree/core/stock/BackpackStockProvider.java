package com.nexy451z.nexcrafttree.core.stock;

import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Sophisticated Backpacks の中身（サーバーから同期された集計スナップショット）を在庫として扱うプロバイダ。
 * バックパックの中身はSavedData側にありクライアントから直接読めないため、
 * サーバーへ要求 → ClientboundBackpackStockPayload で受領 → ここに保存、の流れで更新される。
 */
public class BackpackStockProvider implements IStockProvider {
    private static volatile List<ItemStack> snapshot = List.of();
    private static volatile long snapshotHash = 0L;

    public static final int MAX_TRANSFER_STACKS = 2048;

    /** クライアント受信時に呼ぶ。内容が変化した場合のみtrueを返す（無駄な再計算の抑制） */
    public static boolean update(List<ItemStack> items) {
        List<ItemStack> copy = (items == null || items.isEmpty()) ? List.of() : List.copyOf(items);
        long hash = contentHash(copy);
        boolean changed = hash != snapshotHash;
        snapshot = copy;
        snapshotHash = hash;
        return changed;
    }

    /** ItemStackはequals/hashCodeを内容比較で持たないため、内容ベースのハッシュを自前で作る */
    private static long contentHash(List<ItemStack> items) {
        long h = 1L;
        for (ItemStack s : items) {
            if (s == null || s.isEmpty()) continue;
            long e = 31L * System.identityHashCode(s.getItem()) + s.getCount();
            try {
                e = e * 31L + s.getComponents().hashCode();
            } catch (Throwable ignored) {
            }
            h = h * 31L + e;
        }
        return h;
    }

    public static List<ItemStack> getSnapshot() {
        return snapshot;
    }

    public static void clear() {
        snapshot = List.of();
        snapshotHash = 0L;
    }

    @Override
    public String getSourceName() {
        return "backpacks";
    }

    @Override
    public long getAmount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            long total = 0;
            for (ItemStack held : snapshot) {
                if (held != null && !held.isEmpty() && ItemMatchHelper.isStockMatch(held, stack)) {
                    total += held.getCount();
                }
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }
}
