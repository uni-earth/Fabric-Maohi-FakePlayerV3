package com.maohi;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 假人管理命令系统
 *
 * V5.23 优化:
 *   1. 所有 executes 包裹 safeRun() — 单个命令异常不再让 brigadier 整树坏死
 *   2. /maohi off 接入真清场:置 botEnabled=false + kickAllImmediately()
 *   3. /maohi list 升级:每行显示 task / ping / dim:x,y,z / online time / 成就数
 *   4. 新增 /maohi list <name> 单假人详细 + 完整成就列表
 *   5. /maohi spawn 异步反馈,提示"皮肤抓取中"
 *   6. 移除 OWNERS_CHECK 的旧注释(1.21.11 OWNERS_CHECK == 4 仍可用,但写 4 也合法)
 *
 * NOTE: 所有命令需要 OP 等级 4 才能使用,以确保隐蔽性。
 */
public class MaohiCommands {

    // ===== 性能指标收集器 =====
    private static final AtomicLong totalTickTimeNs = new AtomicLong(0);
    private static final AtomicInteger tickCount = new AtomicInteger(0);
    private static final AtomicInteger spawnCount = new AtomicInteger(0);
    private static final AtomicInteger spawnFailures = new AtomicInteger(0);
    private static final AtomicInteger respawnCount = new AtomicInteger(0);
    private static final AtomicInteger respawnFailures = new AtomicInteger(0);

