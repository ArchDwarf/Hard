package dev.hardstone.tracker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class HardStoneTrackerClient implements ClientModInitializer {
    public static final String MOD_ID = "hardstonetracker";
    public static final Logger LOGGER = LoggerFactory.getLogger("HardStoneTracker");

    private TrackerData data;
    private LocationTracker location;
    private MiningTracker mining;
    private LeaderboardService leaderboard;

    @Override
    public void onInitializeClient() {
        data = TrackerData.load();
        location = new LocationTracker();
        mining = new MiningTracker(data, location);
        leaderboard = new LeaderboardService(data);
        HudRenderer hud = new HudRenderer(data, mining, location, leaderboard);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            location.tick(mc);
            mining.tick(mc);
            if (mc.player != null && location.isOnHypixel()) leaderboard.autoRefresh(mc.getUser().getName());
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            mining.onChat(message);
            return location.onChat(message.getString());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mining.endSession());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> mining.endSession());

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"), hud::extract);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> registerCommands(dispatcher));
        LOGGER.info("Hard Stone Tracker загружен");
    }

    // ------------------------------------------------------------------ команды

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        LiteralCommandNode<FabricClientCommandSource> root = d.register(literal("hs")
                .executes(this::cmdStats)
                .then(literal("help").executes(this::cmdHelp))
                .then(literal("stats").executes(this::cmdStats))
                .then(literal("reset").executes(c -> {
                    mining.endSession();
                    ok(c, "Сессия сохранена в историю и сброшена.");
                    return 1;
                }))
                .then(literal("sessions").executes(this::cmdSessions))
                .then(literal("anywhere").executes(c -> {
                    data.countEverywhere = !data.countEverywhere;
                    data.save();
                    ok(c, "Подсчёт везде (не только в шахтах): " + onOff(data.countEverywhere));
                    return 1;
                }))
                .then(literal("afk").then(argument("seconds", IntegerArgumentType.integer(10, 3600)).executes(c -> {
                    data.afkSeconds = IntegerArgumentType.getInteger(c, "seconds");
                    data.save();
                    ok(c, "Пауза таймера после " + data.afkSeconds + " с без добычи.");
                    return 1;
                })))
                .then(literal("hud")
                        .executes(c -> {
                            data.hudEnabled = !data.hudEnabled;
                            data.save();
                            ok(c, "HUD: " + onOff(data.hudEnabled));
                            return 1;
                        })
                        .then(literal("top").executes(c -> {
                            data.hudShowTop = !data.hudShowTop;
                            data.save();
                            ok(c, "Топ на HUD: " + onOff(data.hudShowTop));
                            return 1;
                        }))
                        .then(literal("pos")
                                .then(argument("x", IntegerArgumentType.integer(0, 4000))
                                        .then(argument("y", IntegerArgumentType.integer(0, 4000)).executes(c -> {
                                            data.hudX = IntegerArgumentType.getInteger(c, "x");
                                            data.hudY = IntegerArgumentType.getInteger(c, "y");
                                            data.save();
                                            ok(c, "HUD перемещён в " + data.hudX + ", " + data.hudY);
                                            return 1;
                                        })))))
                .then(literal("apikey").then(argument("key", StringArgumentType.greedyString()).executes(c -> {
                    data.hypixelApiKey = StringArgumentType.getString(c, "key").trim();
                    data.save();
                    ok(c, "API-ключ сохранён. Обновляю топ...");
                    refreshTop(c.getSource());
                    return 1;
                })))
                .then(literal("top")
                        .executes(this::cmdTop)
                        .then(literal("refresh").executes(c -> {
                            refreshTop(c.getSource());
                            return 1;
                        }))
                        .then(literal("add").then(argument("player", StringArgumentType.word()).executes(c -> {
                            String p = StringArgumentType.getString(c, "player");
                            if (data.trackedPlayers.stream().noneMatch(p::equalsIgnoreCase)) data.trackedPlayers.add(p);
                            data.save();
                            ok(c, p + " добавлен в топ. /hs top refresh — обновить.");
                            return 1;
                        })))
                        .then(literal("remove").then(argument("player", StringArgumentType.word()).executes(c -> {
                            String p = StringArgumentType.getString(c, "player");
                            boolean removed = data.trackedPlayers.removeIf(p::equalsIgnoreCase);
                            data.save();
                            ok(c, removed ? p + " удалён из топа." : p + " не найден в списке.");
                            return 1;
                        })))
                        .then(literal("boards").executes(this::cmdBoards))
                        .then(literal("board").then(argument("path", StringArgumentType.greedyString()).executes(c -> {
                            String p = StringArgumentType.getString(c, "path").trim();
                            data.officialBoardPath = p.equalsIgnoreCase("auto") ? "" : p;
                            data.save();
                            ok(c, data.officialBoardPath.isEmpty() ? "Доска: автопоиск Hard Stone." : "Доска: " + p);
                            refreshTop(c.getSource());
                            return 1;
                        })))
                        .then(literal("clear").executes(c -> {
                            data.trackedPlayers.clear();
                            data.save();
                            ok(c, "Список игроков очищен.");
                            return 1;
                        }))));
        d.register(literal("hardstone").redirect(root));
    }

    private int cmdHelp(CommandContext<FabricClientCommandSource> c) {
        String[][] help = {
                {"/hs", "статистика сессии"},
                {"/hs reset", "сохранить и начать новую сессию"},
                {"/hs sessions", "топ твоих лучших сессий"},
                {"/hs top", "топ игроков по коллекции Hard Stone"},
                {"/hs top boards", "официальные лидерборды Hypixel"},
                {"/hs top board <path>|auto", "выбрать доску Hypixel"},
                {"/hs top add|remove <ник>", "свой список (если у Hypixel нет топа)"},
                {"/hs top refresh", "обновить топ из Hypixel API"},
                {"/hs apikey <ключ>", "ключ с developer.hypixel.net"},
                {"/hs hud", "вкл/выкл HUD"},
                {"/hs hud top", "вкл/выкл топ на HUD"},
                {"/hs hud pos <x> <y>", "позиция HUD"},
                {"/hs afk <сек>", "пауза таймера при простое"},
                {"/hs anywhere", "считать вне шахт (тест)"},
        };
        send(c.getSource(), header("Hard Stone Tracker — команды"));
        for (String[] h : help) {
            send(c.getSource(), Component.literal(h[0]).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" — " + h[1]).withStyle(ChatFormatting.GRAY)));
        }
        return 1;
    }

    private int cmdStats(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource s = c.getSource();
        send(s, header("Hard Stone — текущая сессия"));
        send(s, kv("Локация", location.prettyLocation() + (mining.isCountingHere() ? " (считаю)" : " (не считаю)")));
        send(s, kv("Блоков добыто", Fmt.num(mining.sessionBlocks)));
        send(s, kv("Блоков в час", Fmt.num(Math.round(mining.perHour()))));
        send(s, kv("Дроп в сакс", Fmt.num(mining.sessionSackItems) + " (" + Fmt.num(Math.round(mining.sackPerHour())) + "/ч)"));
        send(s, kv("Активное время", Fmt.time(mining.sessionActiveMs) + (mining.isPaused() ? " (пауза)" : "")));
        send(s, header("За всё время"));
        send(s, kv("Блоков", Fmt.num(data.allTimeBlocks)));
        send(s, kv("Дроп в сакс", Fmt.num(data.allTimeSackItems)));
        send(s, kv("Время добычи", Fmt.time(data.allTimeActiveMs)));
        send(s, kv("Рекорд блоков/ч", Fmt.num(Math.round(data.bestBlocksPerHour)) + " (сессии от 5 мин)"));
        return 1;
    }

    private int cmdSessions(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource s = c.getSource();
        send(s, header("Лучшие сессии (по блокам)"));
        if (data.sessions.isEmpty()) {
            send(s, Component.literal("Пока пусто — история пополняется при /hs reset или выходе с сервера.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm");
        int i = 1;
        for (TrackerData.SessionRecord r : data.sessions) {
            if (i > 10) break;
            send(s, Component.literal("#" + i + " ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(Fmt.num(r.blocks) + " блоков").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  " + Fmt.num(Math.round(r.perHour())) + "/ч  "
                            + Fmt.time(r.activeMs) + "  " + df.format(new Date(r.endedAt))).withStyle(ChatFormatting.GRAY)));
            i++;
        }
        return 1;
    }

    private int cmdTop(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource s = c.getSource();
        List<LeaderboardService.Entry> top = leaderboard.top();
        if (top.isEmpty()) {
            if (data.hypixelApiKey.isBlank()) {
                send(s, Component.literal("Для топа нужен Hypixel API-ключ: developer.hypixel.net → /hs apikey <ключ>")
                        .withStyle(ChatFormatting.RED));
            } else {
                refreshTop(s);
            }
            return 1;
        }
        printTop(s);
        return 1;
    }

    private void printTop(FabricClientCommandSource s) {
        long ago = (System.currentTimeMillis() - leaderboard.lastUpdateMs()) / 60_000;
        send(s, header("Топ Hard Stone (обновлено " + ago + " мин назад)"));
        send(s, Component.literal("Источник: " + leaderboard.source()).withStyle(ChatFormatting.DARK_GRAY));
        String self = Minecraft.getInstance().getUser().getName();
        int i = 1;
        for (LeaderboardService.Entry e : leaderboard.top()) {
            MutableComponent line = Component.literal("#" + i + " ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(e.name()).withStyle(e.name().equalsIgnoreCase(self)
                            ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            if (e.hardStone() >= 0) {
                line.append(Component.literal("  " + Fmt.num(e.hardStone())).withStyle(ChatFormatting.YELLOW));
            } else {
                line.append(Component.literal("  (" + e.error() + ")").withStyle(ChatFormatting.RED));
            }
            send(s, line);
            i++;
        }
    }

    private int cmdBoards(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource s = c.getSource();
        if (leaderboard.boards().isEmpty()) {
            send(s, Component.literal("Список досок ещё не загружен — сначала /hs top refresh").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        send(s, header("Официальные лидерборды Hypixel"));
        for (String line : leaderboard.describeBoards()) {
            send(s, Component.literal(line).withStyle(ChatFormatting.WHITE));
        }
        send(s, Component.literal("Выбрать: /hs top board <path>,  авто-поиск: /hs top board auto")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private void refreshTop(FabricClientCommandSource s) {
        send(s, Component.literal("Запрашиваю Hypixel API...").withStyle(ChatFormatting.GRAY));
        leaderboard.refresh(Minecraft.getInstance().getUser().getName(), msg ->
                Minecraft.getInstance().execute(() -> {
                    send(s, Component.literal(msg).withStyle(ChatFormatting.GRAY));
                    if (!leaderboard.top().isEmpty()) printTop(s);
                }));
    }

    // ------------------------------------------------------------------ helpers

    private static void send(FabricClientCommandSource s, Component c) {
        s.sendFeedback(c);
    }

    private static void ok(CommandContext<FabricClientCommandSource> c, String msg) {
        send(c.getSource(), Component.literal("[HS] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
    }

    private static Component header(String t) {
        return Component.literal("— " + t + " —").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static Component kv(String k, String v) {
        return Component.literal(k + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(v).withStyle(ChatFormatting.WHITE));
    }

    private static String onOff(boolean b) {
        return b ? "ВКЛ" : "ВЫКЛ";
    }
}
