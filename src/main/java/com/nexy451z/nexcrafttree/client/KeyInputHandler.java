package com.nexy451z.nexcrafttree.client;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.client.gui.NexCraftTreeScreen;
import com.nexy451z.nexcrafttree.core.calculation.RecipeResolver;
import com.nexy451z.nexcrafttree.integration.ModIntegration;
import com.nexy451z.nexcrafttree.integration.jei.JeiHover;
import com.nexy451z.nexcrafttree.integration.rei.ReiHover;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Cキー検知＋ホバーアイテム取得＋ツリー画面オープン。
 */
public final class KeyInputHandler {
    private KeyInputHandler() {
    }

    public static final KeyMapping OPEN_TREE = new KeyMapping(
            "key.nexcrafttree.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.NexCraftTree");

    public static void register(IEventBus modEventBus) {
        try {
            modEventBus.addListener(KeyInputHandler::onRegisterKeys);
            NeoForge.EVENT_BUS.addListener(KeyInputHandler::onScreenKey);
            NeoForge.EVENT_BUS.addListener(KeyInputHandler::onScreenMouse);
            NeoForge.EVENT_BUS.addListener(KeyInputHandler::onKeyInput);
            NeoForge.EVENT_BUS.addListener(KeyInputHandler::onRecipesUpdated);
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] key handler register failed", t);
        }
    }

    /** サーバーからレシピ同期・データパック再読込があったらレシピ探索キャッシュを破棄 */
    private static void onRecipesUpdated(RecipesUpdatedEvent event) {
        try {
            RecipeResolver.invalidateCaches();
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] cache invalidation failed", t);
        }
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        try {
            event.register(OPEN_TREE);
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] key register failed", t);
        }
    }

    private static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        try {
            if (!OPEN_TREE.matches(event.getKeyCode(), event.getScanCode())) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            Screen screen = event.getScreen();
            if (screen == null) return;

            Optional<ItemStack> hovered = findHovered(screen);
            if (hovered.isEmpty() || hovered.get().isEmpty()) return;

            ItemStack target = hovered.get().copy();
            openTree(target, screen);
            event.setCanceled(true);
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] screen key handling failed", t);
        }
    }

    private static void onScreenMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        try {
            if (!OPEN_TREE.matchesMouse(event.getButton())) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            Screen screen = event.getScreen();
            if (screen == null) return;

            Optional<ItemStack> hovered = findHovered(screen);
            if (hovered.isEmpty() || hovered.get().isEmpty()) return;

            ItemStack target = hovered.get().copy();
            openTree(target, screen);
            event.setCanceled(true);
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] screen mouse handling failed", t);
        }
    }

    private static void onKeyInput(InputEvent.Key event) {
        try {
            if (OPEN_TREE.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.screen == null) {
                    ItemStack mainHand = mc.player.getMainHandItem();
                    if (!mainHand.isEmpty()) {
                        openTree(mainHand.copy(), null);
                    }
                }
            }
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] key input handling failed", t);
        }
    }

    /** NexCraftTreeScreen → RS Grid → バニラ/AE2スロット → JEI → REI の順でアイテム取得 */
    public static Optional<ItemStack> findHovered(Screen screen) {
        if (screen == null) return Optional.empty();

        // 0) NexCraftTreeScreen 自身が開いている場合
        if (screen instanceof NexCraftTreeScreen NexCraftTreeScreen) {
            ItemStack hovered = NexCraftTreeScreen.getHoveredItemStack();
            if (hovered != null && !hovered.isEmpty()) {
                return Optional.of(hovered.copy());
            }
        }

        // 1) Refined Storage Grid Screen（正式API: AbstractGridScreen#getCurrentGridResource）
        try {
            if (ModIntegration.isRefinedStorageLoaded()
                    && screen instanceof com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen<?> gridScreen) {
                com.refinedmods.refinedstorage.common.api.grid.view.GridResource gridResource = gridScreen.getCurrentGridResource();
                if (gridResource instanceof com.refinedmods.refinedstorage.common.grid.view.ItemGridResource itemGridResource) {
                    ItemStack is = itemGridResource.getItemStack();
                    if (is != null && !is.isEmpty()) {
                        return Optional.of(is.copy());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) バニラコンテナスロット（AE2のRepoSlotもSlotを継承しているためここで取得可能）
        try {
            if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                Slot slot = containerScreen.getSlotUnderMouse();
                if (slot != null && slot.hasItem()) {
                    return Optional.of(slot.getItem().copy());
                }
                // マウスで掴んでいるアイテム
                try {
                    ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
                    if (carried != null && !carried.isEmpty()) return Optional.of(carried.copy());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) JEI
        try {
            Optional<ItemStack> s = JeiHover.getHovered(screen);
            if (s.isPresent() && !s.get().isEmpty()) return s;
        } catch (Throwable ignored) {
        }

        // 4) REI
        try {
            Optional<ItemStack> s = ReiHover.getHovered(screen);
            if (s.isPresent() && !s.get().isEmpty()) return s;
        } catch (Throwable ignored) {
        }

        return Optional.empty();
    }

    public static void openTree(ItemStack target) {
        openTree(target, Minecraft.getInstance().screen);
    }

    public static void openTree(ItemStack target, Screen parent) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            mc.setScreen(new NexCraftTreeScreen(target, parent));
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] openTree failed", t);
        }
    }
}


