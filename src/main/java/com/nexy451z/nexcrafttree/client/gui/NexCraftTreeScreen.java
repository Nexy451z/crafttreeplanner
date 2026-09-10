package com.nexy451z.nexcrafttree.client.gui;

import com.nexy451z.nexcrafttree.NexCraftTree;
import com.nexy451z.nexcrafttree.client.KeyInputHandler;
import com.nexy451z.nexcrafttree.core.ItemMatchHelper;
import com.nexy451z.nexcrafttree.core.calculation.CraftingTreeNode;
import com.nexy451z.nexcrafttree.core.calculation.PlannedRecipe;
import com.nexy451z.nexcrafttree.core.calculation.ProcessingStation;
import com.nexy451z.nexcrafttree.core.calculation.RecipeResolver;
import com.nexy451z.nexcrafttree.core.stock.PlayerInventoryStock;
import com.nexy451z.nexcrafttree.core.stock.UnifiedStockSnapshot;
import com.nexy451z.nexcrafttree.integration.ModIntegration;
import com.nexy451z.nexcrafttree.integration.RecipeViewerIntegration;
import com.nexy451z.nexcrafttree.integration.ae2.Ae2Stock;
import com.nexy451z.nexcrafttree.integration.refinedstorage.RefinedStorageStock;
import com.nexy451z.nexcrafttree.network.NexCraftTreeNetwork;
import com.nexy451z.nexcrafttree.network.DirectCraftStep;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.*;

/**
 * AE2 / Refined Storage に着想を得た、モダンで洗練されたクラフトツリー計画GUI。
 * - ウィンドウ枠、アイテムアイコン、階層ツリー線
 * - 🟢在庫・🟡作成可・🔴不足の色分けバッジ
 * - 実際の所持在庫総数（例: 在庫 3 / 20個）の正確な反映
 * - [作成 / クラフト] ボタンによる自動配置・RS自動クラフト連携
 * - Rキー/左クリックでレシピ（JEI/REI）、Uキー/右クリックで用途（JEI/REI）の即時表示
 * - スムーズスクロール
 */
public class NexCraftTreeScreen extends Screen {
    private final Screen parentScreen;
    private final ItemStack targetItem;
    private long quantity;
    private CraftingTreeNode root;
    private final List<TreeNodeRow> rows = new ArrayList<>();

    private double scrollOffset = 0;
    private int maxScroll = 0;
    private boolean isDraggingScrollbar = false;
    private double dragStartMouseY = 0;
    private double dragStartScrollOffset = 0;

    private int totalMissingKinds = 0;
    private long totalMissingCount = 0;
    private long totalCraftSteps = 0;

    @Nullable
    private TreeNodeRow currentHoveredRow = null;
    private boolean isHoveringTargetIcon = false;

    private ItemStack outputSlotStack = ItemStack.EMPTY;
    private boolean isHoveringOutputSlot = false;
    private int outputSlotSparkleTicks = 0;
    private String statusFeedback = "";
    private int statusFeedbackColor = 0xFFA6E3A1;

    private ItemStack slottedWorkstation = ItemStack.EMPTY;
    private boolean isHoveringWorkstationSlot = false;
    @Nullable
    private TreeNodeRow currentHoveredStationRow = null;

    private static boolean showItemNames = true;
    private static float zoomScale = 1.0f;
    private static final float[] ZOOM_LEVELS = {0.5f, 0.65f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f};

    // 加工法ポップアップ選択UI
    @Nullable
    private CraftingTreeNode stationPopupNode = null;
    private int stationPopupX = 0;
    private int stationPopupY = 0;
    private double stationPopupScroll = 0;
    private int stationPopupHover = -1;
    private int stationPopupStage = 0;
    private int stationPopupGroup = -1;
    private List<List<Integer>> stationPopupGroups = new ArrayList<>();
    private static final int POPUP_ROW_H = 16;
    private static final int POPUP_MAX_ROWS = 10;
    private static final int POPUP_WIDTH = 186;

    // 探索上限プリセット（深度 / ノード数 / タイムアウトms）
    private static final int[][] LIMIT_PRESETS = {
            {16, 250, 300},
            {24, 800, 800},
            {32, 2000, 2000},
            {48, 6000, 5000},
    };
    private static int limitPresetIndex = 1;
    @Nullable
    private Button limitPresetBtn;

