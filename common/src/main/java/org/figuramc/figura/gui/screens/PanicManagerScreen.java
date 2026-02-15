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
    private int maxScroll;

    private DistanceSlider distanceSlider;
    private Button toggleButton;

    public PanicManagerScreen(Screen parent) {
        super(FiguraText.of("figura.gui.panic_manager.title"));
        this.parentScreen = parent;
        this.maxScroll = 0; // Will be calculated dynamically in render
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
        toggleButton = this.addRenderableWidget(Button.builder(
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
                        scrollOffset = 0; // Reset scroll when clearing
                    }
                }
        ).bounds(rightCol, 40, 150, 20).build());

        // Distance slider (centered)
        distanceSlider = new DistanceSlider(centerX - 75, 70, 150, 20);
        this.addRenderableWidget(distanceSlider);

        // Info button (shows help)
        this.addRenderableWidget(Button.builder(
                Component.literal("?"),
                button -> {
                    // Could open a help screen or show tooltip
                }
        ).bounds(this.width - 30, 10, 20, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);

        // Status info with real-time update indicator
        boolean panicEnabled = (Boolean) Configs.PANIC_ENABLED.value;
        String statusText = panicEnabled ? "§a●§f Active (Real-time)" : "§7●§f Inactive";
        graphics.drawCenteredString(this.font, statusText, this.width / 2, 100, 0xFFFFFF);

        // Explanation with better formatting
        graphics.drawCenteredString(this.font, "§7Players outside range are automatically blocked",
                this.width / 2, 112, 0xFFFFFF);

        // Player list header with count
        FiguraPanicManager manager = FiguraMod.getPanicManager();
        if (manager != null) {
            Map<UUID, Double> distances = manager.getPlayerDistances();
            int playerCount = distances.size();
            graphics.drawCenteredString(this.font, "§lNearby Players §7(" + playerCount + ")",
                    this.width / 2, 130, 0xFFFFFF);

            if (distances.isEmpty()) {
                graphics.drawCenteredString(this.font, "§7No players detected",
                        this.width / 2, 150, 0x888888);
            } else {
                float panicDistance = (Float) Configs.PANIC_DISTANCE.value;

                // Sort players by distance
                List<Map.Entry<UUID, Double>> sortedPlayers = distances.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .collect(Collectors.toList());

                int yPos = 145 - scrollOffset;
                int listHeight = this.height - 195;

                // Calculate dynamic scroll
                this.maxScroll = Math.max(0, sortedPlayers.size() * 16 - listHeight);

                // Enable scissor for scrolling
                graphics.enableScissor(10, 145, this.width - 10, 145 + listHeight);

                for (Map.Entry<UUID, Double> entry : sortedPlayers) {
                    UUID uuid = entry.getKey();
                    double distance = entry.getValue();

                    // Get player name from Minecraft's player list
                    String playerName = null;
                    if (this.minecraft != null && this.minecraft.level != null) {
                        net.minecraft.world.entity.player.Player player = this.minecraft.level.getPlayerByUUID(uuid);
                        if (player != null) {
                            playerName = player.getName().getString();
                        }
                    }

                    // Fallback to UUID if name not found
                    if (playerName == null || playerName.isEmpty()) {
                        playerName = uuid.toString().substring(0, 8) + "...";
                    }

                    // Get permission status
                    PermissionPack.PlayerPermissionPack pack = PermissionManager.get(uuid);
                    boolean isBlocked = pack != null && pack.getCategory() == Permissions.Category.BLOCKED;
                    boolean shouldBeBlocked = distance > panicDistance;

                    String status;
                    int statusColor;
                    if (isBlocked && shouldBeBlocked) {
                        status = "BLOCKED"; // Correctly blocked (too far)
                        statusColor = 0xFF5555; // Red
                    } else if (!isBlocked && !shouldBeBlocked) {
                        status = "ALLOWED"; // Correctly allowed (close enough)
                        statusColor = 0x55FF55; // Green
                    } else {
                        status = "UPDATING"; // Transitioning state
                        statusColor = 0xFFAA00; // Yellow
                    }

                    // Draw player entry with better formatting
                    int xStart = this.width / 2 - 150;

                    // Status badge
                    graphics.drawString(this.font, "[" + status + "]", xStart, yPos, statusColor);

                    // Player name
                    graphics.drawString(this.font, "§f" + playerName, xStart + 80, yPos, 0xFFFFFF);

                    // Distance with color coding
                    String distanceStr = String.format("%.1fm", distance);
                    int distanceColor = distance > panicDistance ? 0xFF5555 : 0x55FF55;
                    graphics.drawString(this.font, distanceStr, xStart + 220, yPos, distanceColor);

                    yPos += 16;
                }

                graphics.disableScissor();
            }
        }

        // Render buttons and widgets
        super.render(graphics, mouseX, mouseY, delta);

        // Scroll indicator
        if (maxScroll > 0) {
            graphics.drawCenteredString(this.font, "§7⬍ Scroll for more ⬍",
                    this.width / 2, this.height - 50, 0x888888);
        }

        // Help tooltip
        if (mouseX >= this.width - 30 && mouseX <= this.width - 10 &&
                mouseY >= 10 && mouseY <= 30) {
            List<Component> tooltip = Arrays.asList(
                    Component.literal("§ePanic Mode Help"),
                    Component.literal("§7Automatically blocks avatars"),
                    Component.literal("§7of players outside range"),
                    Component.literal("§7Updates in real-time (10x/sec)")
            );
            graphics.renderTooltip(this.font, tooltip, mouseX, mouseY);
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
}