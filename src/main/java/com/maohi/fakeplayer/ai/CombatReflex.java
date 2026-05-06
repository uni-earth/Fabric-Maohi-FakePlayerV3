package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗反射系统 (V3)
 *
 * V5.22 加固:
 *   P0 删除 player.attack 双扣血(在 PacketHelper 修)
 *   P1 strafe/forward 速度走 actionMultiplier,不再硬编码 0.5/0.3
 *   P2 跳劈概率从 1/3 → 1/15(反作弊不再误判 KillAura)
 *   P3 setYaw 用 Fitts lerp 替代瞬移
 *   P4 fleeFrom forwardSpeed 1.0 → 走 actionMultiplier;并加 fleeUntilTick 上限
 */
public class CombatReflex {

	/** 攻击冷却阈值:只有冷却进度 ≥ 90% 时才攻击(模拟真人的攻击节奏) */
	private static final float ATTACK_COOLDOWN_THRESHOLD = 0.9f;

	/** 苦力怕逃跑最长持续 tick(超过自动放弃,避免无限直线狂奔) */
	private static final int FLEE_MAX_DURATION_TICKS = 60; // 3 秒

	/**
	 * 执行战斗扫描与自卫动作
	 *
	 * @return true 表示正在逃跑（需要 MovementController 暂停寻路）
	 */
	public static boolean executeCombatLogic(ServerPlayerEntity player) {
		// 1. 获取周围实体
		List<Entity> entities = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(12.0)
		);

