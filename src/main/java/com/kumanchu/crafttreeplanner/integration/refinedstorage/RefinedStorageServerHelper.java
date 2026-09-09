package com.kumanchu.crafttreeplanner.integration.refinedstorage;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.ItemMatchHelper;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * サーバー側での Refined Storage 2 ストレージ直接抽出・返却ヘルパー。
 * コンパイル時依存ゼロ（完全リフレクション）。
 * プレイヤーが開いている RS グリッドから、材料アイテムを安全に1個単位で抽出/返却する。
 */
public class RefinedStorageServerHelper {

    public static boolean isRsContainerOpen(ServerPlayer player) {
        if (!ModIntegration.isRefinedStorageLoaded() || player == null) return false;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return false;
        return menu.getClass().getName().contains("refinedstorage");
    }

    private static Object getGrid(AbstractContainerMenu menu) {
        try {
            Field gridField = findField(menu.getClass(), "grid");
            if (gridField != null) {
                gridField.setAccessible(true);
                return gridField.get(menu);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getItemStorage(Object grid) {
        try {
            Method m = findMethod(grid.getClass(), "getItemStorage");
            if (m != null) {
                return m.invoke(grid);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * RSグリッドから指定 Ingredient に一致するアイテムを1個抽出する
     */
    public static ItemStack extractSingle(ServerPlayer player, Ingredient ingredient) {
        if (!isRsContainerOpen(player) || ingredient == null || ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try {
            AbstractContainerMenu menu = player.containerMenu;
            Object grid = getGrid(menu);
            if (grid == null) return ItemStack.EMPTY;

            Object storage = getItemStorage(grid);
            if (storage == null) return ItemStack.EMPTY;

            Class<?> playerActorClass = Class.forName("com.refinedmods.refinedstorage.common.api.storage.PlayerActor");
            Constructor<?> actorCtor = playerActorClass.getConstructor(net.minecraft.world.entity.player.Player.class);
            Object playerActor = actorCtor.newInstance(player);

            Class<?> actionClass = Class.forName("com.refinedmods.refinedstorage.api.core.Action");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object executeAction = Enum.valueOf((Class<Enum>) actionClass, "EXECUTE");

            // グリッド内の全リソースを取得
            Method getResourcesMethod = findMethod(grid.getClass(), "getResources", Class.class);
            if (getResourcesMethod == null) return ItemStack.EMPTY;

            List<?> trackedList = (List<?>) getResourcesMethod.invoke(grid, playerActorClass);
            if (trackedList == null) return ItemStack.EMPTY;

            ItemStack[] ingOptions = ingredient.getItems();

            for (Object tracked : trackedList) {
                if (tracked == null) continue;
                Method resourceAmountMethod = findMethod(tracked.getClass(), "resourceAmount");
                if (resourceAmountMethod == null) continue;
                Object resourceAmount = resourceAmountMethod.invoke(tracked);
                if (resourceAmount == null) continue;

                Method resourceMethod = findMethod(resourceAmount.getClass(), "resource");
                if (resourceMethod == null) continue;
                Object resource = resourceMethod.invoke(resourceAmount);
                if (resource == null) continue;

                // ItemResource か判定
                if (!resource.getClass().getName().contains("ItemResource")) continue;

                Method toItemStackMethod = findMethod(resource.getClass(), "toItemStack");
                if (toItemStackMethod == null) continue;
                ItemStack candidate = (ItemStack) toItemStackMethod.invoke(resource);
                if (candidate == null || candidate.isEmpty()) continue;

                boolean matches = ingredient.test(candidate);
                if (!matches && ingOptions != null) {
                    for (ItemStack opt : ingOptions) {
                        if (ItemMatchHelper.isStockMatch(candidate, opt)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (matches) {
                    // 一致するアイテムを発見！ストレージから1個抽出
                    Method extractMethod = findMethod(storage.getClass(), "extract",
                            Class.forName("com.refinedmods.refinedstorage.api.resource.ResourceKey"),
                            long.class,
                            actionClass,
                            Class.forName("com.refinedmods.refinedstorage.api.storage.Actor")
                    );
                    if (extractMethod != null) {
                        long extracted = (long) extractMethod.invoke(storage, resource, 1L, executeAction, playerActor);
                        if (extracted > 0) {
                            return candidate.copyWithCount((int) extracted);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] Failed to extract item from RS storage", t);
        }

        return ItemStack.EMPTY;
    }

    /**
     * ロールバック用：抽出したアイテムを RS ストレージに戻す
     */
    public static boolean returnToStorage(ServerPlayer player, ItemStack stack) {
        if (!isRsContainerOpen(player) || stack == null || stack.isEmpty()) return false;

        try {
            AbstractContainerMenu menu = player.containerMenu;
            Object grid = getGrid(menu);
            if (grid == null) return false;

            Object storage = getItemStorage(grid);
            if (storage == null) return false;

            Class<?> itemResourceClass = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource");
            Method ofItemStack = itemResourceClass.getMethod("ofItemStack", ItemStack.class);
            Object resource = ofItemStack.invoke(null, stack);

            Class<?> playerActorClass = Class.forName("com.refinedmods.refinedstorage.common.api.storage.PlayerActor");
            Constructor<?> actorCtor = playerActorClass.getConstructor(net.minecraft.world.entity.player.Player.class);
            Object playerActor = actorCtor.newInstance(player);

            Class<?> actionClass = Class.forName("com.refinedmods.refinedstorage.api.core.Action");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object executeAction = Enum.valueOf((Class<Enum>) actionClass, "EXECUTE");

            Method insertMethod = findMethod(storage.getClass(), "insert",
                    Class.forName("com.refinedmods.refinedstorage.api.resource.ResourceKey"),
                    long.class,
                    actionClass,
                    Class.forName("com.refinedmods.refinedstorage.api.storage.Actor")
            );

            if (insertMethod != null) {
                long inserted = (long) insertMethod.invoke(storage, resource, (long) stack.getCount(), executeAction, playerActor);
                return inserted == stack.getCount();
            }
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] Failed to return item to RS storage", t);
        }
        return false;
    }

    /**
     * RSグリッド内に指定アイテムが保管されているか確認する（引き出しは行わない）
     */
    public static boolean hasItem(ServerPlayer player, ItemStack target) {
        if (!isRsContainerOpen(player) || target == null || target.isEmpty()) return false;
        try {
            AbstractContainerMenu menu = player.containerMenu;
            Object grid = getGrid(menu);
            if (grid == null) return false;

            Class<?> playerActorClass = Class.forName("com.refinedmods.refinedstorage.common.api.storage.PlayerActor");
            Method getResourcesMethod = findMethod(grid.getClass(), "getResources", Class.class);
            if (getResourcesMethod == null) return false;

            List<?> trackedList = (List<?>) getResourcesMethod.invoke(grid, playerActorClass);
            if (trackedList == null) return false;

            for (Object tracked : trackedList) {
                if (tracked == null) continue;
                Method resourceAmountMethod = findMethod(tracked.getClass(), "resourceAmount");
                if (resourceAmountMethod == null) continue;
                Object resourceAmount = resourceAmountMethod.invoke(tracked);
                if (resourceAmount == null) continue;

                Method resourceMethod = findMethod(resourceAmount.getClass(), "resource");
                if (resourceMethod == null) continue;
                Object resource = resourceMethod.invoke(resourceAmount);
                if (resource == null) continue;

                if (!resource.getClass().getName().contains("ItemResource")) continue;

                Method toItemStackMethod = findMethod(resource.getClass(), "toItemStack");
                if (toItemStackMethod == null) continue;
                ItemStack candidate = (ItemStack) toItemStackMethod.invoke(resource);
                if (candidate != null && !candidate.isEmpty() && (ItemStack.isSameItem(candidate, target) || ItemMatchHelper.isStockMatch(candidate, target))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] RS hasItem check failed", t);
        }
        return false;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && (parameterTypes.length == 0 || m.getParameterCount() == parameterTypes.length)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
