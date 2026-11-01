package dev.hardstone.tracker;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Панель статистики. Строки можно скрывать/добавлять и двигать в /hs edit. */
public class HudRenderer {
    static final int GOLD = 0xFFFFAA00;
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFAAAAAA;
    static final int GREEN = 0xFF55FF55;
    static final int AQUA = 0xFF55FFFF;
    static final int YELLOW = 0xFFFFFF55;
    static final int RED = 0xFFFF5555;

    /** Все строки HUD: ключ → название в редакторе (порядок = порядок в HUD) */
    public static final Map<String, String> LINES = new LinkedHashMap<>();
    static {
        LINES.put("title", "Заголовок");
        LINES.put("location", "Локация");
        LINES.put("collection", "Коллекция");
        LINES.put("session", "За сессию");
        LINES.put("perhour", "В час");
        LINES.put("record", "Рекорд в час");
        LINES.put("api", "Таймер API");
        LINES.put("sack", "Дроп (сакс)");
        LINES.put("time", "Время сессии");
        LINES.put("rank", "Глоб. рейтинг");
        LINES.put("rankupdate", "Обновл. рейтинга");
        LINES.put("fortune", "Удача и Spread");
        LINES.put("blocks", "Блоков за сессию");
    }

    public record Line(String key, String text, int color) {}

    private final TrackerData data;
    private final MiningTracker mining;
    private final LocationTracker location;
    private final CollectionTracker collection;
    private final StatsReader stats;
    private final GlobalRankReader globalRank;

    /** Границы HUD при последней отрисовке: x1, y1, x2, y2 */
    public int[] lastBounds = {0, 0, 0, 0};

    public HudRenderer(TrackerData data, MiningTracker mining, LocationTracker location, LeaderboardService leaderboard,
                       CollectionTracker collection, StatsReader stats, GlobalRankReader globalRank) {
        this.data = data;
        this.mining = mining;
        this.location = location;
        this.collection = collection;
        this.stats = stats;
        this.globalRank = globalRank;
    }

    public boolean isShown(String key) {
        return !data.hudHidden.contains(key);
    }

    public void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!data.hudEnabled || mc.player == null) return;
        if (mc.gui.screen() instanceof HudEditScreen) return; // в редакторе рисует сам экран
        // Показываем только на Hypixel в SkyBlock или если включён режим «везде»
        if (!data.countEverywhere && !(location.isOnHypixel() && "SKYBLOCK".equals(location.gametype))) return;
        draw(g, build(false), data.hudX, data.hudY);
    }

    /** @param all true — все строки (для редактора), false — только включённые */
    public List<Line> build(boolean all) {
        List<Line> out = new ArrayList<>();
        add(out, all, "title", "Hard Stone Tracker", GOLD);
        add(out, all, "location", "Локация: " + (data.countEverywhere ? "везде" : location.prettyLocation()),
                mining.isCountingHere() ? GREEN : GRAY);

        boolean api = collection.enabled();
        String noKey = "нужен /hs apikey";
        add(out, all, "collection", "Коллекция: " + (!api ? noKey
                : collection.total() >= 0 ? Fmt.num(collection.total()) : "загрузка..."), GOLD);

        long gain = collection.gained();
        add(out, all, "session", "За сессию: " + (!api ? noKey : collection.error() != null ? collection.error()
                : gain < 0 ? "загрузка..." : "+" + Fmt.num(gain)), GREEN);

        double ph = collection.perHour();
        add(out, all, "perhour", "В час: " + (!api ? noKey : ph < 0 ? "считаю... (нужно 2 обновления API)"
                : Fmt.num(Math.round(ph)) + "/ч" + (collection.idle() ? " (нет прироста 10+ мин)" : "")),
                collection.idle() ? GRAY : YELLOW);

        add(out, all, "record", "Рекорд в час: " + (data.bestApiPerHour > 0
                ? Fmt.num(Math.round(data.bestApiPerHour)) + "/ч" : "ещё нет (нужно 10+ мин добычи)"), GRAY);

        long next = collection.secondsUntilNext();
        add(out, all, "api", !api ? "API: " + noKey : collection.isLoading() ? "API: обновляю..."
                : next >= 0 ? "API: обновление через " + Fmt.mmss(next) : "API: загрузка...", GRAY);

        add(out, all, "sack", "Дроп (сакс): " + Fmt.num(mining.sessionSackItems)
                + "  (" + Fmt.compact(mining.sackPerHour()) + "/ч)", AQUA);

        add(out, all, "time", "Время: " + Fmt.time(mining.sessionActiveMs) + (mining.isPaused() ? " (пауза)" : ""),
                mining.isPaused() ? RED : WHITE);

        add(out, all, "rank", "Глобальный рейтинг: " + (globalRank.known() ? globalRank.describe()
                : "открой меню Collections"), globalRank.known() ? GOLD : GRAY);

        if (globalRank.known() || all) {
            long tu = globalRank.secondsUntilUpdate();
            add(out, all, "rankupdate", !globalRank.known() ? "Обновлено: —"
                    : tu > 0 ? "Обновление топа через " + Fmt.hms(tu)
                    : tu == 0 ? "Топ обновился — открой Collections"
                    : "Обновлено " + globalRank.ago() + " назад", GRAY);
        }

        add(out, all, "fortune", "Удача: " + Fmt.num(Math.round(stats.totalFortune()))
                + "  Spread: " + Fmt.num(Math.round(stats.miningSpread())), GRAY);

        add(out, all, "blocks", "Блоков: " + Fmt.num(mining.sessionBlocks)
                + (mining.lastBurst > 1 ? "  (+" + mining.lastBurst + " за удар)" : ""), WHITE);
        return out;
    }

    private void add(List<Line> out, boolean all, String key, String text, int color) {
        if (all || isShown(key)) out.add(new Line(key, text, color));
    }

    /** Рисует строки; возвращает границы {x1, y1, x2, y2} */
    public int[] draw(GuiGraphicsExtractor g, List<Line> lines, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int w = 0;
        for (Line l : lines) w = Math.max(w, font.width(l.text()));
        int lh = font.lineHeight + 1;
        int h = Math.max(lines.size(), 1) * lh;
        if (data.hudBackground) g.fill(x - 3, y - 3, x + w + 3, y + h + 2, 0x90000000);
        int yy = y;
        for (Line l : lines) {
            g.text(font, l.text(), x, yy, l.color(), true);
            yy += lh;
        }
        lastBounds = new int[]{x - 3, y - 3, x + Math.max(w, 40) + 3, y + h + 2};
        return lastBounds;
    }
}