		// 2. 自动持盾(V4.2):检测远程威胁
		boolean hasShield = player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD);
		if (hasShield) {
			boolean underRangedAttack = false;
			for (Entity entity : entities) {
				if (entity instanceof net.minecraft.entity.mob.SkeletonEntity || entity instanceof net.minecraft.entity.mob.PillagerEntity) {
					if (entity.isAlive() && player.squaredDistanceTo(entity) < 144.0) {
						underRangedAttack = true;
						break;
					}
				}
			}
			player.setSneaking(underRangedAttack);
		}

		// 3. 优先级1:苦力怕逃跑
		for (Entity entity : entities) {
			if (entity instanceof CreeperEntity creeper && creeper.isAlive()) {
				if (player.squaredDistanceTo(creeper) < 25.0) {
					return fleeFrom(player, creeper);
				}
			}
		}

		// 4. 优先级2:其他敌对生物 → 反击
		Personality pers = Personality.get(player);
		for (Entity entity : entities) {
			if (entity instanceof HostileEntity hostile && hostile.isAlive()
				&& !(entity instanceof CreeperEntity)) {

				// V5.22 P3: PVP 预判 + Fitts 平滑转头(不再瞬移)
				double predictX = hostile.getX() + hostile.getVelocity().x * 4.0;
				double predictZ = hostile.getZ() + hostile.getVelocity().z * 4.0;
				double dx = predictX - player.getX();
				double dz = predictZ - player.getZ();
				float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
				smoothTurnYaw(player, targetYaw);

				if (player.squaredDistanceTo(hostile) < 16.0) {
					// V5.22 P1: strafe/forward 用 actionMultiplier 缩放,贴合真人速度分布
					float speedMod = pers != null ? pers.actionMultiplier : 1.0f;
					float strafeDirection = (player.getId() % 2 == 0) ? 1.0f : -1.0f;
					if (ThreadLocalRandom.current().nextInt(20) == 0) strafeDirection *= -1;
					player.sidewaysSpeed = 0.5f * strafeDirection * speedMod;
					player.forwardSpeed = 0.3f * speedMod;

					float cooldown = player.getAttackCooldownProgress(0.5f);
					if (cooldown >= ATTACK_COOLDOWN_THRESHOLD) {
						// V5.22 P2: 跳劈频率 1/3 → 1/15,避免被反作弊判 KillAura+Jump
						if (player.experienceLevel > 10 && player.isOnGround()
							&& ThreadLocalRandom.current().nextInt(15) == 0) {
							player.jump();
						}

						com.maohi.fakeplayer.network.PacketHelper.attackEntity(player, hostile);

						if (pers != null) {
							pers.lastAttackTick = player.getEntityWorld().getServer().getTicks();
						}
					}
				}

				return false;
			}
		}

		return false;
	}

	/**
	 * 从目标实体逃跑
	 *
	 * V5.22 P4 修复:
	 *   - forwardSpeed 1.0 → 走 actionMultiplier(原值反作弊会判 SpeedHack)
	 *   - 增加 fleeUntilTick 上限,防止苦力怕走开后假人继续直线狂奔到天涯海角
	 *
	 * V5.24 危险加固(P0):
	 *   - 旧实现完全不查危险,苦力怕在悬崖/岩浆边时假人会直线倒退跳坑/跳浆
	 *   - 新实现:先 isDangerAhead 探查正向逃跑点,危险则改尝试 ±90° 侧滑;三向皆险
	 *     就站定吃苦力怕的爆炸伤(7-22 半心,有甲撑得住),胜过自杀坠崖/烫熟
	 *   - 站定时仍朝远离方向看,贴合"绝境瞪眼"的真人画面
	 */
	private static boolean fleeFrom(ServerPlayerEntity player, Entity threat) {
		Personality pers = Personality.get(player);
		long now = player.getEntityWorld().getServer().getTicks();

		// 第一次进入逃跑状态时记下截止 tick
		if (pers != null && pers.fleeUntilTick < now) {
			pers.fleeUntilTick = now + FLEE_MAX_DURATION_TICKS;
		}

		double dx = player.getX() - threat.getX();
		double dz = player.getZ() - threat.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 0.1) dist = 0.1;
		double fleeX = dx / dist;
		double fleeZ = dz / dist;

		// 轻微随机偏移,避免假人沿直线逃跑
		double jitter = ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
		double cos = Math.cos(jitter);
		double sin = Math.sin(jitter);
		double rotatedX = fleeX * cos - fleeZ * sin;
		double rotatedZ = fleeX * sin + fleeZ * cos;

		// V5.24 P0: 危险检测——逃跑前先看正向 1.5 格是不是悬崖/岩浆/火;
		//   危险则尝试 ±90° 侧滑;三向皆险时站定
		ServerWorld world = player.getEntityWorld();
		double moveX = rotatedX, moveZ = rotatedZ;
		boolean cornered = false;

		if (isFleePathDangerous(world, player, moveX, moveZ)) {
			// 试右侧 90°(顺时针):(x,z) → (z, -x)
			double rightX = rotatedZ;
			double rightZ = -rotatedX;
			if (!isFleePathDangerous(world, player, rightX, rightZ)) {
				moveX = rightX;
				moveZ = rightZ;
			} else {
				// 试左侧 90°(逆时针):(x,z) → (-z, x)
				double leftX = -rotatedZ;
				double leftZ = rotatedX;
				if (!isFleePathDangerous(world, player, leftX, leftZ)) {
					moveX = leftX;
					moveZ = leftZ;
				} else {
					cornered = true;
				}
			}
		}

		if (cornered) {
			// 三向皆险:站定吃爆炸,胜过自杀坠落/烫死
			player.setSprinting(false);
			player.forwardSpeed = 0.0f;
			player.sidewaysSpeed = 0.0f;
			// 仍朝远离威胁方向瞪眼
			float fleeYawCornered = (float) (Math.toDegrees(Math.atan2(-rotatedX, rotatedZ)));
			smoothTurnYaw(player, fleeYawCornered);
			return true;
		}

		// V5.22 P4: 速度受 actionMultiplier 与战斗状态影响,不再硬编码 1.0
		float speedMod = pers != null ? pers.actionMultiplier : 1.0f;
		player.setSprinting(true);
		player.forwardSpeed = 0.85f * speedMod; // 略低于 vanilla sprint 上限,留余量
		player.sidewaysSpeed = 0.0f;
		player.travel(new Vec3d(player.sidewaysSpeed, 0, player.forwardSpeed));

		// 平滑朝逃跑方向(用最终选定的 moveX/moveZ,不再仅基于威胁方向)
		float fleeYaw = (float) (Math.toDegrees(Math.atan2(-moveX, moveZ)));
		smoothTurnYaw(player, fleeYaw);

		// 前方有障碍跳跃逃跑
		if (player.isOnGround() && player.horizontalCollision) {
			player.jump();
		}

		// 8% 概率惊恐喊话
		if (ThreadLocalRandom.current().nextInt(120) == 0) {
			com.maohi.fakeplayer.Personality persP = com.maohi.fakeplayer.Personality.get(player);
			String panicMsg = com.maohi.fakeplayer.social.VocabularyBank.getCreeperFear(persP);
			VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null && mgr.getServer() != null) {
				mgr.getSocialEngine().sendImmediateChat(player.getUuid(), panicMsg);
			}
		}

		return true;
	}

	/**
	 * V5.24 P0: 探查给定方向 1.5 格远的逃跑落脚点是否危险。
	 * 直接调 PathfindingNavigation.isDangerAhead — 内部已涵盖:
	 *   - fall ≥ 3 格(脚下 + 再下 2 格全 air)
	 *   - 岩浆流体
	 *   - 火、岩浆块(脚底烫脚)
	 */
	private static boolean isFleePathDangerous(ServerWorld world, ServerPlayerEntity player,
	                                           double dirX, double dirZ) {
		BlockPos ahead = BlockPos.ofFloored(
			player.getX() + dirX * 1.5,
			player.getY(),
			player.getZ() + dirZ * 1.5);
		return PathfindingNavigation.isDangerAhead(world, ahead);
	}

	/**
	 * V5.22 P3: Fitts 平滑转头——避免战斗中视角瞬移触发反作弊。
	 * 大角度差快速转,小角度差缓慢微调,贴合真人鼠标手感。
	 */
	private static void smoothTurnYaw(ServerPlayerEntity player, float targetYaw) {
		float currentYaw = player.getYaw();
		float diff = MathHelper.wrapDegrees(targetYaw - currentYaw);
		float absDiff = Math.abs(diff);
		float lerp = 0.5f;
		if (absDiff > 60.0f) lerp = 0.6f;       // 大转向:略快
		else if (absDiff < 5.0f) lerp = 0.2f;   // 微调:缓慢
		player.setYaw(MathHelper.lerp(lerp, currentYaw, targetYaw));
	}
}
