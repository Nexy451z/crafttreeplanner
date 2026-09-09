package com.nexy451z.nexcrafttree.network;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.crafting.DirectCraftingEngine;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

public class NexCraftTreeNetwork {
    private static final String PROTOCOL_VERSION = "1.0";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToClient(
                ClientboundDirectCraftResultPayload.TYPE,
                ClientboundDirectCraftResultPayload.STREAM_CODEC,
                DirectCraftingClientReceiver::handle
        );

        registrar.playToServer(
                ServerboundExecuteDirectCraftPayload.TYPE,
                ServerboundExecuteDirectCraftPayload.STREAM_CODEC,
                DirectCraftingEngine::handleExecuteCraft
        );

        registrar.playToServer(
                ServerboundTakeOutputPayload.TYPE,
                ServerboundTakeOutputPayload.STREAM_CODEC,
                DirectCraftingEngine::handleTakeOutput
        );

        NexCraftTree.LOGGER.info("[NexCraftTree] Network payloads registered successfully.");
    }

    public static void sendDirectCraftRequest(ItemStack target, int qty, List<DirectCraftStep> steps, ItemStack slottedWorkstation) {
        PacketDistributor.sendToServer(new ServerboundExecuteDirectCraftPayload(
                target, qty, steps, (slottedWorkstation != null) ? slottedWorkstation : ItemStack.EMPTY
        ));
    }

    public static void sendDirectCraftRequest(ItemStack target, int qty, List<DirectCraftStep> steps) {
        sendDirectCraftRequest(target, qty, steps, ItemStack.EMPTY);
    }

    public static void sendTakeOutputRequest() {
        PacketDistributor.sendToServer(new ServerboundTakeOutputPayload());
    }
}


