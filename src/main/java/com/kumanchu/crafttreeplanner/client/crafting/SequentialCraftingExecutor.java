package com.kumanchu.crafttreeplanner.client.crafting;

import com.kumanchu.crafttreeplanner.CraftTreePlanner;
import com.kumanchu.crafttreeplanner.core.calculation.CraftingTreeNode;
import com.kumanchu.crafttreeplanner.integration.ModIntegration;
import com.kumanchu.crafttreeplanner.integration.refinedstorage.RefinedStorageStock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * ツリー全体の自動クラフト実行エンジン。
 * レシピツリーをボトムアップ（葉ノードから根ノード）で走査し、
 * 中間アイテムのクラフト（レシピ転送 → 完成品取り出し）を1クリックで順番に自動実行する。
 */
public class SequentialCraftingExecutor {
    private static final Queue<CraftStep> queue = new ArrayDeque<>();
    private static State state = State.IDLE;
    private static int ticksWaiting = 0;
    private static int syncWaitTicks = 0;
    private static int totalSteps = 0;
    private static String finalTargetName = "";
    private static boolean registered = false;

    private static Slot pendingFallbackSlot = null;
    private static int pendingInventorySlot = -1;

    public static void init() {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(SequentialCraftingExecutor::onClientTick);
            registered = true;
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static void start(CraftingTreeNode node, Screen parentScreen) {
        init();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 1. 不足素材がある場合はクラフト不可
        if (hasMissing(node)) {
            mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.missing"));
            return;
        }

        // 2. クラフト手順をボトムアップ（末端から親ノードへ）で収集
        List<CraftStep> steps = new ArrayList<>();
        collectSteps(node, steps);
        if (steps.isEmpty()) {
            mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.no_steps"));
            return;
        }

        // 3. 作業台またはRSクラフトグリッドが開かれているか確認
        if (parentScreen != null) {
            mc.setScreen(parentScreen);
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || !isCraftingContainer(menu)) {
            mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.need_container"));
            return;
        }

        // 4. キュー構築と開始
        queue.clear();
        queue.addAll(steps);
        totalSteps = steps.size();
        finalTargetName = node.item.getHoverName().getString();
        pendingFallbackSlot = null;
        pendingInventorySlot = -1;
        syncWaitTicks = 0;
        clearCarriedItem(menu, mc);
        state = State.TRANSFER_RECIPE;
        ticksWaiting = 1;

        mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.started", totalSteps));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (state == State.IDLE || queue.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) {
            cancel(Component.translatable("msg.crafttreeplanner.seq.cancel_closed"));
            return;
        }

        if (ticksWaiting > 0) {
            ticksWaiting--;
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        CraftStep current = queue.peek();
        if (current == null) {
            state = State.IDLE;
            return;
        }

        switch (state) {
            case TRANSFER_RECIPE -> {
                clearCarriedItem(menu, mc);
                int currentStepIdx = totalSteps - queue.size() + 1;
                String itemName = current.target.getHoverName().getString();
                mc.gui.setOverlayMessage(Component.translatable("msg.crafttreeplanner.seq.progress", currentStepIdx, totalSteps, itemName), false);

                boolean transferred = false;
                // 1) Refined Storage Crafting Grid
                if (ModIntegration.isRefinedStorageLoaded()) {
                    try {
                        transferred = RefinedStorageStock.transferRecipeToCraftingGrid(menu, current.recipe);
                    } catch (Throwable ignored) {
                    }
                }
                // 2) Vanilla Crafting Table / Inventory
                if (!transferred && mc.gameMode != null) {
                    try {
                        mc.gameMode.handlePlaceRecipe(menu.containerId, current.recipe, false);
                        transferred = true;
                    } catch (Throwable ignored) {
                    }
                }

                // サーバー側パケット処理とグリッド同期を待機（同一Tickでの即時クリック乱打を防ぐ）
                syncWaitTicks = 0;
                state = State.WAIT_TRANSFER_SYNC;
                ticksWaiting = 2; // 2 tickサーバー同期待ち
            }
            case WAIT_TRANSFER_SYNC -> {
                int resultSlot = findResultSlot(menu);
                boolean hasResult = (resultSlot >= 0 && resultSlot < menu.slots.size() && menu.getSlot(resultSlot).hasItem());
                if (hasResult) {
                    clearCarriedItem(menu, mc);
                    state = State.CLICK_RESULT;
                    ticksWaiting = 0;
                    return;
                }

                syncWaitTicks++;
                if (syncWaitTicks < 6) {
                    // 最大6 tick（約0.3秒）までサーバーからの同期パケットを待機
                    ticksWaiting = 1;
                    return;
                }

                // 待機しても完成品が出ない場合、手動フォールバック配置へ移行
                prepareNextFallbackSlot(menu, current.recipe);
                if (pendingFallbackSlot != null && pendingInventorySlot != -1) {
                    state = State.FALLBACK_PLACE_PICKUP;
                    ticksWaiting = 1;
                } else {
                    // フォールバック候補もなければクリック試行へ
                    state = State.CLICK_RESULT;
                    ticksWaiting = 1;
                }
            }
            case FALLBACK_PLACE_PICKUP -> {
                if (pendingFallbackSlot == null || pendingInventorySlot == -1) {
                    state = State.WAIT_TRANSFER_SYNC;
                    ticksWaiting = 1;
                    return;
                }
                if (mc.gameMode != null && menu.getCarried().isEmpty()) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, pendingInventorySlot, 0, ClickType.PICKUP, mc.player);
                }
                state = State.FALLBACK_PLACE_DROP;
                ticksWaiting = 1; // 1 tickあけてサーバー側でアイテム持ち上げを確定
            }
            case FALLBACK_PLACE_DROP -> {
                if (pendingFallbackSlot != null && mc.gameMode != null) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, pendingFallbackSlot.index, 0, ClickType.PICKUP, mc.player);
                }
                pendingFallbackSlot = null;
                pendingInventorySlot = -1;
                clearCarriedItem(menu, mc);
                state = State.WAIT_TRANSFER_SYNC;
                ticksWaiting = 1;
            }
            case CLICK_RESULT -> {
                clearCarriedItem(menu, mc);
                int resultSlot = findResultSlot(menu);
                if (mc.gameMode != null && resultSlot >= 0 && resultSlot < menu.slots.size() && menu.getSlot(resultSlot).hasItem()) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot, 0, ClickType.QUICK_MOVE, mc.player);
                    current.remainingCrafts--;
                    state = State.WAIT_RESULT_SYNC;
                    ticksWaiting = 2; // 2 tick（完成品受領待ち）
                } else {
                    cancel(Component.translatable("msg.crafttreeplanner.seq.cancel_transfer"));
                }
            }
            case WAIT_RESULT_SYNC -> {
                clearCarriedItem(menu, mc);
                if (current.remainingCrafts > 0) {
                    state = State.TRANSFER_RECIPE;
                    ticksWaiting = 1;
                } else {
                    queue.poll();
                    if (queue.isEmpty()) {
                        state = State.IDLE;
                        mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.done", finalTargetName));
                        try {
                            mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8f, 1.2f);
                        } catch (Throwable ignored) {
                        }
                    } else {
                        state = State.TRANSFER_RECIPE;
                        ticksWaiting = 2; // 次の工程へ移行する前に2 tick待機（中間素材のインベントリ同期を完全にする）
                    }
                }
            }
        }
    }

    private static void clearCarriedItem(AbstractContainerMenu menu, Minecraft mc) {
        if (menu == null || mc.player == null || mc.gameMode == null) return;
        try {
            if (!menu.getCarried().isEmpty()) {
                for (Slot s : menu.slots) {
                    if (s.container instanceof net.minecraft.world.entity.player.Inventory && !s.hasItem()) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.PICKUP, mc.player);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void prepareNextFallbackSlot(AbstractContainerMenu menu, RecipeHolder<?> recipe) {
        pendingFallbackSlot = null;
        pendingInventorySlot = -1;
        if (menu == null || recipe == null) return;

        List<Slot> matrixSlots = getCraftingMatrixSlots(menu);
        if (matrixSlots.isEmpty()) return;

        if (recipe.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            List<Ingredient> ingredients = shaped.getIngredients();
            for (int r = 0; r < height && r < 3; r++) {
                for (int c = 0; c < width && c < 3; c++) {
                    int idx = r * width + c;
                    int gridIdx = r * 3 + c;
                    if (idx < ingredients.size() && gridIdx < matrixSlots.size()) {
                        Slot mSlot = matrixSlots.get(gridIdx);
                        Ingredient ing = ingredients.get(idx);
                        if (ing != null && !ing.isEmpty() && !mSlot.hasItem()) {
                            int invSlot = findContainerSlotMatchingIngredient(menu, ing);
                            if (invSlot != -1) {
                                pendingFallbackSlot = mSlot;
                                pendingInventorySlot = invSlot;
                                return;
                            }
                        }
                    }
                }
            }
        } else {
            List<Ingredient> ingredients = recipe.value().getIngredients();
            for (int i = 0; i < ingredients.size() && i < matrixSlots.size(); i++) {
                Slot mSlot = matrixSlots.get(i);
                Ingredient ing = ingredients.get(i);
                if (ing != null && !ing.isEmpty() && !mSlot.hasItem()) {
                    int invSlot = findContainerSlotMatchingIngredient(menu, ing);
                    if (invSlot != -1) {
                        pendingFallbackSlot = mSlot;
                        pendingInventorySlot = invSlot;
                        return;
                    }
                }
            }
        }
    }

    private static int findContainerSlotMatchingIngredient(AbstractContainerMenu menu, Ingredient ing) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.hasItem()) {
                ItemStack stack = slot.getItem();
                if (ing.test(stack) || matchesAny(stack, ing)) {
                    return slot.index;
                }
            }
        }
        return -1;
    }

    private static boolean matchesAny(ItemStack stack, Ingredient ing) {
        for (ItemStack tmpl : ing.getItems()) {
            if (com.kumanchu.crafttreeplanner.core.ItemMatchHelper.isStockMatch(stack, tmpl)) return true;
        }
        return false;
    }

    public static List<Slot> getCraftingMatrixSlots(AbstractContainerMenu menu) {
        List<Slot> matrixSlots = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container != null && !(s instanceof net.minecraft.world.inventory.ResultSlot) && !s.getClass().getName().contains("Result")) {
                String cName = s.container.getClass().getName();
                if (cName.contains("Crafting") || cName.contains("Matrix")) {
                    matrixSlots.add(s);
                }
            }
        }
        return matrixSlots;
    }

    private static void cancel(Component reason) {
        state = State.IDLE;
        queue.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu != null) {
            clearCarriedItem(mc.player.containerMenu, mc);
        }
        if (mc.gui != null && mc.gui.getChat() != null) {
            mc.gui.getChat().addMessage(Component.translatable("msg.crafttreeplanner.seq.cancel_prefix", reason.getString()));
        }
    }

    private static boolean hasMissing(CraftingTreeNode node) {
        if (node == null) return false;
        if (node.missingAmount > 0) return true;
        for (CraftingTreeNode child : node.children) {
            if (hasMissing(child)) return true;
        }
        return false;
    }

    private static void collectSteps(CraftingTreeNode node, List<CraftStep> steps) {
        if (node == null) return;
        // 子ノード（末端の素材）を先にクラフトするボトムアップ順
        for (CraftingTreeNode child : node.children) {
            collectSteps(child, steps);
        }
        if (node.toCraftAmount > 0 && node.recipe != null) {
            int resultCount = 1;
            try {
                ItemStack res = node.recipe.value().getResultItem(Minecraft.getInstance().level.registryAccess());
                resultCount = Math.max(1, res.getCount());
            } catch (Throwable ignored) {
            }
            long times = (node.toCraftAmount + resultCount - 1) / resultCount;
            steps.add(new CraftStep(node.recipe, node.item, times));
        }
    }

    public static boolean isCraftingContainer(AbstractContainerMenu menu) {
        if (menu == null) return false;
        if (menu instanceof net.minecraft.world.inventory.CraftingMenu) return true;
        if (menu instanceof net.minecraft.world.inventory.InventoryMenu) return true;
        String name = menu.getClass().getName();
        if (name.contains("Crafting")) return true;
        for (Method m : menu.getClass().getMethods()) {
            if (m.getName().equals("transferRecipe") && m.getParameterCount() == 1) return true;
        }
        return false;
    }

    public static int findResultSlot(AbstractContainerMenu menu) {
        if (menu == null) return 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot instanceof net.minecraft.world.inventory.ResultSlot) {
                return i;
            }
            if (slot.getClass().getName().contains("Result")) {
                return i;
            }
        }
        return 0;
    }

    private enum State {
        IDLE,
        TRANSFER_RECIPE,
        WAIT_TRANSFER_SYNC,
        FALLBACK_PLACE_PICKUP,
        FALLBACK_PLACE_DROP,
        CLICK_RESULT,
        WAIT_RESULT_SYNC
    }

    public static class CraftStep {
        public final RecipeHolder<?> recipe;
        public final ItemStack target;
        public long remainingCrafts;

        public CraftStep(RecipeHolder<?> recipe, ItemStack target, long times) {
            this.recipe = recipe;
            this.target = target;
            this.remainingCrafts = Math.max(1, times);
        }
    }
}
