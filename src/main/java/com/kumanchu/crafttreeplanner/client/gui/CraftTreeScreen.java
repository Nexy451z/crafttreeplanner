package com.kumanchu.crafttreeplanner.client.gui;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.client.KeyInputHandler;
import com.kumanchu.crafttreeplanner.core.calculation.CraftingTreeNode;
import com.kumanchu.crafttreeplanner.core.calculation.PlannedRecipe;
import com.kumanchu.crafttreeplanner.core.calculation.ProcessingStation;
import com.kumanchu.crafttreeplanner.core.calculation.RecipeResolver;
import com.kumanchu.crafttreeplanner.core.stock.PlayerInventoryStock;
import com.kumanchu.crafttreeplanner.core.stock.UnifiedStockSnapshot;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import com.kumanchu.crafttreeplanner.integration.RecipeViewerIntegration;
import com.kumanchu.crafttreeplanner.integration.ae2.Ae2Stock;
import com.kumanchu.crafttreeplanner.integration.refinedstorage.RefinedStorageStock;
import com.kumanchu.crafttreeplanner.network.CraftTreeNetwork;
import com.kumanchu.crafttreeplanner.network.DirectCraftStep;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
public class CraftTreeScreen extends Screen {
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
    private static final int POPUP_ROW_H = 16;
    private static final int POPUP_MAX_ROWS = 10;
    private static final int POPUP_WIDTH = 186;

    private void openStationPopup(CraftingTreeNode node, int anchorX, int anchorY) {
        stationPopupNode = node;
        stationPopupScroll = 0;
        stationPopupHover = -1;
        int visible = Math.min(node.alternativeRecipes.size(), POPUP_MAX_ROWS);
        int panelH = visible * POPUP_ROW_H + 8;
        int px = anchorX + 18;
        if (px + POPUP_WIDTH > width - 4) {
            px = Math.max(4, anchorX - POPUP_WIDTH - 18);
        }
        int py = Math.max(4, Math.min(anchorY, height - panelH - 4));
        stationPopupX = px;
        stationPopupY = py;
    }

    private void closeStationPopup() {
        stationPopupNode = null;
        stationPopupHover = -1;
    }

    private int stationPopupMaxScroll() {
        if (stationPopupNode == null) return 0;
        int visible = Math.min(stationPopupNode.alternativeRecipes.size(), POPUP_MAX_ROWS);
        return Math.max(0, (stationPopupNode.alternativeRecipes.size() - visible) * POPUP_ROW_H);
    }

