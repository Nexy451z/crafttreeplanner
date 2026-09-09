package com.nexy451z.nexcrafttree.core.calculation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 仮想在庫シミュレータ。AE2の ChildCraftingSimulationState 相当の簡易版。
 * 実在庫を減らしながら「消費したらどうなるか」を試す。
 */
public class VirtualStockTracker {
    private final Map<Item, Long> amounts = new HashMap<>();

    public VirtualStockTracker() {
    }

    public void set(Item item, long amount) {
        amounts.put(item, Math.max(0, amount));
    }

    public long get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return amounts.getOrDefault(stack.getItem(), 0L);
    }

    /** 指定数だけ引き当て、実際に充てられた数を返す */
    public long consume(ItemStack stack, long want) {
        if (want <= 0) return 0;
        long have = get(stack);
        long use = Math.min(have, want);
        if (use > 0) {
            amounts.put(stack.getItem(), have - use);
        }
        return use;
    }

    public static ResourceLocation keyOf(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem());
        } catch (Throwable t) {
            return ResourceLocation.fromNamespaceAndPath("unknown", "unknown");
        }
    }
}


