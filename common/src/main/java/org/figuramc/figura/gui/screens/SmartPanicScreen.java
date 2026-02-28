package org.figuramc.figura.gui.screens;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.SmartPanicManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.utils.FiguraText;

import java.util.*;
import java.util.stream.Collectors;

public class SmartPanicScreen extends Screen {

    public static final float MIN_DISTANCE = 5.0f;
    public static final float MAX_DISTANCE = 200.0f;
    private static final float HYSTERESIS_MARGIN = 2.0f;

    private static final int BG_OVERLAY   = 0x88000000;
    private static final int PANEL_BG     = 0xCC0D0D0F;
    private static final int PANEL_BORDER = 0x33FFFFFF;
    private static final int HEADER_BG    = 0xDD111115;
    private static final int DIVIDER      = 0x22FFFFFF;
    private static final int TEXT         = 0xFFEEEEEE;
    private static final int TEXT_DIM     = 0xFF888899;
    private static final int TEXT_MUTED   = 0xFF444455;
    private static final int ACCENT       = 0xFF5B8DEF;
    private static final int ACCENT_DIM   = 0x445B8DEF;
    private static final int GREEN        = 0xFF4CD98A;
    private static final int AMBER        = 0xFFE6A817;
    private static final int RED          = 0xFFE05252;
    private static final int HOVER_TINT   = 0x14FFFFFF;

    private static final int PAD         = 12;
    private static final int ROW_H       = 34;
    private static final int HEAD_SIZE   = 18;
    private static final int PANEL_W_MAX = 340;
    private static final int PANEL_W_MIN = 180;
    private static final int CENTER_GAP  = 8;

    private static final int TITLE_H    = 22;
    private static final int CONTROLS_H = 24;
    private static final int SLIDER_H   = 34;
    private static final int HEADER_H   = TITLE_H + CONTROLS_H + SLIDER_H;

    private final Screen parentScreen;

    private int panelX, panelW;
    private float   sliderValue;
    private boolean sliderDragging = false;
    private int     sliderX, sliderY, sliderW;

    private int   scrollOffset   = 0;
    private int   maxScroll      = 0;
    private float scrollVelocity = 0f;
    private long  lastFrameTime;

    public SmartPanicScreen(Screen parent) {
        super(FiguraText.of("gui.panic_manager.title"));
        this.parentScreen  = parent;
        this.lastFrameTime = System.currentTimeMillis();
        float saved = clampDistance((Float) Configs.PANIC_DISTANCE.value);
        this.sliderValue = (saved - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);
    }

    private void computePanelLayout() {
        int cx         = this.width / 2;
        int panelRight = cx - CENTER_GAP;
        panelW = Math.max(PANEL_W_MIN, Math.min(PANEL_W_MAX, panelRight - PAD));
        panelX = panelRight - panelW;
    }

    @Override
    protected void init() {
        super.init();
        computePanelLayout();

        boolean on = (Boolean) Configs.PANIC_ENABLED.value;

        int ctrlY = PAD + TITLE_H + 3;
        int doneW = 46, clearW = 52;
        int togW  = Math.max(50, panelW - PAD * 2 - clearW - doneW - 8);
        int doneX = panelX + panelW - PAD - doneW;
        int clearX = doneX - clearW - 4;

        this.addRenderableWidget(Button.builder(
                toggleLabel(on),
                btn -> {
                    boolean next = !(Boolean) Configs.PANIC_ENABLED.value;
                    Configs.PANIC_ENABLED.setValue(String.valueOf(next));
                    btn.setMessage(toggleLabel(next));
                }
        ).bounds(panelX + PAD, ctrlY, togW, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Clear"),
                btn -> {
                    SmartPanicManager mgr = FiguraMod.getPanicManager();
                    if (mgr != null) { mgr.clear(); scrollOffset = 0; scrollVelocity = 0; }
                }
        ).bounds(clearX, ctrlY, clearW, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.minecraft.setScreen(parentScreen)
        ).bounds(doneX, ctrlY, doneW, 18).build());

        sliderX = panelX + PAD;
        sliderY = PAD + TITLE_H + CONTROLS_H + 8;
        sliderW = panelW - PAD * 2;
    }

