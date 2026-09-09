package com.kumanchu.crafttreeplanner.integration;

import com.kumanchu.crafttreeplanner.integration.jei.JeiHover;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.runtime.IJeiKeyMapping;
import mezz.jei.api.runtime.IJeiKeyMappings;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

/**
 * JEI / REI との相互作用ユーティリティ。
 * Rキーでレシピ（作成方法）、Uキーで用途（使用先）を表示する。
 */
public final class RecipeViewerIntegration {
    private RecipeViewerIntegration() {
    }

    public static void showRecipe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        // 1. JEI でレシピ（OUTPUT）を表示
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IFocusFactory ff = runtime.getJeiHelpers().getFocusFactory();
                IFocus<ItemStack> focus = ff.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack.copy());
                runtime.getRecipesGui().show(focus);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. REI でレシピを表示
        try {
            showRei(stack, false);
        } catch (Throwable ignored) {
        }
    }

    public static void showUsage(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        // 1. JEI で用途（INPUT）を表示
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IFocusFactory ff = runtime.getJeiHelpers().getFocusFactory();
                IFocus<ItemStack> focus = ff.createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, stack.copy());
                runtime.getRecipesGui().show(focus);
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. REI で用途を表示
        try {
            showRei(stack, true);
        } catch (Throwable ignored) {
        }
    }

    private static void showRei(ItemStack stack, boolean usage) {
        try {
            Class<?> entryStacks = Class.forName("me.shedaniel.rei.api.common.util.EntryStacks");
            Method ofMethod = entryStacks.getMethod("of", ItemStack.class);
            Object entryStack = ofMethod.invoke(null, stack);

            Class<?> builderClass = Class.forName("me.shedaniel.rei.api.client.view.ViewSearchBuilder");
            Method builderMethod = builderClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);

            String methodName = usage ? "addUsagesFor" : "addRecipesFor";
            Method addMethod = builderClass.getMethod(methodName, Class.forName("me.shedaniel.rei.api.common.entry.EntryStack"));
            addMethod.invoke(builder, entryStack);

            Method openMethod = builderClass.getMethod("open");
            openMethod.invoke(builder);
        } catch (Throwable ignored) {
        }
    }

    /**
     * JEI / REI の設定からレシピ表示キー名（例: "R"）を取得。未設定や取得不能時は "R" を返す。
     */
    public static String getRecipeKeyName() {
        // 1. JEI
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IJeiKeyMappings keyMappings = runtime.getKeyMappings();
                if (keyMappings != null) {
                    IJeiKeyMapping mapping = keyMappings.getShowRecipe();
                    if (mapping != null && !mapping.isUnbound()) {
                        Component c = mapping.getTranslatedKeyMessage();
                        if (c != null && !c.getString().isEmpty()) {
                            return c.getString();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. REI
        try {
            Class<?> configClass = Class.forName("me.shedaniel.rei.api.client.config.ConfigObject");
            Object config = configClass.getMethod("getInstance").invoke(null);
            Object keybind = configClass.getMethod("getRecipeKeybind").invoke(config);
            if (keybind != null) {
                Method getLoc = keybind.getClass().getMethod("getLocalizedName");
                Component comp = (Component) getLoc.invoke(keybind);
                if (comp != null && !comp.getString().isEmpty()) {
                    return comp.getString();
                }
            }
        } catch (Throwable ignored) {
        }

        return "R";
    }

    /**
     * JEI / REI の設定から用途表示キー名（例: "U"）を取得。未設定や取得不能時は "U" を返す。
     */
    public static String getUsageKeyName() {
        // 1. JEI
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IJeiKeyMappings keyMappings = runtime.getKeyMappings();
                if (keyMappings != null) {
                    IJeiKeyMapping mapping = keyMappings.getShowUses();
                    if (mapping != null && !mapping.isUnbound()) {
                        Component c = mapping.getTranslatedKeyMessage();
                        if (c != null && !c.getString().isEmpty()) {
                            return c.getString();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. REI
        try {
            Class<?> configClass = Class.forName("me.shedaniel.rei.api.client.config.ConfigObject");
            Object config = configClass.getMethod("getInstance").invoke(null);
            Object keybind = configClass.getMethod("getUsageKeybind").invoke(config);
            if (keybind != null) {
                Method getLoc = keybind.getClass().getMethod("getLocalizedName");
                Component comp = (Component) getLoc.invoke(keybind);
                if (comp != null && !comp.getString().isEmpty()) {
                    return comp.getString();
                }
            }
        } catch (Throwable ignored) {
        }

        return "U";
    }

    /**
     * 入力されたキーが JEI / REI のレシピ表示キーと一致するか判定
     */
    public static boolean matchesRecipeKey(int keyCode, int scanCode) {
        // 1. JEI
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IJeiKeyMappings keyMappings = runtime.getKeyMappings();
                if (keyMappings != null) {
                    IJeiKeyMapping mapping = keyMappings.getShowRecipe();
                    if (mapping != null && !mapping.isUnbound()) {
                        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
                        if (mapping.isActiveAndMatches(key)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. REI
        try {
            Class<?> configClass = Class.forName("me.shedaniel.rei.api.client.config.ConfigObject");
            Object config = configClass.getMethod("getInstance").invoke(null);
            Object keybind = configClass.getMethod("getRecipeKeybind").invoke(config);
            if (keybind != null) {
                Method matchesKey = keybind.getClass().getMethod("matchesKey", int.class, int.class);
                if ((Boolean) matchesKey.invoke(keybind, keyCode, scanCode)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return keyCode == GLFW.GLFW_KEY_R;
    }

    /**
     * 入力されたキーが JEI / REI の用途表示キーと一致するか判定
     */
    public static boolean matchesUsageKey(int keyCode, int scanCode) {
        // 1. JEI
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IJeiKeyMappings keyMappings = runtime.getKeyMappings();
                if (keyMappings != null) {
                    IJeiKeyMapping mapping = keyMappings.getShowUses();
                    if (mapping != null && !mapping.isUnbound()) {
                        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
                        if (mapping.isActiveAndMatches(key)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. REI
        try {
            Class<?> configClass = Class.forName("me.shedaniel.rei.api.client.config.ConfigObject");
            Object config = configClass.getMethod("getInstance").invoke(null);
            Object keybind = configClass.getMethod("getUsageKeybind").invoke(config);
            if (keybind != null) {
                Method matchesKey = keybind.getClass().getMethod("matchesKey", int.class, int.class);
                if ((Boolean) matchesKey.invoke(keybind, keyCode, scanCode)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return keyCode == GLFW.GLFW_KEY_U;
    }
}
