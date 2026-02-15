package org.figuramc.figura.gui.screens;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.FiguraPanicManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.permissions.PermissionManager;
import org.figuramc.figura.permissions.PermissionPack;
import org.figuramc.figura.permissions.Permissions;
import org.figuramc.figura.utils.FiguraText;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Screen displaying panic mode status and player distances
 */
public class PanicManagerScreen extends Screen {

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 24;

    private final Screen parentScreen;
    private int scrollOffset = 0;
    private final int maxScroll;

    private DistanceSlider distanceSlider;
    private IntervalSlider intervalSlider;

    public PanicManagerScreen(Screen parent) {
        super(FiguraText.of("figura.gui.panic_manager.title"));
        this.parentScreen = parent;

        FiguraPanicManager manager = FiguraMod.getPanicManager();
        int playerCount = manager != null ? manager.getPlayerDistances().size() : 0;
        this.maxScroll = Math.max(0, playerCount * 16 - 100);
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int leftCol = centerX - 155;
        int rightCol = centerX + 5;

        // Back button (bottom)
        this.addRenderableWidget(Button.builder(
                FiguraText.of("gui.done"),
                button -> this.minecraft.setScreen(parentScreen)
        ).bounds(centerX - 75, this.height - 28, 150, 20).build());

        // Toggle panic mode button (top left)
        boolean panicEnabled = (Boolean) Configs.PANIC_ENABLED.value;
        this.addRenderableWidget(Button.builder(
                Component.literal("Panic Mode: " + (panicEnabled ? "§aON" : "§cOFF")),
                button -> {
                    boolean newValue = !(Boolean) Configs.PANIC_ENABLED.value;
                    Configs.PANIC_ENABLED.setValue(String.valueOf(newValue));
                    button.setMessage(Component.literal("Panic Mode: " + (newValue ? "§aON" : "§cOFF")));
                }
        ).bounds(leftCol, 40, 150, 20).build());

        // Clear all panic data button (top right)
        this.addRenderableWidget(Button.builder(
                FiguraText.of("gui.panic_manager.clear_all"),
                button -> {
                    FiguraPanicManager manager = FiguraMod.getPanicManager();
                    if (manager != null) {
                        manager.clear();
                    }
                }
        ).bounds(rightCol, 40, 150, 20).build());

        // Distance slider
        distanceSlider = new DistanceSlider(leftCol, 70, 150, 20);
        this.addRenderableWidget(distanceSlider);

        // Interval slider
        intervalSlider = new IntervalSlider(rightCol, 70, 150, 20);
        this.addRenderableWidget(intervalSlider);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Status info
        boolean panicEnabled = (Boolean) Configs.PANIC_ENABLED.value;

        graphics.drawCenteredString(this.font, "Status: " + (panicEnabled ? "§aActive" : "§7Inactive"),
                this.width / 2, 100, 0xFFFFFF);

        // Explanation
        graphics.drawCenteredString(this.font, "§7Players outside range are blocked",
                this.width / 2, 112, 0xFFFFFF);

        // Player list header
        graphics.drawCenteredString(this.font, "§lNearby Players", this.width / 2, 130, 0xFFFFFF);

        // Player list
        FiguraPanicManager manager = FiguraMod.getPanicManager();
        if (manager != null) {
            Map<UUID, Double> distances = manager.getPlayerDistances();

            if (distances.isEmpty()) {
                graphics.drawCenteredString(this.font, "§7No players nearby",
                        this.width / 2, 150, 0xFFFFFF);
            } else {
                float panicDistance = (Float) Configs.PANIC_DISTANCE.value;

                // Sort players by distance
                List<Map.Entry<UUID, Double>> sortedPlayers = distances.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .collect(Collectors.toList());

                int yPos = 145 - scrollOffset;
                int listHeight = this.height - 195;

                // Enable scissor for scrolling
                graphics.enableScissor(10, 145, this.width - 10, 145 + listHeight);

                for (Map.Entry<UUID, Double> entry : sortedPlayers) {
                    UUID uuid = entry.getKey();
                    double distance = entry.getValue();

                    // Get player name (simplified - you might want to use GameProfileCache)
                    String playerName = uuid.toString().substring(0, 8);

                    // Get permission status
                    PermissionPack.PlayerPermissionPack pack = PermissionManager.get(uuid);
                    boolean isBlocked = pack != null && pack.getCategory() == Permissions.Category.BLOCKED;
                    boolean shouldBeBlocked = distance > panicDistance;

                    String status;
                    if (isBlocked && shouldBeBlocked) {
                        status = "§c[BLOCKED-FAR]"; // Correctly blocked (too far)
                    } else if (!isBlocked && !shouldBeBlocked) {
                        status = "§a[OK-NEAR]"; // Correctly allowed (close enough)
                    } else if (isBlocked && !shouldBeBlocked) {
                        status = "§e[PENDING]"; // Will be unblocked next update
                    } else {
                        status = "§e[PENDING]"; // Will be blocked next update
                    }

                    // Draw player entry
                    String text = String.format("%s §7%s §f- §e%.1f blocks",
                            status, playerName, distance);
                    graphics.drawString(this.font, text, this.width / 2 - 150, yPos, 0xFFFFFF);

                    yPos += 16;
                }

                graphics.disableScissor();
            }
        }

        // Render buttons and widgets
        super.render(graphics, mouseX, mouseY, delta);

        // Scroll indicator
        if (maxScroll > 0) {
            graphics.drawString(this.font, "§7Scroll to see more",
                    this.width / 2 - 50, this.height - 50, 0xFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - verticalAmount * 10));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    // Custom slider for panic distance
    private class DistanceSlider extends AbstractSliderButton {
        private static final float MIN_DISTANCE = 5.0f;
        private static final float MAX_DISTANCE = 100.0f;

        public DistanceSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), getInitialValue());
            updateMessage();
        }

        private static double getInitialValue() {
            float distance = (Float) Configs.PANIC_DISTANCE.value;
            return (distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);
        }

        @Override
        protected void updateMessage() {
            float distance = MIN_DISTANCE + (float) this.value * (MAX_DISTANCE - MIN_DISTANCE);
            this.setMessage(Component.literal("Distance: §e" + String.format("%.1f", distance) + " blocks"));
        }

        @Override
        protected void applyValue() {
            float distance = MIN_DISTANCE + (float) this.value * (MAX_DISTANCE - MIN_DISTANCE);
            Configs.PANIC_DISTANCE.setValue(String.valueOf(distance));
        }
    }

    // Custom slider for update interval
    private class IntervalSlider extends AbstractSliderButton {
        private static final int MIN_INTERVAL = 1;
        private static final int MAX_INTERVAL = 10;

        public IntervalSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), getInitialValue());
            updateMessage();
        }

        private static double getInitialValue() {
            int interval = (Integer) Configs.PANIC_UPDATE_INTERVAL.value;
            return (double) (interval - MIN_INTERVAL) / (MAX_INTERVAL - MIN_INTERVAL);
        }

        @Override
        protected void updateMessage() {
            int interval = MIN_INTERVAL + (int) Math.round(this.value * (MAX_INTERVAL - MIN_INTERVAL));
            this.setMessage(Component.literal("Update: §b" + interval + "s"));
        }

        @Override
        protected void applyValue() {
            int interval = MIN_INTERVAL + (int) Math.round(this.value * (MAX_INTERVAL - MIN_INTERVAL));
            Configs.PANIC_UPDATE_INTERVAL.setValue(String.valueOf(interval));
        }
    }
}