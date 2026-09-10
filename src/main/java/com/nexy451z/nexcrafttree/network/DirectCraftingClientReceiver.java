package com.nexy451z.nexcrafttree.network;

import com.nexy451z.nexcrafttree.client.gui.NexCraftTreeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class DirectCraftingClientReceiver {
    public static void handle(ClientboundDirectCraftResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            if (mc.screen instanceof NexCraftTreeScreen screen) {
                screen.onDirectCraftResult(payload.success(), payload.message(), payload.resultStack(), payload.count());
            }

            if (payload.success()) {
                if (!payload.resultStack().isEmpty()) {
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F, 1.2F);
                } else {
                    mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 1.0F);
                }
            } else {
                mc.player.playSound(SoundEvents.VILLAGER_NO, 0.8F, 1.0F);
                if (payload.message() != null && !payload.message().getString().isEmpty()) {
                    mc.gui.getChat().addClientSystemMessage(payload.message());
                }
            }
        });
    }
}


