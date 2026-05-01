package com.maohi.fakeplayer.social;

import com.maohi.fakeplayer.FakeClientConnection;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人社交引擎中枢 (V3 社交引擎)
 * 统筹所有的社交行为，包括聊天模拟、打字机延迟、环境感应反馈。
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final List<SocialResponse> pendingResponses = new CopyOnWriteArrayList<>();
    private long nextAvailableChatTime = 0;

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    /**
     * 当有真实玩家在聊天频道说话时触发
     */
    public void onChatMessage(ServerPlayerEntity sender, String content) {
        // 极致拦截：如果是假人发的，或者说话人本身就是假人，不回复，防止无限套娃
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        
        // 简单的关键词匹配回复 (后续可对接 AI 接口)
        if (content.toLowerCase().contains("hi") || content.toLowerCase().contains("hello") || content.toLowerCase().contains("yo")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);
                
		// 距离 15 格内且通过社交冷却校验的假人才会回复
		if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225 && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {
                    String resp = generateRealisticMessage("GREETING", sender.getName().getString(), id);
                    scheduleDelayedResponse(new String[]{resp}, 1, 4, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    /**
     * 当附近有真实玩家死亡时触发
     */
    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality personality = manager.getPersonality(id);
            
            // 10 格内目睹死亡，30% 概率嘲讽或同情
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
		if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
			String reaction = generateRealisticMessage("DEATH", victim.getName().getString(), id);
                    scheduleDelayedResponse(new String[]{reaction}, 2, 5, id);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    /**
     * 计划一个延迟回复 (模拟人类打字延迟)
     */
    public void scheduleDelayedResponse(String[] pool, int minSec, int maxSec, UUID sender) {
        if (manager.isLoggingOut(sender)) return;

        String msg = (pool != null && pool.length > 0) ? pool[ThreadLocalRandom.current().nextInt(pool.length)] : null;
        if (msg == null) return;

        // 打字机模拟：基础 1 秒 + 每字符 150-250 毫秒
        long typingDelay = 1000L + (msg.length() * (150L + ThreadLocalRandom.current().nextInt(100)));
        long totalDelay = (ThreadLocalRandom.current().nextInt(minSec, maxSec + 1) * 1000L) + typingDelay;
        pendingResponses.add(new SocialResponse(sender, msg, System.currentTimeMillis() + totalDelay));
    }

    /**
     * 社交循环：处理待发送的消息
     */
    public void tick(long nowMs) {
        pendingResponses.removeIf(resp -> {
            if (nowMs >= resp.sendAt) {
                if (manager.isLoggingOut(resp.sender)) return true;

                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(resp.sender);
                if (p != null) {
                    // NOTE: 极大化随机间隔（20秒~3600秒），彻底消除机械发言指纹
                    if (nowMs < nextAvailableChatTime) return false;
                    
                    String senderName = p.getName().getString();
                    String finalMessage = resp.message;
                    
                    manager.getServer().execute(() -> {
                        // NOTE: 不用 broadcast — 它走系统消息通道，日志路径与真人聊天不同
                        // 改用 sendMessageToAll 让游戏内显示正常，手动 info 模拟真人聊天日志格式
                        String formatted = "<" + senderName + "> " + finalMessage;
                        manager.getServer().getPlayerManager().broadcast(Text.literal(formatted), false);
                        // 补一行与真人完全一致的聊天日志（MC 原版格式）
                        org.slf4j.LoggerFactory.getLogger("Server thread")
                            .info("<{}> {}", senderName, finalMessage);
                    });

                    // 发送后，设定下一次允许发言的时间点
                    long range = TimingConstants.GLOBAL_CHAT_COOLDOWN_MAX - TimingConstants.GLOBAL_CHAT_COOLDOWN_MIN;
                    nextAvailableChatTime = nowMs + TimingConstants.GLOBAL_CHAT_COOLDOWN_MIN + java.util.concurrent.ThreadLocalRandom.current().nextLong(range);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 生成极其逼真的人类短句
     */
    public String generateRealisticMessage(String category, String targetName, UUID senderUuid) {
        if (manager.isLoggingOut(senderUuid)) return null;

        java.util.Random r = ThreadLocalRandom.current();
        String core = "";
        
// V3.4: 改用 VocabularyBank 的丰富词库，告别内联小词库导致的重复/单调
	if ("DEATH".equals(category)) {
		core = VocabularyBank.getDeathReaction();
		if (targetName != null && r.nextBoolean()) core += " " + targetName;
	} else if ("GREETING".equals(category)) {
		core = VocabularyBank.getConfigGreeting();
		if (targetName != null && r.nextInt(100) < 30) core += " " + targetName;
	} else {
		core = VocabularyBank.getConfigChat();
	}

	return core;
    }

    private static class SocialResponse {
        UUID sender; String message; long sendAt;
        public SocialResponse(UUID s, String m, long t) { this.sender = s; this.message = m; this.sendAt = t; }
    }
}
