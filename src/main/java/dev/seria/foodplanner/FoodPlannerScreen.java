package dev.seria.foodplanner;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorites;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

public class FoodPlannerScreen extends Screen {
    private static final double RADIUS = 16;
    private static final int ROW_H = 20;
    private static final int TOP = 70;
    private static final int BOTTOM_MARGIN = 34;

    private FoodFinder.Results results = new FoodFinder.Results(List.of(), List.of());
    private boolean almostTab;
    private int containers;
    private int seenVersion = -1;
    private boolean wasScanning;
    private double scroll;
    private String message;
    private int messageColor;
    private Button scanButton;
    private Button readyTabButton;
    private Button almostTabButton;

    public FoodPlannerScreen() {
        super(Component.literal("Food Planner"));
    }

    @Override
    protected void init() {
        readyTabButton = addRenderableWidget(Button.builder(Component.empty(), b -> switchTab(false))
                .bounds(width / 2 - 132, 6, 130, 20).build());
        almostTabButton = addRenderableWidget(Button.builder(Component.empty(), b -> switchTab(true))
                .bounds(width / 2 + 2, 6, 130, 20).build());
        int y = height - BOTTOM_MARGIN + 7;
        scanButton = addRenderableWidget(Button.builder(Component.literal("Scan nearby"), b -> {
            message = ContainerMemory.startScan();
            messageColor = 0xFF8080;
        }).bounds(width / 2 - 154, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, y, 150, 20).build());
        refresh();
    }

    private void switchTab(boolean almost) {
        almostTab = almost;
        scroll = 0;
        updateTabButtons();
    }

    private void updateTabButtons() {
        readyTabButton.setMessage(Component.literal("Can make (" + results.ready().size() + ")"));
        almostTabButton.setMessage(Component.literal("Missing 1-" + FoodFinder.MAX_MISSING + " (" + results.almost().size() + ")"));
        readyTabButton.active = almostTab;
        almostTabButton.active = !almostTab;
    }

    private List<FoodFinder.Option> options() {
        return almostTab ? results.almost() : results.ready();
    }

    private void refresh() {
        if (minecraft == null || minecraft.player == null) return;
        ContainerMemory.Nearby nearby = ContainerMemory.nearby(minecraft.player, RADIUS);
        List<ItemStack> items = new ArrayList<>(nearby.items());
        for (ItemStack s : minecraft.player.getInventory().items) {
            if (!s.isEmpty()) items.add(s.copy());
        }
        containers = nearby.containers();
        results = FoodFinder.find(minecraft.player, items);
        seenVersion = ContainerMemory.version();
        updateTabButtons();
    }

