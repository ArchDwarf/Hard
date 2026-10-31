package dev.hardstone.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Глобальное место в официальном топе Hypixel по коллекции Hard Stone (среди ВСЕХ игроков, личная коллекция).
 * Hypixel не отдаёт этот топ в публичном API — он есть только в игре:
 * SkyBlock Menu → Collections → кнопка-картина вверху (лидерборды) → наведение на Hard Stone.
 * Мод читает место из описания предмета, когда это меню открыто.
 */
public class GlobalRankReader {
    private static final Pattern RANK = Pattern.compile("#\\s*([\\d,]+)");
    private static final Pattern DUR = Pattern.compile("(\\d+)\\s*([dhms])\\b");
    private static final Pattern PERCENT = Pattern.compile("[Tt]op\\s*([\\d.,]+)\\s*%");

    private final TrackerData data;
    private long lastScanMs = 0;

    public GlobalRankReader(TrackerData data) {
        this.data = data;
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastScanMs < 500) return;
        lastScanMs = now;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> scr)) return;
        String title = scr.getTitle().getString().toLowerCase(Locale.ROOT);
        if (!(title.contains("collection") || title.contains("leaderboard") || title.contains("mining"))) return;

        for (Slot slot : scr.getMenu().slots) {
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().replaceAll("§.", "");
            if (!name.contains("Hard Stone")) continue;
            ItemLore lore = st.get(DataComponents.LORE);
            if (lore == null) continue;
            Long rank = null;
            Double pct = null;
            Long updateIn = null;
            String rankText = null;
            StringBuilder dump = new StringBuilder("[" + scr.getTitle().getString() + "] " + name + "\n");
            for (Component line : lore.lines()) {
                String t = line.getString().replaceAll("§.", "");
                String low = t.toLowerCase(Locale.ROOT);
                dump.append(t).append('\n');
                if (rankText == null && (low.contains("leaderboard") || low.contains("rank") || low.contains("position")
                        || low.contains("place") || low.matches(".*top\\s*[\\d.,]+\\s*%.*") || low.trim().startsWith("#"))) {
                    rankText = t.trim();
                }
                if (rank == null && (low.contains("leaderboard") || low.contains("position")
                        || low.contains("rank") || low.contains("place") || low.trim().startsWith("#"))) {
                    Matcher m = RANK.matcher(t);
                    if (m.find()) rank = parseLong(m.group(1));
                }
                if (updateIn == null && (low.contains("update") || low.contains("refresh") || low.contains("reset"))) {
                    updateIn = parseDuration(low);
                }
                if (pct == null) {
                    Matcher m = PERCENT.matcher(t);
                    if (m.find()) {
                        try { pct = Double.parseDouble(m.group(1).replace(",", ".")); } catch (NumberFormatException ignored) { }
                    }
                }
            }
            if (updateIn != null && updateIn > 0) data.globalNextUpdateAt = now + updateIn * 1000;
            saveDump(dump.toString());
            if (pct != null) data.globalTopPercent = pct;
            if (rankText != null && !rankText.equals(data.globalRankText)) {
                data.globalRankText = rankText;
                data.globalRankAt = now;
                data.save();
            } else if (rankText != null) {
                data.globalRankAt = now;
            }
            if (rank != null && rank > 0) {
                if (rank != data.globalRank || (pct != null && pct != data.globalTopPercent)) {
                    data.globalRank = rank;
                    if (pct != null) data.globalTopPercent = pct;
                    data.globalRankAt = now;
                    data.save();
                } else {
                    data.globalRankAt = now;
                }
                return;
            }
        }
    }

    /** "1h 23m", "5m 10s", "2d 3h" → секунды */
    private static Long parseDuration(String s) {
        Matcher m = DUR.matcher(s);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d" -> total += v * 86400;
                case "h" -> total += v * 3600;
                case "m" -> total += v * 60;
                default -> total += v;
            }
            any = true;
        }
        return any ? total : null;
    }

    /** Секунд до обновления топа: &gt;0 — отсчёт, 0 — уже обновился, -1 — неизвестно */
    public long secondsUntilUpdate() {
        if (data.globalNextUpdateAt <= 0) return -1;
        return Math.max(0, (data.globalNextUpdateAt - System.currentTimeMillis()) / 1000);
    }

    public String ago() {
        long m = (System.currentTimeMillis() - data.globalRankAt) / 60_000;
        if (m < 60) return m + " мин";
        if (m < 48 * 60) return (m / 60) + " ч";
        return (m / 1440) + " дн";
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean known() {
        return data.globalRank > 0 || (data.globalRankText != null && !data.globalRankText.isBlank());
    }

    private String lastDump = "";

    /** Сохраняет подсказку Hard Stone в config/hardstonetracker-lore.txt (для настройки распознавания) */
    private void saveDump(String text) {
        if (text.equals(lastDump)) return;
        lastDump = text;
        try {
            java.nio.file.Files.writeString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("hardstonetracker-lore.txt"), text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public String describe() {
        if (!known()) return "неизвестно";
        if (data.globalRank <= 0) return data.globalRankText;
        String s = "#" + Fmt.num(data.globalRank);
        if (data.globalTopPercent > 0) s += " (топ " + data.globalTopPercent + "%)";
        return s;
    }
}
