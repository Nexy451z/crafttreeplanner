package com.nexy451z.nexcrafttree.integration.jei;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.integration.ModIntegration;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JEIのホバーアイテム取得。
 * - RecipesGui（レシピ閲覧画面：作業台クラフト、かまど、MOD加工機等）
 * - Ingredient List Overlay（右側アイテム一覧）
 * - Bookmark Overlay（左側ブックマーク）
 * - ScreenHelper（インベントリや外部MODコンテナ画面）
 * すべてに対応。
 */
public final class JeiHover {
    private JeiHover() {
    }

    public static Optional<ItemStack> getHovered(Screen screen) {
        try {
            if (!ModIntegration.isJeiLoaded()) return Optional.empty();
            if (screen == null || Minecraft.getInstance().player == null) return Optional.empty();

            IJeiRuntime runtime = JeiRuntimeHolder.getRuntime();
            if (runtime == null) return Optional.empty();

            Minecraft mc = Minecraft.getInstance();
            double mouseX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getScreenWidth();
            double mouseY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getScreenHeight();

            // 1) RecipesGui (JEIのレシピ表示画面 - 作業台クラフト等のレシピGUI内アイテム・スロット・触媒)
            try {
                Optional<ItemStack> fromRecipesGui = getFromRecipesGui(runtime, screen, mouseX, mouseY);
                if (fromRecipesGui.isPresent() && !fromRecipesGui.get().isEmpty()) {
                    return fromRecipesGui;
                }
            } catch (Throwable t) {
                NexCraftTree.LOGGER.debug("[NexCraftTree] getFromRecipesGui error", t);
            }

            // 2) Ingredient List Overlay (右側のアイテム一覧)
            try {
                IIngredientListOverlay overlay = runtime.getIngredientListOverlay();
                if (overlay != null) {
                    ItemStack stack = overlay.getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
                    if (stack != null && !stack.isEmpty()) {
                        return Optional.of(stack.copy());
                    }
                    Optional<ITypedIngredient<?>> generic = overlay.getIngredientUnderMouse();
                    if (generic.isPresent()) {
                        Optional<ItemStack> item = generic.get().getItemStack();
                        if (item.isPresent() && !item.get().isEmpty()) {
                            return Optional.of(item.get().copy());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3) Bookmark Overlay (左側のブックマーク一覧)
            try {
                IBookmarkOverlay bookmarks = runtime.getBookmarkOverlay();
                if (bookmarks != null) {
                    ItemStack stack = bookmarks.getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
                    if (stack != null && !stack.isEmpty()) {
                        return Optional.of(stack.copy());
                    }
                    Optional<ITypedIngredient<?>> generic = bookmarks.getIngredientUnderMouse();
                    if (generic.isPresent()) {
                        Optional<ItemStack> item = generic.get().getItemStack();
                        if (item.isPresent() && !item.get().isEmpty()) {
                            return Optional.of(item.get().copy());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 4) ScreenHelper (外部MODコンテナやバニラ画面)
            try {
                IScreenHelper helper = runtime.getScreenHelper();
                if (helper != null) {
                    Optional<ItemStack> clickable = helper.getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                            .map(IClickableIngredient::getTypedIngredient)
                            .map(ITypedIngredient::getItemStack)
                            .flatMap(Optional::stream)
                            .filter(s -> !s.isEmpty())
                            .map(ItemStack::copy)
                            .findFirst();

                    if (clickable.isPresent()) {
                        return clickable;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return Optional.empty();
    }

    private static Optional<ItemStack> getFromRecipesGui(IJeiRuntime runtime, Screen screen, double mouseX, double mouseY) {
        java.util.List<Object> targets = new java.util.ArrayList<>(2);
        if (screen != null && screen.getClass().getName().contains("RecipesGui")) {
            targets.add(screen);
        }
        if (runtime != null) {
            try {
                IRecipesGui rg = runtime.getRecipesGui();
                if (rg != null && !targets.contains(rg)) {
                    targets.add(rg);
                }
            } catch (Throwable ignored) {
            }
        }
        if (targets.isEmpty() && screen != null) {
            targets.add(screen);
        }

        for (Object targetGui : targets) {
            Optional<ItemStack> res = checkTargetGui(targetGui, runtime, mouseX, mouseY);
            if (res.isPresent() && !res.get().isEmpty()) {
                return res;
            }
        }
        return Optional.empty();
    }

    private static Optional<ItemStack> checkTargetGui(Object targetGui, IJeiRuntime runtime, double mouseX, double mouseY) {
        if (targetGui == null) return Optional.empty();

        // 1. targetGui.getIngredientUnderMouse(mouseX, mouseY) -> Stream<IClickableIngredientInternal<?>>
        // JEIのRecipesGuiはLayouts, Catalysts, Tabs, Tooltipの全てを束ねてこのメソッドから返す
        try {
            Method mIng = findMethod(targetGui.getClass(), "getIngredientUnderMouse", double.class, double.class);
            if (mIng != null) {
                Object res = mIng.invoke(targetGui, mouseX, mouseY);
                if (res instanceof Stream<?> stream) {
                    for (Object obj : stream.toList()) {
                        Method mTyped = findMethod(obj.getClass(), "getTypedIngredient");
                        if (mTyped != null) {
                            Object typed = mTyped.invoke(obj);
                            if (typed instanceof ITypedIngredient<?> ti) {
                                Optional<ItemStack> is = ti.getItemStack();
                                if (is.isPresent() && !is.get().isEmpty()) {
                                    return Optional.of(is.get().copy());
                                }
                            }
                        }
                        if (runtime != null) {
                            Method mCheat = findMethod(obj.getClass(), "getCheatItemStack", IIngredientManager.class);
                            if (mCheat != null) {
                                Object is = mCheat.invoke(obj, runtime.getIngredientManager());
                                if (is instanceof ItemStack s && !s.isEmpty()) {
                                    return Optional.of(s.copy());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. getRecipeLayoutUnderMouse(mouseX, mouseY) -> IRecipeLayoutDrawable / IRecipeSlotDrawable
        try {
            Method mLayout = findMethod(targetGui.getClass(), "getRecipeLayoutUnderMouse", double.class, double.class);
            if (mLayout != null) {
                Object layoutOpt = mLayout.invoke(targetGui, mouseX, mouseY);
                if (layoutOpt instanceof Optional<?> opt && opt.isPresent()) {
                    Object layoutWithButtons = opt.get();
                    Object drawable = layoutWithButtons;
                    Method mGetLayout = findMethod(layoutWithButtons.getClass(), "getRecipeLayout");
                    if (mGetLayout != null) {
                        Object res = mGetLayout.invoke(layoutWithButtons);
                        if (res != null) drawable = res;
                    }

                    if (drawable instanceof IRecipeLayoutDrawable<?> layoutDrawable) {
                        Optional<ItemStack> itemStack = layoutDrawable.getItemStackUnderMouse((int) mouseX, (int) mouseY);
                        if (itemStack.isPresent() && !itemStack.get().isEmpty()) {
                            return Optional.of(itemStack.get().copy());
                        }
                        Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slotOpt = layoutDrawable.getSlotUnderMouse(mouseX, mouseY);
                        if (slotOpt.isPresent()) {
                            IRecipeSlotView slot = slotOpt.get().slot();
                            Optional<ItemStack> displayed = slot.getDisplayedItemStack();
                            if (displayed.isPresent() && !displayed.get().isEmpty()) {
                                return Optional.of(displayed.get().copy());
                            }
                            Optional<ITypedIngredient<?>> typed = slot.getDisplayedIngredient();
                            if (typed.isPresent()) {
                                Optional<ItemStack> is = typed.get().getItemStack();
                                if (is.isPresent() && !is.get().isEmpty()) {
                                    return Optional.of(is.get().copy());
                                }
                            }
                            Optional<ItemStack> first = slot.getItemStacks().filter(s -> !s.isEmpty()).findFirst();
                            if (first.isPresent()) {
                                return Optional.of(first.get().copy());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. 公式API: getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
        if (targetGui instanceof IRecipesGui rg) {
            try {
                Optional<ItemStack> stack = rg.getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
                if (stack.isPresent() && !stack.get().isEmpty()) {
                    return Optional.of(stack.get().copy());
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. 触媒カラム直接探索 (recipeCatalysts フィールド)
        try {
            java.lang.reflect.Field fCat = findField(targetGui.getClass(), "recipeCatalysts");
            if (fCat != null) {
                Object cat = fCat.get(targetGui);
                if (cat != null) {
                    Method mCatIng = findMethod(cat.getClass(), "getIngredientUnderMouse", double.class, double.class);
                    if (mCatIng != null) {
                        Object res = mCatIng.invoke(cat, mouseX, mouseY);
                        if (res instanceof Stream<?> stream) {
                            for (Object obj : stream.toList()) {
                                Method mTyped = findMethod(obj.getClass(), "getTypedIngredient");
                                if (mTyped != null) {
                                    Object typed = mTyped.invoke(obj);
                                    if (typed instanceof ITypedIngredient<?> ti) {
                                        Optional<ItemStack> is = ti.getItemStack();
                                        if (is.isPresent() && !is.get().isEmpty()) {
                                            return Optional.of(is.get().copy());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return Optional.empty();
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && (paramTypes.length == 0 || m.getParameterCount() == paramTypes.length)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static final class JeiRuntimeHolder {
        private static volatile IJeiRuntime runtime;

        public static void setRuntime(IJeiRuntime r) {
            runtime = r;
        }

        public static IJeiRuntime getRuntime() {
            return runtime;
        }
    }
}


