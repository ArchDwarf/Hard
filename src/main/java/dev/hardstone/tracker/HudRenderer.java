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
    private final CollectionTracker collection;
    private final StatsReader stats;
    private final GlobalRankReader globalRank;

    public HudRenderer(TrackerData data, MiningTracker mining, LocationTracker location, LeaderboardService leaderboard,
                       CollectionTracker collection, StatsReader stats,
                       GlobalRankReader globalRank) {
        this.data = data;
        this.mining = mining;
        this.location = location;
        this.leaderboard = leaderboard;
        this.collection = collection;
        this.stats = stats;
        this.globalRank = globalRank;
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
        if (mining.sessionSackItems > 0) {
            lines.add(new Line("Дроп (сакс): " + Fmt.num(mining.sessionSackItems)
                    + "  (" + Fmt.compact(mining.sackPerHour()) + "/ч)", AQUA));
        }
        double dpb = stats.dropsPerBlock();
        boolean noStats = !(stats.tabMf || stats.tabBf || stats.tabSpread) && data.statsFromMenuAt == 0
                && data.miningFortune == 0 && data.blockFortune == 0;
        if (noStats) lines.add(new Line("Удача: нет данных (/hs status)", RED));
        else lines.add(new Line("Удача: " + Fmt.num(Math.round(stats.totalFortune()))
                + String.format(java.util.Locale.ROOT, " (x%.2f/блок)", dpb)
                + "  Spread: " + Fmt.num(Math.round(stats.miningSpread())), GRAY));
        lines.add(new Line("Дроп (формула): " + Fmt.num(Math.round(mining.sessionBlocks * dpb))
                + "  (" + Fmt.compact(mining.perHour() * dpb) + "/ч)", AQUA));
        if (collection.enabled()) {
            long gain = collection.gained();
            String v = collection.error() != null ? collection.error() : gain < 0 ? "загрузка..." : "+" + Fmt.num(gain);
            long next = collection.secondsUntilNext();
            if (collection.total() >= 0) {
                lines.add(new Line("Коллекция: " + Fmt.num(collection.total()), GOLD));
            }
            lines.add(new Line("За сессию (API): " + v
                    + (collection.isLoading() ? "  · обновляю..." : next >= 0 ? "  · обн. через " + Fmt.mmss(next) : ""), GREEN));
        }
        lines.add(new Line("Время: " + Fmt.time(mining.sessionActiveMs) + (mining.isPaused() ? " (пауза)" : ""),
                mining.isPaused() ? RED : WHITE));
        lines.add(new Line("Рекорд/ч: " + Fmt.compact(data.bestBlocksPerHour)
                + "  Всего: " + Fmt.compact(data.allTimeBlocks), GRAY));

        {
            lines.add(new Line("Глобальное место: " + (globalRank.known() ? globalRank.describe()
                    : "открой Collections (Collection rankings)"), globalRank.known() ? GOLD : GRAY));
            if (globalRank.known()) {
                long tu = globalRank.secondsUntilUpdate();
                lines.add(new Line(tu > 0 ? "Обновление топа через " + Fmt.hms(tu)
                        : tu == 0 ? "Топ обновился — открой Leaderboards"
                        : "Обновлено " + globalRank.ago() + " назад", GRAY));
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