    /** 动态建议器:提供当前在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> ONLINE_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            return CommandSource.suggestMatching(
                manager.getOnlinePlayerInfo().values().stream().map(s -> s.split(" ")[0]),
                builder
            );
        }
        return builder.buildFuture();
    };

    /** 动态建议器:提供历史库中且当前不在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> OFFLINE_KNOWN_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            return CommandSource.suggestMatching(
                manager.getKnownPlayers().values().stream()
                    .filter(p -> !manager.isVirtualPlayer(p.uuid))
                    .map(p -> p.name),
                builder
            );
        }
        return builder.buildFuture();
    };

    public static void recordTickTime(long nanos) {
        totalTickTimeNs.addAndGet(nanos);
        tickCount.incrementAndGet();
    }
    public static void recordSpawnSuccess() { spawnCount.incrementAndGet(); }
    public static void recordSpawnFailure() { spawnFailures.incrementAndGet(); }
    public static void recordRespawnSuccess() { respawnCount.incrementAndGet(); }
    public static void recordRespawnFailure() { respawnFailures.incrementAndGet(); }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                net.minecraft.command.CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        registerCommands(dispatcher);
    }

    public static void register() { /* 已弃用,Mixin 路径不再走 */ }

    private static com.maohi.fakeplayer.VirtualPlayerManager requireManager(ServerCommandSource source) {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager == null) {
            source.sendFeedback(() -> Text.of("§c[FS Core] 管理器未初始化"), false);
        }
        return manager;
    }

    /**
     * V5.23: 安全网包装 — 任何命令异常都转成 source.sendError 返回 0,不让 brigadier 树坏死。
     */
    private static int safeRun(CommandContext<ServerCommandSource> ctx,
                               Function<com.maohi.fakeplayer.VirtualPlayerManager, Integer> body) {
        try {
            com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
            if (manager == null) return 0;
            return body.apply(manager);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            ctx.getSource().sendError(Text.of("§c[FS Core] 命令执行异常: " + msg));
            org.slf4j.LoggerFactory.getLogger("Server thread")
                .error("MaohiCommands error: {}", msg, t);
            return 0;
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("maohi")
                // OP 等级 4(OWNERS):1.21.11 权限系统重构后,ServerCommandSource 不再有
                // hasPermissionLevel(int);改用 CommandManager.requirePermissionLevel(OWNERS_CHECK)。
                .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))

                // === /maohi status ===
                .then(CommandManager.literal("status")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        ctx.getSource().sendFeedback(() -> Text.of("§6[FS Core] " + manager.getStatusSummary()), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7总开关: %s §7| AI tick: §f%d §7次, 平均 §f%.3fms",
                            MaohiConfig.getInstance().botEnabled ? "§a开启" : "§c关闭",
                            tickCount.get(),
                            tickCount.get() > 0 ? (totalTickTimeNs.get() / (double) tickCount.get()) / 1_000_000.0 : 0
                        )), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi list  &  /maohi list <name> ===
                .then(CommandManager.literal("list")
                    .executes(ctx -> safeRun(ctx, manager -> listAll(ctx, manager)))
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(ONLINE_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager ->
                            listOne(ctx, manager, StringArgumentType.getString(ctx, "name"))))
                    )
                )

                // === /maohi spawn <name> ===
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(OFFLINE_KNOWN_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = manager.spawnNamedPlayer(name);
                            if (ok) {
                                ctx.getSource().sendFeedback(() -> Text.of(
                                    "§a[FS Core] 已发起召唤: §f" + name + " §7(异步抓取皮肤,约 1-3s 上线)"), false);
                                return Command.SINGLE_SUCCESS;
                            }
                            ctx.getSource().sendFeedback(() ->
                                Text.of("§c[FS Core] 召唤失败:同名玩家可能已在线"), false);
                            return 0;
                        }))
                    )
                )

                // === /maohi kick <name> ===
                .then(CommandManager.literal("kick")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(ONLINE_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = manager.kickNamedPlayer(name);
                            ctx.getSource().sendFeedback(() -> Text.of(ok
                                ? "§a[FS Core] 已踢出假人: §f" + name
                                : "§c[FS Core] 未找到该假人: " + name), false);
                            return ok ? Command.SINGLE_SUCCESS : 0;
                        }))
                    )
                )

                // === /maohi reload ===
                .then(CommandManager.literal("reload")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.reload();
                        ctx.getSource().sendFeedback(() -> Text.of("§a[FS Core] 配置已重载"), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi on ===
                .then(CommandManager.literal("on")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.getInstance().botEnabled = true;
                        ctx.getSource().sendFeedback(() ->
                            Text.of("§a[FS Core] 假人系统已启用 (按调度策略陆续上线)"), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi off ===
                .then(CommandManager.literal("off")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.getInstance().botEnabled = false;
                        int kicked = manager.kickAllImmediately();
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "§c[FS Core] 假人系统已禁用 (已紧急清场 §f%d §c名假人)", kicked)), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi metrics ===
                .then(CommandManager.literal("metrics")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        int ticks = tickCount.get();
                        long totalNs = totalTickTimeNs.get();
                        double avgMs = ticks > 0 ? (totalNs / (double) ticks) / 1_000_000.0 : 0;
                        ctx.getSource().sendFeedback(() -> Text.of("§6[FS Core] 性能指标:"), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7Tick 平均耗时: §f%.3fms §7(共 %d 次)", avgMs, ticks)), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7生成: §a%d 成功 §c%d 失败", spawnCount.get(), spawnFailures.get())), false);
                        ctx.getSource().sendFeedback(() -> Text.of(String.format(
                            "  §7复活: §a%d 成功 §c%d 失败", respawnCount.get(), respawnFailures.get())), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                )
        );
    }

    // ============================================================
    // V5.23: /maohi list 实现
    // ============================================================

    /**
     * 全量假人深度列表。
     * 每行: name | [task] | ping | dim:x,y,z | 时长 | adv 计数
     * 真实玩家也罗列一行供管理员对比(标 ★)。
     */
    private static int listAll(CommandContext<ServerCommandSource> ctx,
                                com.maohi.fakeplayer.VirtualPlayerManager manager) {
        List<UUID> uuids = manager.getOnlinePlayerUuids();
        if (uuids.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.of("§7[FS Core] 当前没有在线的假人"), false);
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.of(
            "§6[FS Core] 在线假人 §f" + uuids.size() + " §6名:"), false);

        for (UUID uuid : new java.util.ArrayList<>(uuids)) {
            String line = formatBotLine(manager, uuid);
            ctx.getSource().sendFeedback(() -> Text.of(line), false);
        }
        ctx.getSource().sendFeedback(() ->
            Text.of("§7用 §f/maohi list <name> §7查看单假人详细成就列表"), false);
        return uuids.size();
    }

    /** 单假人详细输出,含完整成就 ID 列表。 */
    private static int listOne(CommandContext<ServerCommandSource> ctx,
                                com.maohi.fakeplayer.VirtualPlayerManager manager,
                                String name) {
        UUID uuid = null;
        for (Map.Entry<UUID, String> e : manager.getOnlinePlayerInfo().entrySet()) {
            if (e.getValue().split(" ")[0].equalsIgnoreCase(name)) {
                uuid = e.getKey();
                break;
            }
        }
        if (uuid == null) {
            ctx.getSource().sendFeedback(() -> Text.of("§c[FS Core] 假人不在线: " + name), false);
            return 0;
        }
        com.maohi.fakeplayer.Personality pers = manager.getPersonality(uuid);
        ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);

        final UUID finalUuid = uuid;
        ctx.getSource().sendFeedback(() -> Text.of("§6=== " + name + " §6详情 ==="), false);
        ctx.getSource().sendFeedback(() -> Text.of(formatBotLine(manager, finalUuid)), false);

        if (pers != null) {
            ctx.getSource().sendFeedback(() -> Text.of(String.format(
                "  §7阶段: §f%s §7| 职业偏好: §f%s §7| 累计挖块: §f%d",
                pers.growthPhase, pers.jobFocus == null ? "无" : pers.jobFocus, pers.blocksMinedTotal)), false);

            // 成就完整列表
            java.util.Set<String> advs = pers.unlockedAdvancements;
            if (advs == null || advs.isEmpty()) {
                ctx.getSource().sendFeedback(() -> Text.of("  §7成就: §8无"), false);
            } else {
                ctx.getSource().sendFeedback(() -> Text.of(
                    "  §7成就 §f" + advs.size() + " §7个:"), false);
                // 按 namespace 分组排序输出,每行最多 4 个
                java.util.List<String> sorted = new java.util.ArrayList<>(advs);
                java.util.Collections.sort(sorted);
                StringBuilder line = new StringBuilder("    §a");
                int colCount = 0;
                for (String adv : sorted) {
                    if (colCount > 0) line.append("§7, §a");
                    line.append(adv);
                    colCount++;
                    if (colCount >= 3) {
                        String l = line.toString();
                        ctx.getSource().sendFeedback(() -> Text.of(l), false);
                        line.setLength(0);
                        line.append("    §a");
                        colCount = 0;
                    }
                }
                if (colCount > 0) {
                    String l = line.toString();
                    ctx.getSource().sendFeedback(() -> Text.of(l), false);
                }
            }

            // 任务目标
            if (pers.taskTarget != null) {
                ctx.getSource().sendFeedback(() -> Text.of(String.format(
                    "  §7任务目标: §f%d, %d, %d", pers.taskTarget.getX(), pers.taskTarget.getY(), pers.taskTarget.getZ())), false);
            }
        }

        if (p != null && p.getName() != null) {
            ctx.getSource().sendFeedback(() -> Text.of(String.format(
                "  §7血量: §f%.1f§7/%.1f §7| 经验: §fLv %d §7| 食物: §f%d/20",
                p.getHealth(), p.getMaxHealth(), p.experienceLevel, p.getHungerManager().getFoodLevel())), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 单行格式化:name | [task] | ping | dim:x,y,z | 在线时长 | adv 数 */
    private static String formatBotLine(com.maohi.fakeplayer.VirtualPlayerManager manager, UUID uuid) {
        String name = manager.getVirtualPlayerName(uuid);
        com.maohi.fakeplayer.Personality pers = manager.getPersonality(uuid);
        ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);

        String task = pers != null && pers.currentTask != null ? pers.currentTask.name() : "?";
        int ping = manager.getLatency(uuid);

        String posPart;
        if (p != null) {
            String dimPath = p.getEntityWorld().getRegistryKey().getValue().getPath();
            posPart = String.format("§f%s§7:§f%d§7,§f%d§7,§f%d",
                dimPath, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        } else {
            posPart = "§8?";
        }

        // 在线时长
        String uptime;
        if (pers != null && pers.firstJoinAt > 0L) {
            long sec = Math.max(0, (System.currentTimeMillis() - pers.firstJoinAt) / 1000L);
            long h = sec / 3600;
            long m = (sec % 3600) / 60;
            long s = sec % 60;
            if (h > 0) uptime = String.format("%dh%dm", h, m);
            else if (m > 0) uptime = String.format("%dm%02ds", m, s);
            else uptime = String.format("%ds", s);
        } else {
            uptime = "?";
        }

        int advCount = pers != null && pers.unlockedAdvancements != null
            ? pers.unlockedAdvancements.size() : 0;

        return String.format("  §a%s §7[§e%s§7] §7ping §f%dms §7| %s §7| §f%s §7| 成就 §f%d",
            name, task, ping, posPart, uptime, advCount);
    }
}
