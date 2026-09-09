package com.kumanchu.crafttreeplanner.core.stock;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 複数プロバイダの合算スナップショット。
 * 各プロバイダの例外は隔離し、1つ壊れても他は活かす（柔軟性・エラー対策の要）。
 */
public class UnifiedStockSnapshot {
    private final List<IStockProvider> providers = new ArrayList<>();

    public void addProvider(IStockProvider provider) {
        if (provider == null) return;
        try {
            if (provider.isAvailable()) {
                providers.add(provider);
            }
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] provider {} availability check failed, skipped",
                    safeName(provider), t);
        }
    }

    private static String safeName(IStockProvider p) {
        try {
            return p.getSourceName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public long getTotal(ItemStack stack) {
        long total = 0;
        for (IStockProvider p : providers) {
            try {
                total += Math.max(0, p.getAmount(stack));
            } catch (Throwable t) {
                CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] provider {} failed, treated as 0",
                        safeName(p), t);
            }
        }
        return total;
    }

    public boolean isAutocraftableAnywhere(ItemStack stack) {
        for (IStockProvider p : providers) {
            try {
                if (p.isAutocraftable(stack)) return true;
            } catch (Throwable t) {
                CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] autocraft check skip {}: {}",
                        safeName(p), t.toString());
            }
        }
        return false;
    }
}
