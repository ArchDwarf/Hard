package dev.hardstone.tracker;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Редактор HUD (/hs edit): перетаскивание мышью, включение/выключение строк, фон, сброс.
 */
public class HudEditScreen extends Screen {
    private static final int BTN_W = 120;
    private static final int BTN_H = 16;
    private static final int GAP = 2;

    private final TrackerData data;
    private final HudRenderer hud;

    private boolean dragging = false;
    private int dragOffX, dragOffY;
    private int panelX, panelY, panelW, panelH;

    public HudEditScreen(TrackerData data, HudRenderer hud) {
        super(Component.literal("Редактор HUD — Hard Stone Tracker"));
        this.data = data;
        this.hud = hud;
    }

    @Override
    protected void init() {
        List<Map.Entry<String, String>> keys = new ArrayList<>(HudRenderer.LINES.entrySet());
        int cols = 2;
        int rows = (keys.size() + cols - 1) / cols;
        panelW = cols * BTN_W + (cols - 1) * GAP;
        panelH = rows * (BTN_H + GAP) + 6 + 20 + GAP + 20 + GAP + 20;
        panelX = width - panelW - 8;
        panelY = Math.max(24, (height - panelH) / 2);

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i).getKey();
            String name = keys.get(i).getValue();
            int col = i / rows, row = i % rows;
            int bx = panelX + col * (BTN_W + GAP);
            int by = panelY + row * (BTN_H + GAP);
            addRenderableWidget(Button.builder(label(key, name), b -> {
                if (data.hudHidden.contains(key)) data.hudHidden.remove(key);
                else data.hudHidden.add(key);
                b.setMessage(label(key, name));
            }).bounds(bx, by, BTN_W, BTN_H).build());
        }

        int by = panelY + rows * (BTN_H + GAP) + 6;
        int half = (panelW - GAP) / 2;
        addRenderableWidget(Button.builder(bgLabel(), b -> {
            data.hudBackground = !data.hudBackground;
            b.setMessage(bgLabel());
        }).bounds(panelX, by, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Сбросить"), b -> {
            data.hudHidden.clear();
            data.hudHidden.addAll(TrackerData.defaultHidden());
            data.hudX = 4;
            data.hudY = 4;
            data.hudBackground = true;
            clearWidgets();
            init();
        }).bounds(panelX + half + GAP, by, half, 20).build());
        // AFK: −/+ и значение посередине
        int ay = by + 20 + GAP;
        int small = 30;
        Button afkLabel = Button.builder(afkLabel(), b -> { }).bounds(panelX + small + GAP, ay,
                panelW - 2 * (small + GAP), 20).build();
        afkLabel.active = false;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            changeAfk(-1);
            afkLabel.setMessage(afkLabel());
        }).bounds(panelX, ay, small, 20).build());
        addRenderableWidget(afkLabel);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            changeAfk(+1);
            afkLabel.setMessage(afkLabel());
        }).bounds(panelX + panelW - small, ay, small, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
                .bounds(panelX, ay + 20 + GAP, panelW, 20).build());
    }

    private Component label(String key, String name) {
        boolean on = !data.hudHidden.contains(key);
        return Component.literal((on ? "§a✔ " : "§c✘ ") + "§r" + name);
    }

    private static final int[] AFK_STEPS = {10, 20, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1800, 3600};

    private void changeAfk(int dir) {
        int cur = data.afkSeconds;
        int next = dir > 0 ? AFK_STEPS[AFK_STEPS.length - 1] : AFK_STEPS[0];
        if (dir > 0) {
            for (int st : AFK_STEPS) if (st > cur) { next = st; break; }
        } else {
            for (int st : AFK_STEPS) if (st < cur) next = st;
        }
        data.afkSeconds = next;
        data.apiIdleSeconds = next;
    }

    private Component afkLabel() {
        return Component.literal("AFK через: " + Fmt.dur(data.afkSeconds));
    }

    private Component bgLabel() {
        return Component.literal("Фон: " + (data.hudBackground ? "§aвкл" : "§cвыкл"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x60000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // превью HUD (только включённые строки — как будет в игре)
        List<HudRenderer.Line> lines = hud.build(false);
        if (lines.isEmpty()) lines = List.of(new HudRenderer.Line("empty", "(все строки скрыты)", HudRenderer.GRAY));
        int[] b = hud.draw(g, lines, data.hudX, data.hudY);
        int frame = dragging ? 0xFFFFFF55 : 0xFFFFAA00;
        g.outline(b[0] - 1, b[1] - 1, b[2] - b[0] + 2, b[3] - b[1] + 2, frame);

        g.fill(panelX - 4, panelY - 16, panelX + panelW + 4, panelY + panelH + 4, 0xA0000000);
        g.text(font, "Строки HUD (вкл/выкл):", panelX, panelY - 12, HudRenderer.GOLD, true);
        g.centeredText(font, "Перетащи HUD мышью. Esc или «Готово» — сохранить.", width / 2, height - 14, HudRenderer.GRAY);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        int[] b = hud.lastBounds;
        if (e.button() == 0 && e.x() >= b[0] && e.x() <= b[2] && e.y() >= b[1] && e.y() <= b[3]) {
            dragging = true;
            dragOffX = (int) e.x() - data.hudX;
            dragOffY = (int) e.y() - data.hudY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            int[] b = hud.lastBounds;
            int w = b[2] - b[0], h = b[3] - b[1];
            data.hudX = clamp((int) e.x() - dragOffX, 3, Math.max(3, width - w + 3));
            data.hudY = clamp((int) e.y() - dragOffY, 3, Math.max(3, height - h + 3));
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging) {
            dragging = false;
            data.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void onClose() {
        data.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
