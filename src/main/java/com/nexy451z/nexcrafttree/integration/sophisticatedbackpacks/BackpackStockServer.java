package com.nexy451z.nexcrafttree.integration.sophisticatedbackpacks;

import com.nexy451z.nexcrafttree.core.stock.BackpackStockProvider;
import com.nexy451z.nexcrafttree.network.ClientboundBackpackStockPayload;
import com.nexy451z.nexcrafttree.network.ServerboundRequestBackpackStockPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー側でプレイヤーのバックパックの中身を集計してクライアントへ返す。
 * Sophisticated Backpacks が無い環境では空リストを返す（機能は静かに無効）。
 */
public final class BackpackStockServer {
    private BackpackStockServer() {
    }

    public static void handleRequest(ServerboundRequestBackpackStockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            List<ItemStack> items = new ArrayList<>();
            try {
                if (BackpackBridge.isAvailable()) {
                    for (ItemStack s : BackpackBridge.collectFromPlayer(player)) {
                        if (s == null || s.isEmpty()) continue;
                        items.add(s);
                        if (items.size() >= BackpackStockProvider.MAX_TRANSFER_STACKS) {
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            PacketDistributor.sendToPlayer(player, new ClientboundBackpackStockPayload(items));
        });
    }
}
