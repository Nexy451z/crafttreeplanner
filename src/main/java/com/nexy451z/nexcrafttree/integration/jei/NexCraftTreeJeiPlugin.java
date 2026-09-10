package com.nexy451z.nexcrafttree.integration.jei;

import com.nexy451z.nexcrafttree.client.gui.NexCraftTreeScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

@JeiPlugin
public class NexCraftTreeJeiPlugin implements IModPlugin, IGlobalGuiHandler {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("nexcrafttree", "jei_plugin");

    @Override
    public Identifier getPluginUid() {
        return ID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGlobalGuiHandler(this);
    }

    @Override
    public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory, double mouseX, double mouseY) {
        try {
            Screen s = Minecraft.getInstance().screen;
            if (s instanceof NexCraftTreeScreen treeScreen) {
                ItemStack hovered = treeScreen.getHoveredItemStack();
                if (hovered != null && !hovered.isEmpty()) {
                    return factory.createBuilder(hovered).buildWithArea((int) mouseX, (int) mouseY, 16, 16);
                }
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiHover.JeiRuntimeHolder.setRuntime(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiHover.JeiRuntimeHolder.setRuntime(null);
    }
}