    // バックグラウンド再計算
    private static final java.util.concurrent.ExecutorService RESOLVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NexCraftTree-Resolve");
                t.setDaemon(true);
                return t;
            });
    private volatile int recomputeSeq = 0;
    private int debounceTicks = 0;
    private boolean computing = false;
    private long computeStartMs = 0;
    private volatile int progressNodes = 0;

    private static void applyLimitPreset() {
        int[] p = LIMIT_PRESETS[limitPresetIndex];
        com.nexy451z.nexcrafttree.core.calculation.RecipeResolver.limitMaxDepth = p[0];
        com.nexy451z.nexcrafttree.core.calculation.RecipeResolver.limitMaxNodes = p[1];
        com.nexy451z.nexcrafttree.core.calculation.RecipeResolver.limitTimeoutMs = p[2];
    }

    private static String limitPresetLabel() {
        return tr("gui.nexcrafttree.limit." + limitPresetIndex);
    }

    private void cycleLimitPreset() {
        limitPresetIndex = (limitPresetIndex + 1) % LIMIT_PRESETS.length;
        applyLimitPreset();
        if (limitPresetBtn != null) {
            limitPresetBtn.setMessage(Component.literal(limitPresetLabel()));
        }
        recompute();
    }

    private void openStationPopup(CraftingTreeNode node, int anchorX, int anchorY) {
        stationPopupNode = node;
        stationPopupScroll = 0;
        stationPopupHover = -1;
        stationPopupStage = 0;
        stationPopupGroup = -1;
        // カテゴリごとにレシピをグループ化（出現順を維持）
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < node.alternativeRecipes.size(); i++) {
            PlannedRecipe alt = node.alternativeRecipes.get(i);
            String uid = (alt.getStation() != null && alt.getStation().getCategoryUid() != null)
                    ? alt.getStation().getCategoryUid()
                    : ("station" + i);
            grouped.computeIfAbsent(uid, k -> new ArrayList<>()).add(i);
        }
        stationPopupGroups = new ArrayList<>(grouped.values());
        int visible = Math.min(popupCurrentRowCount(), POPUP_MAX_ROWS);
        int panelH = visible * POPUP_ROW_H + 8;
        int px = anchorX + 18;
        if (px + POPUP_WIDTH > width - 4) {
            px = Math.max(4, anchorX - POPUP_WIDTH - 18);
        }
        int py = Math.max(4, Math.min(anchorY, height - panelH - 4));
        stationPopupX = px;
        stationPopupY = py;
    }

    private int popupCurrentRowCount() {
        if (stationPopupNode == null) return 0;
        if (stationPopupStage == 0) return stationPopupGroups.size();
        if (stationPopupGroup < 0 || stationPopupGroup >= stationPopupGroups.size()) return 0;
        List<Integer> group = stationPopupGroups.get(stationPopupGroup);
        return group == null ? 0 : group.size();
    }

    private int popupHeaderH() {
        return stationPopupStage == 0 ? 0 : 14;
    }

    private void closeStationPopup() {
        stationPopupNode = null;
        stationPopupHover = -1;
        stationPopupStage = 0;
        stationPopupGroup = -1;
    }

    private int stationPopupMaxScroll() {
        int count = popupCurrentRowCount();
        int visible = Math.min(count, POPUP_MAX_ROWS);
        return Math.max(0, (count - visible) * POPUP_ROW_H);
    }

    private int popupFirstRow() {
        int count = popupCurrentRowCount();
        int first = (int) (stationPopupScroll / POPUP_ROW_H);
        int visible = Math.min(count, POPUP_MAX_ROWS);
        int maxFirst = Math.max(0, count - visible);
        return Math.max(0, Math.min(first, maxFirst));
    }

    private void selectAlternative(CraftingTreeNode node, int index) {
        if (node == null || node.alternativeRecipes.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 進行中のバックグラウンド計算結果を破棄（ユーザーの切替が古い結果で上書きされるのを防ぐ）
        recomputeSeq++;
        // 切替は同期実行のため進捗バー表示を解除しておく（詰み防止）
        computing = false;
        progressNodes = 0;

        UnifiedStockSnapshot snapshot = new UnifiedStockSnapshot();
        snapshot.addProvider(new PlayerInventoryStock(mc.player));
        snapshot.addProvider(com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.of(
                "refinedstorage", com.nexy451z.nexcrafttree.integration.refinedstorage.RefinedStorageStock.captureEntries()));
        snapshot.addProvider(com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.of(
                "ae2", com.nexy451z.nexcrafttree.integration.ae2.Ae2Stock.captureEntries()));

        int idx = Math.max(0, Math.min(node.alternativeRecipes.size() - 1, index));
        new RecipeResolver().switchRecipe(node, idx, snapshot, mc.level, slottedWorkstation);
        flatten();
        updateMaxScroll();
    }

    private Button nameToggleBtn;
    private Button zoomResetBtn;
    @Nullable
    private EditBox amountField;
    private boolean isUpdatingAmountField = false;

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void changeZoom(int direction) {
        int curIdx = 3;
        float minDiff = Float.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            float diff = Math.abs(ZOOM_LEVELS[i] - zoomScale);
            if (diff < minDiff) {
                minDiff = diff;
                curIdx = i;
            }
        }
        int newIdx = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, curIdx + direction));
        zoomScale = ZOOM_LEVELS[newIdx];
        if (zoomResetBtn != null) {
            zoomResetBtn.setMessage(Component.literal(getZoomPercentText()));
        }
        updateMaxScroll();
    }

    private void resetZoom() {
        zoomScale = 1.0f;
        if (zoomResetBtn != null) {
            zoomResetBtn.setMessage(Component.literal("100%"));
        }
        updateMaxScroll();
    }

    private String getZoomPercentText() {
        return Math.round(zoomScale * 100) + "%";
    }

    private void toggleItemNames() {
        showItemNames = !showItemNames;
        if (nameToggleBtn != null) {
            nameToggleBtn.setMessage(Component.literal(showItemNames ? tr("gui.nexcrafttree.button.names_on") : tr("gui.nexcrafttree.button.names_off")));
        }
        updateMaxScroll();
    }

    private void updateMaxScroll() {
        int winHeight = Math.min(height - 20, 300);
        int headerH = 34;
        int footerH = 30;
        int contentH = winHeight - headerH - footerH;
        int rowH = showItemNames ? 22 : 18;
        int totalContentH = (int) (rows.size() * rowH * zoomScale);
        maxScroll = Math.max(0, totalContentH - contentH);
        clampScroll();
    }

    private record TreeNodeRow(
            CraftingTreeNode node,
            int depth,
            boolean isLastChild,
            boolean[] ancestorIsLast
    ) {
    }

    public NexCraftTreeScreen(ItemStack target, @Nullable Screen parentScreen) {
        super(Component.translatable("gui.nexcrafttree.tree"));
        this.parentScreen = parentScreen;
        this.targetItem = target.copy();
        this.quantity = Math.max(1, target.getCount());
        recompute();
    }

    public NexCraftTreeScreen(ItemStack target) {
        this(target, null);
    }

    public NexCraftTreeScreen(CraftingTreeNode root) {
        super(Component.translatable("gui.nexcrafttree.tree"));
        this.parentScreen = null;
        this.root = root;
        this.targetItem = (root != null && root.item != null) ? root.item.copy() : ItemStack.EMPTY;
        this.quantity = (root != null) ? Math.max(1, root.requiredAmount) : 1;
        flatten();
    }

    /** JEI/REIなどの相互作用用に、現在マウスホバーしているアイテムを取得 */
    @Nullable
    public ItemStack getHoveredItemStack() {
        if (stationPopupNode != null && stationPopupHover >= 0 && stationPopupStage != 0) {
            List<Integer> group = (stationPopupGroup >= 0 && stationPopupGroup < stationPopupGroups.size())
                    ? stationPopupGroups.get(stationPopupGroup) : null;
            if (group != null && stationPopupHover < group.size()) {
                int globalIdx = group.get(stationPopupHover);
                PlannedRecipe alt = stationPopupNode.alternativeRecipes.get(globalIdx);
                if (alt.getStation() != null && !alt.getStation().getIcon().isEmpty()) {
                    return alt.getStation().getIcon();
                }
            }
        }
        if (isHoveringWorkstationSlot && !slottedWorkstation.isEmpty()) {
            return slottedWorkstation;
        }
        if (isHoveringOutputSlot && !outputSlotStack.isEmpty()) {
            return outputSlotStack;
        }
        if (isHoveringTargetIcon && !targetItem.isEmpty()) {
            return targetItem;
        }
        if (currentHoveredStationRow != null && currentHoveredStationRow.node != null
                && currentHoveredStationRow.node.station != null && !currentHoveredStationRow.node.station.getIcon().isEmpty()) {
            return currentHoveredStationRow.node.station.getIcon();
        }
        if (currentHoveredRow != null && currentHoveredRow.node != null && !currentHoveredRow.node.item.isEmpty()) {
            return currentHoveredRow.node.item;
        }
        return null;
    }

    public void onDirectCraftResult(boolean success, Component message, ItemStack resultStack, int count) {
        if (success) {
            this.outputSlotStack = resultStack.copy();
            if (!resultStack.isEmpty()) {
                this.outputSlotSparkleTicks = 40;
                this.statusFeedback = tr("gui.nexcrafttree.status.done_click_take");
                this.statusFeedbackColor = 0xFFA6E3A1;
            } else {
                this.outputSlotSparkleTicks = 0;
                this.statusFeedback = tr("gui.nexcrafttree.status.done_collected");
                this.statusFeedbackColor = 0xFFA6E3A1;
            }
            recompute();
        } else {
            this.statusFeedback = (message != null && !message.getString().isEmpty()) ? message.getString() : tr("gui.nexcrafttree.status.failed");
            this.statusFeedbackColor = 0xFFF38BA8;
            recompute();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (outputSlotSparkleTicks > 0) {
            outputSlotSparkleTicks--;
        }
        // 数量入力のデバウンストレイリング再計算（キーストローク毎の重い再探索を防ぐ）
        if (debounceTicks > 0 && --debounceTicks == 0) {
            recompute();
        }
    }

    private void changeQuantity(long newQty) {
        long clamped = Math.max(1, Math.min(99999, newQty));
        if (this.quantity != clamped) {
            this.quantity = clamped;
            if (amountField != null) {
                isUpdatingAmountField = true;
                amountField.setValue(String.valueOf(this.quantity));
                isUpdatingAmountField = false;
            }
            recompute();
        }
    }

    /** 数量入力欄のライブ更新（再計算はtickのデバウンスでまとめて実施） */
    private void setQuantityDirect(long newQty) {
        long clamped = Math.max(1, Math.min(99999, newQty));
        if (this.quantity != clamped) {
            this.quantity = clamped;
            debounceTicks = 4;
        }
    }

    private void commitAmountField() {
        if (amountField == null) return;
        String text = amountField.getValue();
        long val = this.quantity;
        if (text != null && !text.isEmpty()) {
            try {
                val = Math.max(1, Math.min(99999, Long.parseLong(text)));
            } catch (NumberFormatException ignored) {
            }
        }
        this.quantity = val;
        isUpdatingAmountField = true;
        amountField.setValue(String.valueOf(this.quantity));
        isUpdatingAmountField = false;
        recompute();
    }

    private void initAmountField(int x, int y, int w, int h) {
        amountField = new EditBox(font, x, y, w, h, Component.translatable("gui.nexcrafttree.amount.label"));
        amountField.setMaxLength(5);
        amountField.setFilter(s -> s.matches("\\d*"));
        amountField.setValue(String.valueOf(quantity));
        amountField.setTextColor(0xFFFFFFFF);
        amountField.setTextColorUneditable(0xFFA6ADC8);
        amountField.setBordered(false);
        amountField.setResponder(text -> {
            if (isUpdatingAmountField) return;
            if (text != null && !text.isEmpty()) {
                try {
                    long val = Long.parseLong(text);
                    if (val >= 1 && val <= 99999) {
                        setQuantityDirect(val);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });
        addRenderableWidget(amountField);
    }

    private void recompute() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || targetItem.isEmpty()) return;

        // 在庫はメインスレッドで不変スナップショットとして取り込む
        // （バックグラウンド計算がRS/AE2のライブデータを直接触らないようにする）
        UnifiedStockSnapshot snapshot = new UnifiedStockSnapshot();
        snapshot.addProvider(new PlayerInventoryStock(mc.player));
        snapshot.addProvider(com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.of(
                "refinedstorage", com.nexy451z.nexcrafttree.integration.refinedstorage.RefinedStorageStock.captureEntries()));
        snapshot.addProvider(com.nexy451z.nexcrafttree.core.stock.StaticStockProvider.of(
                "ae2", com.nexy451z.nexcrafttree.integration.ae2.Ae2Stock.captureEntries()));

        final int seq = ++recomputeSeq;
        final net.minecraft.world.level.Level level = mc.level;
        final long qty = quantity;
        final ItemStack target = targetItem.copy();
        final ItemStack ws = slottedWorkstation.copy();
        // 現在のツリーでユーザーが選んでいる加工法をパス単位で引き継ぐ
        final Map<String, net.minecraft.resources.Identifier> preferences = captureRecipePreferences();

        computing = true;
        computeStartMs = System.currentTimeMillis();
        progressNodes = 0;

        RESOLVE_EXECUTOR.submit(() -> {
            if (seq != recomputeSeq) return; // 新しい要求が発行済みなら何もしない
            try {
                com.nexy451z.nexcrafttree.core.calculation.RecipeResolver resolver =
                        new com.nexy451z.nexcrafttree.core.calculation.RecipeResolver();
                CraftingTreeNode newRoot = resolver.resolve(target, qty, snapshot, level, ws, (nodes, finished) -> {
                    if (seq == recomputeSeq) progressNodes = nodes;
                }, preferences);
                Minecraft.getInstance().execute(() -> {
                    if (seq != recomputeSeq || Minecraft.getInstance().screen != this) return;
                    this.root = newRoot;
                    computing = false;
                    flatten();
                    updateMaxScroll();
                });
            } catch (Throwable t) {
                NexCraftTree.LOGGER.warn("[NexCraftTree] async resolve failed", t);
                Minecraft.getInstance().execute(() -> {
                    if (seq == recomputeSeq) computing = false;
                });
            }
        });
    }

    /** 現在のツリーの「ノードパス→選択レシピID」を収集する（再計算時の選択引継ぎ用） */
    private Map<String, net.minecraft.resources.Identifier> captureRecipePreferences() {
        Map<String, net.minecraft.resources.Identifier> out = new HashMap<>();
        try {
            CraftingTreeNode currentRoot = this.root;
            if (currentRoot != null) {
                String rootKey = RecipeResolver.pathSegment(currentRoot.item, 0);
                captureRecipePreferencesRecursive(currentRoot, rootKey, out);
            }
        } catch (Throwable ignored) {
        }
        return out.isEmpty() ? java.util.Collections.emptyMap() : Map.copyOf(out);
    }

    private void captureRecipePreferencesRecursive(CraftingTreeNode node, String pathKey,
                                                   Map<String, net.minecraft.resources.Identifier> out) {
        if (node == null) return;
        if (!node.alternativeRecipes.isEmpty()
                && node.selectedRecipeIndex >= 0 && node.selectedRecipeIndex < node.alternativeRecipes.size()) {
            com.nexy451z.nexcrafttree.core.calculation.PlannedRecipe selected =
                    node.alternativeRecipes.get(node.selectedRecipeIndex);
            // 情報カテゴリ（村人取引・クエスト・ドロップ等）は引き継がない（最底辺カテゴリを固定選択にしない）
            boolean info = selected.getStation() != null
                    && RecipeResolver.isInfoCategoryUid(selected.getStation().getCategoryUid());
            if (!info) {
                out.put(pathKey, selected.getId());
            }
        }
        for (int i = 0; i < node.children.size(); i++) {
            CraftingTreeNode child = node.children.get(i);
            String childKey = pathKey + "/" + RecipeResolver.pathSegment(child.item, i);
            captureRecipePreferencesRecursive(child, childKey, out);
        }
    }

    private void cycleWorkstationFromPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        List<ItemStack> candidates = new ArrayList<>();
        candidates.add(new ItemStack(Items.CRAFTING_TABLE));
        candidates.add(new ItemStack(Items.FURNACE));
        candidates.add(new ItemStack(Items.BLAST_FURNACE));
        candidates.add(new ItemStack(Items.SMOKER));

        // インベントリ内の加工機・作業台アイテムを収集
        for (ItemStack invStack : mc.player.getInventory().getNonEquipmentItems()) {
            if (!invStack.isEmpty()) {
                String idStr = BuiltInRegistries.ITEM.getKey(invStack.getItem()).toString();
                if (idStr.contains("furnace") || idStr.contains("crafter") || idStr.contains("table")
                        || idStr.contains("smelt") || idStr.contains("infuser") || idStr.contains("press")
                        || idStr.contains("crusher") || idStr.contains("machine")) {
                    boolean already = false;
                    for (ItemStack c : candidates) {
                        if (ItemStack.isSameItem(c, invStack)) {
                            already = true;
                            break;
                        }
                    }
                    if (!already) {
                        candidates.add(invStack.copyWithCount(1));
                    }
                }
            }
        }

        if (slottedWorkstation.isEmpty()) {
            this.slottedWorkstation = candidates.get(0);
        } else {
            int curIdx = -1;
            for (int i = 0; i < candidates.size(); i++) {
                if (ItemStack.isSameItem(candidates.get(i), slottedWorkstation)) {
                    curIdx = i;
                    break;
                }
            }
            if (curIdx >= 0 && curIdx < candidates.size() - 1) {
                this.slottedWorkstation = candidates.get(curIdx + 1);
            } else {
                this.slottedWorkstation = ItemStack.EMPTY;
            }
        }
        recompute();
    }

    private void flatten() {
        rows.clear();
        closeStationPopup();
        if (root != null) {
            boolean[] ancestorIsLast = new boolean[64];
            addRecursive(root, 0, true, ancestorIsLast);
        }
        updateStats();
    }

    private void addRecursive(CraftingTreeNode node, int depth, boolean isLast, boolean[] ancestorIsLast) {
        if (rows.size() >= 500) return;
        rows.add(new TreeNodeRow(node, depth, isLast, ancestorIsLast.clone()));
        for (int i = 0; i < node.children.size(); i++) {
            boolean childIsLast = (i == node.children.size() - 1);
            if (depth < ancestorIsLast.length) {
                ancestorIsLast[depth] = isLast;
            }
            addRecursive(node.children.get(i), depth + 1, childIsLast, ancestorIsLast);
        }
    }

    private void updateStats() {
        totalMissingKinds = 0;
        totalMissingCount = 0;
        totalCraftSteps = 0;
        Set<Item> missingSet = new HashSet<>();
        for (TreeNodeRow r : rows) {
            if (r.node.missingAmount > 0) {
                missingSet.add(r.node.item.getItem());
                totalMissingCount += r.node.missingAmount;
            }
            if (r.node.toCraftAmount > 0) {
                totalCraftSteps++;
            }
        }
        totalMissingKinds = missingSet.size();
    }

    @Override
    protected void init() {
        clearWidgets();
        int winWidth = Math.min(width - 20, 520);
        int winHeight = Math.min(height - 20, 300);
        int winX = (width - winWidth) / 2;
        int winY = (height - winHeight) / 2;

        int btnY = winY + 7;
        int btnH = 20;
        int curRight = winX + winWidth - 8;

        // 0. 探索上限プリセット
        curRight -= 76;
        limitPresetBtn = addRenderableWidget(Button.builder(Component.literal(limitPresetLabel()), b -> cycleLimitPreset())
                .bounds(curRight, btnY, 76, btnH).build());

        curRight -= 4; // 区切り

        // 1. アイテム名表示切り替えボタン (名前:ON / 名前:OFF)
        curRight -= 48;
        nameToggleBtn = addRenderableWidget(Button.builder(Component.literal(showItemNames ? tr("gui.nexcrafttree.button.names_on") : tr("gui.nexcrafttree.button.names_off")), b -> toggleItemNames())
                .bounds(curRight, btnY, 48, btnH).build());

        curRight -= 4; // 区切り

        // 2. ズームコントロールボタン (+, 100%, -)
        curRight -= 16;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> changeZoom(1)).bounds(curRight, btnY, 16, btnH).build());
        curRight -= 32;
        zoomResetBtn = addRenderableWidget(Button.builder(Component.literal(getZoomPercentText()), b -> resetZoom()).bounds(curRight, btnY, 32, btnH).build());
        curRight -= 16;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> changeZoom(-1)).bounds(curRight, btnY, 16, btnH).build());

        curRight -= 4; // 区切り

        // 3. ヘッダー作成ボタン
        curRight -= 38;
        addRenderableWidget(Button.builder(Component.literal(tr("gui.nexcrafttree.button.create")), b -> {
            if (root != null) executeCraft(root);
        }).bounds(curRight, btnY, 38, btnH).build());

        curRight -= 6; // 区切り

        // 4. 数量プリセットボタン (x64, x10, x1)
        curRight -= 24;
        addRenderableWidget(Button.builder(Component.literal("x64"), b -> changeQuantity(64)).bounds(curRight, btnY, 24, btnH).build());
        curRight -= 24;
        addRenderableWidget(Button.builder(Component.literal("x10"), b -> changeQuantity(10)).bounds(curRight, btnY, 24, btnH).build());
        curRight -= 20;
        addRenderableWidget(Button.builder(Component.literal("x1"), b -> changeQuantity(1)).bounds(curRight, btnY, 20, btnH).build());

        curRight -= 4; // 区切り

        // 5. 数量ステッパー & 直接入力ボックス ([+] [入力欄] [-])
        curRight -= 16;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> changeQuantity(quantity + 1)).bounds(curRight, btnY, 16, btnH).build());

        curRight -= 36;
        int inputX = curRight;
        int inputY = btnY + 2;
        int inputW = 36;
        int inputH = 16;
        initAmountField(inputX, inputY, inputW, inputH);

        curRight -= 16;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> changeQuantity(quantity - 1)).bounds(curRight, btnY, 16, btnH).build());

        // フッターの一括作成ボタン
        addRenderableWidget(Button.builder(Component.literal(tr("gui.nexcrafttree.button.craft_all")), b -> {
            if (root != null) executeCraft(root);
        }).bounds(winX + winWidth - 124, winY + winHeight - 25, 58, 19).build());

        // 閉じるボタン
        addRenderableWidget(Button.builder(Component.literal(tr("gui.nexcrafttree.button.close")), b -> onClose())
                .bounds(winX + winWidth - 62, winY + winHeight - 25, 52, 19).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int winWidth = Math.min(width - 20, 520);
        int winHeight = Math.min(height - 20, 300);
        int winX = (width - winWidth) / 2;
        int winY = (height - winHeight) / 2;

        int headerH = 34;
        int footerH = 30;

        int contentX = winX + 6;
        int contentY = winY + headerH;
        int contentW = winWidth - 12;
        int contentH = winHeight - headerH - footerH;
        int rowH = showItemNames ? 22 : 18;
        int indentStep = showItemNames ? 18 : 14;

        int totalContentH = (int) (rows.size() * rowH * zoomScale);
        maxScroll = Math.max(0, totalContentH - contentH);
        clampScroll();

        currentHoveredRow = null;
        currentHoveredStationRow = null;
        isHoveringTargetIcon = (mouseX >= winX + 9 && mouseX <= winX + 25 && mouseY >= winY + 9 && mouseY <= winY + 25);

        // 1. 全画面暗転オーバーレイ
        g.fill(0, 0, width, height, 0x88000000);

        // 2. ウィンドウ本体描画
        g.fill(winX, winY, winX + winWidth, winY + winHeight, 0xF2181825);
        drawBorder(g, winX, winY, winWidth, winHeight, 0xFF313244);
        drawBorder(g, winX + 1, winY + 1, winWidth - 2, winHeight - 2, 0xFF1E1E2E);

        // ヘッダー背景と境界線
        g.fill(winX + 1, winY + 1, winX + winWidth - 1, winY + headerH, 0xFF1E1E2E);
        g.fill(winX, winY + headerH, winX + winWidth, winY + headerH + 1, 0xFF313244);

        // フッター背景と境界線
        g.fill(winX + 1, winY + winHeight - footerH, winX + winWidth - 1, winY + winHeight - 1, 0xFF181825);
        g.fill(winX, winY + winHeight - footerH - 1, winX + winWidth, winY + winHeight - footerH, 0xFF313244);

        // 3. ヘッダー要素描画
        if (!targetItem.isEmpty()) {
            g.item(targetItem, winX + 9, winY + 9);
            g.itemDecorations(font, targetItem, winX + 9, winY + 9, String.valueOf(quantity));

            String titleStr = targetItem.getHoverName().getString();
            int maxTitleW = Math.max(60, (amountField != null ? amountField.getX() - 24 : winX + winWidth - 325) - (winX + 30));
            if (font.width(titleStr) > maxTitleW) {
                titleStr = font.plainSubstrByWidth(titleStr, maxTitleW - 6) + "…";
            }
            g.text(font, titleStr, winX + 30, winY + 13, 0xFFFFFFFF, true);
        }

        // 数量入力欄の背景と境界線描画
        if (amountField != null) {
            int fx = amountField.getX();
            int fy = amountField.getY();
            int fw = amountField.getWidth();
            int fh = amountField.getHeight();
            g.fill(fx - 2, fy - 2, fx + fw + 2, fy + fh + 2, 0xFF11111B);
            int fBorderColor = amountField.isFocused() ? 0xFF89B4FA : 0xFF45475A;
            drawBorder(g, fx - 2, fy - 2, fw + 4, fh + 4, fBorderColor);
        }

        // 4. ツリーリスト描画（クリッピング領域内 ＋ ズーム適用）
        g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);

        g.pose().pushMatrix();
        g.pose().translate(contentX, contentY);
        g.pose().scale(zoomScale, zoomScale);
        g.pose().translate(-contentX, -contentY);

        double localMouseX = contentX + (mouseX - contentX) / zoomScale;
        double localMouseY = contentY + (mouseY - contentY) / zoomScale;
        double virtualScrollOffset = scrollOffset / zoomScale;

        int treeBaseX = contentX + 6;

        for (int i = 0; i < rows.size(); i++) {
            TreeNodeRow r = rows.get(i);
            int rY = (int) (contentY + i * rowH - virtualScrollOffset);
            if (rY + rowH < contentY || rY > contentY + contentH / zoomScale) continue;

            int indent = r.depth * indentStep;
            int iconX = treeBaseX + indent;

            // ツリー線の描画
            for (int a = 0; a < r.depth; a++) {
                if (!r.ancestorIsLast[a]) {
                    int lx = treeBaseX + a * indentStep + (showItemNames ? 8 : 6);
                    g.fill(lx, rY, lx + 1, rY + rowH, 0xFF45475A);
                }
            }
            if (r.depth > 0) {
                int lx = treeBaseX + (r.depth - 1) * indentStep + (showItemNames ? 8 : 6);
                int midY = rY + (showItemNames ? 11 : 9);
                g.fill(lx, rY, lx + 1, r.isLastChild ? midY + 1 : rY + rowH, 0xFF45475A);
                g.fill(lx, midY, iconX - 2, midY + 1, 0xFF45475A);
            }

            if (showItemNames) {
                // ==================== 通常モード（アイテム名ON） ====================
                boolean isRowHovered = localMouseX >= contentX && localMouseX <= contentX + contentW - 10 && localMouseY >= rY && localMouseY < rY + rowH;
                if (isRowHovered) {
                    g.fill(contentX, rY, contentX + contentW - 10, rY + rowH, 0x1AFFFFFF);
                    currentHoveredRow = r;
                }

                int iconY = rY + 3;
                g.item(r.node.item, iconX, iconY);
                g.itemDecorations(font, r.node.item, iconX, iconY, String.valueOf(r.node.requiredAmount));

                int nameX = iconX + 20;
                int badgeW = 78;
                int badgeX = contentX + contentW - 14 - badgeW;

                // 設備アイコン ＆ レシピ切替ボックス
                boolean hasMultipleRecipes = (r.node.alternativeRecipes.size() > 1);
                int stationBoxW = hasMultipleRecipes ? 34 : 20;
                int stationBoxX = badgeX - stationBoxW - 4;
                int maxNameW = stationBoxX - nameX - 6;

                String name = r.node.item.getHoverName().getString();
                if (font.width(name) > maxNameW) {
                    name = font.plainSubstrByWidth(name, maxNameW - 6) + "…";
                }
                g.text(font, name, nameX, rY + 7, 0xFFCDD6F4, true);

                // 設備ボックス描画
                int stationBoxY = rY + 3;
                int stationBoxH = 16;
                boolean isStationHovered = localMouseX >= stationBoxX && localMouseX <= stationBoxX + stationBoxW
                        && localMouseY >= stationBoxY && localMouseY <= stationBoxY + stationBoxH;
                if (isStationHovered) {
                    currentHoveredStationRow = r;
                }

                ItemStack stIcon = (r.node.station != null && !r.node.station.getIcon().isEmpty()) ?
                        r.node.station.getIcon() : new ItemStack(Items.CRAFTING_TABLE);

                g.fill(stationBoxX, stationBoxY, stationBoxX + stationBoxW, stationBoxY + stationBoxH, isStationHovered ? 0x4489B4FA : 0x22181825);
                drawBorder(g, stationBoxX, stationBoxY, stationBoxW, stationBoxH, isStationHovered ? 0xFF89B4FA : (hasMultipleRecipes ? 0x8889B4FA : 0x4445475A));
                g.item(stIcon, stationBoxX + 2, stationBoxY);

                if (hasMultipleRecipes) {
                    g.text(font, "⇄", stationBoxX + 20, stationBoxY + 4, isStationHovered ? 0xFFFFFFFF : 0xFF89B4FA, false);
                }

                int badgeBg;
                int badgeBorder;
                int badgeFg;
                String badgeText;

                boolean isBadgeHovered = localMouseX >= badgeX && localMouseX <= badgeX + badgeW && localMouseY >= rY + 4 && localMouseY <= rY + 18;

                if (r.node.missingAmount > 0) {
                    badgeBg = 0x33F38BA8;
                    badgeBorder = 0x88F38BA8;
                    badgeFg = 0xFFF38BA8;
                    badgeText = tr("gui.nexcrafttree.badge.missing", r.node.missingAmount);
                } else if (r.node.toCraftAmount > 0) {
                    badgeBg = isBadgeHovered ? 0x66F9E2AF : 0x33F9E2AF;
                    badgeBorder = isBadgeHovered ? 0xFFF9E2AF : 0x88F9E2AF;
                    badgeFg = 0xFFF9E2AF;
                    if (r.node.storedAmount > 0) {
                        badgeText = tr("gui.nexcrafttree.badge.craft_with_stock", r.node.toCraftAmount, r.node.storedAmount);
                    } else {
                        badgeText = r.node.autocraftable ? tr("gui.nexcrafttree.badge.auto", r.node.toCraftAmount)
                                : tr("gui.nexcrafttree.badge.craft", r.node.toCraftAmount);
                    }
                } else {
                    badgeBg = 0x33A6E3A1;
                    badgeBorder = 0x88A6E3A1;
                    badgeFg = 0xFFA6E3A1;
                    if (r.node.totalStockAmount > r.node.storedAmount) {
                        badgeText = tr("gui.nexcrafttree.badge.stock", r.node.storedAmount, r.node.totalStockAmount);
                    } else {
                        badgeText = tr("gui.nexcrafttree.badge.stock_short", r.node.storedAmount);
                    }
                }
                if (r.node.cutByCycle) badgeText += " " + tr("gui.nexcrafttree.suffix.cycle");
                else if (r.node.cutByLimit) badgeText += " " + tr("gui.nexcrafttree.suffix.limit");

                int badgeY = rY + 4;
                int badgeH = 14;
                g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeBg);
                drawBorder(g, badgeX, badgeY, badgeW, badgeH, badgeBorder);
                g.centeredText(font, badgeText, badgeX + badgeW / 2, badgeY + 3, badgeFg);
            } else {
                // ==================== コンパクトモード（アイテム名OFF・美麗ノード表示） ====================
                int tileX = iconX;
                int tileY = rY + 1;
                boolean hasMultipleRecipes = (r.node.alternativeRecipes.size() > 1);
                int tileW = 72;
                int tileH = 16;

                boolean isTileHovered = localMouseX >= tileX && localMouseX <= tileX + tileW && localMouseY >= tileY && localMouseY <= tileY + tileH;
                if (isTileHovered) {
                    currentHoveredRow = r;
                }

                int stationPartX = tileX + 54;
                boolean isStationHovered = localMouseX >= stationPartX && localMouseX <= tileX + tileW && localMouseY >= tileY && localMouseY <= tileY + tileH;
                if (isStationHovered) {
                    currentHoveredStationRow = r;
                }

                int statusColor;
                int badgeBg;
                int badgeBorder;
                int badgeFg;
                String miniText;

                if (r.node.missingAmount > 0) {
                    statusColor = 0xFFF38BA8;
                    badgeBg = 0x44F38BA8;
                    badgeBorder = 0x88F38BA8;
                    badgeFg = 0xFFF38BA8;
                    miniText = tr("gui.nexcrafttree.badge.mini_missing", r.node.missingAmount);
                } else if (r.node.toCraftAmount > 0) {
                    statusColor = 0xFFF9E2AF;
                    badgeBg = isTileHovered ? 0x88F9E2AF : 0x44F9E2AF;
                    badgeBorder = isTileHovered ? 0xFFF9E2AF : 0x88F9E2AF;
                    badgeFg = 0xFFF9E2AF;
                    miniText = tr("gui.nexcrafttree.badge.mini_craft", r.node.toCraftAmount);
                } else {
                    statusColor = 0xFFA6E3A1;
                    badgeBg = 0x44A6E3A1;
                    badgeBorder = 0x88A6E3A1;
                    badgeFg = 0xFFA6E3A1;
                    miniText = tr("gui.nexcrafttree.badge.mini_stock", r.node.storedAmount);
                }

                // タイル背景とステータスボーダー
                g.fill(tileX, tileY, tileX + tileW, tileY + tileH, isTileHovered ? 0xF22A2B3D : 0xF2181825);
                drawBorder(g, tileX, tileY, tileW, tileH, isTileHovered ? 0xFFFFFFFF : statusColor);

                // アイコン描画
                g.item(r.node.item, tileX, tileY);
                g.itemDecorations(font, r.node.item, tileX, tileY, String.valueOf(r.node.requiredAmount));

                // ミニステータスバッジ
                int miniX = tileX + 17;
                int miniY = tileY + 2;
                int miniW = 34;
                int miniH = 12;
                g.fill(miniX, miniY, miniX + miniW, miniY + miniH, badgeBg);
                drawBorder(g, miniX, miniY, miniW, miniH, badgeBorder);
                g.centeredText(font, miniText, miniX + miniW / 2, miniY + 2, badgeFg);

                // 設備アイコン描画
                ItemStack stIcon = (r.node.station != null && !r.node.station.getIcon().isEmpty()) ?
                        r.node.station.getIcon() : new ItemStack(Items.CRAFTING_TABLE);
                g.item(stIcon, stationPartX, tileY);
                if (hasMultipleRecipes) {
                    g.text(font, "⇄", stationPartX + 10, tileY, isStationHovered ? 0xFFFFFFFF : 0xFF89B4FA, false);
                }
            }
        }

        g.pose().popMatrix();
        g.disableScissor();

        // 5. スクロールバー描画
        if (maxScroll > 0) {
            int sbX = contentX + contentW - 6;
            int sbY = contentY + 2;
            int sbW = 4;
            int sbH = contentH - 4;
            g.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0x33000000);

            int thumbH = Math.max(16, (int) ((double) contentH / totalContentH * sbH));
            int thumbY = sbY + (int) ((scrollOffset / maxScroll) * (sbH - thumbH));
            int thumbColor = isDraggingScrollbar ? 0xFF9399B2 :
                    (mouseX >= sbX - 2 && mouseX <= sbX + sbW + 2 && mouseY >= thumbY && mouseY <= thumbY + thumbH ? 0xFF7F849C : 0xFF585B70);
            g.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH, thumbColor);
        }

        // 6. フッター情報
        int footerTextY = winY + winHeight - 19;
        int outSlotX = winX + winWidth - 156;
        int outSlotY = winY + winHeight - footerH + 4;
        int outSlotSize = 22;

        int wsSlotSize = 22;
        int wsSlotX = outSlotX - wsSlotSize - 32;
        int wsSlotY = outSlotY;

        isHoveringOutputSlot = (mouseX >= outSlotX && mouseX <= outSlotX + outSlotSize && mouseY >= outSlotY && mouseY <= outSlotY + outSlotSize);
        isHoveringWorkstationSlot = (mouseX >= wsSlotX && mouseX <= wsSlotX + wsSlotSize && mouseY >= wsSlotY && mouseY <= wsSlotY + wsSlotSize);

        // 作業台・設備スロット背景と枠線
        g.fill(wsSlotX, wsSlotY, wsSlotX + wsSlotSize, wsSlotY + wsSlotSize, 0xFF11111B);
        int wsBorderColor = 0xFF45475A;
        if (!slottedWorkstation.isEmpty()) {
            wsBorderColor = isHoveringWorkstationSlot ? 0xFFA6E3A1 : 0xFF89B4FA;
        } else if (isHoveringWorkstationSlot) {
            wsBorderColor = 0xFF7F849C;
        }
        drawBorder(g, wsSlotX, wsSlotY, wsSlotSize, wsSlotSize, wsBorderColor);

        // 設備アイコン描画
        String wsLabel = tr("gui.nexcrafttree.slot.workstation");
        String outLabel = tr("gui.nexcrafttree.slot.output");
        if (!slottedWorkstation.isEmpty()) {
            g.item(slottedWorkstation, wsSlotX + 3, wsSlotY + 3);
            g.itemDecorations(font, slottedWorkstation, wsSlotX + 3, wsSlotY + 3);
        } else {
            g.centeredText(font, tr("gui.nexcrafttree.slot.workstation_ph"), wsSlotX + 11, wsSlotY + 7, 0x44CDD6F4);
        }
        g.text(font, wsLabel, wsSlotX - font.width(wsLabel) - 4, footerTextY, 0xFFA6ADC8, true);

        // 完成品スロット背景と枠線
        g.fill(outSlotX, outSlotY, outSlotX + outSlotSize, outSlotY + outSlotSize, 0xFF11111B);
        int slotBorderColor = 0xFF45475A;
        if (outputSlotSparkleTicks > 0) {
            slotBorderColor = (outputSlotSparkleTicks % 6 < 3) ? 0xFFFFFFFF : 0xFFF9E2AF;
        } else if (!outputSlotStack.isEmpty()) {
            slotBorderColor = isHoveringOutputSlot ? 0xFFA6E3A1 : 0xFFF9E2AF;
        } else if (isHoveringOutputSlot) {
            slotBorderColor = 0xFF7F849C;
        }
        drawBorder(g, outSlotX, outSlotY, outSlotSize, outSlotSize, slotBorderColor);

        // 完成品アイコン描画
        if (!outputSlotStack.isEmpty()) {
            g.item(outputSlotStack, outSlotX + 3, outSlotY + 3);
            g.itemDecorations(font, outputSlotStack, outSlotX + 3, outSlotY + 3, String.valueOf(outputSlotStack.getCount()));
        } else {
            g.centeredText(font, tr("gui.nexcrafttree.slot.output_ph"), outSlotX + 11, outSlotY + 7, 0x44CDD6F4);
        }

        // 完成品ラベル
        g.text(font, outLabel, outSlotX - font.width(outLabel) - 4, footerTextY, 0xFFA6ADC8, true);

        // 左側ステータステキスト
        int maxTextW = wsSlotX - font.width(wsLabel) - 16 - (winX + 12);
        if (statusFeedback != null && !statusFeedback.isEmpty()) {
            String txt = statusFeedback;
            if (font.width(txt) > maxTextW) {
                txt = font.plainSubstrByWidth(txt, maxTextW - 6) + "…";
            }
            g.text(font, txt, winX + 12, footerTextY, statusFeedbackColor, true);
        } else if (totalMissingCount == 0) {
            g.text(font, tr("gui.nexcrafttree.status.ready"), winX + 12, footerTextY, 0xFFA6E3A1, true);
        } else {
            String sumText = tr("gui.nexcrafttree.status.missing_summary",
                    totalMissingKinds, totalMissingCount, totalCraftSteps);
            if (font.width(sumText) > maxTextW) {
                sumText = font.plainSubstrByWidth(sumText, maxTextW - 6) + "…";
            }
            g.text(font, sumText, winX + 12, footerTextY, 0xFFF38BA8, true);
        }

        // 7. ウィジェット描画（ボタン等）
        super.extractRenderState(g, mouseX, mouseY, partial);

        // 探索中プログレスバー（最前面）
        if (computing) {
            int barW = Math.min(220, winWidth - 40);
            int barX = winX + (winWidth - barW) / 2;
            int barY = winY + winHeight - footerH - 12;
            long elapsed = System.currentTimeMillis() - computeStartMs;
            int presetNodes = LIMIT_PRESETS[limitPresetIndex][1];
            int pct = Math.min(95, (int) (progressNodes * 100L / Math.max(1, presetNodes)));
            g.fill(barX, barY, barX + barW, barY + 9, 0xFF11111B);
            int fillW = Math.max(4, barW * pct / 100);
            int barColor = ((elapsed / 150) % 2 == 0) ? 0xFF89B4FA : 0xFF7C87F5;
            g.fill(barX + 1, barY + 1, barX + 1 + (barW - 2) * Math.max(6, pct) / 100, barY + 8, barColor);
            drawBorder(g, barX, barY, barW, 9, 0xFF45475A);
            String txt = tr("gui.nexcrafttree.status.searching") + " " + pct + "% (" + (elapsed / 1000) + "s)";
            g.centeredText(font, txt, winX + winWidth / 2, barY - 10, 0xFFA6ADC8);
        }

        // 8. ツールチップ描画（最前面）
        if (isHoveringWorkstationSlot) {
            List<Component> tooltip = new ArrayList<>();
            if (!slottedWorkstation.isEmpty()) {
                tooltip.add(slottedWorkstation.getHoverName());
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.priority", slottedWorkstation.getHoverName().getString()));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.desc"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.leftclick"));
                String usageKey = RecipeViewerIntegration.getUsageKeyName();
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.usage", usageKey));
            } else {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.empty.title"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.empty.desc"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.empty.click"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.workstation.empty.fallback"));
            }
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        } else if (isHoveringOutputSlot) {
            List<Component> tooltip = new ArrayList<>();
            if (!outputSlotStack.isEmpty()) {
                tooltip.add(outputSlotStack.getHoverName());
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.count", outputSlotStack.getCount()));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.take"));
                String usageKey = RecipeViewerIntegration.getUsageKeyName();
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.usage", usageKey));
            } else {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.empty.title"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.empty.desc"));
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.output.empty.take"));
            }
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        } else if (currentHoveredStationRow != null && stationPopupNode == null) {
            CraftingTreeNode node = currentHoveredStationRow.node;
            List<Component> tooltip = new ArrayList<>();
            ProcessingStation st = (node.station != null) ? node.station : ProcessingStation.CRAFTING_TABLE;
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.station.title", st.getDisplayName().getString()));
            if (st.getCategoryUid() != null && !st.getCategoryUid().isEmpty()) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.station.category", st.getCategoryUid()));
            }
            if (node.alternativeRecipes.size() > 1) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.station.switch", node.alternativeRecipes.size()));
                for (int ai = 0; ai < node.alternativeRecipes.size(); ai++) {
                    PlannedRecipe alt = node.alternativeRecipes.get(ai);
                    boolean isCur = (ai == node.selectedRecipeIndex);
                    String prefix = isCur ? " §a✔ " : " §7 - ";
                    tooltip.add(Component.literal(prefix + alt.getStation().getDisplayName().getString() + " (" + alt.getCategoryTitle().getString() + ")"));
                }
            } else {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.station.single"));
            }
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.station.usage", usageKey));
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        } else if (currentHoveredRow != null) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(currentHoveredRow.node.item.getHoverName());
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.required", currentHoveredRow.node.requiredAmount));
            if (currentHoveredRow.node.storedAmount > 0) {
                if (currentHoveredRow.node.totalStockAmount > currentHoveredRow.node.storedAmount) {
                    tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.stock_allocated",
                            currentHoveredRow.node.totalStockAmount, currentHoveredRow.node.storedAmount));
                } else {
                    tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.stock", currentHoveredRow.node.storedAmount));
                }
            } else if (currentHoveredRow.node.totalStockAmount > 0) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.stock", currentHoveredRow.node.totalStockAmount));
            }
            if (currentHoveredRow.node.toCraftAmount > 0) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.tocraft", currentHoveredRow.node.toCraftAmount));
            }
            if (currentHoveredRow.node.missingAmount > 0) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.missing", currentHoveredRow.node.missingAmount));
            }
            if (currentHoveredRow.node.autocraftable) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.autocraft"));
            }
            if (currentHoveredRow.node.cutByCycle) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.cycle"));
            } else if (currentHoveredRow.node.cutByLimit) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.limit"));
            }

            if (currentHoveredRow.node.toCraftAmount > 0) {
                tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.actions"));
            }
            String recipeKey = RecipeViewerIntegration.getRecipeKeyName();
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.keys", recipeKey, usageKey));
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        } else if (amountField != null && mouseX >= amountField.getX() - 2 && mouseX <= amountField.getX() + amountField.getWidth() + 2
                && mouseY >= amountField.getY() - 2 && mouseY <= amountField.getY() + amountField.getHeight() + 2) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.amount.title", quantity));
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.amount.desc"));
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.amount.hints"));
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        } else if (isHoveringTargetIcon && !targetItem.isEmpty()) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(targetItem.getHoverName());
            String recipeKey = RecipeViewerIntegration.getRecipeKeyName();
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.nexcrafttree.tt.row.keys", recipeKey, usageKey));
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }

        // 9. 加工法ポップアップ（全ウィジェット・ツールチップより最前面）
        renderStationPopup(g, mouseX, mouseY);
    }

    private void renderStationPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (stationPopupNode == null || stationPopupNode.alternativeRecipes.isEmpty()) {
            closeStationPopup();
            return;
        }
        int count = popupCurrentRowCount();
        if (count <= 0) {
            closeStationPopup();
            return;
        }
        int visible = Math.min(count, POPUP_MAX_ROWS);
        int panelW = POPUP_WIDTH;
        int headerH = popupHeaderH();
        int panelH = headerH + visible * POPUP_ROW_H + 8;
        int px = stationPopupX;
        int py = stationPopupY;
        int listTop = py + 4 + headerH;

        // ホバー行検出（描画と同時に行う）
        stationPopupHover = -1;
        if (stationPopupStage == 1 && mouseY >= py + 3 && mouseY < py + 3 + 13 && mouseX >= px && mouseX <= px + panelW) {
            stationPopupHover = -2; // 戻るヘッダー
        } else if (mouseX >= px && mouseX <= px + panelW && mouseY >= listTop && mouseY < listTop + visible * POPUP_ROW_H) {
            int rowIdx = (mouseY - listTop) / POPUP_ROW_H;
            int idx = popupFirstRow() + rowIdx;
            if (idx >= 0 && idx < count) {
                stationPopupHover = idx;
            }
        }

        g.fill(px, py, px + panelW, py + panelH, 0xFF181825);
        drawBorder(g, px, py, panelW, panelH, 0xFF89B4FA);
        drawBorder(g, px + 1, py + 1, panelW - 2, panelH - 2, 0xFF313244);

        // ステージ2: 戻るヘッダー
        if (stationPopupStage != 0) {
            boolean backHovered = (stationPopupHover == -2);
            g.fill(px + 2, py + 2, px + panelW - 2, py + 17, backHovered ? 0x3389B4FA : 0x22181825);
            g.text(font, tr("gui.nexcrafttree.popup.back"), px + 6, py + 6,
                    backHovered ? 0xFFFFFFFF : 0xFF89B4FA, true);
        }

        g.enableScissor(px + 1, listTop, px + panelW - 1, py + panelH - 1);
        int iconArea = POPUP_WIDTH - 54;
        for (int i = 0; i < visible; i++) {
            int rowIdx = popupFirstRow() + i;
            if (rowIdx < 0 || rowIdx >= count) continue;
            int rowY = listTop + i * POPUP_ROW_H;
            boolean isHovered = (stationPopupHover == rowIdx);
            if (isHovered) {
                g.fill(px + 2, rowY, px + panelW - 2, rowY + POPUP_ROW_H, 0x3389B4FA);
            }

            if (stationPopupStage == 0) {
                // ステージ1: 加工カテゴリ一覧
                List<Integer> group = stationPopupGroups.get(rowIdx);
                PlannedRecipe rep = group != null && !group.isEmpty()
                        ? stationPopupNode.alternativeRecipes.get(group.get(0)) : null;
                if (rep == null) continue;
                boolean containsCurrent = group != null && group.contains(stationPopupNode.selectedRecipeIndex);
                String stName = rep.getStation().getDisplayName().getString();
                String label = (containsCurrent ? "✔ " : "  ") + stName + "  ×" + group.size();
                int maxLabelW = iconArea - 12;
                if (font.width(label) > maxLabelW) {
                    label = font.plainSubstrByWidth(label, maxLabelW - 6) + "…";
                }
                g.text(font, label, px + 5, rowY + 4,
                        containsCurrent ? 0xFFA6E3A1 : (isHovered ? 0xFFFFFFFF : 0xFFCDD6F4), true);

                // カテゴリを代表する触媒アイコン
                ItemStack stIcon = rep.getStation().getIcon();
                if (stIcon != null && !stIcon.isEmpty()) {
                    g.item(stIcon, px + panelW - 36, rowY);
                }
            } else {
                // ステージ2: 選択カテゴリ内のレシピ一覧
                List<Integer> group = stationPopupGroups.get(stationPopupGroup);
                if (group == null || rowIdx >= group.size()) continue;
                int globalIdx = group.get(rowIdx);
                PlannedRecipe alt = stationPopupNode.alternativeRecipes.get(globalIdx);
                boolean isCur = (globalIdx == stationPopupNode.selectedRecipeIndex);
                boolean notExecutable = (alt.getRecipeHolder() == null);
                String stName = alt.getStation().getDisplayName().getString();
                String catName = alt.getCategoryTitle().getString();
                String label = (isCur ? "✔ " : "  ") + stName
                        + (notExecutable ? tr("gui.nexcrafttree.popup.no_exec") : "")
                        + (catName.equals(stName) ? "" : " (" + catName + ")");
                int maxLabelW = iconArea - 12;
                if (font.width(label) > maxLabelW) {
                    label = font.plainSubstrByWidth(label, maxLabelW - 6) + "…";
                }
                int labelColor = isCur ? 0xFFA6E3A1
                        : notExecutable ? 0xFF6C7086
                        : (isHovered ? 0xFFFFFFFF : 0xFFCDD6F4);
                g.text(font, label, px + 5, rowY + 4, labelColor, true);

                // 材料プレビューアイコン（先頭3種）で候補を識別しやすく
                List<ItemStack> previews = new ArrayList<>(3);
                for (Ingredient ing : alt.getIngredients()) {
                    if (previews.size() >= 3) break;
                    try {
                        List<ItemStack> options = ItemMatchHelper.ingredientStacks(ing);
                        if (options != null && !options.isEmpty() && options.get(0) != null && !options.get(0).isEmpty()) {
                            previews.add(options.get(0));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                for (int pi = 0; pi < previews.size(); pi++) {
                    g.item(previews.get(pi), px + panelW - 54 + pi * 18, rowY);
                }
            }
        }
        g.disableScissor();
    }

    /** 作成アクション実行：手持ち＆RSストレージから直接材料を引いて中間クラフトを含め全自動直接作成 */
    public void executeCraft(CraftingTreeNode node) {
        if (node == null) return;
        if (node.missingAmount > 0) {
            this.statusFeedback = tr("gui.nexcrafttree.status.cannot_missing");
            this.statusFeedbackColor = 0xFFF38BA8;
            return;
        }
        List<DirectCraftStep> steps = new ArrayList<>();
        collectDirectCraftSteps(node, steps);
        if (steps.isEmpty()) {
            this.statusFeedback = tr("gui.nexcrafttree.status.no_steps");
            this.statusFeedbackColor = 0xFFF9E2AF;
            return;
        }
        // 送信前検査: エンコード上限を超えるとパケットエンコード失敗で切断されるため、ここで拒否する
        boolean tooLarge = steps.size() > 512;
        if (!tooLarge) {
            for (DirectCraftStep step : steps) {
                if (step.inputs() != null && step.inputs().size() > DirectCraftStep.MAX_INPUTS) {
                    tooLarge = true;
                    break;
                }
            }
        }
        if (tooLarge) {
            this.statusFeedback = tr("gui.nexcrafttree.status.too_many_steps", steps.size());
            this.statusFeedbackColor = 0xFFF38BA8;
            return;
        }
        this.statusFeedback = tr("gui.nexcrafttree.status.crafting");
        this.statusFeedbackColor = 0xFFF9E2AF;
        NexCraftTreeNetwork.sendDirectCraftRequest(node.item, (int) node.requiredAmount, steps, slottedWorkstation);
    }

    private void collectDirectCraftSteps(CraftingTreeNode node, List<DirectCraftStep> steps) {
        for (CraftingTreeNode child : node.children) {
            collectDirectCraftSteps(child, steps);
        }
        if (node.toCraftAmount > 0 && node.recipe != null) {
            long perCraft = 1;
            ItemStack expectedOutput = ItemStack.EMPTY;
            try {
                if (Minecraft.getInstance().level != null) {
                    ItemStack out = RecipeResolver.resolveRecipeOutput(node.recipe.value(), Minecraft.getInstance().level);
                    if (out != null && !out.isEmpty()) {
                        perCraft = Math.max(1, out.getCount());
                        expectedOutput = out.copyWithCount((int) Math.min(64, perCraft));
                    }
                }
            } catch (Throwable ignored) {
            }
            long executions = (node.toCraftAmount + perCraft - 1) / perCraft;
            if (expectedOutput.isEmpty()) {
                // レシピ出力が取得不能な機械レシピ等: 計画値から期待出力を組み立てる
                long perOut = Math.max(1, executions > 0 ? node.toCraftAmount / executions : node.toCraftAmount);
                expectedOutput = node.item.copyWithCount((int) Math.max(1, perOut));
            }

            // 1実行あたりの材料（子ノードの総必要数をクラフト回数で割る）
            Map<Item, Integer> perExec = new LinkedHashMap<>();
            for (CraftingTreeNode child : node.children) {
                if (child == null || child.item == null || child.item.isEmpty()) continue;
                long per = executions > 0 ? Math.round(child.requiredAmount / (double) executions) : child.requiredAmount;
                if (per <= 0) per = 1;
                perExec.merge(child.item.getItem(), (int) Math.min(64, per), Integer::sum);
            }
            List<ItemStack> inputs = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : perExec.entrySet()) {
                inputs.add(new ItemStack(e.getKey(), Math.max(1, e.getValue())));
            }

            ItemStack stationIcon = (node.station != null) ? node.station.getIcon() : ItemStack.EMPTY;
            steps.add(new DirectCraftStep(node.recipe.id().identifier(), (int) executions, stationIcon, expectedOutput, inputs));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // 加工法ポップアップ: 最優先で処理
        if (stationPopupNode != null) {
            int headerH = popupHeaderH();
            int panelH = headerH + Math.min(popupCurrentRowCount(), POPUP_MAX_ROWS) * POPUP_ROW_H + 8;
            int listTop = stationPopupY + 4 + headerH;
            boolean insidePanel = mouseX >= stationPopupX && mouseX <= stationPopupX + POPUP_WIDTH
                    && mouseY >= stationPopupY && mouseY <= stationPopupY + panelH;
            if (insidePanel) {
                if (stationPopupStage == 1 && mouseY < listTop && stationPopupHover == -2) {
                    // 戻る: カテゴリ一覧へ
                    stationPopupStage = 0;
                    stationPopupGroup = -1;
                    stationPopupScroll = 0;
                    return true;
                }
                if (button == 0 && stationPopupHover >= 0) {
                    int rowIdx = stationPopupHover;
                    CraftingTreeNode node = stationPopupNode;
                    if (stationPopupStage == 0) {
                        // カテゴリ選択 → レシピ一覧へ
                        if (rowIdx >= 0 && rowIdx < stationPopupGroups.size()) {
                            stationPopupStage = 1;
                            stationPopupGroup = rowIdx;
                            stationPopupScroll = 0;
                        }
                    } else {
                        // レシピ選択
                        List<Integer> group = (stationPopupGroup >= 0 && stationPopupGroup < stationPopupGroups.size())
                                ? stationPopupGroups.get(stationPopupGroup) : null;
                        if (group != null && rowIdx < group.size()) {
                            int globalIdx = group.get(rowIdx);
                            closeStationPopup();
                            selectAlternative(node, globalIdx);
                        }
                    }
                }
            } else {
                closeStationPopup();
            }
            return true;
        }

        int winWidth = Math.min(width - 20, 520);
        int winHeight = Math.min(height - 20, 300);
        int winX = (width - winWidth) / 2;
        int winY = (height - winHeight) / 2;
        int headerH = 34;
        int footerH = 30;
        int contentX = winX + 6;
        int contentY = winY + headerH;
        int contentW = winWidth - 12;
        int contentH = winHeight - headerH - footerH;
        int rowH = showItemNames ? 22 : 18;
        int indentStep = showItemNames ? 18 : 14;

        // 数量入力欄のフォーカスおよびクリック処理
        if (amountField != null) {
            int fx = amountField.getX();
            int fy = amountField.getY();
            int fw = amountField.getWidth();
            int fh = amountField.getHeight();
            boolean clickedInside = mouseX >= fx - 2 && mouseX <= fx + fw + 2 && mouseY >= fy - 2 && mouseY <= fy + fh + 2;
            if (clickedInside) {
                amountField.setFocused(true);
                setFocused(amountField);
                return amountField.mouseClicked(event, doubleClick);
            } else if (amountField.isFocused()) {
                amountField.setFocused(false);
                commitAmountField();
            }
        }

        // スクロールバーのドラッグ開始
        if (button == 0 && maxScroll > 0) {
            int sbX = contentX + contentW - 6;
            if (mouseX >= sbX - 4 && mouseX <= sbX + 8 && mouseY >= contentY && mouseY <= contentY + contentH) {
                isDraggingScrollbar = true;
                dragStartMouseY = mouseY;
                dragStartScrollOffset = scrollOffset;
                return true;
            }
        }

        // 作業台・設備スロットクリック
        int outSlotX = winX + winWidth - 156;
        int outSlotY = winY + winHeight - footerH + 4;
        int outSlotSize = 22;
        int wsSlotSize = 22;
        int wsSlotX = outSlotX - wsSlotSize - 32;
        int wsSlotY = outSlotY;
        if (mouseX >= wsSlotX && mouseX <= wsSlotX + wsSlotSize && mouseY >= wsSlotY && mouseY <= wsSlotY + wsSlotSize) {
            Minecraft mc = Minecraft.getInstance();
            ItemStack carried = (mc.player != null && mc.player.containerMenu != null) ? mc.player.containerMenu.getCarried() : ItemStack.EMPTY;
            if (button == 0) {
                if (carried != null && !carried.isEmpty()) {
                    this.slottedWorkstation = carried.copyWithCount(1);
                    recompute();
                    return true;
                } else if (!slottedWorkstation.isEmpty()) {
                    this.slottedWorkstation = ItemStack.EMPTY;
                    recompute();
                    return true;
                } else {
                    cycleWorkstationFromPlayer();
                    return true;
                }
            } else if (button == 1) {
                if (!slottedWorkstation.isEmpty()) {
                    RecipeViewerIntegration.showUsage(slottedWorkstation);
                    return true;
                }
            }
            return true;
        }

        // 完成品スロットクリック（左クリック: インベントリへ回収, 右クリック: 用途表示）
        if (mouseX >= outSlotX && mouseX <= outSlotX + outSlotSize && mouseY >= outSlotY && mouseY <= outSlotY + outSlotSize) {
            if (!outputSlotStack.isEmpty()) {
                if (button == 0) {
                    NexCraftTreeNetwork.sendTakeOutputRequest();
                    return true;
                } else if (button == 1) {
                    RecipeViewerIntegration.showUsage(outputSlotStack);
                    return true;
                }
            }
            return true;
        }

        // ヘッダーターゲットアイコンクリック（左クリック: レシピ, 右クリック: 用途）
        if (mouseX >= winX + 9 && mouseX <= winX + 25 && mouseY >= winY + 9 && mouseY <= winY + 25 && !targetItem.isEmpty()) {
            if (button == 0) {
                RecipeViewerIntegration.showRecipe(targetItem);
                return true;
            } else if (button == 1) {
                RecipeViewerIntegration.showUsage(targetItem);
                return true;
            }
        }

        // ツリー行クリック処理（ズーム＆スクロール座標変換対応。判定はローカル座標で描画側と一致させる）
        double gateMouseX = contentX + (mouseX - contentX) / zoomScale;
        if (gateMouseX >= contentX && gateMouseX <= contentX + contentW - 10 && mouseY >= contentY && mouseY <= contentY + contentH) {
            double localMouseX = gateMouseX;
            double localMouseY = contentY + (mouseY - contentY) / zoomScale;
            double virtualScrollOffset = scrollOffset / zoomScale;
            int treeBaseX = contentX + 6;

            for (int i = 0; i < rows.size(); i++) {
                TreeNodeRow r = rows.get(i);
                int rY = (int) (contentY + i * rowH - virtualScrollOffset);
                if (localMouseY < rY || localMouseY >= rY + rowH) continue;

                int indent = r.depth * indentStep;
                int iconX = treeBaseX + indent;

                if (showItemNames) {
                    int iconY = rY + 3;
                    // アイテムアイコンクリック: 左クリックでレシピ、右クリックで用途
                    if (localMouseX >= iconX && localMouseX <= iconX + 16 && localMouseY >= iconY && localMouseY <= iconY + 16) {
                        if (button == 0) {
                            RecipeViewerIntegration.showRecipe(r.node.item);
                            return true;
                        } else if (button == 1) {
                            RecipeViewerIntegration.showUsage(r.node.item);
                            return true;
                        }
                    }

                    // 設備 / レシピ切替ボックスクリック
                    int badgeW = 78;
                    int badgeX = contentX + contentW - 14 - badgeW;
                    boolean hasMultipleRecipes = (r.node.alternativeRecipes.size() > 1);
                    int stationBoxW = hasMultipleRecipes ? 34 : 20;
                    int stationBoxX = badgeX - stationBoxW - 4;
                    int stationBoxY = rY + 3;

                    if (localMouseX >= stationBoxX && localMouseX <= stationBoxX + stationBoxW
                            && localMouseY >= stationBoxY && localMouseY <= stationBoxY + 16) {
                        if (button == 0) {
                            if (hasMultipleRecipes) {
                                int absX = (int) (contentX + (stationBoxX - contentX) * zoomScale);
                                int absY = (int) (contentY + (stationBoxY - contentY) * zoomScale);
                                openStationPopup(r.node, absX, absY);
                                return true;
                            } else if (r.node.station != null && !r.node.station.getIcon().isEmpty()) {
                                RecipeViewerIntegration.showUsage(r.node.station.getIcon());
                                return true;
                            }
                        } else if (button == 1) {
                            if (r.node.station != null && !r.node.station.getIcon().isEmpty()) {
                                RecipeViewerIntegration.showUsage(r.node.station.getIcon());
                                return true;
                            }
                        }
                    }

                    // 作成バッジクリック
                    int badgeY = rY + 4;
                    if (localMouseX >= badgeX && localMouseX <= badgeX + badgeW && localMouseY >= badgeY && localMouseY <= badgeY + 14) {
                        if (r.node.toCraftAmount > 0) {
                            executeCraft(r.node);
                            return true;
                        }
                    }
                } else {
                    // コンパクトモード: 72x16ノードタイル
                    int tileX = iconX;
                    int tileY = rY + 1;
                    int tileW = 72;
                    int tileH = 16;
                    boolean hasMultipleRecipes = (r.node.alternativeRecipes.size() > 1);
                    int stationPartX = tileX + 54;

                    if (localMouseX >= tileX && localMouseX <= tileX + tileW && localMouseY >= tileY && localMouseY <= tileY + tileH) {
                        if (localMouseX >= stationPartX) {
                            if (button == 0 && hasMultipleRecipes) {
                                int absX = (int) (contentX + (tileX - contentX) * zoomScale);
                                int absY = (int) (contentY + (tileY - contentY) * zoomScale);
                                openStationPopup(r.node, absX, absY);
                                return true;
                            } else if (r.node.station != null && !r.node.station.getIcon().isEmpty()) {
                                RecipeViewerIntegration.showUsage(r.node.station.getIcon());
                                return true;
                            }
                        } else if (button == 1) {
                            RecipeViewerIntegration.showUsage(r.node.item);
                            return true;
                        } else if (button == 0) {
                            if (localMouseX >= tileX + 17 && localMouseX < stationPartX && r.node.toCraftAmount > 0) {
                                executeCraft(r.node);
                                return true;
                            } else {
                                RecipeViewerIntegration.showRecipe(r.node.item);
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.amountField != null && this.amountField.isFocused()) {
            if (this.amountField.charTyped(event)) {
                return true;
            }
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        // ポップアップ: ESCで閉じる（画面自体は閉じない）
        if (stationPopupNode != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeStationPopup();
            return true;
        }

        // 数量入力欄にフォーカスがある場合
        if (this.amountField != null && this.amountField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.amountField.setFocused(false);
                commitAmountField();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.amountField.setFocused(false);
                commitAmountField();
                return true;
            }
            if (this.amountField.keyPressed(event) || this.amountField.canConsumeInput()) {
                return true;
            }
        }

        // クラフトツリー / JEI / REI レシピ・用途キー
        ItemStack hovered = getHoveredItemStack();
        if (hovered != null && !hovered.isEmpty()) {
            if (KeyInputHandler.OPEN_TREE.matches(event)) {
                KeyInputHandler.openTree(hovered.copy(), this);
                return true;
            }
            if (RecipeViewerIntegration.matchesRecipeKey(event)) {
                RecipeViewerIntegration.showRecipe(hovered);
                return true;
            }
            if (RecipeViewerIntegration.matchesUsageKey(event)) {
                RecipeViewerIntegration.showUsage(hovered);
                return true;
            }
        }

        // Nキー: アイテム名表示のON/OFF切り替え
        if (keyCode == GLFW.GLFW_KEY_N && !event.hasControlDown() && !event.hasShiftDown() && !event.hasAltDown()) {
            toggleItemNames();
            return true;
        }

        // ズームショートカット: Ctrl + '+' / Ctrl + '-' / Ctrl + '0'
        if (event.hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
                changeZoom(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
                changeZoom(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_0 || keyCode == GLFW.GLFW_KEY_KP_0) {
                resetZoom();
                return true;
            }
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // ポップアップ上でのホイール: 候補リスクロールを優先
        if (stationPopupNode != null) {
            int headerH = popupHeaderH();
            int panelH = headerH + Math.min(popupCurrentRowCount(), POPUP_MAX_ROWS) * POPUP_ROW_H + 8;
            if (mouseX >= stationPopupX && mouseX <= stationPopupX + POPUP_WIDTH
                    && mouseY >= stationPopupY && mouseY <= stationPopupY + panelH) {
                stationPopupScroll = Math.max(0, Math.min(stationPopupMaxScroll(), stationPopupScroll - scrollY * POPUP_ROW_H));
                return true;
            }
            closeStationPopup();
        }

        // 数量入力欄周辺でのマウスホイールスクロール: 数量を直接加減
        if (amountField != null && mouseX >= amountField.getX() - 20 && mouseX <= amountField.getX() + amountField.getWidth() + 20
                && mouseY >= amountField.getY() - 6 && mouseY <= amountField.getY() + amountField.getHeight() + 6) {
            changeQuantity(quantity + (scrollY > 0 ? 1 : -1));
            return true;
        }

        if (Minecraft.getInstance().hasControlDown()) {
            changeZoom(scrollY > 0 ? 1 : -1);
            return true;
        }
        scrollOffset -= scrollY * (24.0 / zoomScale);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            isDraggingScrollbar = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = event.y();
        if (isDraggingScrollbar && maxScroll > 0) {
            int winHeight = Math.min(height - 20, 300);
            int headerH = 34;
            int footerH = 30;
            int contentH = winHeight - headerH - footerH;
            double deltaY = mouseY - dragStartMouseY;
            double scrollRatio = (double) maxScroll / Math.max(1, contentH - 20);
            scrollOffset = dragStartScrollOffset + deltaY * scrollRatio;
            clampScroll();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    private void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public void onClose() {
        if (!outputSlotStack.isEmpty()) {
            NexCraftTreeNetwork.sendTakeOutputRequest();
        }
        if (this.parentScreen != null) {
            Minecraft.getInstance().setScreen(this.parentScreen);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}


