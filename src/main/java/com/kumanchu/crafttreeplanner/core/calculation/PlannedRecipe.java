package com.kumanchu.crafttreeplanner.core.calculation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 探索された加工レシピ候補の情報。
 * 1つのアイテムに対して複数の加工法（作業台、合金製錬機、高炉等）が存在する場合の選択肢となる。
 */
public class PlannedRecipe {
    private final ResourceLocation id;
    @Nullable
    private final RecipeHolder<?> recipeHolder;
    private final ProcessingStation station;
    private final List<Ingredient> ingredients;
    private final ItemStack output;
    private final int outputCount;
    private final Component categoryTitle;

    public PlannedRecipe(
            ResourceLocation id,
            @Nullable RecipeHolder<?> recipeHolder,
            ProcessingStation station,
            List<Ingredient> ingredients,
            ItemStack output,
            int outputCount,
            Component categoryTitle
    ) {
        this.id = id;
        this.recipeHolder = recipeHolder;
        this.station = station != null ? station : ProcessingStation.CRAFTING_TABLE;
        this.ingredients = ingredients != null ? ingredients : new ArrayList<>();
        this.output = (output != null && !output.isEmpty()) ? output.copy() : ItemStack.EMPTY;
        this.outputCount = Math.max(1, outputCount);
        this.categoryTitle = categoryTitle != null ? categoryTitle : Component.translatable("gui.crafttreeplanner.category_default");
    }

    public ResourceLocation getId() {
        return id;
    }

    @Nullable
    public RecipeHolder<?> getRecipeHolder() {
        return recipeHolder;
    }

    public ProcessingStation getStation() {
        return station;
    }

    public List<Ingredient> getIngredients() {
        return Collections.unmodifiableList(ingredients);
    }

    public ItemStack getOutput() {
        return output;
    }

    public int getOutputCount() {
        return outputCount;
    }

    public Component getCategoryTitle() {
        return categoryTitle;
    }

    public String getShortLabel() {
        return station.getDisplayName().getString();
    }
}
