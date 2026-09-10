package com.nexy451z.nexcrafttree.integration.sophisticatedbackpacks;

import com.nexy451z.nexcrafttree.NexCraftTree;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Sophisticated Backpacks 連携ブリッジ（リフレクションのみ・コンパイル依存なし）。
 *
 * 26.1系の Sophisticated Backpacks はバックパックの中身をアイテムNBTではなく
 * SavedData(BackpackStorage) にUUIDで保持するため、クライアントからは直接読めない。
 * サーバー側で BackpackWrapper#getInventoryHandler 経由で読み書きし、
 * クライアントへは集計結果をネットワーク同期する（BackpackStockProvider 参照）。
 *
 * すべての参照は初回のみ解決し、失敗時は available=false で静かに無効化する。
 */
public final class BackpackBridge {
    private BackpackBridge() {
    }

    private static volatile boolean initTried = false;
    private static volatile boolean available = false;

    private static Class<?> backpackItemClass;
    private static Method fromStackNoCache;
    private static Method getInventoryHandler;
    private static Method getContentsUuid;
    private static Method handlerSize;
    private static Method handlerGetStack;
    private static Method handlerSetStack;
    private static Method handlerSave;

    private static synchronized void init() {
        if (initTried) return;
        initTried = true;
        try {
            if (!net.neoforged.fml.ModList.get().isLoaded("sophisticatedbackpacks")) {
                return;
            }
            ClassLoader cl = BackpackBridge.class.getClassLoader();
            backpackItemClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem", false, cl);
            Class<?> wrapperClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper", false, cl);
            Class<?> iWrapperClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper", false, cl);
            fromStackNoCache = wrapperClass.getMethod("fromStackNoCache", ItemStack.class);
            getInventoryHandler = iWrapperClass.getMethod("getInventoryHandler");
            getContentsUuid = iWrapperClass.getMethod("getContentsUuid");
            Class<?> handlerClass = getInventoryHandler.getReturnType();
            handlerSize = findMethod(handlerClass, "size");
            handlerGetStack = findMethod(handlerClass, "getStackInSlot", int.class);
            handlerSetStack = findMethod(handlerClass, "setStackInSlot", int.class, ItemStack.class);
            handlerSave = findMethod(handlerClass, "saveInventory");
            available = handlerSize != null && handlerGetStack != null && handlerSetStack != null && handlerSave != null;
            if (available) {
                NexCraftTree.LOGGER.info("[NexCraftTree] Sophisticated Backpacks integration enabled.");
            }
        } catch (Throwable t) {
            available = false;
            NexCraftTree.LOGGER.warn("[NexCraftTree] Sophisticated Backpacks integration unavailable (disabled): {}", t.toString());
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    public static boolean isAvailable() {
        if (!initTried) init();
        return available;
    }

    public static boolean isBackpack(ItemStack stack) {
        if (!isAvailable() || stack == null || stack.isEmpty()) return false;
        try {
            return backpackItemClass.isInstance(stack.getItem());
        } catch (Throwable t) {
            return false;
        }
    }

    /** プレイヤーのメイン/オフハンド/チェスト装備にあるバックパックを列挙（重複スタックは除外） */
    public static List<ItemStack> backpackStacks(Player player) {
        List<ItemStack> result = new ArrayList<>();
        if (!isAvailable() || player == null) return result;
        try {
            for (ItemStack s : player.getInventory().getNonEquipmentItems()) {
                if (isBackpack(s)) result.add(s);
            }
            ItemStack off = player.getInventory().getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND);
            if (isBackpack(off)) result.add(off);
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (isBackpack(chest)) result.add(chest);
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static Optional<UUID> contentsUuid(Object wrapper) {
        try {
            @SuppressWarnings("unchecked")
            Optional<UUID> uuid = (Optional<UUID>) getContentsUuid.invoke(wrapper);
            return uuid == null ? Optional.empty() : uuid;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** 1つのバックパックの中身を読み出す（サーバーは実データ、クライアントは同期済みコピー） */
    public static List<ItemStack> readContents(ItemStack backpack) {
        List<ItemStack> result = new ArrayList<>();
        if (!isAvailable() || backpack == null || backpack.isEmpty()) return result;
        try {
            Object wrapper = fromStackNoCache.invoke(null, backpack);
            if (wrapper == null) return result;
            Object handler = getInventoryHandler.invoke(wrapper);
            if (handler == null) return result;
            int n = (int) handlerSize.invoke(handler);
            for (int i = 0; i < n; i++) {
                ItemStack s = (ItemStack) handlerGetStack.invoke(handler, i);
                if (s != null && !s.isEmpty()) {
                    result.add(s.copy());
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** プレイヤーの全バックパックの中身を集計（UUID重複による二重計上を防止） */
    public static List<ItemStack> collectFromPlayer(Player player) {
        List<ItemStack> result = new ArrayList<>();
        if (!isAvailable() || player == null) return result;
        Set<UUID> seen = new HashSet<>();
        for (ItemStack backpack : backpackStacks(player)) {
            try {
                Object wrapper = fromStackNoCache.invoke(null, backpack);
                if (wrapper == null) continue;
                Optional<UUID> uuid = contentsUuid(wrapper);
                if (uuid.isPresent() && !seen.add(uuid.get())) continue;
                result.addAll(readContents(backpack));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /**
     * バックパック内から条件に一致するアイテムを1個取り出す（サーバー側専用）。
     * 取り出しに成功した実スタックを返す。失敗時はEMPTY。
     */
    public static ItemStack extractFirst(Player player, Predicate<ItemStack> match) {
        if (!isAvailable() || player == null || match == null) return ItemStack.EMPTY;
        for (ItemStack backpack : backpackStacks(player)) {
            try {
                Object wrapper = fromStackNoCache.invoke(null, backpack);
                if (wrapper == null) continue;
                Object handler = getInventoryHandler.invoke(wrapper);
                if (handler == null) continue;
                int n = (int) handlerSize.invoke(handler);
                for (int i = 0; i < n; i++) {
                    ItemStack s = (ItemStack) handlerGetStack.invoke(handler, i);
                    if (s == null || s.isEmpty() || !match.test(s)) continue;
                    ItemStack out = s.copyWithCount(1);
                    ItemStack remainder = s.copy();
                    remainder.shrink(1);
                    handlerSetStack.invoke(handler, i, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
                    handlerSave.invoke(handler);
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    /** バックパック内に条件一致アイテムがあるか（読み出しのみ・変更なし） */
    public static boolean contains(Player player, Predicate<ItemStack> match) {
        if (!isAvailable() || player == null || match == null) return false;
        for (ItemStack backpack : backpackStacks(player)) {
            for (ItemStack s : readContents(backpack)) {
                try {
                    if (match.test(s)) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }
}
