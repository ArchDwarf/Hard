package dev.hardstone.tracker;

/**
 * Самый точный счётчик: прирост твоей коллекции HARD_STONE по Hypixel API.
 * Учитывает всё — Mining Fortune, Mining Spread, способности, дроп в инвентарь и в сакс.
 * Минус: Hypixel обновляет данные API с задержкой (обычно 1–5 минут).
 */
public class CollectionTracker {
    /** Раз в 30 с = 10 запросов за 5 мин (лимит ключа Hypixel — 300 за 5 мин) */
    private static final long INTERVAL_MS = 30_000;
    /** Пауза после ответа 429 (лимит превышен) */
    private static final long BACKOFF_MS = 5 * 60_000;
    private volatile long backoffUntil = 0;

    private final TrackerData data;
    private final LeaderboardService api;

    private volatile long baseline = -1;
    private volatile long latest = -1;
    private volatile long lastFetchMs = 0;
    private volatile long lastChangeMs = 0;
    private volatile String error = null;
    private volatile boolean running = false;
    /** Точки (время, значение) в моменты, когда коллекция в API изменилась */
    private final java.util.ArrayDeque<long[]> samples = new java.util.ArrayDeque<>();
    private static final long RATE_WINDOW_MS = 60 * 60_000;
    private static final long IDLE_MS = 10 * 60_000;

    public CollectionTracker(TrackerData data, LeaderboardService api) {
        this.data = data;
        this.api = api;
    }

    /** Прирост коллекции за сессию, -1 если ещё неизвестно */
    public long gained() {
        return baseline < 0 || latest < 0 ? -1 : Math.max(0, latest - baseline);
    }

    public long total() { return latest; }

    /**
     * Скорость по данным API: прирост коллекции за последний час (или с начала сессии, если меньше часа),
     * пересчитывается при каждом обновлении API. -1 — пока мало данных.
     */
    public synchronized double perHour() {
        if (samples.size() < 2) return -1;
        long[] last = samples.peekLast();
        long[] first = samples.peekFirst();
        for (long[] s : samples) {
            if (last[0] - s[0] <= RATE_WINDOW_MS) { first = s; break; }
        }
        if (first == last) {
            // в окне один замер — берём предыдущий
            long[] prev = null;
            for (long[] s : samples) { if (s == last) break; prev = s; }
            if (prev == null) return -1;
            first = prev;
        }
        double hours = (last[0] - first[0]) / 3_600_000.0;
        if (hours <= 0) return -1;
        double rate = (last[1] - first[1]) / hours;
        // рекорд — только по замерам не короче 10 минут, чтобы скачок API не дал ложный рекорд
        if (last[0] - first[0] >= 10 * 60_000 && rate > data.bestApiPerHour) {
            data.bestApiPerHour = rate;
            data.save();
        }
        return rate;
    }

    /** true — давно (10+ мин) нет прироста в API: добыча, видимо, остановлена */
    public boolean idle() {
        return lastChangeMs > 0 && System.currentTimeMillis() - lastChangeMs > IDLE_MS;
    }
    public boolean isLoading() { return running; }

    /** Секунд до следующего запроса к API, -1 если ещё не запрашивали */
    public long secondsUntilNext() {
        if (lastFetchMs == 0) return -1;
        long at = Math.max(lastFetchMs + INTERVAL_MS, backoffUntil);
        return Math.max(0, (at - System.currentTimeMillis()) / 1000);
    }
    public String error() { return error; }
    public boolean enabled() { return !data.hypixelApiKey.isBlank(); }

    /** Секунд с момента последнего изменения значения в API */
    public long secondsSinceChange() {
        return lastChangeMs == 0 ? -1 : (System.currentTimeMillis() - lastChangeMs) / 1000;
    }

    public void tick(String selfName, boolean onHypixel) {
        if (!onHypixel || running || !enabled() || selfName == null) return;
        long now = System.currentTimeMillis();
        if (now < backoffUntil || now - lastFetchMs < INTERVAL_MS) return;
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
                long now = System.currentTimeMillis();
                if (v != latest) {
                    lastChangeMs = now;
                    synchronized (this) {
                        samples.addLast(new long[]{now, v});
                        while (samples.size() > 200) samples.pollFirst();
                    }
                }
                latest = v;
                if (baseline < 0) baseline = v;
                error = null;
            } catch (LeaderboardService.ApiException e) {
                error = e.getMessage();
                if (e.getMessage() != null && e.getMessage().contains("лимит")) {
                    backoffUntil = System.currentTimeMillis() + BACKOFF_MS;
                }
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
    public synchronized void resetSession() {
        baseline = latest;
        samples.clear();
        if (latest >= 0) samples.addLast(new long[]{System.currentTimeMillis(), latest});
    }
}
