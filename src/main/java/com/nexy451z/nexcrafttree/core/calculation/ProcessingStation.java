package com.nexy451z.nexcrafttree.core.calculation;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

/**
 * レシピを実行するために必要な加工設備（作業台、かまど、合金製錬機、冶金注入機など）の情報。
 */
public class ProcessingStation {
    public static final ProcessingStation CRAFTING_TABLE = new ProcessingStation(
            new ItemStack(Items.CRAFTING_TABLE),
            Component.translatable("block.minecraft.crafting_table"),
            "minecraft:crafting",
            true
    );

    public static final ProcessingStation FURNACE = new ProcessingStation(
            new ItemStack(Items.FURNACE),
            Component.translatable("block.minecraft.furnace"),
            "minecraft:smelting",
            false
    );

    private final ItemStack icon;
    private final Component displayName;
    private final String categoryUid;
    private final boolean isCraftingTable;

    public ProcessingStation(ItemStack icon, Component displayName, String categoryUid, boolean isCraftingTable) {
        this.icon = (icon != null && !icon.isEmpty()) ? icon.copy() : new ItemStack(Items.CRAFTING_TABLE);
        this.displayName = displayName != null ? displayName : Component.translatable("gui.nexcrafttree.station_default");
        this.categoryUid = categoryUid != null ? categoryUid : "minecraft:crafting";
        this.isCraftingTable = isCraftingTable;
    }

    public ItemStack getIcon() {
        return icon;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public String getCategoryUid() {
        return categoryUid;
    }

    public boolean isCraftingTable() {
        return isCraftingTable;
    }

    public boolean matches(ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return ItemStack.isSameItem(this.icon, candidate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessingStation that = (ProcessingStation) o;
        return isCraftingTable == that.isCraftingTable &&
                Objects.equals(categoryUid, that.categoryUid) &&
                ItemStack.isSameItem(icon, that.icon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(categoryUid, icon.getItem(), isCraftingTable);
    }
}


