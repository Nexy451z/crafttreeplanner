package com.nexy451z.nexcrafttree.integration;

import net.neoforged.fml.ModList;

/**
 * MOD連携の安全ゲート。全ての連携はここでisLoaded確認＋try-catchする。
 */
public final class ModIntegration {
    private ModIntegration() {
    }

    public static boolean isLoaded(String modid) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modid);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRefinedStorageLoaded() {
        return isLoaded("refinedstorage");
    }

    public static boolean isAe2Loaded() {
        return isLoaded("ae2");
    }

    public static boolean isJeiLoaded() {
        return isLoaded("jei");
    }

    public static boolean isReiLoaded() {
        // REIのmodidは roughlyenoughitems
        return isLoaded("roughlyenoughitems");
    }
}


