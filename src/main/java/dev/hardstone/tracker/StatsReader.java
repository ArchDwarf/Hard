package dev.hardstone.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Статы игрока для расчёта по формулам вики Hypixel SkyBlock.
 *
 * <p>Fortune (Mining Fortune + Block Fortune, «удача» на блоки): каждые 100 — гарантированный +1 дроп,
 * остаток — шанс на ещё один. Ожидаемый дроп с блока = 1 + (MF + BF) / 100.
 * Block Fortune действует на Hard Stone и складывается с Mining Fortune.
 *
 * <p>Mining Spread: та же логика для дополнительных блоков. Ожидаемо блоков с удара = 1 + Spread / 100.
 *
 * <p>Итоговые значения уже включают ВСЁ: питомца, броню, атрибуты, Heart of the Mountain, кирку,
 * зачарования, баффы — Hypixel сам их суммирует. Мод берёт готовую сумму:
 * <ol>
 *   <li>из таб-листа (виджет «Stats» — живое значение, меняется при смене питомца/брони);</li>
 *   <li>из меню SkyBlock Menu → Your SkyBlock Profile (запоминается при открытии);</li>
 *   <li>или вручную: /hs fortune и /hs spread.</li>
 * </ol>
 */
public class StatsReader {
    private static final Pattern MF = Pattern.compile("Mining Fortune:?\\s*\\D*?([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern BF = Pattern.compile("Block Fortune:?\\s*\\D*?([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern SPREAD = Pattern.compile("(?<!Gemstone )Mining Spread:?\\s*\\D*?([\\d,]+(?:\\.\\d+)?)");

    private final TrackerData data;
    private long lastScanMs = 0;

    /** Найдено ли значение в таб-листе при последнем скане */
    public boolean tabMf, tabBf, tabSpread;
    private double tabMfValue, tabBfValue, tabSpreadValue;

    public StatsReader(TrackerData data) {
        this.data = data;
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastScanMs < 1000) return;
        lastScanMs = now;
        tabMf = tabBf = tabSpread = false;
        if (!data.autoStats) return;
        readProfileMenu(mc);
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        for (PlayerInfo info : conn.getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String s = name.getString().replaceAll("§.", "");
            Double v;
            if ((v = find(MF, s)) != null) { tabMf = true; tabMfValue = v; }
            if ((v = find(BF, s)) != null) { tabBf = true; tabBfValue = v; }
            if ((v = find(SPREAD, s)) != null) { tabSpread = true; tabSpreadValue = v; }
        }
    }

    /** Меню профиля SkyBlock: читаем итоговые статы из описаний предметов */
    private void readProfileMenu(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> scr)) return;
        String title = scr.getTitle().getString();
        if (!(title.contains("Profile") || title.contains("Stats"))) return;
        Double mf = null, bf = null, sp = null;
        for (Slot slot : scr.getMenu().slots) {
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            ItemLore lore = st.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String t = line.getString().replaceAll("§.", "");
                Double v;
                if (mf == null && (v = find(MF, t)) != null) mf = v;
                if (bf == null && (v = find(BF, t)) != null) bf = v;
                if (sp == null && (v = find(SPREAD, t)) != null) sp = v;
            }
        }
        boolean changed = false;
        if (mf != null && mf != data.miningFortune) { data.miningFortune = mf; changed = true; }
        if (bf != null && bf != data.blockFortune) { data.blockFortune = bf; changed = true; }
        if (sp != null && sp != data.miningSpread) { data.miningSpread = sp; changed = true; }
        if (changed) {
            data.statsFromMenuAt = System.currentTimeMillis();
            data.save();
        }
    }

    private static Double find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public double miningFortune() { return tabMf ? tabMfValue : data.miningFortune; }
    public double blockFortune() { return tabBf ? tabBfValue : data.blockFortune; }
    public double miningSpread() { return tabSpread ? tabSpreadValue : data.miningSpread; }

    /** Суммарная удача на Hard Stone */
    public double totalFortune() { return miningFortune() + blockFortune(); }

    /** Ожидаемый дроп Hard Stone с одного блока: 1 + Fortune/100 */
    public double dropsPerBlock() { return 1.0 + Math.max(0, totalFortune()) / 100.0; }

    /** Ожидаемо блоков с одного удара: 1 + Spread/100 */
    public double blocksPerHit() { return 1.0 + Math.max(0, miningSpread()) / 100.0; }

    public String source() {
        if (!data.autoStats) return "вручную";
        if (tabMf || tabBf || tabSpread) return "таб-лист";
        if (data.statsFromMenuAt > 0) return "меню профиля";
        return "вручную";
    }
}
