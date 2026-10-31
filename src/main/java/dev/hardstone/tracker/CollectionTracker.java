package dev.hardstone.tracker;

/**
 * Самый точный счётчик: прирост твоей коллекции HARD_STONE по Hypixel API.
 * Учитывает всё — Mining Fortune, Mining Spread, способности, дроп в инвентарь и в сакс.
 * Минус: Hypixel обновляет данные API с задержкой (обычно 1–5 минут).
 */
public class CollectionTracker {
    private static final long INTERVAL_MS = 2 * 60_000;

    private final TrackerData data;
    private final LeaderboardService api;

    private volatile long baseline = -1;
    private volatile long latest = -1;
    private volatile long lastFetchMs = 0;
    private volatile long lastChangeMs = 0;
    private volatile String error = null;
    private volatile boolean running = false;

    public CollectionTracker(TrackerData data, LeaderboardService api) {
        this.data = data;
        this.api = api;
    }

    /** Прирост коллекции за сессию, -1 если ещё неизвестно */
    public long gained() {
        return baseline < 0 || latest < 0 ? -1 : Math.max(0, latest - baseline);
    }

    public long total() { return latest; }
    public String error() { return error; }
    public boolean enabled() { return !data.hypixelApiKey.isBlank(); }

    /** Секунд с момента последнего изменения значения в API */
    public long secondsSinceChange() {
        return lastChangeMs == 0 ? -1 : (System.currentTimeMillis() - lastChangeMs) / 1000;
    }

    public void tick(String selfName, boolean onHypixel) {
        if (!onHypixel || running || !enabled() || selfName == null) return;
        if (System.currentTimeMillis() - lastFetchMs < INTERVAL_MS) return;
        fetch(selfName);
    }

    public void forceRefresh(String selfName) {
        if (!running && enabled() && selfName != null) fetch(selfName);
    }

    private void fetch(String selfName) {
        running = true;
        lastFetchMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                long v = api.fetchCollection(selfName);
                if (v != latest) lastChangeMs = System.currentTimeMillis();
                latest = v;
                if (baseline < 0) baseline = v;
                error = null;
            } catch (LeaderboardService.ApiException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "ошибка сети";
            } finally {
                running = false;
            }
        }, "HardStone-Collection");
        t.setDaemon(true);
        t.start();
    }

    /** Новая сессия: текущее значение становится точкой отсчёта */
    public void resetSession() {
        baseline = latest;
    }
}
