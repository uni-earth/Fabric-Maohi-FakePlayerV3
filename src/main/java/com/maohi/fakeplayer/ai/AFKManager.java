package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.social.VocabularyBank;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * M1: AFK 管理器 — 从 VirtualPlayerManager.processHeavyAILogic 拆分
 * 
 * 负责：AFK 触发/恢复、离开/回来消息、AFK 中偶尔回头
 */
public final class AFKManager {

	private AFKManager() {} // 工具类

	/**
	 * 处理假人 AFK 状态
	 * @param p 假人玩家实体
	 * @param personality 假人个性数据
	 * @param uuid 假人 UUID
	 * @param tickNow 当前时间戳（毫秒）
	 * @param scheduleMsg 消息调度回调（VPM 持有此方法，通过 lambda 传入）
	 * @return true 表示假人当前处于 AFK 状态（调用方应跳过后续逻辑）
	 */
	public static boolean tick(ServerPlayerEntity p, Personality personality, UUID uuid, long tickNow, MessageScheduler scheduleMsg) {
		// AFK 恢复
		if (personality.isAFK) {
			personality.afkRemainingTicks--;
			if (personality.afkRemainingTicks <= 0) {
				personality.isAFK = false;
				personality.currentTask = TaskType.IDLE;
				String backMsg = VocabularyBank.getBackMessage();
				scheduleMsg.schedule(new String[]{backMsg}, 1, 3, uuid);
			} else {
				// AFK 中：偶尔转头但不动
				if (ThreadLocalRandom.current().nextInt(100) < 3) {
					p.setYaw(p.getYaw() + ThreadLocalRandom.current().nextFloat() * 10 - 5);
				}
				return true; // AFK 中不做任何动作
			}
		}

		// 随机触发 AFK
		if (tickNow >= personality.nextAFKTime && !personality.isAFK && personality.currentTask != TaskType.IDLE) {
			if (ThreadLocalRandom.current().nextInt(300) == 0) {
				personality.isAFK = true;
				personality.afkRemainingTicks = 200 + ThreadLocalRandom.current().nextInt(1000); // 10-60 秒
				personality.currentTask = TaskType.AFK;
				p.setSneaking(false);
				p.setSprinting(false);
				// AFK 前可能发一条离开消息
				String afkMsg = VocabularyBank.getAFKMessage();
				if (ThreadLocalRandom.current().nextInt(3) == 0) {
					scheduleMsg.schedule(new String[]{afkMsg}, 0, 2, uuid);
				}
				return true;
			}
			personality.nextAFKTime = tickNow + TimingConstants.AFK_CHECK_MIN + ThreadLocalRandom.current().nextLong(TimingConstants.AFK_CHECK_JITTER);
		}

		return false; // 不在 AFK
	}

	/** 消息调度接口（解耦 VPM 依赖） */
	@FunctionalInterface
	public interface MessageScheduler {
		void schedule(String[] messages, int minSec, int maxSec, UUID sender);
	}
}
