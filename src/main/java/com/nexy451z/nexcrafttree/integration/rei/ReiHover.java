package com.nexy451z.nexcrafttree.integration.rei;

import com.nexy451z.nexcrafttree.integration.ModIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * REIのホバー取得（リフレクションのみ）。
 * 正規経路: ScreenRegistry#getInstance().getFocusedStack(screen, mousePoint)
 * 26.1のCloth Configには me.shedaniel.math.PointHelper が無いため、
 * me.shedaniel.math.Point(double,double) を現在のマウス座標から直接生成する。
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

            Object point = createMousePoint();
            if (point == null) return Optional.empty();

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

    /** 現在のマウス位置をGUIスケール座標の me.shedaniel.math.Point として生成する */
    private static Object createMousePoint() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) return null;
            int screenW = Math.max(1, mc.getWindow().getScreenWidth());
            int screenH = Math.max(1, mc.getWindow().getScreenHeight());
            double guiX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) screenW;
            double guiY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) screenH;
            Class<?> pointCls = Class.forName("me.shedaniel.math.Point");
            return pointCls.getConstructor(double.class, double.class).newInstance(guiX, guiY);
        } catch (Throwable ignored) {
            return null;
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


