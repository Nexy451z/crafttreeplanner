package com.nexy451z.nexcrafttree.core.crafting;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.nexy451z.nexcrafttree.core.calculation.RecipeResolver;
import com.nexy451z.nexcrafttree.integration.refinedstorage.RefinedStorageServerHelper;
import com.nexy451z.nexcrafttree.network.ClientboundDirectCraftResultPayload;
import com.nexy451z.nexcrafttree.network.DirectCraftStep;
import com.nexy451z.nexcrafttree.network.ServerboundExecuteDirectCraftPayload;
import com.nexy451z.nexcrafttree.network.ServerboundTakeOutputPayload;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = NexCraftTree.MODID)
public class DirectCraftingEngine {
    // プレイヤーごとの未回収完成品バッファ: UUID -> ItemStack
    private static final Map<UUID, ItemStack> pendingOutputs = new ConcurrentHashMap<>();

    public static ItemStack getPendingOutput(ServerPlayer player) {
        return pendingOutputs.getOrDefault(player.getUUID(), ItemStack.EMPTY);
    }

    public static void setPendingOutput(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            pendingOutputs.remove(player.getUUID());
        } else {
            pendingOutputs.put(player.getUUID(), stack);
        }
    }

    public static ItemStack takePendingOutput(ServerPlayer player) {
        ItemStack stack = pendingOutputs.remove(player.getUUID());
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flushPendingOutputToInventory(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flushPendingOutputToInventory(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flushPendingOutputToInventory(player);
        }
    }

    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            flushPendingOutputToInventory(player);
        }
    }

    private static void flushPendingOutputToInventory(ServerPlayer player) {
        ItemStack pending = takePendingOutput(player);
        if (!pending.isEmpty()) {
            if (!player.getInventory().add(pending)) {
                player.drop(pending, false);
            }
        }
    }

    public static void handleExecuteCraft(ServerboundExecuteDirectCraftPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            execute(player, payload.targetItem(), payload.quantity(), payload.steps(), payload.slottedWorkstation());
        });
    }

    public static void handleTakeOutput(ServerboundTakeOutputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack pending = takePendingOutput(player);
            if (!pending.isEmpty()) {
                if (!player.getInventory().add(pending)) {
                    player.drop(pending, false);
                }
                PacketDistributor.sendToPlayer(player, new ClientboundDirectCraftResultPayload(
                        true, Component.literal(""), ItemStack.EMPTY, 0
                ));
            }
        });
    }

    public static void execute(ServerPlayer player, ItemStack targetItem, int quantity, List<DirectCraftStep> steps) {
        execute(player, targetItem, quantity, steps, ItemStack.EMPTY);
    }

    /** 1プレイヤーあたりの実行クールダウン(ms)と合計実行数の上限（サーバー保護） */
    private static final long EXECUTE_COOLDOWN_MS = 1000;
    private static final int MAX_TOTAL_EXECUTIONS = 20000;
    private static final Map<UUID, Long> lastExecuteMs = new ConcurrentHashMap<>();

    public static void execute(ServerPlayer player, ItemStack targetItem, int quantity, List<DirectCraftStep> steps, ItemStack slottedWorkstation) {
        ServerLevel level = player.serverLevel();

        // 既存の未回収品があれば先にインベントリに格納
        flushPendingOutputToInventory(player);

        if (steps == null || steps.isEmpty() || steps.size() > 512) {
            reject(player, Component.translatable("msg.nexcrafttree.no_steps"));
            return;
        }
        for (DirectCraftStep step : steps) {
            if (step.inputs() != null && step.inputs().size() > DirectCraftStep.MAX_INPUTS) {
                reject(player, Component.translatable("msg.nexcrafttree.no_steps"));
                return;
            }
        }
        int totalExecutions = 0;
        for (DirectCraftStep step : steps) {
            int count = step.count();
            if (count < 0 || count > 4096) {
                reject(player, Component.translatable("msg.nexcrafttree.too_large"));
                return;
            }
            totalExecutions += count;
        }
        if (totalExecutions > MAX_TOTAL_EXECUTIONS) {
            reject(player, Component.translatable("msg.nexcrafttree.too_large"));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastExecuteMs.get(player.getUUID());
        if (last != null && now - last < EXECUTE_COOLDOWN_MS) {
            reject(player, Component.translatable("msg.nexcrafttree.cooldown"));
            return;
        }
        lastExecuteMs.put(player.getUUID(), now);

        List<ItemStack> intermediatePool = new ArrayList<>();
        List<ItemStack> producedRemainders = new ArrayList<>();

        // 必要設備の事前バリデーション（作業台以外の加工機をプレイヤーが所持しているか確認）
        for (DirectCraftStep step : steps) {
            ItemStack stationIcon = step.stationIcon();
            if (stationIcon != null && !stationIcon.isEmpty() && !stationIcon.is(Items.CRAFTING_TABLE)) {
                if (!isStationAvailable(player, stationIcon, slottedWorkstation)) {
                    String stationName = stationIcon.getHoverName().getString();
                    rollback(player, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                            intermediatePool, producedRemainders, ItemStack.EMPTY,
                            Component.translatable("msg.nexcrafttree.station_missing", stationName));
                    return;
                }
            }
        }

        ItemStack finalOutput = ItemStack.EMPTY;

        for (int stepIdx = 0; stepIdx < steps.size(); stepIdx++) {
            DirectCraftStep step = steps.get(stepIdx);
            ResourceLocation recipeId = step.recipeId();
            int executions = Math.max(1, step.count());

            Optional<RecipeHolder<?>> recipeOpt = level.getRecipeManager().byKey(recipeId);
            if (recipeOpt.isEmpty()) {
                rollback(player, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                        intermediatePool, producedRemainders, finalOutput,
                        Component.translatable("msg.nexcrafttree.recipe_not_found", String.valueOf(recipeId)));
                return;
            }

            Recipe<?> rawRecipe = recipeOpt.get().value();
            boolean isFinalStep = (stepIdx == steps.size() - 1);

            // 設備種別はサーバー側のレシピ型から検証する（クライアント送信のstationIconだけを信じない）。
            // 既知のバニラ型は必須設備が確定、それ以外（MOD機械等）は空でなければ許可。
            if (!stationMatchesRecipe(rawRecipe, step.stationIcon())) {
                rollback(player, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                        intermediatePool, producedRemainders, finalOutput,
                        Component.translatable("msg.nexcrafttree.station_missing",
                                (step.stationIcon() != null && !step.stationIcon().isEmpty())
                                        ? step.stationIcon().getHoverName().getString()
                                        : "?"));
                return;
            }

                for (int exec = 0; exec < executions; exec++) {
                    ItemStack assembled = ItemStack.EMPTY;

                    // この実行1回分で引き抜いた素材。完了したら消費扱い、失敗時のみロールバック対象
                    List<ItemStack> execExtractedPlayer = new ArrayList<>();
                    List<ItemStack> execExtractedRs = new ArrayList<>();
                    List<ItemStack> execPoolTaken = new ArrayList<>();
                    // この実行1回分の残余（バケツ等）。出力が確定してから全体リストへマージする
                    // （失敗時は元アイテムごと返すため残余を返すと二重還元になる）
                    List<ItemStack> execRemainders = new ArrayList<>();


                if (rawRecipe instanceof CraftingRecipe craftingRecipe) {
                    CraftingInput craftingInput;
                    if (craftingRecipe instanceof ShapedRecipe shaped) {
                        int w = shaped.getWidth();
                        int h = shaped.getHeight();
                        NonNullList<Ingredient> ingredients = shaped.getIngredients();
                        NonNullList<ItemStack> inputItems = NonNullList.withSize(w * h, ItemStack.EMPTY);

                        for (int i = 0; i < ingredients.size() && i < w * h; i++) {
                            Ingredient ing = ingredients.get(i);
                            if (ing.isEmpty()) continue;

                            ItemStack extracted = pullIngredient(player, ing, intermediatePool, execPoolTaken, execExtractedPlayer, execExtractedRs);
                            if (extracted.isEmpty()) {
                                rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                        Component.translatable("msg.nexcrafttree.ingredient_missing", String.valueOf(recipeId)));
                                return;
                            }
                            inputItems.set(i, extracted);
                        }
                        craftingInput = CraftingInput.of(w, h, inputItems);
                    } else {
                        NonNullList<Ingredient> ingredients = craftingRecipe.getIngredients();
                        NonNullList<ItemStack> inputItems = NonNullList.withSize(ingredients.size(), ItemStack.EMPTY);

                        for (int i = 0; i < ingredients.size(); i++) {
                            Ingredient ing = ingredients.get(i);
                            if (ing.isEmpty()) continue;

                            ItemStack extracted = pullIngredient(player, ing, intermediatePool, execPoolTaken, execExtractedPlayer, execExtractedRs);
                            if (extracted.isEmpty()) {
                                rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                        Component.translatable("msg.nexcrafttree.ingredient_missing", String.valueOf(recipeId)));
                                return;
                            }
                            inputItems.set(i, extracted);
                        }
                        craftingInput = CraftingInput.of(ingredients.size(), 1, inputItems);
                    }

                    assembled = craftingRecipe.assemble(craftingInput, level.registryAccess());
                    if (assembled.isEmpty()) {
                        rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                Component.translatable("msg.nexcrafttree.assemble_failed", String.valueOf(recipeId)));
                        return;
                    }

                    // 残余アイテム（バケツ等）の回収（出力確定後なので全体リストへ直行）
                    NonNullList<ItemStack> remainders = craftingRecipe.getRemainingItems(craftingInput);
                    for (ItemStack rem : remainders) {
                        if (!rem.isEmpty()) {
                            producedRemainders.add(rem);
                        }
                    }
                } else {
                    // かまど・合金製錬機・機械レシピ（MOD含む）などの非グリッド加工レシピ
                    List<Ingredient> ingredients = RecipeResolver.safeIngredients(recipeOpt.get());
                    boolean usingClientInputs = false;
                    // Mekanism等、getIngredientsが空で独自Ingredient型のレシピ:
                    // 正規入力が取得不能なため、クライアントが計算した材料リストを代用する（READMEの制限事項）
                    List<ItemStack> clientInputs = step.inputs();
                    if (ingredients.isEmpty() && clientInputs != null && !clientInputs.isEmpty()) {
                        usingClientInputs = true;
                    }

                    List<ItemStack> inputItems = new ArrayList<>();
                    if (usingClientInputs) {
                        for (ItemStack need : clientInputs) {
                            if (need == null || need.isEmpty()) continue;
                            int needCount = Math.max(1, need.getCount());
                            ItemStack template = need.copyWithCount(1);
                            for (int k = 0; k < needCount; k++) {
                                ItemStack extracted = pullExact(player, template, intermediatePool, execPoolTaken, execExtractedPlayer, execExtractedRs);
                                if (extracted.isEmpty()) {
                                    rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                            Component.translatable("msg.nexcrafttree.ingredient_missing", String.valueOf(recipeId)));
                                    return;
                                }
                                inputItems.add(extracted);
                                ItemStack remainder = extracted.getCraftingRemainingItem();
                                if (!remainder.isEmpty()) {
                                    execRemainders.add(remainder);
                                }
                            }
                        }
                    } else {
                        for (Ingredient ing : ingredients) {
                            if (ing.isEmpty()) continue;
                            ItemStack extracted = pullIngredient(player, ing, intermediatePool, execPoolTaken, execExtractedPlayer, execExtractedRs);
                            if (extracted.isEmpty()) {
                                rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                        Component.translatable("msg.nexcrafttree.ingredient_missing", String.valueOf(recipeId)));
                                return;
                            }
                            inputItems.add(extracted);

                            ItemStack remainder = extracted.getCraftingRemainingItem();
                            if (!remainder.isEmpty()) {
                                execRemainders.add(remainder);
                            }
                        }
                    }

                    // 入力ゼロのまま assemble に進むと無消費のアイテム生成になるため一律で実行不可
                    if (inputItems.isEmpty()) {
                        rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                Component.translatable("msg.nexcrafttree.ingredient_missing", String.valueOf(recipeId)));
                        return;
                    }

                    // 1. SingleRecipeInput での assemble 試行（かまど、高炉、石切機、Mekanism item系等）
                    if (inputItems.size() == 1) {
                        try {
                            SingleRecipeInput singleInput = new SingleRecipeInput(inputItems.get(0));
                            assembled = ((Recipe<SingleRecipeInput>) (Object) rawRecipe).assemble(singleInput, level.registryAccess());
                        } catch (Throwable ignored) {
                        }
                    }

                    // 2. getResultItem による取得（Oritech / Create ProcessingRecipe / Mekanism BasicItemStackToItemStack 等はここで取れる）
                    if (assembled.isEmpty()) {
                        try {
                            assembled = rawRecipe.getResultItem(level.registryAccess());
                        } catch (Throwable ignored) {
                        }
                    }

                    // 3. 汎用出力ディスカバリ（getResultItemがEMPTYのMODレシピ向け。
                    //    すべてレシピオブジェクト自身（サーバー実データ）から取得するため改ざん耐性は維持される）
                    if (assembled.isEmpty()) {
                        assembled = discoverRecipeOutput(rawRecipe);
                    }

                    if (assembled.isEmpty()) {
                        rollback(player, execExtractedPlayer, execExtractedRs, execPoolTaken, intermediatePool, producedRemainders, finalOutput,
                                Component.translatable("msg.nexcrafttree.process_failed", String.valueOf(recipeId)));
                        return;
                    }
                    // 出力が確定した時点で残余を確定させる
                    producedRemainders.addAll(execRemainders);
                }

                if (isFinalStep) {
                    if (finalOutput.isEmpty()) {
                        finalOutput = assembled.copy();
                    } else if (ItemStack.isSameItemSameComponents(finalOutput, assembled)
                            && finalOutput.getCount() + assembled.getCount() <= finalOutput.getMaxStackSize()) {
                        finalOutput.grow(assembled.getCount());
                    } else {
                        // スタックできないアイテム（バックパック等）や上限超過はインベントリへ
                        if (!player.getInventory().add(assembled)) {
                            player.drop(assembled, false);
                        }
                    }
                } else {
                    intermediatePool.add(assembled);
                }
            }
        }

        // 残余アイテムをインベントリに返却
        for (ItemStack rem : producedRemainders) {
            if (!player.getInventory().add(rem)) {
                player.drop(rem, false);
            }
        }

        // 余剰の中間アイテムがあればインベントリに返却
        for (ItemStack inter : intermediatePool) {
            if (!inter.isEmpty()) {
                if (!player.getInventory().add(inter)) {
                    player.drop(inter, false);
                }
            }
        }

        // 完成品を完成品スロットにセット
        setPendingOutput(player, finalOutput);

        PacketDistributor.sendToPlayer(player, new ClientboundDirectCraftResultPayload(
                true,
                Component.translatable("msg.nexcrafttree.done"),
                finalOutput,
                finalOutput.getCount()
        ));
    }

    private static void reject(ServerPlayer player, Component message) {
        PacketDistributor.sendToPlayer(player, new ClientboundDirectCraftResultPayload(
                false, message, ItemStack.EMPTY, 0
        ));
    }

    /** 出力ディスカバリのメソッド/フィールドキャッシュ（実行毎のリフレクションコスト回避） */
    private static final Map<Class<?>, java.util.List<java.lang.reflect.Method>> OUTPUT_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.util.List<java.lang.reflect.Field>> OUTPUT_FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * レシピオブジェクトからItemStack出力を汎用的に発見する（全てサーバー側データ）。
     * 優先順: 0引数メソッドの戻り値ItemStack（名前にoutput/result）→ List&lt;ItemStack&gt;の0引数メソッド
     * （getResults/getOutputDefinition等）→ ItemStack/List&lt;ItemStack&gt;フィールド。
     */
    private static ItemStack discoverRecipeOutput(Recipe<?> recipe) {
        try {
            Class<?> cls = recipe.getClass();

            java.util.List<java.lang.reflect.Method> methods = OUTPUT_METHOD_CACHE.computeIfAbsent(cls, c -> {
                java.util.List<java.lang.reflect.Method> found = new ArrayList<>();
                for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                    for (java.lang.reflect.Method m : cur.getDeclaredMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        String name = m.getName().toLowerCase(Locale.ROOT);
                        if (!name.contains("output") && !name.contains("result")) continue;
                        m.setAccessible(true);
                        found.add(m);
                    }
                }
                // 出力語を含むメソッドを優先順に並べる: 正確な "output" 名 → ItemStack戻り値 → その他
                found.sort((a, b) -> {
                    boolean aStack = ItemStack.class.isAssignableFrom(a.getReturnType());
                    boolean bStack = ItemStack.class.isAssignableFrom(b.getReturnType());
                    if (aStack != bStack) return aStack ? -1 : 1;
                    boolean aOut = a.getName().toLowerCase(Locale.ROOT).contains("output");
                    boolean bOut = b.getName().toLowerCase(Locale.ROOT).contains("output");
                    if (aOut != bOut) return aOut ? -1 : 1;
                    return 0;
                });
                return found;
            });

            for (java.lang.reflect.Method m : methods) {
                try {
                    Object v = m.invoke(recipe);
                    if (v instanceof ItemStack s && !s.isEmpty()) return s;
                    if (v instanceof List<?> l) {
                        for (Object e : l) {
                            if (e instanceof ItemStack s && !s.isEmpty()) return s;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            java.util.List<java.lang.reflect.Field> fields = OUTPUT_FIELD_CACHE.computeIfAbsent(cls, c -> {
                java.util.List<java.lang.reflect.Field> found = new ArrayList<>();
                for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                    for (java.lang.reflect.Field f : cur.getDeclaredFields()) {
                        Class<?> t = f.getType();
                        if (ItemStack.class.isAssignableFrom(t)
                                || (List.class.isAssignableFrom(t))) {
                            f.setAccessible(true);
                            found.add(f);
                        }
                    }
                }
                return found;
            });
            for (java.lang.reflect.Field f : fields) {
                try {
                    Object v = f.get(recipe);
                    if (v instanceof ItemStack s && !s.isEmpty()) return s;
                    if (v instanceof List<?> l) {
                        for (Object e : l) {
                            if (e instanceof ItemStack s && !s.isEmpty()) return s;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack pullIngredient(
            ServerPlayer player,
            Ingredient ing,
            List<ItemStack> intermediatePool,
            List<ItemStack> poolTaken,
            List<ItemStack> extractedFromPlayer,
            List<ItemStack> extractedFromRs
    ) {
        // 1. 中間プールから探索
        for (int i = 0; i < intermediatePool.size(); i++) {
            ItemStack poolStack = intermediatePool.get(i);
            if (!poolStack.isEmpty() && isIngredientMatch(ing, poolStack)) {
                ItemStack single = poolStack.split(1);
                if (poolStack.isEmpty()) {
                    intermediatePool.remove(i);
                }
                poolTaken.add(single.copy());
                return single;
            }
        }

        // 2. プレイヤー手持ちインベントリ（メインインベントリ + オフハンド）から探索
        for (int s = 0; s < player.getInventory().items.size(); s++) {
            ItemStack invStack = player.getInventory().items.get(s);
            if (!invStack.isEmpty() && isIngredientMatch(ing, invStack)) {
                ItemStack single = invStack.split(1);
                if (invStack.isEmpty()) {
                    player.getInventory().items.set(s, ItemStack.EMPTY);
                }
                extractedFromPlayer.add(single.copy());
                return single;
            }
        }
        for (int s = 0; s < player.getInventory().offhand.size(); s++) {
            ItemStack offStack = player.getInventory().offhand.get(s);
            if (!offStack.isEmpty() && isIngredientMatch(ing, offStack)) {
                ItemStack single = offStack.split(1);
                if (offStack.isEmpty()) {
                    player.getInventory().offhand.set(s, ItemStack.EMPTY);
                }
                extractedFromPlayer.add(single.copy());
                return single;
            }
        }

        // 3. RS ストレージから探索
        if (RefinedStorageServerHelper.isRsContainerOpen(player)) {
            ItemStack rsSingle = RefinedStorageServerHelper.extractSingle(player, ing);
            if (!rsSingle.isEmpty()) {
                extractedFromRs.add(rsSingle.copy());
                return rsSingle;
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean isIngredientMatch(Ingredient ing, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (ing.test(stack)) return true;
        ItemStack[] options = ing.getItems();
        if (options != null) {
            for (ItemStack opt : options) {
                if (ItemMatchHelper.isStockMatch(stack, opt)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 具体的なアイテムスタック（テンプレート）と同一かで引き当てる（クライアント計算入力用） */
    private static ItemStack pullExact(
            ServerPlayer player,
            ItemStack template,
            List<ItemStack> intermediatePool,
            List<ItemStack> poolTaken,
            List<ItemStack> extractedFromPlayer,
            List<ItemStack> extractedFromRs
    ) {
        // 1. 中間プールから探索
        for (int i = 0; i < intermediatePool.size(); i++) {
            ItemStack poolStack = intermediatePool.get(i);
            if (!poolStack.isEmpty() && ItemStack.isSameItemSameComponents(poolStack, template)) {
                ItemStack single = poolStack.split(1);
                if (poolStack.isEmpty()) {
                    intermediatePool.remove(i);
                }
                poolTaken.add(single.copy());
                return single;
            }
        }

        // 2. プレイヤー手持ちインベントリ
        for (int s = 0; s < player.getInventory().items.size(); s++) {
            ItemStack invStack = player.getInventory().items.get(s);
            if (!invStack.isEmpty() && ItemStack.isSameItemSameComponents(invStack, template)) {
                ItemStack single = invStack.split(1);
                if (invStack.isEmpty()) {
                    player.getInventory().items.set(s, ItemStack.EMPTY);
                }
                extractedFromPlayer.add(single.copy());
                return single;
            }
        }
        for (int s = 0; s < player.getInventory().offhand.size(); s++) {
            ItemStack offStack = player.getInventory().offhand.get(s);
            if (!offStack.isEmpty() && ItemStack.isSameItemSameComponents(offStack, template)) {
                ItemStack single = offStack.split(1);
                if (offStack.isEmpty()) {
                    player.getInventory().offhand.set(s, ItemStack.EMPTY);
                }
                extractedFromPlayer.add(single.copy());
                return single;
            }
        }

        // 3. RS ストレージから探索
        if (RefinedStorageServerHelper.isRsContainerOpen(player)) {
            ItemStack rsSingle = RefinedStorageServerHelper.extractSingle(player, Ingredient.of(template));
            if (!rsSingle.isEmpty()) {
                extractedFromRs.add(rsSingle.copy());
                return rsSingle;
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * 失敗時の巻き戻し。
     * poolTaken: 失敗した実行が中間プールから取り出した分 → プールに戻す
     * extractedFromPlayer / extractedFromRs: 失敗した実行で引き抜いた未消費素材 → 所持品/RSに返す
     * intermediatePool: 未使用の中間品 → 所持品に返す（先行工程の実物換算）
     * producedRemainders: 生成済みの残余（バケツ等） → 所持品に返す
     * finalOutputRefund: 失敗前に完成していた最終品 → 所持品に返す（先行実行分の損失防止）
     * 先行工程の素材は中間品に変換済みのため返却せず、二重還元を防ぐ
     */
    private static void rollback(
            ServerPlayer player,
            List<ItemStack> extractedFromPlayer,
            List<ItemStack> extractedFromRs,
            List<ItemStack> poolTaken,
            List<ItemStack> intermediatePool,
            List<ItemStack> producedRemainders,
            ItemStack finalOutputRefund,
            Component message
    ) {
        if (poolTaken != null && !poolTaken.isEmpty()) {
            intermediatePool.addAll(poolTaken);
        }
        for (ItemStack stack : extractedFromPlayer) {
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        for (ItemStack stack : extractedFromRs) {
            if (!stack.isEmpty()) {
                boolean returned = RefinedStorageServerHelper.returnToStorage(player, stack);
                if (!returned) {
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
        }
        for (ItemStack stack : intermediatePool) {
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        for (ItemStack stack : producedRemainders) {
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        if (finalOutputRefund != null && !finalOutputRefund.isEmpty()) {
            if (!player.getInventory().add(finalOutputRefund)) {
                player.drop(finalOutputRefund, false);
            }
        }
        PacketDistributor.sendToPlayer(player, new ClientboundDirectCraftResultPayload(
                false, message, ItemStack.EMPTY, 0
        ));
    }

    /**
     * レシピ型から要求される設備を検証する（クライアントのstationIconはヒントに過ぎない）。
     * 既知のバニラ型は必須設備が確定。それ以外（MOD機械・特殊レシピ等）は空でなければ許可。
     */
    private static boolean stationMatchesRecipe(Recipe<?> recipe, ItemStack stationIcon) {
        if (stationIcon == null || stationIcon.isEmpty()) return false;
        if (recipe instanceof CraftingRecipe) return stationIcon.is(Items.CRAFTING_TABLE);
        if (recipe instanceof SmeltingRecipe) return stationIcon.is(Items.FURNACE);
        if (recipe instanceof BlastingRecipe) return stationIcon.is(Items.BLAST_FURNACE);
        if (recipe instanceof SmokingRecipe) return stationIcon.is(Items.SMOKER);
        if (recipe instanceof StonecutterRecipe) return stationIcon.is(Items.STONECUTTER);
        if (recipe instanceof SmithingRecipe) return stationIcon.is(Items.SMITHING_TABLE);
        return true;
    }

    private static boolean isStationAvailable(ServerPlayer player, ItemStack stationIcon, ItemStack slottedWorkstation) {
        if (stationIcon == null || stationIcon.isEmpty() || stationIcon.is(Items.CRAFTING_TABLE)) return true;

        // スロット設備で満たす場合、その設備を実際に所持していることを検証する
        // （クライアントが未所持の設備を装って申告するのを防ぐ）
        if (slottedWorkstation != null && !slottedWorkstation.isEmpty()
                && (ItemStack.isSameItem(stationIcon, slottedWorkstation) || ItemMatchHelper.isStockMatch(stationIcon, slottedWorkstation))) {
            if (playerActuallyOwns(player, slottedWorkstation)) {
                return true;
            }
            return false;
        }

        if (playerActuallyOwns(player, stationIcon)) {
            return true;
        }

        return false;
    }

    private static boolean playerActuallyOwns(ServerPlayer player, ItemStack target) {
        for (ItemStack invStack : player.getInventory().items) {
            if (!invStack.isEmpty() && (ItemStack.isSameItem(target, invStack) || ItemMatchHelper.isStockMatch(target, invStack))) {
                return true;
            }
        }
        for (ItemStack offStack : player.getInventory().offhand) {
            if (!offStack.isEmpty() && (ItemStack.isSameItem(target, offStack) || ItemMatchHelper.isStockMatch(target, offStack))) {
                return true;
            }
        }
        if (RefinedStorageServerHelper.isRsContainerOpen(player) && RefinedStorageServerHelper.hasItem(player, target)) {
            return true;
        }
        return false;
    }
}