    private static Component toggleLabel(boolean on) {
        return Component.literal(on ? "● On" : "○ Off");
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastFrameTime) / 1000f, 0.1f);
        lastFrameTime = now;
        if (Math.abs(scrollVelocity) > 0.1f) {
            scrollOffset = Math.max(0, Math.min(maxScroll,
                    scrollOffset + (int)(scrollVelocity * dt * 60)));
            scrollVelocity *= 0.80f;
        }

        computePanelLayout();

        int W = this.width;
        int H = this.height;
        float panicDist = sliderToDist(sliderValue);
        boolean on = (Boolean) Configs.PANIC_ENABLED.value;

        g.fill(0, 0, W, H, BG_OVERLAY);

        int panelY = PAD;
        int panelH = H - PAD * 2;

        drawGlassPanel(g, panelX, panelY, panelW, panelH);

        g.fill(panelX, panelY, panelX + panelW, panelY + HEADER_H, HEADER_BG);
        g.fill(panelX, panelY + HEADER_H, panelX + panelW, panelY + HEADER_H + 1, PANEL_BORDER);

        String title  = "Smart Panic";
        int titleTW   = font.width(title);
        int dotR      = 3;
        int gap       = 5;
        int totalW    = titleTW + gap + dotR * 2;
        int titleX    = panelX + (panelW - totalW) / 2;
        int titleY    = panelY + (TITLE_H - 9) / 2;

        g.drawString(this.font, title, titleX, titleY, TEXT);

        int dotCX  = titleX + titleTW + gap + dotR;
        int dotCY  = titleY + 4;
        drawDot(g, dotCX, dotCY, dotR, on ? GREEN : TEXT_MUTED);

        g.fill(panelX + PAD, panelY + TITLE_H - 1, panelX + panelW - PAD, panelY + TITLE_H, DIVIDER);

        sliderX = panelX + PAD;
        sliderY = panelY + TITLE_H + CONTROLS_H + 7;
        sliderW = panelW - PAD * 2;
        renderCustomSlider(g, mx, my, panicDist);

        int infoY = panelY + HEADER_H + 6;
        String info = truncateText(
                String.format("Full < %.0fm   ·   Zero > %.0fm",
                        panicDist - HYSTERESIS_MARGIN, panicDist * 3),
                panelW - PAD * 2);
        g.drawString(this.font, info, panelX + PAD, infoY, TEXT_MUTED);

        int sectionY = infoY + 18;
        g.fill(panelX, sectionY, panelX + panelW, sectionY + 1, DIVIDER);

        SmartPanicManager mgr = FiguraMod.getPanicManager();
        if (mgr == null) { super.render(g, mx, my, delta); return; }

        Map<UUID, Double>                     distances  = mgr.getPlayerDistances();
        Map<UUID, Integer>                    complexity = mgr.getPlayerComplexities();
        Map<UUID, Integer>                    originals  = mgr.getOriginalComplexities();
        Map<UUID, SmartPanicManager.LodLevel> lodLevels  = mgr.getPlayerLodLevels();
        int count = distances.size();

        int nearbyY = sectionY + 5;
        g.drawString(this.font, "Nearby", panelX + PAD, nearbyY, TEXT_DIM);
        if (count > 0)
            g.drawString(this.font, String.valueOf(count),
                    panelX + PAD + font.width("Nearby") + 5, nearbyY, ACCENT);

        int listTop    = nearbyY + 13;
        int listBottom = panelY + panelH - 2;
        int listHeight = listBottom - listTop;

        if (distances.isEmpty()) {
            g.drawString(this.font, "No players nearby",
                    panelX + PAD, listTop + 10, TEXT_MUTED);
        } else {
            List<Map.Entry<UUID, Double>> sorted = distances.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue()).collect(Collectors.toList());

            maxScroll = Math.max(0, sorted.size() * ROW_H - listHeight);
            g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
            int yPos = listTop - scrollOffset;

            for (Map.Entry<UUID, Double> entry : sorted) {
                UUID uuid   = entry.getKey();
                double dist = entry.getValue();
                if (uuid == null || Double.isNaN(dist) || Double.isInfinite(dist)) continue;
                if (yPos + ROW_H < listTop || yPos > listBottom) { yPos += ROW_H; continue; }

                int    cplx = complexity.getOrDefault(uuid, 0);
                Integer maxC = originals.get(uuid);
                SmartPanicManager.LodLevel lod = lodLevels.get(uuid);

                boolean hov = mx >= panelX && mx <= panelX + panelW
                        && my >= yPos && my <= yPos + ROW_H - 1;
                if (hov) g.fill(panelX, yPos, panelX + panelW, yPos + ROW_H - 1, HOVER_TINT);

                int headX = panelX + PAD, headY = yPos + (ROW_H - HEAD_SIZE) / 2;
                AbstractClientPlayer cp = findPlayer(uuid);
                if (cp != null) {
                    renderPlayerHead(g, cp, headX, headY, HEAD_SIZE);
                } else {
                    g.fill(headX, headY, headX + HEAD_SIZE, headY + HEAD_SIZE, colorWithAlpha(TEXT_DIM, 0x33));
                    g.drawString(this.font, "?",
                            headX + (HEAD_SIZE - font.width("?")) / 2,
                            headY + (HEAD_SIZE - 9) / 2, TEXT_MUTED);
                }

                int nameY  = yPos + (ROW_H - 9) / 2;
                String name = truncateText(getPlayerName(uuid), panelW - HEAD_SIZE - PAD * 3 - 90);
                g.drawString(this.font, name, headX + HEAD_SIZE + 6, nameY, hov ? TEXT : 0xFFCCCCCC);

                int lodC = lodColor(lod, cplx, dist, panicDist);
                String lbl = lodLabel(lod, cplx, dist, panicDist);
                int rEdge  = panelX + panelW - PAD;

                int barW = Math.min(40, panelW / 7);
                int barX = rEdge - barW, barMidY = yPos + ROW_H / 2;
                g.fill(barX, barMidY - 1, barX + barW, barMidY + 1, colorWithAlpha(TEXT_MUTED, 0x55));
                float pct = (maxC != null && maxC > 0)
                        ? Math.max(0f, Math.min(1f, (float) cplx / maxC)) : 0f;
                g.fill(barX, barMidY - 1, barX + Math.max(1, (int)(barW * pct)), barMidY + 1,
                        colorWithAlpha(lodC, 0xCC));

                int chipW = font.width(lbl) + 6, chipX = barX - chipW - 6;
                int chipY = yPos + (ROW_H - 11) / 2;
                g.fill(chipX, chipY, chipX + chipW, chipY + 11, colorWithAlpha(lodC, 0x1E));
                g.fill(chipX,              chipY,      chipX + chipW, chipY + 1,  colorWithAlpha(lodC, 0x99));
                g.fill(chipX,              chipY + 10, chipX + chipW, chipY + 11, colorWithAlpha(lodC, 0x99));
                g.fill(chipX,              chipY,      chipX + 1,     chipY + 11, colorWithAlpha(lodC, 0x99));
                g.fill(chipX + chipW - 1,  chipY,      chipX + chipW, chipY + 11, colorWithAlpha(lodC, 0x99));
                g.drawString(this.font, lbl, chipX + 3, chipY + 1, lodC);

                String distStr = String.format("%.0fm", dist);
                int distColor  = dist < panicDist - HYSTERESIS_MARGIN ? GREEN
                        : dist < panicDist * 3 ? AMBER : RED;
                g.drawString(this.font, distStr, chipX - font.width(distStr) - 6, nameY, distColor);

                g.fill(panelX + PAD, yPos + ROW_H - 1, panelX + panelW - PAD, yPos + ROW_H, DIVIDER);
                yPos += ROW_H;
            }

            g.disableScissor();

            if (maxScroll > 0) {
                int trackX = panelX + panelW - 3;
                int thumbH = Math.max(14, (int)((float) listHeight / (listHeight + maxScroll) * listHeight));
                int thumbY = listTop + (int)((float) scrollOffset / maxScroll * (listHeight - thumbH));
                g.fill(trackX, listTop,  trackX + 2, listBottom, colorWithAlpha(TEXT_MUTED, 0x33));
                g.fill(trackX, thumbY,   trackX + 2, thumbY + thumbH, colorWithAlpha(TEXT_DIM, 0xBB));
            }
        }

        super.render(g, mx, my, delta);
    }

    private void renderCustomSlider(GuiGraphics g, int mx, int my, float panicDist) {
        int thumbR  = 5;
        int trackH  = 3;
        int trackX1 = sliderX + thumbR + font.width("5m") + 4;
        int trackX2 = sliderX + sliderW - thumbR - font.width("200m") - 4;
        int trackLen = trackX2 - trackX1;
        int trackCY = sliderY + thumbR + 1;

        int thumbX = trackX1 + (int)(sliderValue * trackLen);

        boolean hov = sliderDragging ||
                (mx >= trackX1 - thumbR - 2 && mx <= trackX2 + thumbR + 2
                        && my >= trackCY - thumbR - 2 && my <= trackCY + thumbR + 2);

        g.fill(trackX1, trackCY - 1, trackX2, trackCY + trackH - 1, colorWithAlpha(0xFFFFFF, 0x18));
        g.fill(trackX1, trackCY - 1, trackX2, trackCY, colorWithAlpha(0xFFFFFF, 0x22));

        if (thumbX > trackX1) {
            g.fill(trackX1, trackCY - 1, thumbX, trackCY + trackH - 1, ACCENT_DIM);
            g.fill(thumbX - 1, trackCY - 1, thumbX, trackCY + trackH - 1, colorWithAlpha(ACCENT, 0xAA));
        }

        if (hov) {
            g.fill(thumbX - thumbR - 2, trackCY - thumbR,
                    thumbX + thumbR + 2, trackCY + thumbR,
                    colorWithAlpha(ACCENT, 0x28));
        }

        int tY = trackCY + 1;
        g.fill(thumbX - thumbR + 1, tY - thumbR,     thumbX + thumbR - 1, tY + thumbR,     ACCENT);
        g.fill(thumbX - thumbR,     tY - thumbR + 1, thumbX + thumbR,     tY + thumbR - 1, ACCENT);
        // Top sheen
        g.fill(thumbX - thumbR + 2, tY - thumbR + 1, thumbX + thumbR - 2, tY - thumbR + 2,
                colorWithAlpha(0xFFFFFF, 0x50));
        // Bottom shadow
        g.fill(thumbX - thumbR + 2, tY + thumbR - 2, thumbX + thumbR - 2, tY + thumbR - 1,
                colorWithAlpha(0x000000, 0x40));

        String minLbl = (int) MIN_DISTANCE + "m";
        String maxLbl = (int) MAX_DISTANCE + "m";
        int endLblY = trackCY - 4; // vertically centered with track
        g.drawString(this.font, minLbl, trackX1 - font.width(minLbl) - 4, endLblY, TEXT_MUTED);
        g.drawString(this.font, maxLbl, trackX2 + thumbR + 3,              endLblY, TEXT_MUTED);

        String dimPart   = "Distance  ";
        String valuePart = String.format("%.0f blocks", panicDist);
        int lblFullW     = font.width(dimPart) + font.width(valuePart);
        int lblX         = sliderX + (sliderW - lblFullW) / 2;
        int lblY         = trackCY + thumbR + 4;
        g.drawString(this.font, dimPart,   lblX,                        lblY, TEXT_MUTED);
        g.drawString(this.font, valuePart, lblX + font.width(dimPart),  lblY, hov ? TEXT : TEXT_DIM);
    }

    private boolean inSliderArea(double mx, double my) {
        int tY = sliderY + 6;
        return mx >= sliderX && mx <= sliderX + sliderW
                && my >= tY - 8 && my <= tY + 16;
    }

    private void applySliderMouse(double mx) {
        int thumbR  = 5;
        int trackX1 = sliderX + thumbR + font.width("5m") + 4;
        int trackX2 = sliderX + sliderW - thumbR - font.width("200m") - 4;
        sliderValue = (float) Math.max(0.0, Math.min(1.0,
                (mx - trackX1) / (double)(trackX2 - trackX1)));
        Configs.PANIC_DISTANCE.setValue(String.valueOf(sliderToDist(sliderValue)));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && inSliderArea(mx, my)) {
            sliderDragging = true;
            applySliderMouse(mx);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && sliderDragging) { sliderDragging = false; return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (sliderDragging) { applySliderMouse(mx); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (mx >= panelX && mx <= panelX + panelW && maxScroll > 0) {
            scrollVelocity = (float) amount * 3f;
            return true;
        }
        return super.mouseScrolled(mx, my, amount);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parentScreen);
    }

    private void drawGlassPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x,         y,         x + w,     y + 1,     PANEL_BORDER);
        g.fill(x,         y + h - 1, x + w,     y + h,     PANEL_BORDER);
        g.fill(x,         y,         x + 1,     y + h,     PANEL_BORDER);
        g.fill(x + w - 1, y,         x + w,     y + h,     PANEL_BORDER);
        g.fill(x + 1,     y + 1,     x + w - 1, y + 2,     colorWithAlpha(0xFFFFFF, 0x0A));
    }

    private void drawDot(GuiGraphics g, int cx, int cy, int r, int color) {
        g.fill(cx - r + 1, cy - r,     cx + r - 1, cy + r,     color);
        g.fill(cx - r,     cy - r + 1, cx + r,     cy + r - 1, color);
    }

    private String truncateText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        while (text.length() > 1 && font.width(text + "…") > maxWidth)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }

    private void renderPlayerHead(GuiGraphics g, AbstractClientPlayer player, int x, int y, int size) {
        ResourceLocation skin = player.getSkinTextureLocation();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        g.blit(skin, x, y, size, size, 8f, 8f, 8, 8, 64, 64);
        RenderSystem.enableBlend();
        g.blit(skin, x - 1, y - 1, size + 2, size + 2, 40f, 8f, 8, 8, 64, 64);
        RenderSystem.disableBlend();
    }

    private static int lodColor(SmartPanicManager.LodLevel lod, int cplx, double dist, float pd) {
        if (lod != null) return switch (lod) {
            case ULTRA, HIGH  -> GREEN;
            case MEDIUM, LOW  -> AMBER;
            case MINIMAL      -> RED;
            case DISABLED     -> 0xFF555566;
        };
        if (cplx == 0)                     return 0xFF555566;
        if (dist < pd - HYSTERESIS_MARGIN) return GREEN;
        if (dist < pd * 3)                 return AMBER;
        return RED;
    }

    private static String lodLabel(SmartPanicManager.LodLevel lod, int cplx, double dist, float pd) {
        if (lod != null) return switch (lod) {
            case ULTRA    -> "Ultra";
            case HIGH     -> "High";
            case MEDIUM   -> "Med";
            case LOW      -> "Low";
            case MINIMAL  -> "Min";
            case DISABLED -> "Off";
        };
        if (cplx == 0)                     return "Off";
        if (dist < pd - HYSTERESIS_MARGIN) return "Full";
        if (dist < pd * 3)                 return "Fade";
        return "Low";
    }

    private static int colorWithAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static float sliderToDist(float v) {
        return clampDistance(MIN_DISTANCE + v * (MAX_DISTANCE - MIN_DISTANCE));
    }

    private static float clampDistance(float v) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, v));
    }

    private AbstractClientPlayer findPlayer(UUID uuid) {
        if (this.minecraft.level == null) return null;
        for (AbstractClientPlayer p : this.minecraft.level.players())
            if (p.getUUID().equals(uuid)) return p;
        return null;
    }

    private String getPlayerName(UUID uuid) {
        AbstractClientPlayer p = findPlayer(uuid);
        return p != null ? p.getName().getString() : uuid.toString().substring(0, 8) + "…";
    }
}