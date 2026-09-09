package com.kumanchu.crafttreeplanner.core.calculation;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.ItemMatchHelper;
import com.kumanchu.crafttreeplanner.core.stock.UnifiedStockSnapshot;
import com.kumanchu.crafttreeplanner.integration.jei.JeiHover;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * レシピツリー再帰探索エンジン。
 * JEI / バニラRecipeManager の双方から作業台レシピに加え各種加工機械（かまど、合金製錬機、冶金注入機など）
 * のレシピを網羅的に探索し、複数候補の切り替えや対応作業台の特定を行う。
 */
public class RecipeResolver {
    /** 実行時の探索上限。画面内プリセットで上書きされる（null = 設定ファイル値を使用） */
    public static volatile int limitMaxDepth = -1;
    public static volatile int limitMaxNodes = -1;
    public static volatile int limitTimeoutMs = -1;
    public static volatile boolean limitDemoteInfoCategories = true;

    private static int maxDepth() {
        return limitMaxDepth > 0 ? limitMaxDepth : com.kumanchu.crafttreeplanner.Config.CONFIG_MAX_DEPTH.get();
    }

    private static int maxNodes() {
        return limitMaxNodes > 0 ? limitMaxNodes : com.kumanchu.crafttreeplanner.Config.CONFIG_MAX_NODES.get();
    }

    private static long timeoutMs() {
        return limitTimeoutMs > 0 ? limitTimeoutMs : com.kumanchu.crafttreeplanner.Config.CONFIG_TIMEOUT_MS.get();
    }

    private static boolean demoteInfoCategories() {
        return limitDemoteInfoCategories && com.kumanchu.crafttreeplanner.Config.CONFIG_DEMOTE_INFO_CATEGORIES.get();
    }

    /** 探索進捗通知（ノード展開数）。バックグラウンド計算の進捗バー用。 */
    public interface ProgressListener {
        void onProgress(int expandedNodes, boolean finished);
    }

