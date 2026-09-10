package com.nexy451z.nexcrafttree.network;

import com.nexy451z.nexcrafttree.client.gui.NexCraftTreeScreen;
import com.nexy451z.nexcrafttree.core.stock.BackpackStockProvider;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class BackpackStockClientReceiver {
    public static void handle(ClientboundBackpackStockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                boolean changed = BackpackStockProvider.update(payload.items());
                if (changed && Minecraft.getInstance().screen instanceof NexCraftTreeScreen screen) {
                    screen.onBackpackStockUpdated();
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
