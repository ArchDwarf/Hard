package dev.hardstone.tracker;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Считает добытый Hard Stone (в шахтах Hypixel это обычный minecraft:stone).
 *
 * <p>Блоки. Каждый тик сканируется куб радиусом {@link #R} вокруг игрока и запоминаются все блоки камня.
 * Если камень исчез, он засчитывается, когда это связано с твоей добычей:
 * <ul>
 *   <li>блок находится рядом с тем, по которому ты бьёшь (или к которому применил способность ПКМ),</li>
 *   <li>или вплотную к блоку, засчитанному только что (цепочки Mining Spread / Pickobulus).</li>
 * </ul>
 * Так учитываются все блоки, сломанные за один удар: Mining Spread (шанс сломать соседние),
 * способности вроде Pickobulus и т.п. Блоки, которые ломают другие игроки в стороне, не засчитываются.
 *
 * <p>Дроп. Сообщение "[Sacks] +N items." содержит в подсказке "+123 Hard Stone" — реальный дроп
 * с учётом Mining Fortune.
 */
public class MiningTracker {
    private static final Pattern SACK_LINE = Pattern.compile("\\+([\\d,]+) Hard Stone\\b");

    /** Радиус сканирования вокруг игрока */
    private static final int R = 6;
    /** Сколько после удара/ПКМ считаем, что игрок копает */
    private static final long ACTIVE_MS = 1500;
    /** Макс. расстояние (в квадрате) от блока под прицелом до засчитываемого блока */
    private static final int NEAR_TARGET_SQ = 5 * 5;
    /** Макс. расстояние (в квадрате) до недавно засчитанного блока для цепочек Spread */
    private static final int CHAIN_SQ = 3;
    private static final long CHAIN_MS = 2000;

    private final TrackerData data;
    private final LocationTracker location;

    private LongOpenHashSet prevStone = new LongOpenHashSet(4096);
    private LongOpenHashSet curStone = new LongOpenHashSet(4096);
    private final Long2LongOpenHashMap recentMined = new Long2LongOpenHashMap();
    private int prevCx, prevCy, prevCz;
    private boolean havePrev = false;

    private BlockPos lastTarget;
    private long lastTargetMs = 0;
    private long lastActionMs = 0;

    // ---- текущая сессия ----
    public long sessionBlocks = 0;
    /** Блоки, по которым ты бил напрямую (без Spread/способностей) */
    public long sessionHits = 0;
    public long sessionSackItems = 0;
    public long sessionActiveMs = 0;
    /** Сколько блоков сломано последним ударом (для HUD) */
    public int lastBurst = 0;
    private long lastBurstMs = 0;
    private long lastMinedMs = 0;
    private long lastTickMs = 0;
    private Level lastLevel;
    private long dirtySinceMs = -1;

    public MiningTracker(TrackerData data, LocationTracker location) {
        this.data = data;
        this.location = location;
    }

    public boolean isCountingHere() {
        return data.countEverywhere || location.isInMiningArea();
    }

    public boolean isPaused() {
        return System.currentTimeMillis() - lastMinedMs > data.afkSeconds * 1000L;
    }

    public double perHour() {
        return sessionActiveMs < 10_000 ? 0 : sessionBlocks / (sessionActiveMs / 3_600_000.0);
    }

    /** Фактический Spread за сессию: доп. блоков на 100 ударов */
    public double actualSpread() {
        return sessionHits == 0 ? 0 : (sessionBlocks - sessionHits) * 100.0 / sessionHits;
    }

    public double sackPerHour() {
        return sessionActiveMs < 10_000 ? 0 : sessionSackItems / (sessionActiveMs / 3_600_000.0);
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        long dt = lastTickMs == 0 ? 0 : Math.min(now - lastTickMs, 1000);
        lastTickMs = now;

        if (mc.level != lastLevel) {
            // Смена мира: блоки «исчезают» при загрузке — не считаем это добычей
            lastLevel = mc.level;
            havePrev = false;
            recentMined.clear();
        }
        if (mc.level == null || mc.player == null) {
            havePrev = false;
            return;
        }

        if (!isPaused()) {
            sessionActiveMs += dt;
            data.allTimeActiveMs += dt;
        }

        if (!isCountingHere()) {
            havePrev = false;
            saveIfNeeded(now);
            return;
        }

        // 1. Что делает игрок: ЛКМ (копание) или ПКМ (способность вроде Pickobulus) по блоку
        boolean attack = mc.options.keyAttack.isDown();
        boolean use = mc.options.keyUse.isDown();
        if (attack || use) {
            lastActionMs = now;
            if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                lastTarget = bhr.getBlockPos().immutable();
                lastTargetMs = now;
            }
        }

        // 2. Сканируем камень вокруг игрока
        BlockPos center = mc.player.blockPosition();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        curStone.clear();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cx - R; x <= cx + R; x++) {
            for (int y = cy - R; y <= cy + R; y++) {
                for (int z = cz - R; z <= cz + R; z++) {
                    m.set(x, y, z);
                    if (mc.level.getBlockState(m).is(Blocks.STONE)) curStone.add(m.asLong());
                }
            }
        }

        // 3. Ищем исчезнувший камень (только если игрок не телепортировался)
        boolean teleported = Math.abs(cx - prevCx) > 4 || Math.abs(cy - prevCy) > 4 || Math.abs(cz - prevCz) > 4;
        if (havePrev && !teleported && now - lastActionMs < ACTIVE_MS) {
            int mined = 0, hits = 0;
            long target = lastTarget != null ? lastTarget.asLong() : Long.MIN_VALUE;
            LongIterator it = prevStone.iterator();
            while (it.hasNext()) {
                long l = it.nextLong();
                if (curStone.contains(l)) continue;
                int x = BlockPos.getX(l), y = BlockPos.getY(l), z = BlockPos.getZ(l);
                // блок мог просто выйти из зоны сканирования
                if (Math.abs(x - cx) > R || Math.abs(y - cy) > R || Math.abs(z - cz) > R) continue;
                if (isRelatedToPlayer(x, y, z, now)) {
                    recentMined.put(l, now);
                    mined++;
                    if (l == target) hits++;
                }
            }
            if (mined > 0) onBlocksMined(mined, Math.max(hits, 1), now);
        }

        // чистим старые записи цепочек
        if (!recentMined.isEmpty()) recentMined.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > CHAIN_MS);

        LongOpenHashSet t = prevStone;
        prevStone = curStone;
        curStone = t;
        prevCx = cx; prevCy = cy; prevCz = cz;
        havePrev = true;

        if (now - lastBurstMs > 3000) lastBurst = 0;
        saveIfNeeded(now);
    }

    private boolean isRelatedToPlayer(int x, int y, int z, long now) {
        if (lastTarget != null && now - lastTargetMs < ACTIVE_MS) {
            int dx = x - lastTarget.getX(), dy = y - lastTarget.getY(), dz = z - lastTarget.getZ();
            if (dx * dx + dy * dy + dz * dz <= NEAR_TARGET_SQ) return true;
        }
        // цепочка: рядом с только что засчитанным блоком
        for (var e : recentMined.long2LongEntrySet()) {
            long p = e.getLongKey();
            int dx = x - BlockPos.getX(p), dy = y - BlockPos.getY(p), dz = z - BlockPos.getZ(p);
            if (dx * dx + dy * dy + dz * dz <= CHAIN_SQ) return true;
        }
        return false;
    }

    private void onBlocksMined(int count, int hits, long now) {
        sessionHits += hits;
        if (isPaused()) lastTickMs = now; // выходим из паузы без «прыжка» таймера
        lastMinedMs = now;
        sessionBlocks += count;
        data.allTimeBlocks += count;
        lastBurst = (now - lastBurstMs < 300) ? lastBurst + count : count;
        lastBurstMs = now;
        if (sessionActiveMs > 5 * 60_000 && perHour() > data.bestBlocksPerHour) data.bestBlocksPerHour = perHour();
        markDirty();
    }

    /** Разбор сообщения "[Sacks] +N items." */
    public void onChat(Component message) {
        String plain = message.getString();
        if (!plain.startsWith("[Sacks] +")) return;
        String hover = findHoverText(message);
        if (hover == null) return;
        Matcher mt = SACK_LINE.matcher(hover.replaceAll("§.", ""));
        long added = 0;
        while (mt.find()) {
            try {
                added += Long.parseLong(mt.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (added > 0) {
            sessionSackItems += added;
            data.allTimeSackItems += added;
            markDirty();
        }
    }

    private static String findHoverText(Component c) {
        HoverEvent h = c.getStyle().getHoverEvent();
        if (h instanceof HoverEvent.ShowText st) return st.value().getString();
        for (Component sibling : c.getSiblings()) {
            String r = findHoverText(sibling);
            if (r != null) return r;
        }
        return null;
    }

    /** Завершает сессию: кладёт её в историю и обнуляет счётчики */
    public void endSession() {
        if (sessionBlocks > 0 || sessionSackItems > 0) {
            TrackerData.SessionRecord r = new TrackerData.SessionRecord();
            r.endedAt = System.currentTimeMillis();
            r.blocks = sessionBlocks;
            r.sackItems = sessionSackItems;
            r.activeMs = sessionActiveMs;
            data.sessions.add(r);
            data.sessions.sort((a, b) -> Long.compare(b.blocks, a.blocks));
            while (data.sessions.size() > 20) data.sessions.remove(data.sessions.size() - 1);
        }
        sessionBlocks = 0;
        sessionHits = 0;
        sessionSackItems = 0;
        sessionActiveMs = 0;
        lastMinedMs = 0;
        lastBurst = 0;
        recentMined.clear();
        havePrev = false;
        data.save();
        dirtySinceMs = -1;
    }

    private void markDirty() {
        if (dirtySinceMs < 0) dirtySinceMs = System.currentTimeMillis();
    }

    private void saveIfNeeded(long now) {
        if (dirtySinceMs > 0 && now - dirtySinceMs > 30_000) {
            data.save();
            dirtySinceMs = -1;
        }
    }
}