    @javax.annotation.Nullable
    private ProgressListener progressListener = null;
    private int nodeCount = 0;
    private long deadline = 0;
    private final Map<net.minecraft.world.item.Item, Long> consumed = new HashMap<>();
    /** セッション共有のレシピ候補キャッシュ（未ソート・レシピ再読込時にinvalidateCachesで破棄） */
    private static final Map<net.minecraft.world.item.Item, List<PlannedRecipe>> candidateCache = new ConcurrentHashMap<>();

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level) {
        return resolve(target, wantCount, stock, level, null);
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level, @Nullable ItemStack activeWorkstation) {
        return resolve(target, wantCount, stock, level, activeWorkstation, null);
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level,
                                    @Nullable ItemStack activeWorkstation, @Nullable ProgressListener progress) {
        progressListener = progress;
        nodeCount = 0;
        consumed.clear();
        deadline = System.currentTimeMillis() + timeoutMs(); // 上限時間で安全に打ち切り
        VirtualStockTracker tracker = new VirtualStockTracker();
        Deque<ResourceLocation> path = new ArrayDeque<>();
        CraftingTreeNode result = resolveRecursive(target, Math.max(1, wantCount), stock, tracker, level, path, 0, activeWorkstation);
        if (progressListener != null) {
            progressListener.onProgress(nodeCount, true);
        }
        return result;
    }

    private CraftingTreeNode resolveRecursive(ItemStack target, long want, UnifiedStockSnapshot stock,
                                              VirtualStockTracker tracker, Level level,
                                              Deque<ResourceLocation> path, int depth,
                                              @Nullable ItemStack activeWorkstation) {
        CraftingTreeNode node = new CraftingTreeNode(target, want);
        try {
            if (target == null || target.isEmpty() || want <= 0) {
                return node;
            }

            ResourceLocation key = VirtualStockTracker.keyOf(target);

            long deficit;
            if (depth == 0) {
                // ルートノード: ユーザーは target を want 個作成する計画を求めている
                long available = 0;
                try {
                    available = stock.getTotal(target);
                } catch (Throwable ignored) {
                }
                node.storedAmount = 0;
                node.totalStockAmount = available;
                try {
                    node.autocraftable = stock.isAutocraftableAnywhere(target);
                } catch (Throwable ignored) {
                }
                deficit = want;
            } else {
                // 子ノード: 在庫引き当て（手持ち + RS + AE2 の合算）。
                // 在庫充当は上限チェックよりも必ず先に行う（上限打ち切りでも所持分は不足扱いにしない）
                long totalStock = 0;
                try {
                    totalStock = stock.getTotal(target);
                } catch (Throwable ignored) {
                }
                node.totalStockAmount = totalStock;

                long available = Math.max(0, totalStock - consumedSoFar(tracker, target));
                long fromStock = Math.min(available, want);
                node.storedAmount = fromStock;
                addConsumed(tracker, target, fromStock);

                deficit = want - fromStock;
                if (deficit <= 0) {
                    // 全て在庫で充足
                    return node;
                }

                try {
                    node.autocraftable = stock.isAutocraftableAnywhere(target);
                } catch (Throwable ignored) {
                }
            }

            // 在庫で賄えなかった分だけを対象に、循環・上限チェックを行う
            if (path.contains(key)) {
                // 循環参照の打ち切り
                node.missingAmount = deficit;
                node.cutByCycle = true;
                return node;
            }
            if (depth > maxDepth() || nodeCount > maxNodes() || System.currentTimeMillis() > deadline) {
                node.missingAmount = deficit;
                node.cutByLimit = true;
                return node;
            }
            nodeCount++;
            if (progressListener != null) {
                progressListener.onProgress(nodeCount, false);
            }

            // 全加工カテゴリからのレシピ候補探索（キャッシュ経由）
            List<PlannedRecipe> candidates;
            try {
                candidates = getOrFindCandidateRecipes(target, level, activeWorkstation, stock);
            } catch (Throwable t) {
                CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] recipe lookup failed for {}", key, t);
                candidates = Collections.emptyList();
            }
            if (candidates.isEmpty()) {
                // レシピが見つからない場合は原材料として不足計上
                node.missingAmount = deficit;
                return node;
            }

            node.alternativeRecipes.clear();
            node.alternativeRecipes.addAll(candidates);
            node.selectedRecipeIndex = 0;

            PlannedRecipe chosen = candidates.get(0);
            node.recipe = chosen.getRecipeHolder();
            node.station = chosen.getStation();

            int resultCount = chosen.getOutputCount();
            long crafts = (deficit + resultCount - 1) / resultCount;
            node.toCraftAmount = crafts * resultCount;

            // 材料をまとめて再帰展開
            path.addLast(key);
            try {
                List<GroupedIngredient> grouped = groupIngredients(chosen.getIngredients(), stock);
                if (grouped.isEmpty()) {
                    node.missingAmount = deficit;
                    return node;
                }
                for (GroupedIngredient gi : grouped) {
                    long need = crafts * gi.count;
                    CraftingTreeNode child = resolveRecursive(gi.stack, need, stock, tracker, level, path, depth + 1, activeWorkstation);
                    node.children.add(child);
                }
            } finally {
                path.removeLast();
            }
            return node;
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.warn("[CraftTreePlanner] node resolve failed, treated as missing", t);
            node.missingAmount = Math.max(0, want - node.storedAmount);
            return node;
        }
    }

    /**
     * 複数レシピが存在するノードで、ユーザーが別のレシピ（別の作業台・製法）を選択した際にサブツリーを再展開する
     */
    public void switchRecipe(CraftingTreeNode node, int newIndex, UnifiedStockSnapshot stock, Level level, @Nullable ItemStack activeWorkstation) {
        if (node == null || node.alternativeRecipes.isEmpty()) return;
        int idx = (newIndex % node.alternativeRecipes.size() + node.alternativeRecipes.size()) % node.alternativeRecipes.size();
        node.selectedRecipeIndex = idx;
        PlannedRecipe chosen = node.alternativeRecipes.get(idx);
        node.recipe = chosen.getRecipeHolder();
        node.station = chosen.getStation();

        int resultCount = chosen.getOutputCount();
        long deficit = Math.max(1, node.requiredAmount - node.storedAmount);
        long crafts = (deficit + resultCount - 1) / resultCount;
        node.toCraftAmount = crafts * resultCount;

        // 子ノードを新しいレシピの材料で再展開
        node.children.clear();
        nodeCount = 0;
        consumed.clear();
        deadline = System.currentTimeMillis() + timeoutMs();
        VirtualStockTracker tracker = new VirtualStockTracker();
        Deque<ResourceLocation> path = new ArrayDeque<>();
        ResourceLocation key = VirtualStockTracker.keyOf(node.item);
        path.addLast(key);
        try {
            List<GroupedIngredient> grouped = groupIngredients(chosen.getIngredients(), stock);
            for (GroupedIngredient gi : grouped) {
                long need = crafts * gi.count;
                CraftingTreeNode child = resolveRecursive(gi.stack, need, stock, tracker, level, path, 1, activeWorkstation);
                node.children.add(child);
            }
        } finally {
            path.removeLast();
        }
    }

    private static class GroupedIngredient {
        final ItemStack stack;
        int count;

        GroupedIngredient(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }
    }

    private List<GroupedIngredient> groupIngredients(List<Ingredient> ingredients, UnifiedStockSnapshot stock) {
        List<GroupedIngredient> result = new ArrayList<>();
        if (ingredients == null) return result;
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) continue;
            ItemStack[] options;
            try {
                options = ing.getItems();
            } catch (Throwable t) {
                continue;
            }
            if (options == null || options.length == 0) continue;

            // 候補が複数ある場合（タグ指定等）、既に在庫（手持ち・RS・AE2等）にある候補を最優先で選択
            ItemStack chosen = options[0];
            if (options.length > 1 && stock != null) {
                for (ItemStack opt : options) {
                    if (opt != null && !opt.isEmpty()) {
                        try {
                            if (stock.getTotal(opt) > 0) {
                                chosen = opt;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (chosen == null || chosen.isEmpty()) continue;

            boolean found = false;
            for (GroupedIngredient gi : result) {
                if (ItemStack.isSameItem(gi.stack, chosen)) {
                    gi.count++;
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.add(new GroupedIngredient(chosen.copy(), 1));
            }
        }
        return result;
    }

    private long consumedSoFar(VirtualStockTracker tracker, ItemStack stack) {
        try {
            Long c = consumed.get(stack.getItem());
            return c == null ? 0 : c;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void addConsumed(VirtualStockTracker tracker, ItemStack stack, long amount) {
        try {
            consumed.merge(stack.getItem(), amount, Long::sum);
        } catch (Throwable ignored) {
        }
    }

    public static List<Ingredient> safeIngredients(RecipeHolder<?> recipe) {
        List<Ingredient> list = new ArrayList<>();
        if (recipe == null || recipe.value() == null) return list;
        try {
            List<Ingredient> base = recipe.value().getIngredients();
            if (base != null) {
                for (Ingredient ing : base) {
                    if (ing != null && !ing.isEmpty()) {
                        list.add(ing);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 空の場合（一部MODの鍛冶台レシピや特殊レシピ）はリフレクションで材料フィールドを探索
        if (list.isEmpty()) {
            try {
                for (Class<?> c = recipe.value().getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Ingredient.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object v = f.get(recipe.value());
                            if (v instanceof Ingredient ing && !ing.isEmpty()) {
                                list.add(ing);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    /**
     * レシピ候補の取得。セッション共有キャッシュにヒットすればJEI/バニラ問い合わせをスキップする。
     * キャッシュは未ソートの生候補を保持し、ソートは現在の設備・在庫で毎回行う。
     */
    private List<PlannedRecipe> getOrFindCandidateRecipes(
            ItemStack target,
            Level level,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        if (target == null || target.isEmpty()) return Collections.emptyList();
        net.minecraft.world.item.Item item = target.getItem();
        List<PlannedRecipe> raw = candidateCache.get(item);
        if (raw == null) {
            // 期限切れ後は新しい重い探索を開始しない（ゲームフリーズ防止の安全弁）
            if (System.currentTimeMillis() > deadline) return Collections.emptyList();
            raw = findAllCandidateRecipes(target, level, activeWorkstation, stock, deadline);
            candidateCache.put(item, raw);
        }
        List<PlannedRecipe> sorted = new ArrayList<>(raw);
        sortCandidates(sorted, target, activeWorkstation, stock);
        return sorted;
    }    /** レシピ再読込（データパック更新・サーバー同期）時に全キャッシュを破棄する */
    public static void invalidateCaches() {
        candidateCache.clear();
        vanillaRecipeIndex = null;
        lastRecipeManager = null;
    }

    private static volatile Map<net.minecraft.world.item.Item, List<RecipeHolder<?>>> vanillaRecipeIndex = null;
    private static volatile Object lastRecipeManager = null;

    private static void ensureVanillaIndex(Level level) {
        if (level == null) return;
        Object currentRm = level.getRecipeManager();
        if (vanillaRecipeIndex != null && lastRecipeManager == currentRm) {
            return;
        }
        synchronized (RecipeResolver.class) {
            if (vanillaRecipeIndex != null && lastRecipeManager == currentRm) {
                return;
            }
            Map<net.minecraft.world.item.Item, List<RecipeHolder<?>>> index = new HashMap<>();
            try {
                Collection<RecipeHolder<?>> all = level.getRecipeManager().getRecipes();
                for (RecipeHolder<?> h : all) {
                    if (h == null || h.value() == null) continue;
                    try {
                        ItemStack out = h.value().getResultItem(level.registryAccess());
                        if (out != null && !out.isEmpty()) {
                            index.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(h);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            vanillaRecipeIndex = index;
            lastRecipeManager = currentRm;
        }
    }

    private static List<RecipeHolder<?>> getVanillaRecipesFor(net.minecraft.world.item.Item item) {
        if (vanillaRecipeIndex == null || item == null) return Collections.emptyList();
        List<RecipeHolder<?>> list = vanillaRecipeIndex.get(item);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 出力アイテムを作成可能なすべてのレシピ候補（作業台、かまど、合金製錬機、冶金注入機等）を探索して返す
     * @param deadline この時刻を過ぎたら残りカテゴリ・レシピの探索を打ち切る（結果は部分的になり得る）
     */
    public static List<PlannedRecipe> findAllCandidateRecipes(
            ItemStack target,
            Level level,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock,
            long deadline
    ) {
        if (target == null || target.isEmpty()) return Collections.emptyList();
        List<PlannedRecipe> list = new ArrayList<>();
        Set<ResourceLocation> seenRecipeIds = new HashSet<>();

        // 1. JEI からの全加工カテゴリ探索
        try {
            IJeiRuntime runtime = JeiHover.JeiRuntimeHolder.getRuntime();
            if (runtime != null) {
                IRecipeManager rm = runtime.getRecipeManager();
                IJeiHelpers helpers = runtime.getJeiHelpers();
                IFocusFactory ff = helpers.getFocusFactory();

                ItemStack cleanTarget = new ItemStack(target.getItem(), 1);
                IFocus<ItemStack> focus = ff.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, cleanTarget);

                List<IRecipeCategory<?>> categories = rm.createRecipeCategoryLookup()
                        .limitFocus(List.of(focus))
                        .get()
                        .toList();

                for (IRecipeCategory<?> cat : categories) {
                    if (System.currentTimeMillis() > deadline) break; // カテゴリ間でも期限チェック（プラグイン遅延対策）
                    RecipeType<?> recipeType = cat.getRecipeType();
                    String catId = recipeType.getUid().toString();
                    String catPath = recipeType.getUid().getPath().toLowerCase(Locale.ROOT);
                    boolean isCraftingTable = catPath.contains("crafting");

                    // 触媒（作業台・加工機）の取得
                    List<ItemStack> catalysts = Collections.emptyList();
                    try {
                        catalysts = rm.createRecipeCatalystLookup(recipeType)
                                .getItemStack()
                                .filter(s -> s != null && !s.isEmpty())
                                .toList();
                    } catch (Throwable ignored) {
                    }

                    ItemStack stationIcon;
                    if (!catalysts.isEmpty()) {
                        stationIcon = catalysts.get(0);
                        if (activeWorkstation != null && !activeWorkstation.isEmpty()) {
                            for (ItemStack c : catalysts) {
                                if (ItemStack.isSameItem(c, activeWorkstation)) {
                                    stationIcon = c;
                                    break;
                                }
                            }
                        }
                    } else if (isCraftingTable) {
                        stationIcon = new ItemStack(Items.CRAFTING_TABLE);
                    } else if (catPath.contains("smelt") || catPath.contains("furnace")) {
                        stationIcon = new ItemStack(Items.FURNACE);
                    } else if (catPath.contains("blast")) {
                        stationIcon = new ItemStack(Items.BLAST_FURNACE);
                    } else if (catPath.contains("smok")) {
                        stationIcon = new ItemStack(Items.SMOKER);
                    } else if (catPath.contains("stone")) {
                        stationIcon = new ItemStack(Items.STONECUTTER);
                    } else if (catPath.contains("smith")) {
                        stationIcon = new ItemStack(Items.SMITHING_TABLE);
                    } else {
                        stationIcon = new ItemStack(Items.CRAFTING_TABLE);
                    }

                    ProcessingStation station = new ProcessingStation(stationIcon, cat.getTitle(), catId, isCraftingTable);

                    List<?> recipes = Collections.emptyList();
                    try {
                        recipes = rm.createRecipeLookup(recipeType)
                                .limitFocus(List.of(focus))
                                .get()
                                .toList();
                    } catch (Throwable ignored) {
                    }

                    for (Object r : recipes) {
                        if (System.currentTimeMillis() > deadline) break;
                        RecipeHolder<?> holder = (r instanceof RecipeHolder<?> h) ? h : null;
                        ResourceLocation recipeId = holder != null ? holder.id() : null;
                        if (recipeId != null && seenRecipeIds.contains(recipeId)) {
                            continue;
                        }

                        List<Ingredient> ingredients = new ArrayList<>();
                        ItemStack outStack = target.copy();
                        int outCount = 1;

                        if (holder != null && holder.value() != null) {
                            ingredients.addAll(safeIngredients(holder));
                            try {
                                if (level != null) {
                                    ItemStack res = holder.value().getResultItem(level.registryAccess());
                                    if (res != null && !res.isEmpty()) {
                                        outStack = res.copy();
                                        outCount = Math.max(1, res.getCount());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }

                        // もし safeIngredients で材料が取れなかった場合、または holder がない場合は JEI の IIngredientSupplier から取得
                        if (ingredients.isEmpty()) {
                            try {
                                IIngredientSupplier supp = rm.getRecipeIngredients((IRecipeCategory) cat, r);
                                if (supp != null) {
                                    List<ITypedIngredient<?>> inList = supp.getIngredients(RecipeIngredientRole.INPUT);
                                    for (ITypedIngredient<?> ti : inList) {
                                        ti.getItemStack().ifPresent(s -> {
                                            if (!s.isEmpty()) {
                                                // JEI表示上の個数（機械レシピの「1操作あたりの消費数」）を保持する
                                                int copies = Math.max(1, Math.min(64, s.getCount()));
                                                for (int ci = 0; ci < copies; ci++) {
                                                    ingredients.add(Ingredient.of(s));
                                                }
                                            }
                                        });
                                    }
                                    List<ITypedIngredient<?>> outList = supp.getIngredients(RecipeIngredientRole.OUTPUT);
                                    for (ITypedIngredient<?> to : outList) {
                                        if (to.getItemStack().isPresent() && !to.getItemStack().get().isEmpty()) {
                                            outStack = to.getItemStack().get().copy();
                                            outCount = Math.max(1, outStack.getCount());
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }

                        if (ingredients.isEmpty()) continue;

                        if (recipeId == null) {
                            recipeId = ResourceLocation.fromNamespaceAndPath(recipeType.getUid().getNamespace(),
                                    recipeType.getUid().getPath() + "/" + list.size());
                        }
                        seenRecipeIds.add(recipeId);

                        list.add(new PlannedRecipe(recipeId, holder, station, ingredients, outStack, outCount, cat.getTitle()));
                    }
                }
            }
        } catch (Throwable t) {
            CraftTreePlanner.LOGGER.debug("[CraftTreePlanner] JEI candidate lookup error", t);
        }

        // 2. Minecraft RecipeManager からの探索（インデックス経由でO(1)。
        // JEIが見つけていても常にマージする: JEIは非表示設定や未登録レシピを漏らすことがあるため）
        if (level != null) {
            try {
                ensureVanillaIndex(level);
                List<RecipeHolder<?>> matched = getVanillaRecipesFor(target.getItem());
                for (RecipeHolder<?> h : matched) {
                    if (h == null || h.value() == null) continue;
                    if (seenRecipeIds.contains(h.id())) continue;

                    ItemStack out;
                    try {
                        out = h.value().getResultItem(level.registryAccess());
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (out == null || out.isEmpty()) continue;
                    if (!ItemMatchHelper.isRecipeOutputMatch(out, target)) continue;

                    List<Ingredient> ingredients = safeIngredients(h);
                    if (ingredients.isEmpty()) continue;

                    ProcessingStation station = determineStationForVanilla(h);
                    seenRecipeIds.add(h.id());
                    list.add(new PlannedRecipe(h.id(), h, station, ingredients, out.copy(), Math.max(1, out.getCount()), station.getDisplayName()));
                }
            } catch (Throwable ignored) {
            }
        }

        // ソートは設備・在庫依存のためキャッシュ可能なこの段階では行わない（getOrFindCandidateRecipesで実施）
        dedupeCandidates(list);
        return list;
    }

    /**
     * 同一内容のレシピ候補を統合する（金床リペア等、カテゴリ内で材料・出力が同一のバリエーションが大量に出る問題の抑制）。
     * 同一判定: カテゴリ + 出力アイテム + 出力数 + 材料候補の集合
     */
    private static void dedupeCandidates(List<PlannedRecipe> list) {
        if (list.size() <= 1) return;
        Map<String, Integer> seen = new HashMap<>();
        List<PlannedRecipe> result = new ArrayList<>(list.size());
        for (PlannedRecipe r : list) {
            String sig = buildSignature(r);
            Integer prev = seen.get(sig);
            if (prev != null) {
                PlannedRecipe kept = result.get(prev);
                if (r.getRecipeHolder() != null && kept.getRecipeHolder() == null) {
                    // 実RecipeHolderを持つ方を優先（サーバー側実行で byKey が必要なため）
                    result.set(prev, r);
                }
                continue;
            }
            seen.put(sig, result.size());
            result.add(r);
        }
        list.clear();
        list.addAll(result);
    }

    private static String buildSignature(PlannedRecipe r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getStation().getCategoryUid()).append('|');
        try {
            sb.append(BuiltInRegistries.ITEM.getKey(r.getOutput().getItem())).append('|');
        } catch (Throwable t) {
            sb.append(r.getOutput().getItem()).append('|');
        }
        sb.append(r.getOutputCount()).append('|');
        for (Ingredient ing : r.getIngredients()) {
            try {
                ItemStack[] options = ing.getItems();
                List<String> ids = new ArrayList<>(options.length);
                for (ItemStack opt : options) {
                    if (opt != null && !opt.isEmpty()) {
                        ids.add(BuiltInRegistries.ITEM.getKey(opt.getItem()).toString());
                    }
                }
                Collections.sort(ids);
                sb.append(ids).append(';');
            } catch (Throwable t) {
                sb.append("?;");
            }
        }
        return sb.toString();
    }

    private static ProcessingStation determineStationForVanilla(RecipeHolder<?> h) {
        if (h.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe) {
            return ProcessingStation.CRAFTING_TABLE;
        } else if (h.value() instanceof net.minecraft.world.item.crafting.SmeltingRecipe) {
            return ProcessingStation.FURNACE;
        } else if (h.value() instanceof net.minecraft.world.item.crafting.BlastingRecipe) {
            return new ProcessingStation(new ItemStack(Items.BLAST_FURNACE), Component.translatable("block.minecraft.blast_furnace"), "minecraft:blasting", false);
        } else if (h.value() instanceof net.minecraft.world.item.crafting.SmokingRecipe) {
            return new ProcessingStation(new ItemStack(Items.SMOKER), Component.translatable("block.minecraft.smoker"), "minecraft:smoking", false);
        } else if (h.value() instanceof net.minecraft.world.item.crafting.StonecutterRecipe) {
            return new ProcessingStation(new ItemStack(Items.STONECUTTER), Component.translatable("block.minecraft.stonecutter"), "minecraft:stonecutting", false);
        } else if (h.value() instanceof net.minecraft.world.item.crafting.SmithingRecipe) {
            return new ProcessingStation(new ItemStack(Items.SMITHING_TABLE), Component.translatable("block.minecraft.smithing_table"), "minecraft:smithing", false);
        }
        return ProcessingStation.CRAFTING_TABLE;
    }

    private static void sortCandidates(
            List<PlannedRecipe> list,
            ItemStack target,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        list.sort((a, b) -> {
            int scoreA = calculateCandidateScore(a, target, activeWorkstation, stock);
            int scoreB = calculateCandidateScore(b, target, activeWorkstation, stock);
            if (scoreA != scoreB) {
                return Integer.compare(scoreB, scoreA); // 降順
            }
            return Integer.compare(a.getIngredients().size(), b.getIngredients().size());
        });
    }

    private static int calculateCandidateScore(
            PlannedRecipe recipe,
            ItemStack target,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        int score = 0;
        // 0. サーバー側で実行可能なレシピ（RecipeHolder持ち）を優先。
        // JEIの取引・クエスト・ドロップ等のラッパーレシピはサーバーに実体がなく直接作成不可のため、大きく減点する
        if (recipe.getRecipeHolder() != null) {
            score += 80;
        } else {
            score -= 600;
        }
        // 1a. プレイヤーが直接実行できない情報カテゴリ（村人の取引・クエスト・ドロップ等）は大きく減点
        if (demoteInfoCategories()) {
            String uid = recipe.getStation().getCategoryUid() == null ? "" : recipe.getStation().getCategoryUid().toLowerCase(Locale.ROOT);
            if (uid.contains("trade") || uid.contains("villag") || uid.contains("wander") || uid.contains("merchant")
                    || uid.contains("quest") || uid.contains("loot") || uid.contains("drop")
                    || uid.contains("gift") || uid.contains("barter") || uid.contains("reward")) {
                score -= 600;
            }
        }
        // 1. アクティブスロットにセットされた設備と一致する場合: +1000
        if (activeWorkstation != null && !activeWorkstation.isEmpty() && recipe.getStation().matches(activeWorkstation)) {
            score += 1000;
        }
        // 2. プレイヤー手持ち/RSにその設備が存在する場合: +500
        if (stock != null) {
            try {
                if (stock.getTotal(recipe.getStation().getIcon()) > 0) {
                    score += 500;
                }
            } catch (Throwable ignored) {
            }
        }
        // 3. 作業台レシピ: +200（基本クラフトを優先）
        if (recipe.getStation().isCraftingTable()) {
            score += 200;
        }
        // 4. 材料が在庫にあるか: +50 per ingredient
        if (stock != null) {
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] items = ing.getItems();
                if (items != null && items.length > 0) {
                    try {
                        if (stock.getTotal(items[0]) > 0) {
                            score += 50;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 5. 逆変換・解体レシピ（例: 鉄ブロック -> 鉄インゴットx9）のペナルティ
        // 手持ちやRSに在庫がない場合、持っていない圧縮ブロックをわざわざクラフトして解体するのは循環の原因になるため大幅減点
        if (recipe.getOutputCount() > 1 && recipe.getIngredients().size() == 1) {
            Ingredient singleIng = recipe.getIngredients().get(0);
            ItemStack[] items = singleIng.getItems();
            if (items != null && items.length > 0) {
                long inStock = (stock != null) ? stock.getTotal(items[0]) : 0;
                if (inStock <= 0) {
                    score -= 5000;
                } else {
                    score += 300;
                }
            }
        }

        // 6. 自分自身を材料に要求するレシピ（修理、充電、リサイクル等）のペナルティ
        // 手持ちに在庫がないのに自分自身を要求するレシピは循環の直接原因
        if (target != null && !target.isEmpty()) {
            boolean requiresSelf = false;
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] items = ing.getItems();
                if (items != null) {
                    for (ItemStack opt : items) {
                        if (ItemStack.isSameItem(opt, target)) {
                            requiresSelf = true;
                            break;
                        }
                    }
                }
                if (requiresSelf) break;
            }
            if (requiresSelf) {
                long inStock = (stock != null) ? stock.getTotal(target) : 0;
                if (inStock <= 0) {
                    score -= 10000;
                }
            }
        }

        return score;
    }

    /** 互換用：単一レシピ探索 */
    public static RecipeHolder<?> findRecipe(ItemStack target, Level level) {
        List<PlannedRecipe> candidates = findAllCandidateRecipes(target, level, null, null, System.currentTimeMillis() + timeoutMs());
        sortCandidates(candidates, target, null, null);
        return candidates.isEmpty() ? null : candidates.get(0).getRecipeHolder();
    }

    /** デバッグ用：コンソールにツリー出力 */
    public static void logTree(CraftingTreeNode node, int indent) {
        try {
            String pad = "  ".repeat(Math.max(0, indent));
            String name = "?";
            try {
                name = node.item.getHoverName().getString();
            } catch (Throwable ignored) {
            }
            CraftTreePlanner.LOGGER.info("{}- {} x{} [stored={} (stock={}) craft={} missing={} station={}]{}",
                    pad, name, node.requiredAmount, node.storedAmount, node.totalStockAmount,
                    node.toCraftAmount, node.missingAmount, node.station.getDisplayName().getString(),
                    node.autocraftable ? " (auto)" : "");
            for (CraftingTreeNode c : node.children) logTree(c, indent + 1);
        } catch (Throwable ignored) {
        }
    }
}
