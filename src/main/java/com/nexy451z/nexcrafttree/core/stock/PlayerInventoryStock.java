package com.nexy451z.nexcrafttree.core.stock;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * プレイヤー手持ち（0〜35スロット）在庫。
 * 常に利用可能で、絶対にクラッシュさせない。
 */
public class PlayerInventoryStock implements IStockProvider {
    private final Player player;

    public PlayerInventoryStock(Player player) {
        this.player = player;
    }

    @Override
    public String getSourceName() {
        return "player";
    }

    @Override
    public long getAmount(ItemStack stack) {
        try {
            if (player == null || stack == null || stack.isEmpty()) return 0;
            long total = 0;
            for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
                try {
                    if (!s.isEmpty() && ItemMatchHelper.isStockMatch(s, stack)) {
                        total += s.getCount();
                    }
                } catch (Throwable t) {
                    NexCraftTree.LOGGER.debug("[NexCraftTree] inv scan skip: {}", t.toString());
                }
            }
            try {
                ItemStack offhand = player.getInventory().getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND);
                if (!offhand.isEmpty() && ItemMatchHelper.isStockMatch(offhand, stack)) {
                    total += offhand.getCount();
                }
            } catch (Throwable t) {
                NexCraftTree.LOGGER.debug("[NexCraftTree] offhand scan skip: {}", t.toString());
            }
            return total;
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] PlayerInventoryStock failed", t);
            return 0;
        }
    }
}