    private void selectAlternative(CraftingTreeNode node, int index) {
        if (node == null || node.alternativeRecipes.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        UnifiedStockSnapshot snapshot = new UnifiedStockSnapshot();
        snapshot.addProvider(new PlayerInventoryStock(mc.player));
        snapshot.addProvider(new RefinedStorageStock());
        snapshot.addProvider(new Ae2Stock());

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
            nameToggleBtn.setMessage(Component.literal(showItemNames ? tr("gui.crafttreeplanner.button.names_on") : tr("gui.crafttreeplanner.button.names_off")));
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

    public CraftTreeScreen(ItemStack target, @Nullable Screen parentScreen) {
        super(Component.translatable("gui.crafttreeplanner.tree"));
        this.parentScreen = parentScreen;
        this.targetItem = target.copy();
        this.quantity = Math.max(1, target.getCount());
        recompute();
    }

    public CraftTreeScreen(ItemStack target) {
        this(target, null);
    }

    public CraftTreeScreen(CraftingTreeNode root) {
        super(Component.translatable("gui.crafttreeplanner.tree"));
        this.parentScreen = null;
        this.root = root;
        this.targetItem = (root != null && root.item != null) ? root.item.copy() : ItemStack.EMPTY;
        this.quantity = (root != null) ? Math.max(1, root.requiredAmount) : 1;
        flatten();
    }

    /** JEI/REIなどの相互作用用に、現在マウスホバーしているアイテムを取得 */
    @Nullable
    public ItemStack getHoveredItemStack() {
        if (stationPopupNode != null && stationPopupHover >= 0
                && stationPopupHover < stationPopupNode.alternativeRecipes.size()) {
            PlannedRecipe alt = stationPopupNode.alternativeRecipes.get(stationPopupHover);
            if (alt.getStation() != null && !alt.getStation().getIcon().isEmpty()) {
                return alt.getStation().getIcon();
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
                this.statusFeedback = tr("gui.crafttreeplanner.status.done_click_take");
                this.statusFeedbackColor = 0xFFA6E3A1;
            } else {
                this.outputSlotSparkleTicks = 0;
                this.statusFeedback = tr("gui.crafttreeplanner.status.done_collected");
                this.statusFeedbackColor = 0xFFA6E3A1;
            }
            recompute();
        } else {
            this.statusFeedback = (message != null && !message.getString().isEmpty()) ? message.getString() : tr("gui.crafttreeplanner.status.failed");
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

    private void setQuantityDirect(long newQty) {
        long clamped = Math.max(1, Math.min(99999, newQty));
        if (this.quantity != clamped) {
            this.quantity = clamped;
            recompute();
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
        amountField = new EditBox(font, x, y, w, h, Component.translatable("gui.crafttreeplanner.amount.label"));
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

        UnifiedStockSnapshot snapshot = new UnifiedStockSnapshot();
        snapshot.addProvider(new PlayerInventoryStock(mc.player));
        snapshot.addProvider(new RefinedStorageStock());
        snapshot.addProvider(new Ae2Stock());

        this.root = new RecipeResolver().resolve(targetItem, quantity, snapshot, mc.level, slottedWorkstation);
        flatten();
        updateMaxScroll();
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
        for (ItemStack invStack : mc.player.getInventory().items) {
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

        // 1. アイテム名表示切り替えボタン (名前:ON / 名前:OFF)
        curRight -= 48;
        nameToggleBtn = addRenderableWidget(Button.builder(Component.literal(showItemNames ? tr("gui.crafttreeplanner.button.names_on") : tr("gui.crafttreeplanner.button.names_off")), b -> toggleItemNames())
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
        addRenderableWidget(Button.builder(Component.literal(tr("gui.crafttreeplanner.button.create")), b -> {
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
        addRenderableWidget(Button.builder(Component.literal(tr("gui.crafttreeplanner.button.craft_all")), b -> {
            if (root != null) executeCraft(root);
        }).bounds(winX + winWidth - 124, winY + winHeight - 25, 58, 19).build());

        // 閉じるボタン
        addRenderableWidget(Button.builder(Component.literal(tr("gui.crafttreeplanner.button.close")), b -> onClose())
                .bounds(winX + winWidth - 62, winY + winHeight - 25, 52, 19).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
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
            g.renderFakeItem(targetItem, winX + 9, winY + 9);
            g.renderItemDecorations(font, targetItem, winX + 9, winY + 9, String.valueOf(quantity));

            String titleStr = targetItem.getHoverName().getString();
            int maxTitleW = Math.max(60, (amountField != null ? amountField.getX() - 24 : winX + winWidth - 325) - (winX + 30));
            if (font.width(titleStr) > maxTitleW) {
                titleStr = font.plainSubstrByWidth(titleStr, maxTitleW - 6) + "…";
            }
            g.drawString(font, titleStr, winX + 30, winY + 13, 0xFFFFFFFF, true);
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

        g.pose().pushPose();
        g.pose().translate(contentX, contentY, 0);
        g.pose().scale(zoomScale, zoomScale, 1.0f);
        g.pose().translate(-contentX, -contentY, 0);

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
                g.renderFakeItem(r.node.item, iconX, iconY);
                g.renderItemDecorations(font, r.node.item, iconX, iconY, String.valueOf(r.node.requiredAmount));

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
                g.drawString(font, name, nameX, rY + 7, 0xFFCDD6F4, true);

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
                g.renderFakeItem(stIcon, stationBoxX + 2, stationBoxY);

                if (hasMultipleRecipes) {
                    g.drawString(font, "⇄", stationBoxX + 20, stationBoxY + 4, isStationHovered ? 0xFFFFFFFF : 0xFF89B4FA, false);
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
                    badgeText = tr("gui.crafttreeplanner.badge.missing", r.node.missingAmount);
                } else if (r.node.toCraftAmount > 0) {
                    badgeBg = isBadgeHovered ? 0x66F9E2AF : 0x33F9E2AF;
                    badgeBorder = isBadgeHovered ? 0xFFF9E2AF : 0x88F9E2AF;
                    badgeFg = 0xFFF9E2AF;
                    if (r.node.storedAmount > 0) {
                        badgeText = tr("gui.crafttreeplanner.badge.craft_with_stock", r.node.toCraftAmount, r.node.storedAmount);
                    } else {
                        badgeText = r.node.autocraftable ? tr("gui.crafttreeplanner.badge.auto", r.node.toCraftAmount)
                                : tr("gui.crafttreeplanner.badge.craft", r.node.toCraftAmount);
                    }
                } else {
                    badgeBg = 0x33A6E3A1;
                    badgeBorder = 0x88A6E3A1;
                    badgeFg = 0xFFA6E3A1;
                    if (r.node.totalStockAmount > r.node.storedAmount) {
                        badgeText = tr("gui.crafttreeplanner.badge.stock", r.node.storedAmount, r.node.totalStockAmount);
                    } else {
                        badgeText = tr("gui.crafttreeplanner.badge.stock_short", r.node.storedAmount);
                    }
                }
                if (r.node.cutByCycle) badgeText += " " + tr("gui.crafttreeplanner.suffix.cycle");
                else if (r.node.cutByLimit) badgeText += " " + tr("gui.crafttreeplanner.suffix.limit");

                int badgeY = rY + 4;
                int badgeH = 14;
                g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeBg);
                drawBorder(g, badgeX, badgeY, badgeW, badgeH, badgeBorder);
                g.drawCenteredString(font, badgeText, badgeX + badgeW / 2, badgeY + 3, badgeFg);
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
                    miniText = tr("gui.crafttreeplanner.badge.mini_missing", r.node.missingAmount);
                } else if (r.node.toCraftAmount > 0) {
                    statusColor = 0xFFF9E2AF;
                    badgeBg = isTileHovered ? 0x88F9E2AF : 0x44F9E2AF;
                    badgeBorder = isTileHovered ? 0xFFF9E2AF : 0x88F9E2AF;
                    badgeFg = 0xFFF9E2AF;
                    miniText = tr("gui.crafttreeplanner.badge.mini_craft", r.node.toCraftAmount);
                } else {
                    statusColor = 0xFFA6E3A1;
                    badgeBg = 0x44A6E3A1;
                    badgeBorder = 0x88A6E3A1;
                    badgeFg = 0xFFA6E3A1;
                    miniText = tr("gui.crafttreeplanner.badge.mini_stock", r.node.storedAmount);
                }

                // タイル背景とステータスボーダー
                g.fill(tileX, tileY, tileX + tileW, tileY + tileH, isTileHovered ? 0xF22A2B3D : 0xF2181825);
                drawBorder(g, tileX, tileY, tileW, tileH, isTileHovered ? 0xFFFFFFFF : statusColor);

                // アイコン描画
                g.renderFakeItem(r.node.item, tileX, tileY);
                g.renderItemDecorations(font, r.node.item, tileX, tileY, String.valueOf(r.node.requiredAmount));

                // ミニステータスバッジ
                int miniX = tileX + 17;
                int miniY = tileY + 2;
                int miniW = 34;
                int miniH = 12;
                g.fill(miniX, miniY, miniX + miniW, miniY + miniH, badgeBg);
                drawBorder(g, miniX, miniY, miniW, miniH, badgeBorder);
                g.drawCenteredString(font, miniText, miniX + miniW / 2, miniY + 2, badgeFg);

                // 設備アイコン描画
                ItemStack stIcon = (r.node.station != null && !r.node.station.getIcon().isEmpty()) ?
                        r.node.station.getIcon() : new ItemStack(Items.CRAFTING_TABLE);
                g.renderFakeItem(stIcon, stationPartX, tileY);
                if (hasMultipleRecipes) {
                    g.drawString(font, "⇄", stationPartX + 10, tileY, isStationHovered ? 0xFFFFFFFF : 0xFF89B4FA, false);
                }
            }
        }

        g.pose().popPose();
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
        String wsLabel = tr("gui.crafttreeplanner.slot.workstation");
        String outLabel = tr("gui.crafttreeplanner.slot.output");
        if (!slottedWorkstation.isEmpty()) {
            g.renderFakeItem(slottedWorkstation, wsSlotX + 3, wsSlotY + 3);
            g.renderItemDecorations(font, slottedWorkstation, wsSlotX + 3, wsSlotY + 3);
        } else {
            g.drawCenteredString(font, tr("gui.crafttreeplanner.slot.workstation_ph"), wsSlotX + 11, wsSlotY + 7, 0x44CDD6F4);
        }
        g.drawString(font, wsLabel, wsSlotX - font.width(wsLabel) - 4, footerTextY, 0xFFA6ADC8, true);

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
            g.renderFakeItem(outputSlotStack, outSlotX + 3, outSlotY + 3);
            g.renderItemDecorations(font, outputSlotStack, outSlotX + 3, outSlotY + 3, String.valueOf(outputSlotStack.getCount()));
        } else {
            g.drawCenteredString(font, tr("gui.crafttreeplanner.slot.output_ph"), outSlotX + 11, outSlotY + 7, 0x44CDD6F4);
        }

        // 完成品ラベル
        g.drawString(font, outLabel, outSlotX - font.width(outLabel) - 4, footerTextY, 0xFFA6ADC8, true);

        // 左側ステータステキスト
        int maxTextW = wsSlotX - font.width(wsLabel) - 16 - (winX + 12);
        if (statusFeedback != null && !statusFeedback.isEmpty()) {
            String txt = statusFeedback;
            if (font.width(txt) > maxTextW) {
                txt = font.plainSubstrByWidth(txt, maxTextW - 6) + "…";
            }
            g.drawString(font, txt, winX + 12, footerTextY, statusFeedbackColor, true);
        } else if (totalMissingCount == 0) {
            g.drawString(font, tr("gui.crafttreeplanner.status.ready"), winX + 12, footerTextY, 0xFFA6E3A1, true);
        } else {
            String sumText = tr("gui.crafttreeplanner.status.missing_summary",
                    totalMissingKinds, totalMissingCount, totalCraftSteps);
            if (font.width(sumText) > maxTextW) {
                sumText = font.plainSubstrByWidth(sumText, maxTextW - 6) + "…";
            }
            g.drawString(font, sumText, winX + 12, footerTextY, 0xFFF38BA8, true);
        }

        // 7. ウィジェット描画（ボタン等）
        super.render(g, mouseX, mouseY, partial);

        // 8. ツールチップ描画（最前面）
        if (isHoveringWorkstationSlot) {
            List<Component> tooltip = new ArrayList<>();
            if (!slottedWorkstation.isEmpty()) {
                tooltip.add(slottedWorkstation.getHoverName());
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.priority", slottedWorkstation.getHoverName().getString()));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.desc"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.leftclick"));
                String usageKey = RecipeViewerIntegration.getUsageKeyName();
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.usage", usageKey));
            } else {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.empty.title"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.empty.desc"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.empty.click"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.workstation.empty.fallback"));
            }
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else if (isHoveringOutputSlot) {
            List<Component> tooltip = new ArrayList<>();
            if (!outputSlotStack.isEmpty()) {
                tooltip.add(outputSlotStack.getHoverName());
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.count", outputSlotStack.getCount()));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.take"));
                String usageKey = RecipeViewerIntegration.getUsageKeyName();
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.usage", usageKey));
            } else {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.empty.title"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.empty.desc"));
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.output.empty.take"));
            }
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else if (currentHoveredStationRow != null && stationPopupNode == null) {
            CraftingTreeNode node = currentHoveredStationRow.node;
            List<Component> tooltip = new ArrayList<>();
            ProcessingStation st = (node.station != null) ? node.station : ProcessingStation.CRAFTING_TABLE;
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.station.title", st.getDisplayName().getString()));
            if (st.getCategoryUid() != null && !st.getCategoryUid().isEmpty()) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.station.category", st.getCategoryUid()));
            }
            if (node.alternativeRecipes.size() > 1) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.station.switch", node.alternativeRecipes.size()));
                for (int ai = 0; ai < node.alternativeRecipes.size(); ai++) {
                    PlannedRecipe alt = node.alternativeRecipes.get(ai);
                    boolean isCur = (ai == node.selectedRecipeIndex);
                    String prefix = isCur ? " §a✔ " : " §7 - ";
                    tooltip.add(Component.literal(prefix + alt.getStation().getDisplayName().getString() + " (" + alt.getCategoryTitle().getString() + ")"));
                }
            } else {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.station.single"));
            }
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.station.usage", usageKey));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else if (currentHoveredRow != null) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(currentHoveredRow.node.item.getHoverName());
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.required", currentHoveredRow.node.requiredAmount));
            if (currentHoveredRow.node.storedAmount > 0) {
                if (currentHoveredRow.node.totalStockAmount > currentHoveredRow.node.storedAmount) {
                    tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.stock_allocated",
                            currentHoveredRow.node.totalStockAmount, currentHoveredRow.node.storedAmount));
                } else {
                    tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.stock", currentHoveredRow.node.storedAmount));
                }
            } else if (currentHoveredRow.node.totalStockAmount > 0) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.stock", currentHoveredRow.node.totalStockAmount));
            }
            if (currentHoveredRow.node.toCraftAmount > 0) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.tocraft", currentHoveredRow.node.toCraftAmount));
            }
            if (currentHoveredRow.node.missingAmount > 0) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.missing", currentHoveredRow.node.missingAmount));
            }
            if (currentHoveredRow.node.autocraftable) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.autocraft"));
            }
            if (currentHoveredRow.node.cutByCycle) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.cycle"));
            } else if (currentHoveredRow.node.cutByLimit) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.limit"));
            }

            if (currentHoveredRow.node.toCraftAmount > 0) {
                tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.actions"));
            }
            String recipeKey = RecipeViewerIntegration.getRecipeKeyName();
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.keys", recipeKey, usageKey));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else if (amountField != null && mouseX >= amountField.getX() - 2 && mouseX <= amountField.getX() + amountField.getWidth() + 2
                && mouseY >= amountField.getY() - 2 && mouseY <= amountField.getY() + amountField.getHeight() + 2) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.amount.title", quantity));
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.amount.desc"));
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.amount.hints"));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else if (isHoveringTargetIcon && !targetItem.isEmpty()) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(targetItem.getHoverName());
            String recipeKey = RecipeViewerIntegration.getRecipeKeyName();
            String usageKey = RecipeViewerIntegration.getUsageKeyName();
            tooltip.add(Component.translatable("gui.crafttreeplanner.tt.row.keys", recipeKey, usageKey));
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }

        // 9. 加工法ポップアップ（全ウィジェット・ツールチップより最前面）
        renderStationPopup(g, mouseX, mouseY);
    }

    private void renderStationPopup(GuiGraphics g, int mouseX, int mouseY) {
        if (stationPopupNode == null || stationPopupNode.alternativeRecipes.isEmpty()) {
            stationPopupNode = null;
            return;
        }
        List<PlannedRecipe> alts = stationPopupNode.alternativeRecipes;
        int visible = Math.min(alts.size(), POPUP_MAX_ROWS);
        int panelW = POPUP_WIDTH;
        int panelH = visible * POPUP_ROW_H + 8;
        int px = stationPopupX;
        int py = stationPopupY;

        // ホバー行検出（描画と同時に行う）
        stationPopupHover = -1;
        if (mouseX >= px && mouseX <= px + panelW && mouseY >= py + 4 && mouseY < py + 4 + visible * POPUP_ROW_H) {
            int rowIdx = (mouseY - (py + 4)) / POPUP_ROW_H;
            int idx = firstPopupRow() + rowIdx;
            if (idx >= 0 && idx < alts.size()) {
                stationPopupHover = idx;
            }
        }

        g.fill(px, py, px + panelW, py + panelH, 0xF81E1E2E);
        drawBorder(g, px, py, panelW, panelH, 0xFF89B4FA);

        g.enableScissor(px + 1, py + 1, px + panelW - 1, py + panelH - 1);
        int iconArea = POPUP_WIDTH - 54;
        for (int i = 0; i < visible; i++) {
            int idx = firstPopupRow() + i;
            if (idx < 0 || idx >= alts.size()) continue;
            PlannedRecipe alt = alts.get(idx);
            int rowY = py + 4 + i * POPUP_ROW_H;
            boolean isHovered = (stationPopupHover == idx);
            if (isHovered) {
                g.fill(px + 2, rowY, px + panelW - 2, rowY + POPUP_ROW_H, 0x3389B4FA);
            }

            boolean isCur = (idx == stationPopupNode.selectedRecipeIndex);
            String stName = alt.getStation().getDisplayName().getString();
            String catName = alt.getCategoryTitle().getString();
            String label = (isCur ? "✔ " : "  ") + stName
                    + (catName.equals(stName) ? "" : " (" + catName + ")");
            int maxLabelW = iconArea - 12;
            if (font.width(label) > maxLabelW) {
                label = font.plainSubstrByWidth(label, maxLabelW - 6) + "…";
            }
            g.drawString(font, label, px + 5, rowY + 4,
                    isCur ? 0xFFA6E3A1 : (isHovered ? 0xFFFFFFFF : 0xFFCDD6F4), true);

            // 材料プレビューアイコン（先頭3種）で候補を識別しやすく
            List<ItemStack> previews = new ArrayList<>(3);
            for (Ingredient ing : alt.getIngredients()) {
                if (previews.size() >= 3) break;
                try {
                    ItemStack[] options = ing.getItems();
                    if (options != null && options.length > 0 && options[0] != null && !options[0].isEmpty()) {
                        previews.add(options[0]);
                    }
                } catch (Throwable ignored) {
                }
            }
            for (int pi = 0; pi < previews.size(); pi++) {
                g.renderFakeItem(previews.get(pi), px + panelW - 54 + pi * 18, rowY);
            }
        }
        g.disableScissor();
    }

    private int firstPopupRow() {
        int first = (int) (stationPopupScroll / POPUP_ROW_H);
        int visible = Math.min(stationPopupNode == null ? 0 : stationPopupNode.alternativeRecipes.size(), POPUP_MAX_ROWS);
        int maxFirst = Math.max(0, (stationPopupNode == null ? 0 : stationPopupNode.alternativeRecipes.size()) - visible);
        return Math.max(0, Math.min(first, maxFirst));
    }

    /** 作成アクション実行：手持ち＆RSストレージから直接材料を引いて中間クラフトを含め全自動直接作成 */
    public void executeCraft(CraftingTreeNode node) {
        if (node == null) return;
        if (node.missingAmount > 0) {
            this.statusFeedback = tr("gui.crafttreeplanner.status.cannot_missing");
            this.statusFeedbackColor = 0xFFF38BA8;
            return;
        }
        List<DirectCraftStep> steps = new ArrayList<>();
        collectDirectCraftSteps(node, steps);
        if (steps.isEmpty()) {
            this.statusFeedback = tr("gui.crafttreeplanner.status.no_steps");
            this.statusFeedbackColor = 0xFFF9E2AF;
            return;
        }
        this.statusFeedback = tr("gui.crafttreeplanner.status.crafting");
        this.statusFeedbackColor = 0xFFF9E2AF;
        CraftTreeNetwork.sendDirectCraftRequest(node.item, (int) node.requiredAmount, steps, slottedWorkstation);
    }

    private void collectDirectCraftSteps(CraftingTreeNode node, List<DirectCraftStep> steps) {
        for (CraftingTreeNode child : node.children) {
            collectDirectCraftSteps(child, steps);
        }
        if (node.toCraftAmount > 0 && node.recipe != null) {
            long perCraft = 1;
            try {
                if (Minecraft.getInstance().level != null) {
                    ItemStack out = node.recipe.value().getResultItem(Minecraft.getInstance().level.registryAccess());
                    perCraft = Math.max(1, out.getCount());
                }
            } catch (Throwable ignored) {
            }
            long executions = (node.toCraftAmount + perCraft - 1) / perCraft;
            ItemStack stationIcon = (node.station != null) ? node.station.getIcon() : ItemStack.EMPTY;
            steps.add(new DirectCraftStep(node.recipe.id(), (int) executions, stationIcon));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 加工法ポップアップ: 最優先で処理
        if (stationPopupNode != null) {
            int panelH = Math.min(stationPopupNode.alternativeRecipes.size(), POPUP_MAX_ROWS) * POPUP_ROW_H + 8;
            boolean insidePanel = mouseX >= stationPopupX && mouseX <= stationPopupX + POPUP_WIDTH
                    && mouseY >= stationPopupY && mouseY <= stationPopupY + panelH;
            if (insidePanel && button == 0 && stationPopupHover >= 0) {
                int idx = stationPopupHover;
                CraftingTreeNode node = stationPopupNode;
                closeStationPopup();
                selectAlternative(node, idx);
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
                return amountField.mouseClicked(mouseX, mouseY, button);
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
                    CraftTreeNetwork.sendTakeOutputRequest();
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

        // ツリー行クリック処理（ズーム＆スクロール座標変換対応）
        if (mouseX >= contentX && mouseX <= contentX + contentW - 10 && mouseY >= contentY && mouseY <= contentY + contentH) {
            double localMouseX = contentX + (mouseX - contentX) / zoomScale;
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

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.amountField != null && this.amountField.isFocused()) {
            if (this.amountField.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
            if (this.amountField.keyPressed(keyCode, scanCode, modifiers) || this.amountField.canConsumeInput()) {
                return true;
            }
        }

        // クラフトツリー / JEI / REI レシピ・用途キー
        ItemStack hovered = getHoveredItemStack();
        if (hovered != null && !hovered.isEmpty()) {
            if (KeyInputHandler.OPEN_TREE.matches(keyCode, scanCode)) {
                KeyInputHandler.openTree(hovered.copy(), this);
                return true;
            }
            if (RecipeViewerIntegration.matchesRecipeKey(keyCode, scanCode)) {
                RecipeViewerIntegration.showRecipe(hovered);
                return true;
            }
            if (RecipeViewerIntegration.matchesUsageKey(keyCode, scanCode)) {
                RecipeViewerIntegration.showUsage(hovered);
                return true;
            }
        }

        // Nキー: アイテム名表示のON/OFF切り替え
        if (keyCode == GLFW.GLFW_KEY_N && !hasControlDown() && !hasShiftDown() && !hasAltDown()) {
            toggleItemNames();
            return true;
        }

        // ズームショートカット: Ctrl + '+' / Ctrl + '-' / Ctrl + '0'
        if (hasControlDown()) {
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

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // ポップアップ上でのホイール: 候補リスクロールを優先
        if (stationPopupNode != null) {
            int panelH = Math.min(stationPopupNode.alternativeRecipes.size(), POPUP_MAX_ROWS) * POPUP_ROW_H + 8;
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

        if (hasControlDown()) {
            changeZoom(scrollY > 0 ? 1 : -1);
            return true;
        }
        scrollOffset -= scrollY * (24.0 / zoomScale);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDraggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public void onClose() {
        if (!outputSlotStack.isEmpty()) {
            CraftTreeNetwork.sendTakeOutputRequest();
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
