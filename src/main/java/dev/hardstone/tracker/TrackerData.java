package dev.hardstone.tracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Настройки + постоянная статистика. Хранится в config/hardstonetracker.json */
public class TrackerData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("hardstonetracker.json");

    // ---- настройки ----
    public boolean hudEnabled = true;
    public boolean hudShowTop = true;
    public int hudX = 4;
    public int hudY = 4;
    public boolean hudBackground = true;
    /** Скрытые строки HUD (ключи из HudRenderer.LINES) */
    public List<String> hudHidden = new ArrayList<>(defaultHidden());

    public static List<String> defaultHidden() {
        return List.of("fortune", "blocks");
    }
    /** Считать блоки в любом месте (для теста в одиночке), а не только в шахтах Hypixel */
    public boolean countEverywhere = false;
    /** Читать Mining Fortune / Block Fortune / Mining Spread из таб-листа */
    public boolean autoStats = true;
    /** Ручные значения (если в табе их нет) */
    public double miningFortune = 0;
    public double blockFortune = 0;
    public double miningSpread = 0;
    /** Когда статы последний раз прочитаны из меню профиля (0 = никогда) */
    public long statsFromMenuAt = 0;
    /** Глобальное место в официальном топе Hypixel по коллекции Hard Stone (0 = неизвестно) */
    public long globalRank = 0;
    public double globalTopPercent = 0;
    public long globalRankAt = 0;
    /** Когда Hypixel обновит лидерборд (если указано в меню), 0 = неизвестно */
    public long globalNextUpdateAt = 0;
    /** Имя профиля SkyBlock (из сообщения "You are playing on profile: X") */
    public String profileName = "";
    /** Строка о рейтинге из подсказки Hard Stone как есть (если нет номера места) */
    public String globalRankText = "";
    /** Коллекция Hard Stone из меню Collections (в игре) */
    public long menuCollection = 0;
    /** Секунд без добычи, после которых таймер сессии встаёт на паузу */
    public int afkSeconds = 60;
    public String hypixelApiKey = "";
    public List<String> trackedPlayers = new ArrayList<>();
    /** path официального лидерборда Hypixel; пусто = искать Hard Stone автоматически */
    public String officialBoardPath = "";

    // ---- статистика за всё время ----
    public long allTimeBlocks = 0;
    public long allTimeSackItems = 0;
    public long allTimeActiveMs = 0;
    public double bestBlocksPerHour = 0;
    /** Рекорд Hard Stone в час по данным API (замер не короче 10 минут) */
    public double bestApiPerHour = 0;
    public List<SessionRecord> sessions = new ArrayList<>();

    public static class SessionRecord {
        public long endedAt;
        public long blocks;
        public long sackItems;
        public long activeMs;

        public double perHour() {
            return activeMs < 1000 ? 0 : blocks / (activeMs / 3_600_000.0);
        }
    }

    public static TrackerData load() {
        try {
            if (Files.exists(FILE)) {
                TrackerData d = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), TrackerData.class);
                if (d != null) {
                    if (d.trackedPlayers == null) d.trackedPlayers = new ArrayList<>();
                    if (d.hudHidden == null) d.hudHidden = new ArrayList<>(defaultHidden());
                    if (d.sessions == null) d.sessions = new ArrayList<>();
                    if (d.hypixelApiKey == null) d.hypixelApiKey = "";
                    return d;
                }
            }
        } catch (Exception e) {
            HardStoneTrackerClient.LOGGER.warn("Не удалось прочитать конфиг, создаю новый", e);
        }
        return new TrackerData();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            HardStoneTrackerClient.LOGGER.warn("Не удалось сохранить конфиг", e);
        }
    }
}
