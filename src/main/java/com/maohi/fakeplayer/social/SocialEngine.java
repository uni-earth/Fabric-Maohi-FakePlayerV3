package com.maohi.fakeplayer.social;

import com.maohi.fakeplayer.network.FakeClientConnection;
import com.maohi.fakeplayer.network.MovementInputHelper;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 假人社交引擎 (V3)
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final ReentrantLock chatLock = new ReentrantLock();
    
    private long nextAvailableChatTime = 0;

    // V5.22: 全局气候级吐槽节流——同一类天气事件(rain/dark/fire)
    // 整服 N 分钟内最多 K 个假人吐槽,防止 8 个假人接力抱怨同一场雨穿帮。
    // key = "rain" / "night" / "fire"; value = [windowStartMs, countInWindow]
    private final java.util.Map<String, long[]> envEventCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ENV_EVENT_WINDOW_MS = 600_000L; // 10 分钟窗口
    private static final int ENV_EVENT_MAX_PER_WINDOW = 2;    // 每窗口最多 2 条

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    /** V5.23: 假人对假人回复的全局概率(20%);命中后还要过 ChatResponder.shouldEngage 二次门 */
    private static final int BOT_TO_BOT_REPLY_PERCENT = 20;
    /** V5.23: 假人对假人回复链最长跳数(防止 A→B→A→B 回声) */
    private static final int BOT_TO_BOT_MAX_HOPS = 2;
    /** V5.23: per-发起者 假人聊天链当前跳数;senderUuid → hops。每条玩家或假人新发言重置 */
    private final java.util.Map<UUID, Integer> botChainHops = new java.util.concurrent.ConcurrentHashMap<>();

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        if (sender.networkHandler.connection instanceof FakeClientConnection) {
            // 假人发言:走"假人聊假人"路径(20% 概率,且限跳数防回声室)
            handleBotToBotChat(sender, content);
            return;
        }
        if (manager.isVirtualPlayer(sender.getUuid())) {
            handleBotToBotChat(sender, content);
            return;
        }
        // 真人发言:重置链长(玩家发言总会"启动"新对话),走完整 ChatResponder
        botChainHops.put(sender.getUuid(), 0);
        handlePlayerChat(sender, content);
    }

    /** V5.23: 真玩家发言 → 选最近假人响应(走 ChatResponder 完整意图分类) */
    private void handlePlayerChat(ServerPlayerEntity sender, String content) {
        String senderName = sender.getName().getString();
        ChatResponder.Intent intent = ChatResponder.classify(content);
        if (intent == ChatResponder.Intent.NONE) return;
        if (!ChatResponder.shouldEngage(intent)) return;

        UUID respondent = pickClosestEligibleBot(sender, 225);
        if (respondent == null) return;

        Personality respPers = manager.getPersonality(respondent);
        rememberPlayer(respPers, senderName);

        String resp = ChatResponder.respond(intent, senderName, respPers);
        if (resp == null) return;

        sendImmediateChat(respondent, resp, 15000L);
        respPers.lastCommandTime = System.currentTimeMillis();
    }

    /**
     * V5.23: 假人发言 → 是否让另一个假人接话(20% 概率)。
     * 防回声室三重保险:
     *   1) 全局 20% 概率门 — 真人也不是每条都接话
     *   2) BOT_TO_BOT_MAX_HOPS=2 — A→B→A 后链断开
     *   3) ChatResponder.shouldEngage 意图门 — 例如 LAUGH 实际只 30% 概率回
     */
    private void handleBotToBotChat(ServerPlayerEntity sender, String content) {
        // 20% 概率门
        if (ThreadLocalRandom.current().nextInt(100) >= BOT_TO_BOT_REPLY_PERCENT) return;
        // 跳数限制
        int hops = botChainHops.getOrDefault(sender.getUuid(), 0);
        if (hops >= BOT_TO_BOT_MAX_HOPS) return;

        ChatResponder.Intent intent = ChatResponder.classify(content);
        if (intent == ChatResponder.Intent.NONE) return;
        if (!ChatResponder.shouldEngage(intent)) return;

        UUID respondent = pickClosestEligibleBot(sender, 225);
        if (respondent == null) return;
        // 自己不能接自己的话(理论上 isVirtualPlayer 返回的列表里不包含 sender 自己,
        //  但 PlayerManager 路径没保证;再加一道判断稳妥)
        if (respondent.equals(sender.getUuid())) return;

        Personality respPers = manager.getPersonality(respondent);
        // 回应者继承 hops+1,持续追加链
        botChainHops.put(respondent, hops + 1);

        String senderName = sender.getName().getString();
        String resp = ChatResponder.respond(intent, senderName, respPers);
        if (resp == null) return;

        sendImmediateChat(respondent, resp, 15000L);
        respPers.lastCommandTime = System.currentTimeMillis();
    }

    /**
     * V5.23: 共享逻辑 — 在 maxDistSq 范围内挑最近的、未道别冷却的假人。
     * @return uuid 或 null
     */
    private UUID pickClosestEligibleBot(ServerPlayerEntity sender, double maxDistSq) {
        UUID respondent = null;
        double bestDistSq = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        UUID senderUuid = sender.getUuid();
        for (UUID id : manager.getOnlinePlayerUuids()) {
            if (id.equals(senderUuid)) continue;
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            Personality personality = manager.getPersonality(id);
            if (p == null || personality == null || personality.farewellSaid) continue;
            if (now - personality.lastCommandTime <= TimingConstants.NEARBY_GREET_COOLDOWN) continue;
            double distSq = p.squaredDistanceTo(sender);
            if (distSq > maxDistSq) continue;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                respondent = id;
            }
        }
        return respondent;
    }

    private void rememberPlayer(Personality personality, String name) {
        if (personality.knownRealPlayers.contains(name)) return;
        if (personality.knownRealPlayers.size() >= 5) personality.knownRealPlayers.removeFirst();
        personality.knownRealPlayers.addLast(name);
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        // V5.4 怨恨系统：如果击杀者是玩家
        net.minecraft.entity.damage.DamageSource source = victim.getRecentDamageSource();
        if (source != null && source.getAttacker() instanceof ServerPlayerEntity killer) {
            UUID killerUuid = killer.getUuid();
            if (!manager.isVirtualPlayer(killerUuid)) {
                Personality victimPers = manager.getPersonality(victim.getUuid());
                if (victimPers != null) {
                    int count = victimPers.grudgeMap.getOrDefault(killerUuid, 0) + 1;
                    victimPers.grudgeMap.put(killerUuid, count);
                    
                    // 1次击杀：抱怨
                    if (count == 1) sendImmediateChat(victim.getUuid(), "Hey! Why did you do that?", 5000L);
                    // 2次击杀：严重警告
                    else if (count == 2) sendImmediateChat(victim.getUuid(), "Stop it, " + killer.getName().getString() + ". I'm not kidding.", 10000L);
                    // 3次以上：标记为死对头（交给管理器逻辑处理逃跑或反击）
                }
            }
        }

        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            Personality personality = manager.getPersonality(id);
            
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
                if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
                    String reaction = VocabularyBank.getDeathReaction(victim.getName().getString());
                    sendImmediateChat(id, reaction, 10000L);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    public void onVictimDeath(UUID victim) {
        if (manager.isLoggingOut(victim)) return;
        if (ThreadLocalRandom.current().nextInt(100) < 70) {
            sendImmediateChat(victim, VocabularyBank.getCombatLose(), 5000L);
        }
    }

    public boolean sendImmediateChat(UUID uuid, String message, long cooldownMs) {
        chatLock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < nextAvailableChatTime) return false; 

            if (message == null || message.trim().isEmpty()) return false;

            String name = manager.getVirtualPlayerName(uuid);
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
            
            // 增强型名字获取逻辑：三重保障
            if (name == null || name.trim().isEmpty()) {
                if (p != null) {
                    // 1. 优先取 GameProfile 名 (直接访问 name 属性或通过 getEntityName)
                    name = p.getName().getString();
                }
            }
            if (name == null || name.trim().isEmpty()) {
                name = "Player_" + uuid.toString().substring(0, 4); // 2. 终极保底
            }
            
            final String finalName = name.replaceAll("[\\r\\n]", "").trim();
            if (finalName.isEmpty()) return false;
            final String finalMessage = message.trim();
            final long generatedAt = now;
            
            nextAvailableChatTime = now + cooldownMs;

            manager.getServer().execute(() -> {
                if (System.currentTimeMillis() - generatedAt > 1500L) {
                    return;
                }
                
                ServerPlayerEntity fp = manager.getServer().getPlayerManager().getPlayer(uuid);
                String resolvedName = !finalName.isEmpty() ? finalName
                    : (fp != null ? fp.getName().getString() : null);
                if (resolvedName == null || resolvedName.isEmpty()) {
                    org.slf4j.LoggerFactory.getLogger("Maohi").warn("[Chat] name missing for uuid={} msg={}", uuid, finalMessage);
                    return;
                }
                // V5.23: 防御 — 名字侧最终保护(理论上前面三重保障已覆盖,此处兜底);
                // 同时剥离 finalMessage 中的 § color code,避免词库被注入彩色控制字符
                String safeName = resolvedName.replaceAll("[§\\r\\n\\u0000-\\u001F]", "").trim();
                if (safeName.isEmpty()) {
                    safeName = "Player_" + uuid.toString().substring(0, 4);
                }
                String safeMessage = finalMessage.replaceAll("§.", "");
                if (safeMessage.isEmpty()) return;
                String formatted = "[" + safeName + "] " + safeMessage;
                com.maohi.Maohi.LOGGER.info(formatted);
                // V5.23: 走 sendMessage(Text, false) — false=走聊天框(非 actionbar),
                // 不触发 PlayerManager.broadcast,因此 PlayerManagerMixin 不会再钩到自己,
                // 不会形成"假人发言→广播→mixin→onChatMessage→handleBotToBotChat"的二次回调。
                // bot-to-bot 唯一入口是下面的显式 handleBotToBotChat 调用。
                Text chatText = net.minecraft.text.Text.literal(formatted);
                for (net.minecraft.server.network.ServerPlayerEntity online : manager.getServer().getPlayerManager().getPlayerList()) {
                    online.sendMessage(chatText, false);
                }
                // V5.23: 假人发言不走 PlayerManager.broadcast → mixin 钩不到 → 必须自己触发
                // 让旁边假人有 20% 概率接话(handleBotToBotChat 内部还有跳数与意图门保险)
                if (fp != null && manager.isVirtualPlayer(uuid)) {
                    try { handleBotToBotChat(fp, safeMessage); } catch (Throwable ignored) {}
                }
            });
            return true;
        } finally {
            chatLock.unlock();
        }
    }

    public void sendImmediateChat(UUID uuid, String message) {
        sendImmediateChat(uuid, message, 10000L);
    }

    public void tick(long nowMs) {
        // V5.4 非语言社交信号：蹲起问候与对视
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            Personality pers = manager.getPersonality(id);
            if (p == null || pers == null || pers.isAFK) continue;

            // 寻找附近的目标 (优先真玩家，无真玩家时寻找其他假人)
            ServerPlayerEntity target = null;
            for (ServerPlayerEntity other : manager.getServer().getPlayerManager().getPlayerList()) {
                if (other.getUuid().equals(id)) continue;
                double distSq = p.squaredDistanceTo(other);
                if (distSq > 100.0) continue;
                
                // 如果是真玩家，直接锁定
                if (!manager.isVirtualPlayer(other.getUuid())) {
                    target = other;
                    break;
                }
                // 如果是假人，记录为备选
                target = other;
            }

            if (target == null) continue;

            // 1. 蹲起问候 (40% 概率，冷却 1 分钟)
            if (nowMs - pers.lastNonVerbalTick > 60000 && ThreadLocalRandom.current().nextInt(100) < 40) {
                pers.lastNonVerbalTick = nowMs;
                // V5.28 P1-B.4: setSneaking 改 PlayerInputC2SPacket
                MovementInputHelper.setSneaking(p, true);
                pers.sneakRemainingTicks = 4; // 延迟 4 tick (约 200ms) 后自动起身
            }

            // 2. 对视关注
            net.minecraft.util.math.Vec3d lookVec = target.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d toFake = p.getEyePos().subtract(target.getEyePos()).normalize();
            if (lookVec.dotProduct(toFake) > 0.98) { // 目标正在盯着我看
                pers.followPlayerTicks++;
                if (pers.followPlayerTicks > 60) { // 盯着看了 3 秒
                    p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                    pers.followPlayerTicks = 0;
                }
            } else {
                pers.followPlayerTicks = 0;
            }
        }
    }

    public boolean isGlobalChatAvailable() {
        return System.currentTimeMillis() >= nextAvailableChatTime;
    }

    /**
     * V5.22: 气候级吐槽全局去重。返回 true 表示当前窗口内还可以吐槽。
     * 调用方在确认要发"rain/dark/fire"类抱怨前先 check 一下;通过则计数+1。
     *
     * @param category "rain" / "night" / "fire" 等天气事件类别
     */
    public boolean tryClaimEnvComplaint(String category) {
        long now = System.currentTimeMillis();
        long[] state = envEventCounters.computeIfAbsent(category, k -> new long[]{now, 0});
        synchronized (state) {
            if (now - state[0] > ENV_EVENT_WINDOW_MS) {
                state[0] = now;
                state[1] = 0;
            }
            if (state[1] >= ENV_EVENT_MAX_PER_WINDOW) return false;
            state[1]++;
            return true;
        }
    }
}
