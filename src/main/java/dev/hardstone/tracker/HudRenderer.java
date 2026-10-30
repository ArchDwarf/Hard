package dev.hardstone.tracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Панель статистики в углу экрана */
public class HudRenderer {
    private static final int GOLD = 0xFFFFAA00;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int AQUA = 0xFF55FFFF;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int RED = 0xFFFF5555;

    private record Line(String text, int color) {}

    private final TrackerData data;
    private final MiningTracker mining;
    private final LocationTracker location;
    private final LeaderboardService leaderboard;

    public HudRenderer(TrackerData data, MiningTracker mining, LocationTracker location, LeaderboardService leaderboard) {
        this.data = data;
        this.mining = mining;
        this.location = location;
        this.leaderboard = leaderboard;
    }

    public void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!data.hudEnabled || mc.player == null) return;
        // Показываем только на Hypixel в SkyBlock или если включён режим «везде»
        if (!data.countEverywhere && !(location.isOnHypixel() && "SKYBLOCK".equals(location.gametype))) return;

        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Hard Stone Tracker", GOLD));
        boolean counting = mining.isCountingHere();
        lines.add(new Line("Локация: " + (data.countEverywhere ? "везде" : location.prettyLocation()),
                counting ? GREEN : GRAY));
        lines.add(new Line("Блоков: " + Fmt.num(mining.sessionBlocks), WHITE));
        lines.add(new Line("Блоков/ч: " + Fmt.compact(mining.perHour()), YELLOW));
        if (mining.sessionSackItems > 0) {
            lines.add(new Line("Дроп (сакс): " + Fmt.num(mining.sessionSackItems)
                    + "  (" + Fmt.compact(mining.sackPerHour()) + "/ч)", AQUA));
        }
        lines.add(new Line("Время: " + Fmt.time(mining.sessionActiveMs) + (mining.isPaused() ? " (пауза)" : ""),
                mining.isPaused() ? RED : WHITE));
        lines.add(new Line("Рекорд/ч: " + Fmt.compact(data.bestBlocksPerHour)
                + "  Всего: " + Fmt.compact(data.allTimeBlocks), GRAY));

        if (data.hudShowTop && !leaderboard.top().isEmpty()) {
            lines.add(new Line("Топ коллекции Hard Stone:", GOLD));
            String self = mc.getUser().getName();
            int i = 1;
            for (LeaderboardService.Entry e : leaderboard.top()) {
                if (i > 5) break;
                String val = e.hardStone() >= 0 ? Fmt.compact(e.hardStone()) : "—";
                lines.add(new Line("#" + i + " " + e.name() + "  " + val,
                        e.name().equalsIgnoreCase(self) ? GREEN : WHITE));
                i++;
            }
        }

        Font font = mc.font;
        int w = 0;
        for (Line l : lines) w = Math.max(w, font.width(l.text()));
        int lh = font.lineHeight + 1;
        int x = data.hudX, y = data.hudY;
        g.fill(x - 3, y - 3, x + w + 3, y + lines.size() * lh + 2, 0x90000000);
        for (Line l : lines) {
            g.text(font, l.text(), x, y, l.color(), true);
            y += lh;
        }
    }
}
