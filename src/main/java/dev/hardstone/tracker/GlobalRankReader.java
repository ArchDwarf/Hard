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
            for (Component line : lore.lines()) {
                String t = line.getString().replaceAll("§.", "");
                String low = t.toLowerCase(Locale.ROOT);
                if (rank == null && (low.contains("leaderboard") || low.contains("position")
                        || low.contains("rank") || low.contains("place") || low.trim().startsWith("#"))) {
                    Matcher m = RANK.matcher(t);
                    if (m.find()) rank = parseLong(m.group(1));
                }
                if (pct == null) {
                    Matcher m = PERCENT.matcher(t);
                    if (m.find()) {
                        try { pct = Double.parseDouble(m.group(1).replace(",", ".")); } catch (NumberFormatException ignored) { }
                    }
                }
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

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean known() { return data.globalRank > 0; }

    public String describe() {
        if (!known()) return "неизвестно";
        String s = "#" + Fmt.num(data.globalRank);
        if (data.globalTopPercent > 0) s += " (топ " + data.globalTopPercent + "%)";
        long h = (System.currentTimeMillis() - data.globalRankAt) / 3_600_000;
        if (h >= 1) s += ", " + h + " ч назад";
        return s;
    }
}
