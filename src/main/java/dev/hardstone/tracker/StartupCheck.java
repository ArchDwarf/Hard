package dev.hardstone.tracker;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;

/**
 * Автопроверка при входе: через ~15 с после захода в SkyBlock один раз за подключение
 * проверяет ключ API, коллекцию и статы, и пишет в чат, что не так (с кликабельными подсказками).
 */
public class StartupCheck {
    private static final long DELAY_MS = 15_000;

    private final TrackerData data;
    private final LocationTracker location;
    private final CollectionTracker collection;
    private final StatsReader stats;

    private long skyblockSinceMs = -1;
    private boolean done = false;

    public StartupCheck(TrackerData data, LocationTracker location, CollectionTracker collection, StatsReader stats) {
        this.data = data;
        this.location = location;
        this.collection = collection;
        this.stats = stats;
    }

    public void reset() {
        done = false;
        skyblockSinceMs = -1;
    }

    public void tick(Minecraft mc) {
        if (done || mc.player == null) return;
        if (!location.isOnHypixel() || !"SKYBLOCK".equals(location.gametype)) {
            skyblockSinceMs = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (skyblockSinceMs < 0) skyblockSinceMs = now;
        if (now - skyblockSinceMs < DELAY_MS) return;
        done = true;
        report(mc, false);
    }

    /** @param verbose true — показать полный статус (команда /hs status) */
    public void report(Minecraft mc, boolean verbose) {
        boolean ok = true;

        // 1. API-ключ и коллекция
        if (data.hypixelApiKey.isBlank()) {
            ok = false;
            line(mc, Component.literal("✘ Нет API-ключа — без него нет счётчика коллекции и топа. ").withStyle(ChatFormatting.RED)
                    .append(link("[получить ключ]", "https://developer.hypixel.net/dashboard"))
                    .append(Component.literal(" "))
                    .append(suggest("[ввести]", "/hs apikey ")));
        } else if (collection.error() != null) {
            ok = false;
            boolean keyProblem = collection.error().contains("ключ");
            MutableComponent c = Component.literal("✘ Hypixel API: " + collection.error() + " ").withStyle(ChatFormatting.RED);
            if (keyProblem) {
                c.append(link("[обновить ключ]", "https://developer.hypixel.net/dashboard"))
                        .append(Component.literal(" ")).append(suggest("[ввести]", "/hs apikey "));
            } else if (collection.error().contains("коллекций")) {
                c.append(Component.literal("SkyBlock Menu → Settings → API Settings → Collections").withStyle(ChatFormatting.GRAY));
            }
            line(mc, c);
        } else if (verbose) {
            line(mc, Component.literal("✔ Hypixel API работает, коллекция Hard Stone: "
                    + (collection.total() >= 0 ? Fmt.num(collection.total()) : "загрузка...")).withStyle(ChatFormatting.GREEN));
        }

        // 2. Статы (удача / Spread)
        boolean fromTab = stats.tabMf || stats.tabBf || stats.tabSpread;
        if (!fromTab && data.statsFromMenuAt == 0 && data.miningFortune == 0 && data.blockFortune == 0) {
            ok = false;
            line(mc, Component.literal("✘ Не вижу удачу/Spread. Один раз включи в таб-листе виджет ").withStyle(ChatFormatting.RED)
                    .append(Component.literal("Stats").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" со строками Mining Fortune, Block Fortune, Mining Spread ").withStyle(ChatFormatting.RED))
                    .append(Component.literal("(SkyBlock Menu → Settings → Tab Widgets)").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" — или просто открой SkyBlock Menu → Your SkyBlock Profile.").withStyle(ChatFormatting.GRAY)));
        } else if (!fromTab && verbose) {
            line(mc, Component.literal("● Статы из " + stats.source() + " (не обновляются в реальном времени — включи виджет Stats в табе)")
                    .withStyle(ChatFormatting.YELLOW));
        } else if (verbose) {
            line(mc, Component.literal("✔ Статы в реальном времени из таба: удача " + Fmt.num(Math.round(stats.totalFortune()))
                    + ", Spread " + Fmt.num(Math.round(stats.miningSpread()))).withStyle(ChatFormatting.GREEN));
        }

        if (ok) {
            line(mc, Component.literal("✔ Hard Stone Tracker: всё работает автоматически. ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("Удача " + Fmt.num(Math.round(stats.totalFortune()))
                            + ", Spread " + Fmt.num(Math.round(stats.miningSpread()))).withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void line(Minecraft mc, Component c) {
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal("[HS] ").withStyle(ChatFormatting.GOLD).append(c), false);
    }

    private static MutableComponent link(String text, String url) {
        return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
    }

    private static MutableComponent suggest(String text, String cmd) {
        return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd))));
    }

}
