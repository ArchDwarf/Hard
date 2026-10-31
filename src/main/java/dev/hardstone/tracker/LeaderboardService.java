package dev.hardstone.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Топ по Hard Stone через официальный Hypixel API v2 (нужен только API-ключ).
 *
 * 1) Берётся официальный лидерборд Hypixel: GET /v2/leaderboards. Ищется доска, связанная с Hard Stone
 *    (или та, что выбрана командой /hs top board <path>). Hypixel отдаёт там только UUID лидеров,
 *    поэтому ники берутся у Mojang, а количество Hard Stone — из /v2/skyblock/profiles.
 * 2) Если такой доски у Hypixel нет — топ строится по своему списку игроков (/hs top add <ник>).
 */
public class LeaderboardService {
    public record Entry(String name, long hardStone, String error) {}
    public record Board(String game, String path, String prefix, String title, List<String> leaders) {
        String label() { return game + " / " + (prefix == null ? "" : prefix + " ") + title + " [" + path + "]"; }
    }

    private static final long AUTO_REFRESH_MS = 10 * 60_000;
    private static final int OFFICIAL_LIMIT = 10;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> uuidByName = new ConcurrentHashMap<>();
    private final Map<String, String> nameByUuid = new ConcurrentHashMap<>();
    private final TrackerData data;

    private volatile List<Entry> top = Collections.emptyList();
    private volatile List<Board> boards = Collections.emptyList();
    private volatile String source = "";
    private volatile long lastUpdateMs = 0;
    private volatile boolean running = false;

    public LeaderboardService(TrackerData data) {
        this.data = data;
    }

    public List<Entry> top() { return top; }
    public List<Board> boards() { return boards; }
    public String source() { return source; }
    public long lastUpdateMs() { return lastUpdateMs; }

    public void autoRefresh(String selfName) {
        if (!running && !data.hypixelApiKey.isBlank()
                && System.currentTimeMillis() - lastUpdateMs > AUTO_REFRESH_MS) {
            refresh(selfName, null);
        }
    }

