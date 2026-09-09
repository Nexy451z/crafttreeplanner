package com.nexy451z.nexcrafttree.core.stock;

import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * メインスレッドで事前に取得した不変スナップショットから在庫を返すプロバイダ。
 * バックグラウンドスレッド上のツリー計算がRS/AE2のライブデータ（同期なしの可変コレクション）を
 * 直接読まないようにするための隔離層。
 */
public class StaticStockProvider implements IStockProvider {
    public record Entry(ItemStack stack, long amount, boolean autocraftable) {
    }

    private final String name;
    private final List<Entry> entries;

    private StaticStockProvider(String name, List<Entry> entries) {
        this.name = name;
        this.entries = entries == null ? List.of() : entries;
    }

    /** entriesが空の場合はnullを返す（addProvider側で無視される） */
    public static StaticStockProvider of(String name, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;
        return new StaticStockProvider(name, entries);
    }

    @Override
    public String getSourceName() {
        return name;
    }

    @Override
    public long getAmount(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            long total = 0;
            for (Entry entry : entries) {
                ItemStack held = entry.stack();
                if (held != null && !held.isEmpty() && ItemMatchHelper.isStockMatch(held, stack)) {
                    total += Math.max(0, entry.amount());
                }
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean isAutocraftable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            for (Entry entry : entries) {
                ItemStack held = entry.stack();
                if (held != null && !held.isEmpty() && entry.autocraftable() && ItemMatchHelper.isStockMatch(held, stack)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}


