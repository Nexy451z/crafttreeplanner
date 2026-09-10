package com.nexy451z.nexcrafttree;

import com.nexy451z.nexcrafttree.client.KeyInputHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(NexCraftTree.MODID)
public class NexCraftTree {
    public static final String MODID = "nexcrafttree";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NexCraftTree(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[NexCraftTree] init");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);
        modEventBus.addListener(com.nexy451z.nexcrafttree.network.NexCraftTreeNetwork::register);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            KeyInputHandler.register(modEventBus);
        }
    }
}


