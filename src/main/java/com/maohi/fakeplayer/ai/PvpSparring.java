package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.social.VocabularyBank;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import com.maohi.Maohi;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人间切磋演戏系统 (V4 P1-2)
 * 模拟两个假人在野外相遇时的随机互动。
 */
public class PvpSparring {

	private static final String[] PVP_START = {"p?", "1v1", "fight me", "come here", "pvp?"};
	private static final String[] PVP_WIN = {"ez", "gg", "close one", "nice try", "lmao"};
	private static final String[] PVP_LOSE = {"gg", "u win", "lag", "im low", "stop"};

	public static void tickSparring(ServerPlayerEntity player, VirtualPlayerManager.Personality personality, long tickNow) {
		// 1. 如果已经在切磋状态，执行切磋逻辑
		if (personality.isSparring) {
			handleSparring(player, personality, tickNow);
			return;
		}

		// 2. 只有在 IDLE 或 EXPLORING 状态才可能触发（不打断挖矿、进食等重要行为）
		if (personality.currentTask != VirtualPlayerManager.TaskType.IDLE && 
			personality.currentTask != VirtualPlayerManager.TaskType.EXPLORING) {
			return;
		}

		// 3. 频率控制：每 20 tick (1秒) 检查一次
		if (tickNow % 20 != 0) return;

		// 4. 触发概率：10% 瞬时触发（适配小服，扩大触发率）
		if (ThreadLocalRandom.current().nextInt(100) >= 10) return;

		// 5. 全局冷却：距离上次切磋不到 15 分钟 (15*60*20 = 18000 tick)
		if (tickNow - personality.lastSparringTick < 18000) return;

		// 6. 视野范围扫描：寻找 16 格内的其他假人
		World world = player.getEntityWorld();
		Box box = player.getBoundingBox().expand(16.0);
		List<ServerPlayerEntity> nearbyPlayers = world.getEntitiesByClass(ServerPlayerEntity.class, box, p -> {
			if (p == player || !p.isAlive()) return false;
			// 必须是其他假人
			VirtualPlayerManager.Personality otherPers = VirtualPlayerManager.Personality.get(p);
			return otherPers != null;
		});

		if (nearbyPlayers.isEmpty()) return;

		// 随机选一个目标
		ServerPlayerEntity target = nearbyPlayers.get(ThreadLocalRandom.current().nextInt(nearbyPlayers.size()));
		VirtualPlayerManager.Personality targetPers = VirtualPlayerManager.Personality.get(target);

		// 确保目标也有空，且没有在冷却中
		if (targetPers == null || targetPers.isSparring || targetPers.isEating || targetPers.isMining) return;
		if (tickNow - targetPers.lastSparringTick < 18000) return;
		if (targetPers.currentTask != VirtualPlayerManager.TaskType.IDLE && targetPers.currentTask != VirtualPlayerManager.TaskType.EXPLORING) return;

		// ★ 双方正式进入切磋状态
		startSparring(player, personality, target, targetPers, tickNow);
	}

	private static void startSparring(ServerPlayerEntity player, VirtualPlayerManager.Personality pPers, 
									  ServerPlayerEntity target, VirtualPlayerManager.Personality tPers, long tickNow) {
		pPers.isSparring = true;
		pPers.sparringTarget = target.getUuid();
		pPers.sparringStartTick = tickNow;
		pPers.lastSparringTick = tickNow;
		pPers.currentTask = VirtualPlayerManager.TaskType.IDLE;
		pPers.taskTarget = null;

		tPers.isSparring = true;
		tPers.sparringTarget = player.getUuid();
		tPers.sparringStartTick = tickNow;
		tPers.lastSparringTick = tickNow;
		tPers.currentTask = VirtualPlayerManager.TaskType.IDLE;
		tPers.taskTarget = null;

		// 发起方发一条挑衅消息
		String msg = VocabularyBank.addEmotion(PVP_START[ThreadLocalRandom.current().nextInt(PVP_START.length)]);
		if (Maohi.getVirtualPlayerManager() != null) {
			Maohi.getVirtualPlayerManager().scheduleDelayedResponse(new String[]{msg}, 1, 3, player.getUuid());
		}
	}

	private static void handleSparring(ServerPlayerEntity player, VirtualPlayerManager.Personality personality, long tickNow) {
		ServerPlayerEntity target = Maohi.getVirtualPlayerManager().getServer().getPlayerManager().getPlayer(personality.sparringTarget);

		// ★ 终止条件检查
		// 1. 超时 (切磋最长持续 20 秒 = 400 tick)
		boolean timeUp = (tickNow - personality.sparringStartTick) > 400;
		// 2. 血量危机 (任意一方血量低于 50%)
		boolean lowHealth = player.getHealth() < player.getMaxHealth() * 0.5f;
		// 3. 目标丢失 (掉线或死亡)
		boolean targetLost = target == null || !target.isAlive();

		if (timeUp || lowHealth || targetLost) {
			endSparring(player, personality, lowHealth || targetLost);
			return;
		}

		// ★ 战斗移动与攻击逻辑
		double dist = player.distanceTo(target);
		if (dist > 3.0) {
			// 距离较远，跑向目标
			MovementController.doSmartMove(player, target.getBlockPos(), 1.0, 
				personality.noisePhaseYaw, personality.noisePhasePitch);
		} else {
			// 在攻击范围内：停下，但保持面向目标
			player.forwardSpeed = 0.0f;
			player.sidewaysSpeed = 0.0f;
			
			// 平滑转头看向目标
			double dx = target.getX() - player.getX();
			double dz = target.getZ() - player.getZ();
			float targetYaw = (float) (Math.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
			player.setYaw(targetYaw);

			// 限制攻击频率：1 秒只打 1-2 下（演戏，不能像打怪一样死磕）
			if (tickNow % 10 == 0 && ThreadLocalRandom.current().nextBoolean()) {
				PacketHelper.attackEntity(player, target);
				
				// 偶尔走位跳跃
				if (ThreadLocalRandom.current().nextBoolean() && player.isOnGround()) {
					player.jump();
					player.addVelocity(0, 0.42, 0); // 原版基础跳跃高度
				}
			}
		}
	}

	private static void endSparring(ServerPlayerEntity player, VirtualPlayerManager.Personality personality, boolean isLoser) {
		personality.isSparring = false;
		
		ServerPlayerEntity target = Maohi.getVirtualPlayerManager().getServer().getPlayerManager().getPlayer(personality.sparringTarget);
		personality.sparringTarget = null;
		
		// 通知对方也停止
		if (target != null && target.isAlive()) {
			VirtualPlayerManager.Personality tPers = VirtualPlayerManager.Personality.get(target);
			if (tPers != null && tPers.isSparring) {
				tPers.isSparring = false;
				tPers.sparringTarget = null;
				
				// 输赢发言：血少的说输的话，血多的说赢的话
				if (Maohi.getVirtualPlayerManager() != null) {
					String loserMsg = VocabularyBank.addEmotion(PVP_LOSE[ThreadLocalRandom.current().nextInt(PVP_LOSE.length)]);
					String winnerMsg = VocabularyBank.addEmotion(PVP_WIN[ThreadLocalRandom.current().nextInt(PVP_WIN.length)]);
					
					// 谁调用的 endSparring，谁就是"触发终止"的一方（通常是因为血量低）
					Maohi.getVirtualPlayerManager().scheduleDelayedResponse(new String[]{loserMsg}, 1, 3, player.getUuid());
					Maohi.getVirtualPlayerManager().scheduleDelayedResponse(new String[]{winnerMsg}, 3, 5, target.getUuid());
				}
			}
		}
	}
}
