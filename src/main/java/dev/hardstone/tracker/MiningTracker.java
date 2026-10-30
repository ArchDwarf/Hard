package dev.hardstone.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Считает добытый Hard Stone двумя способами:
 * 1) Блоки: Hard Stone в шахтах Hypixel — это обычный minecraft:stone. Запоминаем камень, по которому игрок бьёт,
 *    и засчитываем его, когда сервер заменяет его другим блоком (воздух / бедрок при регенерации).
 * 2) Предметы: сообщение "[Sacks] +N items." содержит в подсказке строки "+123 Hard Stone (Mining Sack)" —
 *    это реальный дроп с учётом Mining Fortune.
 */
public class MiningTracker {
    private static final Pattern SACK_LINE = Pattern.compile("\\+([\\d,]+) Hard Stone\\b");
    private static final long WATCH_MS = 2500;

    private final TrackerData data;
    private final LocationTracker location;
    private final Map<BlockPos, Long> watched = new HashMap<>();

    // ---- текущая сессия ----
    public long sessionBlocks = 0;
    public long sessionSackItems = 0;
    public long sessionActiveMs = 0;
    private long lastMinedMs = 0;
    private long lastTickMs = 0;
    private Object lastLevel;
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

    public double sackPerHour() {
        return sessionActiveMs < 10_000 ? 0 : sessionSackItems / (sessionActiveMs / 3_600_000.0);
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        long dt = lastTickMs == 0 ? 0 : Math.min(now - lastTickMs, 1000);
        lastTickMs = now;

        if (mc.level != lastLevel) {
            // При смене мира блоки «исчезают» — не считаем это добычей
            lastLevel = mc.level;
            watched.clear();
        }
        if (mc.level == null || mc.player == null) return;

        if (!isPaused()) {
            sessionActiveMs += dt;
            data.allTimeActiveMs += dt;
        }

        if (isCountingHere()) {
            // 1. Запоминаем камень под прицелом, пока зажата атака
            if (mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = bhr.getBlockPos().immutable();
                if (mc.level.getBlockState(pos).is(Blocks.STONE)) watched.put(pos, now);
            }
            // 2. Проверяем, не исчез ли запомненный камень
            Vec3 eye = mc.player.getEyePosition();
            Iterator<Map.Entry<BlockPos, Long>> it = watched.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> e = it.next();
                BlockPos pos = e.getKey();
                if (!mc.level.getBlockState(pos).is(Blocks.STONE)) {
                    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) < 10 * 10) onBlockMined(now);
                    it.remove();
                } else if (now - e.getValue() > WATCH_MS) {
                    it.remove();
                }
            }
        } else {
            watched.clear();
        }

        if (dirtySinceMs > 0 && now - dirtySinceMs > 30_000) {
            data.save();
            dirtySinceMs = -1;
        }
    }

    private void onBlockMined(long now) {
        if (isPaused()) lastTickMs = now; // выходим из паузы без «прыжка» таймера
        lastMinedMs = now;
        sessionBlocks++;
        data.allTimeBlocks++;
        if (sessionActiveMs > 5 * 60_000 && perHour() > data.bestBlocksPerHour) data.bestBlocksPerHour = perHour();
        markDirty();
    }

    /** Разбор сообщения "[Sacks] +N items." */
    public void onChat(Component message) {
        String plain = message.getString();
        if (!plain.startsWith("[Sacks] +")) return;
        String hover = findHoverText(message);
        if (hover == null) return;
        Matcher m = SACK_LINE.matcher(hover.replaceAll("§.", ""));
        long added = 0;
        while (m.find()) {
            try {
                added += Long.parseLong(m.group(1).replace(",", ""));
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
        sessionSackItems = 0;
        sessionActiveMs = 0;
        lastMinedMs = 0;
        watched.clear();
        data.save();
        dirtySinceMs = -1;
    }

    private void markDirty() {
        if (dirtySinceMs < 0) dirtySinceMs = System.currentTimeMillis();
    }
}