    @Override
    public void tick() {
        boolean scanning = ContainerMemory.isScanning();
        scanButton.active = !scanning;
        scanButton.setMessage(Component.literal(scanning ? "Scanning... (" + ContainerMemory.scanRemaining() + " left)" : "Scan nearby"));
        if (wasScanning && !scanning) message = null;
        wasScanning = scanning;
        if (seenVersion != ContainerMemory.version()) refresh();
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelWidth() {
        return Math.min(width - 20, 470);
    }

    private int listBottom() {
        return height - BOTTOM_MARGIN;
    }

    private int maxScroll() {
        return Math.max(0, options().size() * ROW_H - (listBottom() - TOP));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Mth.clamp(scroll - delta * ROW_H, 0, maxScroll());
        return true;
    }

    private FoodFinder.Option optionAt(double mouseX, double mouseY) {
        int left = panelLeft();
        if (mouseX < left || mouseX >= left + panelWidth() || mouseY < TOP || mouseY >= listBottom()) return null;
        int i = (int) ((mouseY - TOP + scroll) / ROW_H);
        return i >= 0 && i < options().size() ? options().get(i) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        FoodFinder.Option o = button == 0 ? optionAt(mouseX, mouseY) : null;
        if (o == null) return super.mouseClicked(mouseX, mouseY, button);
        EmiStack food = EmiStack.of(o.food());
        EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(o.recipeId());
        if (hasShiftDown()) {
            // EmiFavorites isn't part of EMI's public API, but it's what EMI's own favorite button calls.
            if (recipe != null) EmiFavorites.addFavorite(food, recipe);
            else EmiFavorites.addFavorite(food);
            message = "Added " + o.food().getHoverName().getString() + " to EMI favorites" + (recipe == null ? " (EMI doesn't know this exact recipe)" : "");
            messageColor = 0x80FF80;
        } else if (recipe != null) {
            EmiApi.displayRecipe(recipe);
        } else {
            EmiApi.displayRecipes(food);
        }
        return true;
    }

    private static ItemStack display(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        if (items.length == 0) return ItemStack.EMPTY;
        return items[(int) (System.currentTimeMillis() / 1000 % items.length)];
    }

    private static List<Component> stepsTooltip(FoodFinder.Option o) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Steps").withStyle(ChatFormatting.GOLD));
        int n = 1;
        for (FoodFinder.Step step : o.steps()) {
            lines.add(Component.literal(n++ + ". " + step.station() + ": " + step.output().getCount() + "x " + step.output().getHoverName().getString()));
        }
        lines.add(Component.literal(n + ". " + o.station() + ": " + o.food().getHoverName().getString()).withStyle(ChatFormatting.WHITE));
        return lines;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        List<FoodFinder.Option> options = options();
        int left = panelLeft();
        int right = left + panelWidth();
        String sub = message != null ? message
                : "From " + containers + " remembered containers within " + (int) RADIUS + " blocks + your inventory";
        g.drawCenteredString(font, sub, width / 2, 32, message != null ? messageColor : 0xA0A0A0);
        g.drawCenteredString(font, "Click: open recipe in EMI  ·  Shift-click: add to EMI favorites", width / 2, 44, 0x707070);

        g.fill(left, TOP - 2, right, listBottom(), 0x80000000);
        int nameX = left + 22, divX = left + 170, stationX = left + 214, usesX = left + 296;
        scroll = Mth.clamp(scroll, 0, maxScroll());

        if (options.isEmpty()) {
            String empty = almostTab ? "Nothing is within " + FoodFinder.MAX_MISSING + " ingredients of being cookable."
                    : "Nothing cookable yet. Open your chests or press Scan nearby.";
            g.drawCenteredString(font, empty, width / 2, TOP + 10, 0xA0A0A0);
        }
        g.drawString(font, "Food", nameX, TOP - 12, 0x808080);
        g.drawString(font, "Diversity", divX - 6, TOP - 12, 0x808080);
        g.drawString(font, "Made in", stationX, TOP - 12, 0x808080);
        g.drawString(font, almostTab ? "Uses / " : "Uses", usesX, TOP - 12, 0x808080);
        if (almostTab) g.drawString(font, "missing", usesX + font.width("Uses / "), TOP - 12, 0xFF6060);

        ItemStack hovered = ItemStack.EMPTY;
        List<Component> hoveredLines = null;
        g.enableScissor(left, TOP - 2, right, listBottom());
        for (int i = 0; i < options.size(); i++) {
            int y = TOP + i * ROW_H - (int) scroll;
            if (y + ROW_H < TOP || y > listBottom()) continue;
            FoodFinder.Option o = options.get(i);
            boolean rowHover = mouseY >= Math.max(y, TOP) && mouseY < Math.min(y + ROW_H, listBottom()) && mouseX >= left && mouseX < right;
            if (rowHover) g.fill(left, y, right, y + ROW_H, 0x30FFFFFF);

            g.renderItem(o.food(), left + 3, y + 2);
            g.drawString(font, font.plainSubstrByWidth(o.food().getHoverName().getString(), divX - nameX - 4), nameX, y + 6, 0xFFFFFF);
            double d = o.diversity();
            String div = (d > 0 ? "+" : "") + String.format("%.2f", d);
            g.drawString(font, div, divX, y + 6, d > 0 ? ChatFormatting.GREEN.getColor() : 0x808080);
            String stepsSuffix = o.steps().isEmpty() ? "" : " +" + o.steps().size();
            String station = font.plainSubstrByWidth(o.station(), usesX - stationX - 4 - font.width(stepsSuffix));
            g.drawString(font, station, stationX, y + 6, 0xC0C0C0);
            g.drawString(font, stepsSuffix, stationX + font.width(station), y + 6, 0xFFD060);
            if (rowHover && mouseX < divX) hovered = o.food();
            if (rowHover && !o.steps().isEmpty() && mouseX >= stationX && mouseX < usesX) hoveredLines = stepsTooltip(o);

            int x = usesX;
            for (ItemStack use : o.uses()) {
                if (x + 16 > right) break;
                g.renderItem(use, x, y + 2);
                g.renderItemDecorations(font, use, x, y + 2);
                if (rowHover && mouseX >= x && mouseX < x + 17) hovered = use;
                x += 18;
            }
            for (Ingredient ing : o.missing()) {
                if (x + 16 > right) break;
                ItemStack shown = display(ing);
                g.fill(x - 1, y + 1, x + 17, y + 19, 0x80FF2020);
                g.renderItem(shown, x, y + 2);
                if (rowHover && mouseX >= x && mouseX < x + 17) hovered = shown;
                x += 18;
            }
        }
        g.disableScissor();

        super.render(g, mouseX, mouseY, partialTick);
        if (hoveredLines != null) g.renderComponentTooltip(font, hoveredLines, mouseX, mouseY);
        else if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
