package com.kumanchu.crafttreeplanner.integration.ae2;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.ItemMatchHelper;
import com.kumanchu.crafttreeplanner.core.stock.IStockProvider;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * AE2在庫取得。コンパイル依存なし（リフレクションのみ）。
 * 調査結果: 開いているMEStorageMenu#getClientRepo().getAllEntries()
 * → GridInventoryEntry#getWhat/getStoredAmount/isCraftable が正規経路。
 */
public class Ae2Stock implements IStockProvider {

    @Override
    public String getSourceName() {
        return "ae2";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (!ModIntegration.isAe2Loaded()) return false;
            if (Minecraft.getInstance().player == null) return false;
            AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
            if (menu == null) return false;
            String name = menu.getClass().getName().toLowerCase();
            return name.contains("appeng") || name.contains("mestorage") || name.contains("craftingterm")
                    || name.contains("patternterm") || name.contains("meterminal");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public long getAmount(ItemStack stack) {
        try {
            if (!isAvailable() || stack == null || stack.isEmpty()) return 0;
            Object repo = getClientRepo();
            if (repo == null) return 0;
            Collection<?> entries = getAllEntries(repo);
            if (entries == null) return 0;
            long total = 0;
            for (Object e : entries) {
                try {
                    ItemStack what = extractItem(e);
                    if (what == null || what.isEmpty()) continue;
                    if (!ItemMatchHelper.isStockMatch(what, stack)) continue;
                    total += extractStored(e);
                } catch (Throwable t) {
                    continue;
                }
            }
            return total;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] AE2 stock failed: {}", t.toString());
            return 0;
        }
    }

    @Override
    public boolean isAutocraftable(ItemStack stack) {
        try {
            if (!isAvailable() || stack == null || stack.isEmpty()) return false;
            Object repo = getClientRepo();
            if (repo == null) return false;
            Collection<?> entries = getAllEntries(repo);
            if (entries == null) return false;
            for (Object e : entries) {
                try {
                    ItemStack what = extractItem(e);
                    if (what == null || what.isEmpty()) continue;
                    if (!ItemMatchHelper.isStockMatch(what, stack)) continue;
                    if (extractCraftable(e)) return true;
                } catch (Throwable ignored) {
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object getClientRepo() {
        try {
            AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
            Method m = null;
            for (Method cand : menu.getClass().getMethods()) {
                if (cand.getName().equals("getClientRepo") && cand.getParameterCount() == 0) {
                    m = cand;
                    break;
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(menu);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<?> getAllEntries(Object repo) {
        try {
            for (Method m : repo.getClass().getMethods()) {
                if (m.getName().equals("getAllEntries") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object r = m.invoke(repo);
                    if (r instanceof Collection<?> c) return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** GridInventoryEntry#getWhat() → AE2Key → ItemStack変換を試みる */
    private ItemStack extractItem(Object entry) {
        try {
            Method getWhat = null;
            for (Method m : entry.getClass().getMethods()) {
                if (m.getName().equals("getWhat") && m.getParameterCount() == 0) {
                    getWhat = m;
                    break;
                }
            }
            if (getWhat == null) return null;
            getWhat.setAccessible(true);
            Object what = getWhat.invoke(entry);
            if (what == null) return null;
            if (what instanceof ItemStack s) return s;
            // AE2Key (AEItemKey) → wrap / asItemStack / toStack 等を総当たり
            for (Method m : what.getClass().getMethods()) {
                try {
                    String n = m.getName().toLowerCase();
                    if (m.getParameterCount() != 0) continue;
                    if (!(n.contains("stack") || n.contains("item") || n.contains("wrap"))) continue;
                    m.setAccessible(true);
                    Object r = m.invoke(what);
                    if (r instanceof ItemStack s) return s;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private long extractStored(Object entry) {
        try {
            for (Method m : entry.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (m.getParameterCount() != 0) continue;
                if (!(n.contains("stored") || n.contains("amount"))) continue;
                m.setAccessible(true);
                Object r = m.invoke(entry);
                if (r instanceof Number num) return num.longValue();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private boolean extractCraftable(Object entry) {
        try {
            for (Method m : entry.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (m.getParameterCount() != 0) continue;
                if (!(n.contains("craftable") || n.contains("craft"))) continue;
                if (!m.getReturnType().equals(boolean.class) && !m.getReturnType().equals(Boolean.class)) continue;
                m.setAccessible(true);
                Object r = m.invoke(entry);
                if (r instanceof Boolean b) return b;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
