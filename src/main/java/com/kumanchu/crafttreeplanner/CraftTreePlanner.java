package com.kumanchu.crafttreeplanner;

import com.kumanchu.crafttreeplanner.client.KeyInputHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(CraftTreePlanner.MODID)
public class CraftTreePlanner {
    public static final String MODID = "crafttreeplanner";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CraftTreePlanner(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[CraftTreePlanner] init");
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);
        modEventBus.addListener(com.kumanchu.crafttreeplanner.network.CraftTreeNetwork::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            KeyInputHandler.register(modEventBus);
        }
    }
}
