package com.kumanchu.crafttreeplanner.integration.rei;

import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * REIのホバー取得（リフレクションのみ）。
 * 正規経路: ScreenRegistry#getInstance().getFocusedStack(screen, mousePoint)
 */
public final class ReiHover {
    private ReiHover() {
    }

    public static Optional<ItemStack> getHovered(Screen screen) {
        try {
            if (!ModIntegration.isReiLoaded()) return Optional.empty();
            if (screen == null) return Optional.empty();

            Class<?> screenRegCls = Class.forName("me.shedaniel.rei.api.client.registry.screen.ScreenRegistry");
            Method getInstance = screenRegCls.getMethod("getInstance");
            Object registry = getInstance.invoke(null);
            if (registry == null) return Optional.empty();

            // PointHelper.ofMouse()
            Class<?> pointHelper = Class.forName("me.shedaniel.math.PointHelper");
            Method ofMouse = pointHelper.getMethod("ofMouse");
            Object point = ofMouse.invoke(null);

            Method getFocused = null;
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getFocusedStack") && m.getParameterCount() == 2) {
                    getFocused = m;
                    break;
                }
            }
            if (getFocused == null) return Optional.empty();
            Object entryStack = getFocused.invoke(registry, screen, point);
            if (entryStack == null) return Optional.empty();
            return entryToItemStack(entryStack);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ItemStack> entryToItemStack(Object entryStack) {
        try {
            // EntryStack#getType / castValue / getValue
            for (Method m : entryStack.getClass().getMethods()) {
                try {
                    String n = m.getName().toLowerCase();
                    if (m.getParameterCount() != 0) continue;
                    if (!(n.contains("value") || n.contains("stack") || n.contains("item"))) continue;
                    Object r = m.invoke(entryStack);
                    if (r instanceof ItemStack s) {
                        if (!s.isEmpty()) return Optional.of(s.copy());
                    }
                } catch (Throwable ignored) {
                }
            }
            // copy()して中を見るフォールバック
            try {
                Method copy = entryStack.getClass().getMethod("copy");
                Object c = copy.invoke(entryStack);
                if (c != null && !c.equals(entryStack)) {
                    return entryToItemStack(c);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }
}
