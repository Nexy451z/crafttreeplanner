package com.kumanchu.crafttreeplanner.integration.refinedstorage;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.ItemMatchHelper;
import com.kumanchu.crafttreeplanner.core.stock.IStockProvider;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.List;

/**
 * RS在庫取得。コンパイル依存なし（リフレクションのみ）。
 * AbstractGridContainerMenu#getRepository() から
 * 1) ItemResource.ofItemStack によるO(1)直接取得
 * 2) repository.getViewList() によるフォールバック走査
 * の両方を備え、確実に在庫と自動クラフト可否を取得する。
 */
public class RefinedStorageStock implements IStockProvider {

    @Override
    public String getSourceName() {
        return "refinedstorage";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (!ModIntegration.isRefinedStorageLoaded()) return false;
            if (Minecraft.getInstance().player == null) return false;
            AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
            if (menu == null) return false;
            String name = menu.getClass().getName();
            return name.contains("refinedstorage");
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getRepository(AbstractContainerMenu menu) {
        try {
            Method m = findMethod(menu.getClass(), "getRepository");
            if (m != null) {
                return m.invoke(menu);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public long getAmount(ItemStack stack) {
        try {
            if (!isAvailable() || stack == null || stack.isEmpty()) return 0;
            AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
            Object repo = getRepository(menu);
            if (repo == null) return 0;

            // 1) 直接取得: ItemResource.ofItemStack(stack)
            // 耐久値を持つツール等ではない場合のみO(1)直接取得を試みる
            // （ツールは耐久値違いが複数存在しうるため、直接取得のみだと合算から漏れる）
            if (!ItemMatchHelper.isToolOrDamageable(stack)) {
                try {
                    Class<?> itemResClass = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource");
                    Method ofStack = itemResClass.getMethod("ofItemStack", ItemStack.class);
                    Object itemRes = ofStack.invoke(null, stack);
                    if (itemRes != null) {
                        Method getAmount = findMethodWithParamCount(repo.getClass(), "getAmount", 1);
                        if (getAmount != null) {
                            Object res = getAmount.invoke(repo, itemRes);
                            if (res instanceof Number num) {
                                long amt = num.longValue();
                                if (amt > 0) return amt;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 2) フォールバック / 耐久値違い合算走査: repository.getViewList()
            long total = 0;
            try {
                Method getViewList = findMethod(repo.getClass(), "getViewList");
                if (getViewList != null) {
                    Object listObj = getViewList.invoke(repo);
                    if (listObj instanceof List<?> viewList) {
                        for (Object gridRes : viewList) {
                            if (gridRes == null) continue;
                            ItemStack item = extractItemStack(gridRes);
                            if (item != null && ItemMatchHelper.isStockMatch(item, stack)) {
                                Method getResAmt = findMethodWithParamCount(gridRes.getClass(), "getAmount", 1);
                                if (getResAmt != null) {
                                    Object res = getResAmt.invoke(gridRes, repo);
                                    if (res instanceof Number num) {
                                        total += num.longValue();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            return total;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] RS stock query failed: {}", t.toString());
            return 0;
        }
    }

    @Override
    public boolean isAutocraftable(ItemStack stack) {
        try {
            if (!isAvailable() || stack == null || stack.isEmpty()) return false;
            AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
            Object repo = getRepository(menu);
            if (repo == null) return false;

            // 1) 直接取得: ItemResource.ofItemStack(stack)
            try {
                Class<?> itemResClass = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource");
                Method ofStack = itemResClass.getMethod("ofItemStack", ItemStack.class);
                Object itemRes = ofStack.invoke(null, stack);
                if (itemRes != null) {
                    Method isSticky = findMethodWithParamCount(repo.getClass(), "isSticky", 1);
                    if (isSticky != null) {
                        Object res = isSticky.invoke(repo, itemRes);
                        if (res instanceof Boolean b && b) return true;
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2) フォールバック走査: repository.getViewList()
            try {
                Method getViewList = findMethod(repo.getClass(), "getViewList");
                if (getViewList != null) {
                    Object listObj = getViewList.invoke(repo);
                    if (listObj instanceof List<?> viewList) {
                        for (Object gridRes : viewList) {
                            if (gridRes == null) continue;
                            ItemStack item = extractItemStack(gridRes);
                            if (item != null && ItemMatchHelper.isStockMatch(item, stack)) {
                                Method isAuto = findMethodWithParamCount(gridRes.getClass(), "isAutocraftable", 1);
                                if (isAuto != null) {
                                    Object res = isAuto.invoke(gridRes, repo);
                                    if (res instanceof Boolean b && b) return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack extractItemStack(Object gridRes) {
        try {
            Method mKey = findMethod(gridRes.getClass(), "getResourceForRecipeMods");
            Object key = mKey != null ? mKey.invoke(gridRes) : null;
            if (key != null) {
                Method toStack = findMethod(key.getClass(), "toItemStack");
                if (toStack != null) {
                    Object res = toStack.invoke(key);
                    if (res instanceof ItemStack is) return is;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name) {
        try {
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethodWithParamCount(Class<?> c, String name, int params) {
        try {
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == params) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * RS Crafting Grid（クラフトグリッド）が開いている場合、レシピの材料を3x3マトリクスに直接配置する。
     * RSの機械（Autocrafter等）は不要で、純粋な手動クラフト用の素材配置を行う。
     */
    public static boolean transferRecipeToCraftingGrid(AbstractContainerMenu menu, net.minecraft.world.item.crafting.RecipeHolder<?> recipe) {
        if (menu == null || recipe == null) return false;
        try {
            Method transferRecipeMethod = null;
            for (Method m : menu.getClass().getMethods()) {
                if (m.getName().equals("transferRecipe") && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(List.class)) {
                    transferRecipeMethod = m;
                    break;
                }
            }
            if (transferRecipeMethod == null) {
                return false;
            }

            Class<?> itemResClass = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource");
            Method ofStack = itemResClass.getMethod("ofItemStack", ItemStack.class);
            Object repo = getRepository(menu);

            List<List<Object>> slots = new java.util.ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                slots.add(new java.util.ArrayList<>());
            }

            if (recipe.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                List<net.minecraft.world.item.crafting.Ingredient> ingredients = shaped.getIngredients();
                for (int r = 0; r < height && r < 3; r++) {
                    for (int c = 0; c < width && c < 3; c++) {
                        int idx = r * width + c;
                        if (idx < ingredients.size()) {
                            net.minecraft.world.item.crafting.Ingredient ing = ingredients.get(idx);
                            if (ing != null && !ing.isEmpty()) {
                                int gridSlot = r * 3 + c;
                                populateSlotPossibilities(slots.get(gridSlot), ing, ofStack, repo);
                            }
                        }
                    }
                }
            } else {
                List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.value().getIngredients();
                for (int i = 0; i < ingredients.size() && i < 9; i++) {
                    net.minecraft.world.item.crafting.Ingredient ing = ingredients.get(i);
                    if (ing != null && !ing.isEmpty()) {
                        populateSlotPossibilities(slots.get(i), ing, ofStack, repo);
                    }
                }
            }

            transferRecipeMethod.invoke(menu, slots);
            return true;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] RS transferRecipe failed", t);
            return false;
        }
    }

    private static void populateSlotPossibilities(List<Object> slotList, net.minecraft.world.item.crafting.Ingredient ing, Method ofStack, Object repo) {
        if (ing == null || ing.isEmpty()) return;

        // 1. プレイヤー手持ちの現物アイテム（耐久値減クワ、NBT/コンポーネント付きバックパック等）を最優先で候補に追加
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                    ItemStack invStack = mc.player.getInventory().getItem(i);
                    if (!invStack.isEmpty() && isIngredientMatch(ing, invStack)) {
                        Object res = ofStack.invoke(null, invStack);
                        if (res != null && !slotList.contains(res)) {
                            slotList.add(res);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. RSストレージ内の現物アイテム（RS内に保管されている耐久値減クワやMODアイテム等）を追加
        if (repo != null) {
            try {
                Method getViewList = findMethod(repo.getClass(), "getViewList");
                if (getViewList != null) {
                    Object listObj = getViewList.invoke(repo);
                    if (listObj instanceof List<?> viewList) {
                        for (Object gridRes : viewList) {
                            if (gridRes == null) continue;
                            ItemStack item = extractItemStack(gridRes);
                            if (item != null && isIngredientMatch(ing, item)) {
                                Object res = ofStack.invoke(null, item);
                                if (res != null && !slotList.contains(res)) {
                                    slotList.add(res);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. レシピのデフォルトテンプレートアイテム（耐久値0の新品デフォルトアイテム等）をフォールバック候補として追加
        for (ItemStack st : ing.getItems()) {
            try {
                Object res = ofStack.invoke(null, st);
                if (res != null && !slotList.contains(res)) {
                    slotList.add(res);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isIngredientMatch(net.minecraft.world.item.crafting.Ingredient ing, ItemStack stack) {
        if (stack == null || stack.isEmpty() || ing == null || ing.isEmpty()) return false;
        try {
            if (ing.test(stack)) return true;
        } catch (Throwable ignored) {
        }
        for (ItemStack tmpl : ing.getItems()) {
            if (ItemMatchHelper.isStockMatch(stack, tmpl)) {
                return true;
            }
        }
        return false;
    }
}