    /** Асинхронно обновляет топ; done вызывается из фонового потока */
    public void refresh(String selfName, Consumer<String> done) {
        if (running) { if (done != null) done.accept("Обновление уже идёт..."); return; }
        if (data.hypixelApiKey.isBlank()) {
            if (done != null) done.accept("Нет API-ключа. Получи на developer.hypixel.net и введи /hs apikey <ключ>");
            return;
        }
        running = true;
        lastUpdateMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            String msg;
            try {
                msg = doRefresh(selfName);
            } catch (ApiException e) {
                msg = "Ошибка: " + e.getMessage();
            } catch (Exception e) {
                HardStoneTrackerClient.LOGGER.warn("Hypixel API", e);
                msg = "Ошибка сети: " + e.getMessage();
            }
            lastUpdateMs = System.currentTimeMillis();
            running = false;
            if (done != null) done.accept(msg);
        }, "HardStone-Leaderboard");
        t.setDaemon(true);
        t.start();
    }

    private String doRefresh(String selfName) throws Exception {
        // --- 1. официальные лидерборды Hypixel
        Board board = null;
        String note = "";
        try {
            boards = fetchBoards();
            board = pickBoard(boards);
        } catch (ApiException e) {
            if (e.fatal) throw e;
            note = " (/v2/leaderboards: " + e.getMessage() + ")";
        }

        // ник -> uuid, в порядке топа
        Map<String, String> people = new LinkedHashMap<>();
        if (board != null) {
            for (String raw : board.leaders()) {
                if (people.size() >= OFFICIAL_LIMIT) break;
                String uuid = raw.replace("-", "");
                people.put(resolveName(uuid), uuid);
                Thread.sleep(150);
            }
            source = "официальный топ Hypixel: " + board.label();
        } else {
            for (String name : data.trackedPlayers) people.put(name, null);
            source = "свой список игроков (у Hypixel не найден топ Hard Stone)" + note;
        }
        if (selfName != null && people.keySet().stream().noneMatch(selfName::equalsIgnoreCase)) people.put(selfName, null);

        // --- 2. количество Hard Stone у каждого
        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, String> p : people.entrySet()) {
            try {
                String uuid = p.getValue() != null ? p.getValue() : resolveUuid(p.getKey());
                result.add(new Entry(p.getKey(), fetchHardStone(uuid), null));
            } catch (ApiException e) {
                result.add(new Entry(p.getKey(), -1, e.getMessage()));
                if (e.fatal) break;
            } catch (Exception e) {
                result.add(new Entry(p.getKey(), -1, "ошибка сети"));
            }
            Thread.sleep(300);
        }
        result.sort((a, b) -> Long.compare(b.hardStone(), a.hardStone()));
        top = result;
        return "Топ обновлён — " + source;
    }

    // ------------------------------------------------------------ официальные лидерборды

    private List<Board> fetchBoards() throws Exception {
        HttpResponse<String> r = get("https://api.hypixel.net/v2/leaderboards", data.hypixelApiKey);
        checkHypixel(r);
        JsonObject lb = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("leaderboards");
        List<Board> list = new ArrayList<>();
        if (lb == null) return list;
        for (Map.Entry<String, JsonElement> game : lb.entrySet()) {
            if (!game.getValue().isJsonArray()) continue;
            for (JsonElement be : game.getValue().getAsJsonArray()) {
                JsonObject b = be.getAsJsonObject();
                List<String> leaders = new ArrayList<>();
                JsonArray arr = b.has("leaders") ? b.getAsJsonArray("leaders") : new JsonArray();
                for (JsonElement l : arr) leaders.add(l.getAsString());
                list.add(new Board(game.getKey(), str(b, "path"), str(b, "prefix"), str(b, "title"), leaders));
            }
        }
        return list;
    }

    private Board pickBoard(List<Board> all) {
        String forced = data.officialBoardPath == null ? "" : data.officialBoardPath.trim();
        for (Board b : all) {
            if (!forced.isEmpty() && forced.equalsIgnoreCase(b.path()) && !b.leaders().isEmpty()) return b;
        }
        if (!forced.isEmpty()) return null;
        for (Board b : all) {
            String s = (b.game() + " " + b.path() + " " + b.prefix() + " " + b.title()).toLowerCase(Locale.ROOT);
            if ((s.contains("hard_stone") || s.contains("hard stone") || s.contains("hardstone")) && !b.leaders().isEmpty()) {
                return b;
            }
        }
        return null;
    }

    /** Доски, которые стоит показать пользователю (SkyBlock / майнинг), либо список игр */
    public List<String> describeBoards() {
        List<String> out = new ArrayList<>();
        for (Board b : boards) {
            String s = (b.game() + " " + b.path() + " " + b.title()).toLowerCase(Locale.ROOT);
            if (s.contains("skyblock") || s.contains("mining") || s.contains("stone") || s.contains("collection")) {
                out.add(b.label());
            }
        }
        if (out.isEmpty()) {
            List<String> games = new ArrayList<>();
            for (Board b : boards) if (!games.contains(b.game())) games.add(b.game());
            if (!games.isEmpty()) out.add("SkyBlock-досок нет. Игры в /v2/leaderboards: " + String.join(", ", games));
        }
        return out;
    }

    // ------------------------------------------------------------ игроки

    /** Коллекция HARD_STONE игрока (блокирующий вызов, звать из фонового потока) */
    public long fetchCollection(String name) throws Exception {
        return fetchHardStone(resolveUuid(name));
    }

    private String resolveUuid(String name) throws Exception {
        String key = name.toLowerCase(Locale.ROOT);
        String cached = uuidByName.get(key);
        if (cached != null) return cached;
        HttpResponse<String> r = get("https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8), null);
        if (r.statusCode() != 200) throw new ApiException("ник не найден", false);
        String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
        uuidByName.put(key, id);
        return id;
    }

    private String resolveName(String uuid) {
        String cached = nameByUuid.get(uuid);
        if (cached != null) return cached;
        try {
            HttpResponse<String> r = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid, null);
            if (r.statusCode() == 200) {
                String name = JsonParser.parseString(r.body()).getAsJsonObject().get("name").getAsString();
                nameByUuid.put(uuid, name);
                uuidByName.put(name.toLowerCase(Locale.ROOT), uuid);
                return name;
            }
        } catch (Exception ignored) { }
        return uuid.substring(0, 8) + "…";
    }

    private long fetchHardStone(String uuid) throws Exception {
        HttpResponse<String> r = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid, data.hypixelApiKey);
        checkHypixel(r);
        JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
        if (!root.has("profiles") || root.get("profiles").isJsonNull()) throw new ApiException("нет SkyBlock профилей", false);

        long selected = -1, best = -1;
        boolean anyApiOn = false;
        for (JsonElement pe : root.getAsJsonArray("profiles")) {
            JsonObject profile = pe.getAsJsonObject();
            JsonObject member = profile.getAsJsonObject("members").getAsJsonObject(uuid);
            if (member == null || !member.has("collection")) continue;
            anyApiOn = true;
            JsonObject col = member.getAsJsonObject("collection");
            long v = col.has("HARD_STONE") ? col.get("HARD_STONE").getAsLong() : 0;
            best = Math.max(best, v);
            if (profile.has("selected") && profile.get("selected").getAsBoolean()) selected = v;
        }
        if (!anyApiOn) throw new ApiException("API коллекций выключен", false);
        return selected >= 0 ? selected : best;
    }

    // ------------------------------------------------------------ http

    private static void checkHypixel(HttpResponse<String> r) throws ApiException {
        switch (r.statusCode()) {
            case 200 -> { }
            case 403 -> throw new ApiException("неверный/просроченный API-ключ", true);
            case 429 -> throw new ApiException("лимит запросов Hypixel, попробуй позже", true);
            case 503 -> throw new ApiException("данные Hypixel ещё не готовы, повтори через минуту", false);
            default -> throw new ApiException("Hypixel API HTTP " + r.statusCode(), false);
        }
    }

    private HttpResponse<String> get(String url, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "HardStoneTracker/1.1");
        if (apiKey != null) b.header("API-Key", apiKey);
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    static class ApiException extends Exception {
        final boolean fatal;
        ApiException(String msg, boolean fatal) { super(msg); this.fatal = fatal; }
    }
}
