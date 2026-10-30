package dev.hardstone.tracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Set;

/**
 * Определяет, где находится игрок на Hypixel, через команду /locraw
 * (сервер отвечает JSON-строкой вида {"server":"mini12A","gametype":"SKYBLOCK","mode":"crystal_hollows"}).
 * Ответ на наш автоматический запрос скрывается из чата.
 */
public class LocationTracker {
    /** Режимы SkyBlock, где добывается Hard Stone */
    private static final Set<String> MINING_MODES = Set.of("mining_3", "crystal_hollows", "mineshaft");

    private Level lastLevel;
    private long sendAtMs = -1;
    private long awaitingUntilMs = -1;

    public String gametype = null;
    public String mode = null;

    public boolean isOnHypixel() {
        ServerData sd = Minecraft.getInstance().getCurrentServer();
        return sd != null && sd.ip != null && sd.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    public boolean isInMiningArea() {
        return "SKYBLOCK".equals(gametype) && mode != null && MINING_MODES.contains(mode);
    }

    public String prettyLocation() {
        if (!isOnHypixel()) return "не Hypixel";
        if (mode == null) return "определяю...";
        return switch (mode) {
            case "mining_3" -> "Dwarven Mines";
            case "crystal_hollows" -> "Crystal Hollows";
            case "mineshaft" -> "Glacite Mineshaft";
            default -> mode;
        };
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (mc.level != lastLevel) {
            // Смена мира (лобби/остров) — сбрасываем локацию и через 1.5 с спрашиваем заново
            lastLevel = mc.level;
            gametype = null;
            mode = null;
            sendAtMs = (mc.level != null && isOnHypixel()) ? now + 1500 : -1;
        }
        if (sendAtMs > 0 && now >= sendAtMs && mc.player != null) {
            sendAtMs = -1;
            awaitingUntilMs = now + 5000;
            mc.player.connection.sendCommand("locraw");
        }
    }

    /** @return false, если сообщение надо скрыть из чата */
    public boolean onChat(String text) {
        String t = text.trim();
        if (!t.startsWith("{\"server\"")) return true;
        try {
            JsonObject o = JsonParser.parseString(t).getAsJsonObject();
            gametype = o.has("gametype") ? o.get("gametype").getAsString() : null;
            mode = o.has("mode") ? o.get("mode").getAsString() : null;
            if (mode == null && "limbo".equals(o.has("server") ? o.get("server").getAsString() : "")) mode = "limbo";
        } catch (Exception ignored) {
            return true;
        }
        boolean ours = System.currentTimeMillis() < awaitingUntilMs;
        awaitingUntilMs = -1;
        return !ours;
    }
}
