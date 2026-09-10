package com.nexy451z.nexcrafttree.core.calculation;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.nexy451z.nexcrafttree.core.stock.UnifiedStockSnapshot;
import com.nexy451z.nexcrafttree.integration.jei.JeiHover;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
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
        return limitMaxDepth > 0 ? limitMaxDepth : com.nexy451z.nexcrafttree.Config.CONFIG_MAX_DEPTH.get();
    }

    private static int maxNodes() {
        return limitMaxNodes > 0 ? limitMaxNodes : com.nexy451z.nexcrafttree.Config.CONFIG_MAX_NODES.get();
    }

    private static long timeoutMs() {
        return limitTimeoutMs > 0 ? limitTimeoutMs : com.nexy451z.nexcrafttree.Config.CONFIG_TIMEOUT_MS.get();
    }

    private static boolean demoteInfoCategories() {
        return limitDemoteInfoCategories && com.nexy451z.nexcrafttree.Config.CONFIG_DEMOTE_INFO_CATEGORIES.get();
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
    /** ノードパス→選択中レシピID。再探索時にユーザーの加工法選択を引き継ぐ */
    private Map<String, Identifier> recipePreferences = Collections.emptyMap();
    /** セッション共有のレシピ候補キャッシュ（未ソート・レシピ再読込時にinvalidateCachesで破棄） */
    private static final Map<net.minecraft.world.item.Item, CacheEntry> candidateCache = new ConcurrentHashMap<>();

    /** ノードパスキーの1セグメント（例: minecraft:iron_ingot@2）。ツリー内の位置を安定して指す */
    public static String pathSegment(ItemStack stack, int siblingIndex) {
        return VirtualStockTracker.keyOf(stack) + "@" + siblingIndex;
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level) {
        return resolve(target, wantCount, stock, level, null);
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level, @Nullable ItemStack activeWorkstation) {
        return resolve(target, wantCount, stock, level, activeWorkstation, null);
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level,
                                    @Nullable ItemStack activeWorkstation, @Nullable ProgressListener progress) {
        return resolve(target, wantCount, stock, level, activeWorkstation, progress, Collections.emptyMap());
    }

    public CraftingTreeNode resolve(ItemStack target, long wantCount, UnifiedStockSnapshot stock, Level level,
                                    @Nullable ItemStack activeWorkstation, @Nullable ProgressListener progress,
                                    Map<String, Identifier> preferences) {
        progressListener = progress;
        recipePreferences = (preferences == null) ? Collections.emptyMap() : preferences;
        nodeCount = 0;
        consumed.clear();
        deadline = System.currentTimeMillis() + timeoutMs(); // 上限時間で安全に打ち切り
        VirtualStockTracker tracker = new VirtualStockTracker();
        Deque<Identifier> path = new ArrayDeque<>();
        String rootPathKey = pathSegment(target, 0);
        CraftingTreeNode result = resolveRecursive(target, Math.max(1, wantCount), stock, tracker, level, path, 0, activeWorkstation, rootPathKey);
        if (progressListener != null) {
            progressListener.onProgress(nodeCount, true);
        }
        return result;
    }

    private CraftingTreeNode resolveRecursive(ItemStack target, long want, UnifiedStockSnapshot stock,
                                              VirtualStockTracker tracker, Level level,
                                              Deque<Identifier> path, int depth,
                                              @Nullable ItemStack activeWorkstation, String nodePathKey) {
        CraftingTreeNode node = new CraftingTreeNode(target, want);
        try {
            if (target == null || target.isEmpty() || want <= 0) {
                return node;
            }

            Identifier key = VirtualStockTracker.keyOf(target);

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
                NexCraftTree.LOGGER.warn("[NexCraftTree] recipe lookup failed for {}", key, t);
                candidates = Collections.emptyList();
            }
            if (candidates.isEmpty()) {
                // レシピが見つからない場合は原材料として不足計上
                node.missingAmount = deficit;
                return node;
            }

            node.alternativeRecipes.clear();
            node.alternativeRecipes.addAll(candidates);

            // ユーザーが以前このパスで選んでいた加工法があれば引き継ぐ
            PlannedRecipe chosen = candidates.get(0);
            int chosenIdx = 0;
            Identifier preferred = recipePreferences.get(nodePathKey);
            if (preferred != null) {
                for (int ci = 0; ci < candidates.size(); ci++) {
                    if (preferred.equals(candidates.get(ci).getId())) {
                        chosen = candidates.get(ci);
                        chosenIdx = ci;
                        break;
                    }
                }
            }
            node.selectedRecipeIndex = chosenIdx;
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
                for (int gi = 0; gi < grouped.size(); gi++) {
                    GroupedIngredient ingredient = grouped.get(gi);
                    long need = crafts * ingredient.count;
                    String childPathKey = nodePathKey + "/" + pathSegment(ingredient.stack, gi);
                    CraftingTreeNode child = resolveRecursive(ingredient.stack, need, stock, tracker, level, path, depth + 1, activeWorkstation, childPathKey);
                    node.children.add(child);
                }
            } finally {
                path.removeLast();
            }
            return node;
        } catch (Throwable t) {
            NexCraftTree.LOGGER.warn("[NexCraftTree] node resolve failed, treated as missing", t);
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
        // switchRecipeはメインスレッド同期実行のため、上限を短めに丸める（UIフリーズ緩和）
        deadline = System.currentTimeMillis() + Math.min(timeoutMs(), 800);
        VirtualStockTracker tracker = new VirtualStockTracker();
        Deque<Identifier> path = new ArrayDeque<>();
        Identifier key = VirtualStockTracker.keyOf(node.item);
        path.addLast(key);
        String switchPathKey = pathSegment(node.item, 0);
        try {
            List<GroupedIngredient> grouped = groupIngredients(chosen.getIngredients(), stock);
            for (int gi = 0; gi < grouped.size(); gi++) {
                GroupedIngredient ingredient = grouped.get(gi);
                long need = crafts * ingredient.count;
                String childPathKey = switchPathKey + "/" + pathSegment(ingredient.stack, gi);
                CraftingTreeNode child = resolveRecursive(ingredient.stack, need, stock, tracker, level, path, 1, activeWorkstation, childPathKey);
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
            java.util.List<ItemStack> options;
            try {
                options = ItemMatchHelper.ingredientStacks(ing);
            } catch (Throwable t) {
                continue;
            }
            if (options == null || options.isEmpty()) continue;

            // 候補が複数ある場合（タグ指定等）、既に在庫（手持ち・RS・AE2等）にある候補を最優先で選択
            ItemStack chosen = options.get(0);
            if (options.size() > 1 && stock != null) {
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
            // 26.1では Recipe#getIngredients が廃止され、placementInfo() が全レシピ共通の入力源
            List<Ingredient> base = recipe.value().placementInfo().ingredients();
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
        // 独自入力API（Mekanism ItemStackIngredient等、バニラIngredientでない場合）:
        // getInput系0引数メソッドの戻り値から ItemStack 候補を抽出してバニラIngredient化する
        if (list.isEmpty()) {
            list.addAll(discoverIngredientsViaInputApi(recipe.value()));
        }
        return list;
    }

    /** 独自入力API探索のメソッドキャッシュ（実行毎のリフレクションコスト回避） */
    private static final Map<Class<?>, java.util.List<java.lang.reflect.Method>> INPUT_METHOD_CACHE = new ConcurrentHashMap<>();

    private static List<Ingredient> discoverIngredientsViaInputApi(Object recipe) {
        List<Ingredient> result = new ArrayList<>();
        try {
            java.util.List<java.lang.reflect.Method> candidates = INPUT_METHOD_CACHE.computeIfAbsent(recipe.getClass(), c -> {
                java.util.List<java.lang.reflect.Method> found = new ArrayList<>();
                for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                    for (java.lang.reflect.Method m : cur.getDeclaredMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        String name = m.getName().toLowerCase(Locale.ROOT);
                        Class<?> ret = m.getReturnType();
                        if (ret == void.class || ret == Ingredient.class || Ingredient.class.isAssignableFrom(ret)) continue;
                        if (name.contains("input") || name.contains("ingredient")) {
                            m.setAccessible(true);
                            found.add(m);
                        }
                    }
                }
                return found;
            });
            for (java.lang.reflect.Method m : candidates) {
                try {
                    Object input = m.invoke(recipe);
                    if (input == null) continue;
                    List<ItemStack> stacks = extractStackList(input);
                    if (!stacks.isEmpty()) {
                        // 26.1では Ingredient.of(ItemStack) が廃止されたため、ベースアイテムから生成する
                        // （MOD独自入力APIのフォールバック用途のため、コンポーネント差異は許容する）
                        result.add(Ingredient.of(stacks.stream().map(ItemStack::getItem)));
                    }
                } catch (Throwable ignored) {
                }
                if (!result.isEmpty()) break;
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** オブジェクトからItemStackのリストを抽出（自身がCollection、またはgetRepresentations/getRepresentationの戻り値） */
    private static List<ItemStack> extractStackList(Object obj) {
        try {
            if (obj instanceof Collection<?> coll) {
                List<ItemStack> stacks = new ArrayList<>();
                for (Object e : coll) {
                    if (e instanceof ItemStack s && !s.isEmpty()) stacks.add(s);
                }
                return stacks;
            }
            for (String name : new String[]{"getRepresentations", "getRepresentation", "getStacks"}) {
                try {
                    java.lang.reflect.Method m = obj.getClass().getMethod(name);
                    Object v = m.invoke(obj);
                    if (v instanceof Collection<?> coll) {
                        List<ItemStack> stacks = new ArrayList<>();
                        for (Object e : coll) {
                            if (e instanceof ItemStack s && !s.isEmpty()) stacks.add(s);
                        }
                        return stacks;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    /** キャッシュ世代。invalidateCachesで加算され、世代不一致のエントリは無効扱い */
    private static final java.util.concurrent.atomic.AtomicLong CACHE_GENERATION = new java.util.concurrent.atomic.AtomicLong(0);
    private record CacheEntry(List<PlannedRecipe> list, long generation) {
    }
    /** 探索結果と完了フラグ（期限打ち切りの部分的結果はキャッシュしないため） */
    public record CandidateFind(List<PlannedRecipe> recipes, boolean complete) {
    }

    /**
     * レシピ候補の取得。セッション共有キャッシュにヒットすればJEI/バニラ問い合わせをスキップする。
     * キャッシュは未ソートの生候補を保持し、ソートは現在の設備・在庫で毎回行う。
     * キャッシュミスの重い探索には猶予deadline（全タイムアウト分）を与え、部分的結果はキャッシュしない。
     */
    private List<PlannedRecipe> getOrFindCandidateRecipes(
            ItemStack target,
            Level level,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        if (target == null || target.isEmpty()) return Collections.emptyList();
        net.minecraft.world.item.Item item = target.getItem();
        long gen = CACHE_GENERATION.get();
        CacheEntry cached = candidateCache.get(item);
        if (cached != null && cached.generation() == gen) {
            List<PlannedRecipe> sorted = new ArrayList<>(cached.list());
            sortCandidates(sorted, target, activeWorkstation, stock);
            return sorted;
        }
        // ツリー全体の残り予算が尽きていても、初回の候補探索には猶予を与える（部分的キャッシュの抑制）
        long now = System.currentTimeMillis();
        long findDeadline = Math.max(deadline, now + timeoutMs());
        if (now > deadline && now >= findDeadline) return Collections.emptyList();
        CandidateFind find = findAllCandidateRecipes(target, level, activeWorkstation, stock, findDeadline);
        if (find != null && find.complete()) {
            candidateCache.put(item, new CacheEntry(find.recipes(), gen));
        }
        List<PlannedRecipe> sorted = new ArrayList<>(find == null ? Collections.<PlannedRecipe>emptyList() : find.recipes());
        sortCandidates(sorted, target, activeWorkstation, stock);
        return sorted;
    }

    /** レシピ再読込（データパック更新・サーバー同期）時に全キャッシュを破棄する */
    public static void invalidateCaches() {
        CACHE_GENERATION.incrementAndGet();
        candidateCache.clear();
        synchronized (RecipeResolver.class) {
            vanillaRecipeIndex = null;
            lastRecipeManager = null;
            lastVanillaIndexGeneration = -1;
        }
    }

    private static volatile Map<net.minecraft.world.item.Item, List<RecipeHolder<?>>> vanillaRecipeIndex = null;
    private static volatile Object lastRecipeManager = null;
    private static long lastVanillaIndexGeneration = -1;
    /** 26.1のクライアントはLevelからレシピ一覧を取得できないため、RecipesReceivedEventで受領したマップを保持する */
    private static volatile RecipeMap clientRecipeMap = null;

    /** RecipesReceivedEvent（クライアントへのレシピ同期）受信時に呼ぶ */
    public static void onRecipesReceived(RecipeMap recipeMap) {
        clientRecipeMap = recipeMap;
        invalidateCaches();
    }

    private static Collection<RecipeHolder<?>> currentRecipeSource(Level level) {
        try {
            if (level instanceof ServerLevel serverLevel) {
                return serverLevel.recipeAccess().getRecipes();
            }
        } catch (Throwable ignored) {
        }
        RecipeMap map = clientRecipeMap;
        if (map != null) {
            return map.values();
        }
        return null;
    }

    private static void ensureVanillaIndex(Level level) {
        if (level == null) return;
        long gen = CACHE_GENERATION.get();
        Collection<RecipeHolder<?>> source;
        try {
            source = currentRecipeSource(level);
        } catch (Throwable t) {
            return;
        }
        if (source == null) return;
        Object currentRm = source;
        if (vanillaRecipeIndex != null && lastRecipeManager == currentRm && lastVanillaIndexGeneration == gen) {
            return;
        }
        synchronized (RecipeResolver.class) {
            if (vanillaRecipeIndex != null && lastRecipeManager == currentRm && lastVanillaIndexGeneration == gen) {
                return;
            }
            Map<net.minecraft.world.item.Item, List<RecipeHolder<?>>> index = new HashMap<>();
            boolean complete = true;
            try {
                for (RecipeHolder<?> h : source) {
                    if (h == null || h.value() == null) continue;
                    try {
                        ItemStack out = resolveRecipeOutput(h.value(), level);
                        if (out != null && !out.isEmpty()) {
                            index.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(h);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                // 再読込と競合した場合は部分的なインデックスを公開しない（次回リトライ）
                complete = false;
            }
            if (!complete) return;
            vanillaRecipeIndex = index;
            lastRecipeManager = currentRm;
            lastVanillaIndexGeneration = gen;
        }
    }

    /** レシピの display()（SlotDisplay）から代表出力スタックを解決する（26.1でgetResultItem廃止のため） */
    public static ItemStack resolveRecipeOutput(net.minecraft.world.item.crafting.Recipe<?> recipe, Level level) {
        if (recipe == null) return ItemStack.EMPTY;
        try {
            for (RecipeDisplay display : recipe.display()) {
                if (display == null) continue;
                ItemStack stack = display.result().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
                if (stack != null && !stack.isEmpty()) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static List<RecipeHolder<?>> getVanillaRecipesFor(net.minecraft.world.item.Item item) {
        if (vanillaRecipeIndex == null || item == null) return Collections.emptyList();
        List<RecipeHolder<?>> list = vanillaRecipeIndex.get(item);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 出力アイテムを作成可能なすべてのレシピ候補（作業台、かまど、合金製錬機、冶金注入機等）を探索して返す
     * @param deadline この時刻を過ぎたら残りカテゴリ・レシピの探索を打ち切る（部分的結果はcomplete=false）
     */
    public static CandidateFind findAllCandidateRecipes(
            ItemStack target,
            Level level,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock,
            long deadline
    ) {
        if (target == null || target.isEmpty()) return new CandidateFind(Collections.emptyList(), true);
        List<PlannedRecipe> list = new ArrayList<>();
        Set<Identifier> seenRecipeIds = new HashSet<>();
        // JEIがRecipeHolderを返さない独自カテゴリ（例: reliquary:alkahestry_crafting）のレシピ実体。
        // バニラ側インデックスに同じ実体が載っていても二重計上・設備誤判定しないよう抑制する
        Set<Integer> seenJeiRecipeObjects = new HashSet<>();
        boolean[] complete = {true};

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
                    if (System.currentTimeMillis() > deadline) {
                        complete[0] = false; // カテゴリ間でも期限チェック（プラグイン遅延対策）
                        break;
                    }
                    IRecipeType<?> recipeType = cat.getRecipeType();
                    Identifier catUid = recipeType.getUid();
                    String catId = catUid.toString();
                    String catPath = catUid.getPath().toLowerCase(Locale.ROOT);
                    // 「crafting」を含むだけのカスタムカテゴリ(例: reliquary:alkahestry_crafting)を
                    // 作業台と誤認しないよう、バニラ作業台カテゴリ(minecraft:crafting)のみ作業台扱いする
                    boolean isCraftingTable = "minecraft".equals(catUid.getNamespace()) && "crafting".equals(catPath);

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
                    if (isCraftingTable) {
                        stationIcon = new ItemStack(Items.CRAFTING_TABLE);
                    } else if (!catalysts.isEmpty()) {
                        stationIcon = catalysts.get(0);
                        if (activeWorkstation != null && !activeWorkstation.isEmpty()) {
                            for (ItemStack c : catalysts) {
                                if (ItemStack.isSameItem(c, activeWorkstation)) {
                                    stationIcon = c;
                                    break;
                                }
                            }
                        }
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

                    // バニラ作業台は標準ステーションに統一（表示名「作業台」・uid minecraft:crafting）
                    ProcessingStation station = isCraftingTable
                            ? ProcessingStation.CRAFTING_TABLE
                            : new ProcessingStation(stationIcon, cat.getTitle(), catId, false);

                    List<?> recipes = Collections.emptyList();
                    try {
                        recipes = rm.createRecipeLookup(recipeType)
                                .limitFocus(List.of(focus))
                                .get()
                                .toList();
                    } catch (Throwable ignored) {
                    }

                    for (Object r : recipes) {
                        if (System.currentTimeMillis() > deadline) {
                            complete[0] = false;
                            break;
                        }
                        RecipeHolder<?> holder = (r instanceof RecipeHolder<?> h) ? h : null;
                        Identifier recipeId = holder != null ? holder.id().identifier() : null;
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
                                    ItemStack res = resolveRecipeOutput(holder.value(), level);
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
                                                    ingredients.add(Ingredient.of(s.getItem()));
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
                            recipeId = Identifier.fromNamespaceAndPath(recipeType.getUid().getNamespace(),
                                    recipeType.getUid().getPath() + "/" + list.size());
                        }
                        seenRecipeIds.add(recipeId);
                        if (holder == null) {
                            seenJeiRecipeObjects.add(System.identityHashCode(r));
                        }

                        // レシピ実体が crafting 型なら設備は作業台（独自JEIカテゴリでも作業台レシピ検索に載るため）
                        ProcessingStation recipeStation = station;
                        try {
                            if (r instanceof net.minecraft.world.item.crafting.Recipe<?> rr
                                    && net.minecraft.world.item.crafting.RecipeType.CRAFTING.equals(rr.getType())) {
                                recipeStation = ProcessingStation.CRAFTING_TABLE;
                            }
                        } catch (Throwable ignored) {
                        }

                        list.add(new PlannedRecipe(recipeId, holder, recipeStation, ingredients, outStack, outCount, cat.getTitle()));
                    }
                }
            }
        } catch (Throwable t) {
            NexCraftTree.LOGGER.debug("[NexCraftTree] JEI candidate lookup error", t);
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
                    // JEI独自カテゴリで既に実体を拾っているレシピはスキップ（設備の誤判定・重複を防ぐ）
                    if (seenJeiRecipeObjects.contains(System.identityHashCode(h.value()))) continue;

                    ItemStack out;
                    try {
                        out = resolveRecipeOutput(h.value(), level);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (out == null || out.isEmpty()) continue;
                    if (!ItemMatchHelper.isRecipeOutputMatch(out, target)) continue;

                    List<Ingredient> ingredients = safeIngredients(h);
                    if (ingredients.isEmpty()) continue;

                    ProcessingStation station = determineStationForVanilla(h);
                    seenRecipeIds.add(h.id().identifier());
                    list.add(new PlannedRecipe(h.id().identifier(), h, station, ingredients, out.copy(), Math.max(1, out.getCount()), station.getDisplayName()));
                }
            } catch (Throwable ignored) {
            }
        }

        // ソートは設備・在庫依存のためキャッシュ可能なこの段階では行わない（getOrFindCandidateRecipesで実施）
        dedupeCandidates(list);
        return new CandidateFind(list, complete[0]);
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
                List<ItemStack> options = ItemMatchHelper.ingredientStacks(ing);
                List<String> ids = new ArrayList<>(options.size());
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
        net.minecraft.world.item.crafting.Recipe<?> r = h.value();
        // レシピ「型」で判定する。crafting型なら（独自シリアライザでも）作業台のレシピ検索に載る＝
        // 作業台でクラフト可能なため、作業台として扱う（例: Reliquaryの錬金術レシピ）
        if (net.minecraft.world.item.crafting.RecipeType.CRAFTING.equals(r.getType())) {
            return ProcessingStation.CRAFTING_TABLE;
        }
        if (net.minecraft.world.item.crafting.RecipeType.SMELTING.equals(r.getType())) {
            return ProcessingStation.FURNACE;
        } else if (net.minecraft.world.item.crafting.RecipeType.BLASTING.equals(r.getType())) {
            return new ProcessingStation(new ItemStack(Items.BLAST_FURNACE), Component.translatable("block.minecraft.blast_furnace"), "minecraft:blasting", false);
        } else if (net.minecraft.world.item.crafting.RecipeType.SMOKING.equals(r.getType())) {
            return new ProcessingStation(new ItemStack(Items.SMOKER), Component.translatable("block.minecraft.smoker"), "minecraft:smoking", false);
        } else if (net.minecraft.world.item.crafting.RecipeType.STONECUTTING.equals(r.getType())) {
            return new ProcessingStation(new ItemStack(Items.STONECUTTER), Component.translatable("block.minecraft.stonecutter"), "minecraft:stonecutting", false);
        } else if (net.minecraft.world.item.crafting.RecipeType.SMITHING.equals(r.getType())) {
            return new ProcessingStation(new ItemStack(Items.SMITHING_TABLE), Component.translatable("block.minecraft.smithing_table"), "minecraft:smithing", false);
        }
        // MOD独自レシピ: 作業台扱いにはしない（レシピ型IDを表示して区別できるようにする）
        return unknownStation(r);
    }

    /** 設備不明（MOD独自型・カスタムシリアライザ）の汎用ステーション。表示名はレシピ型ID */
    private static ProcessingStation unknownStation(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        String typeId = "unknown";
        try {
            Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (id != null) {
                typeId = id.toString();
            }
        } catch (Throwable ignored) {
        }
        return new ProcessingStation(
                new ItemStack(Items.CRAFTING_TABLE),
                Component.literal(typeId),
                "nexcrafttree:unknown:" + typeId,
                false);
    }

    private static void sortCandidates(
            List<PlannedRecipe> list,
            ItemStack target,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        list.sort((a, b) -> {
            // 第1キー: 作業台レシピが存在するなら常に最優先（設備スロット固定や在庫ボーナスより強い）
            int groupA = candidateGroupRank(a);
            int groupB = candidateGroupRank(b);
            if (groupA != groupB) {
                return Integer.compare(groupA, groupB);
            }
            int scoreA = calculateCandidateScore(a, target, activeWorkstation, stock);
            int scoreB = calculateCandidateScore(b, target, activeWorkstation, stock);
            if (scoreA != scoreB) {
                return Integer.compare(scoreB, scoreA); // 降順
            }
            return Integer.compare(a.getIngredients().size(), b.getIngredients().size());
        });
    }

    /**
     * ソート第1キー。
     *  0: 作業台クラフト（クラフトレシピがある場合は常に先頭）
     *  1: その他の加工機・MODレシピ
     *  2: 情報専用カテゴリ（取引・ドロップ等）＝最底辺
     */
    public static int candidateGroupRank(PlannedRecipe recipe) {
        try {
            if (recipe == null || recipe.getStation() == null) return 1;
            String uid = recipe.getStation().getCategoryUid() == null
                    ? "" : recipe.getStation().getCategoryUid().toLowerCase(Locale.ROOT);
            if (demoteInfoCategories() && isInfoCategoryUid(uid)) return 2;
            if (recipe.getStation().isCraftingTable()) return 0;
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /**
     * 候補の優先度スコア。
     * 階層（tier × 100,000）で大枠を決め、その中で小さいボーナス/ペナルティを加える。
     *  tier 4: 作業台クラフト（基本の最優先）
     *  tier 3: 実行可能な加工機レシピ（RecipeHolder持ち）
     *  tier 1: 実行不能なラッパーレシピ（サーバーに実体なし）
     *  tier 0: 情報カテゴリ（村人の取引・クエスト・ドロップ等）＝最底辺
     * 設備スロットを固定した場合のみ +150,000（1段階超え）でその設備を最優先にできる。
     */
    private static int calculateCandidateScore(
            PlannedRecipe recipe,
            ItemStack target,
            @Nullable ItemStack activeWorkstation,
            @Nullable UnifiedStockSnapshot stock
    ) {
        String uid = recipe.getStation().getCategoryUid() == null
                ? "" : recipe.getStation().getCategoryUid().toLowerCase(Locale.ROOT);

        int tier;
        if (demoteInfoCategories() && isInfoCategoryUid(uid)) {
            // 村人の取引(JER等)・クエスト・ドロップ・ギフト等は実行不能かつ情報目的なので最底辺
            tier = 0;
        } else if (recipe.getStation().isCraftingTable()) {
            // 作業台クラフトを基本の最優先にする
            tier = 4;
        } else if (recipe.getRecipeHolder() != null) {
            tier = 3;
        } else {
            tier = 1;
        }
        int score = tier * 100_000;

        // アクティブスロットにセットされた設備と一致する場合: 1段階を超えて最優先（ユーザーの明示指定）
        if (activeWorkstation != null && !activeWorkstation.isEmpty() && recipe.getStation().matches(activeWorkstation)) {
            score += 150_000;
        }
        // プレイヤー手持ち/RSにその設備が存在する場合: +500
        if (stock != null) {
            try {
                if (stock.getTotal(recipe.getStation().getIcon()) > 0) {
                    score += 500;
                }
            } catch (Throwable ignored) {
            }
        }
        // 材料が在庫にあるか: +50 per ingredient
        if (stock != null) {
            for (Ingredient ing : recipe.getIngredients()) {
                List<ItemStack> items = ItemMatchHelper.ingredientStacks(ing);
                if (items != null && !items.isEmpty()) {
                    try {
                        if (stock.getTotal(items.get(0)) > 0) {
                            score += 50;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 逆変換・解体レシピ（例: 鉄ブロック -> 鉄インゴットx9）のペナルティ
        // 手持ちやRSに在庫がない場合、持っていない圧縮ブロックをわざわざクラフトして解体するのは循環の原因になるため減点
        if (recipe.getOutputCount() > 1 && recipe.getIngredients().size() == 1) {
            Ingredient singleIng = recipe.getIngredients().get(0);
            List<ItemStack> items = ItemMatchHelper.ingredientStacks(singleIng);
            if (items != null && !items.isEmpty()) {
                long inStock = (stock != null) ? stock.getTotal(items.get(0)) : 0;
                if (inStock <= 0) {
                    score -= 5000;
                } else {
                    score += 300;
                }
            }
        }

        // 自分自身を材料に要求するレシピ（修理、充電、リサイクル等）のペナルティ
        if (target != null && !target.isEmpty()) {
            boolean requiresSelf = false;
            for (Ingredient ing : recipe.getIngredients()) {
                List<ItemStack> items = ItemMatchHelper.ingredientStacks(ing);
                for (ItemStack opt : items) {
                    if (ItemStack.isSameItem(opt, target)) {
                        requiresSelf = true;
                        break;
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

    /** 情報専用カテゴリ（実行不能）のuid判定。jeresourcesはモブドロップ・取引等の情報専用MODのため名前空間ごと底辺扱い */
    public static boolean isInfoCategoryUid(String uid) {
        if (uid == null) return false;
        String lower = uid.toLowerCase(Locale.ROOT);
        return lower.contains("trade") || lower.contains("villag") || lower.contains("wander") || lower.contains("merchant")
                || lower.contains("quest") || lower.contains("loot") || lower.contains("drop")
                || lower.contains("gift") || lower.contains("barter") || lower.contains("reward")
                || lower.contains("jeresources");
    }

    /** 互換用：単一レシピ探索 */
    public static RecipeHolder<?> findRecipe(ItemStack target, Level level) {
        CandidateFind find = findAllCandidateRecipes(target, level, null, null, System.currentTimeMillis() + timeoutMs());
        List<PlannedRecipe> candidates = new ArrayList<>(find.recipes());
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
            NexCraftTree.LOGGER.info("{}- {} x{} [stored={} (stock={}) craft={} missing={} station={}]{}",
                    pad, name, node.requiredAmount, node.storedAmount, node.totalStockAmount,
                    node.toCraftAmount, node.missingAmount, node.station.getDisplayName().getString(),
                    node.autocraftable ? " (auto)" : "");
            for (CraftingTreeNode c : node.children) logTree(c, indent + 1);
        } catch (Throwable ignored) {
        }
    }
}


