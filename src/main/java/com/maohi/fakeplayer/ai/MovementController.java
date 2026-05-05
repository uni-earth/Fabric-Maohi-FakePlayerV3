package com.maohi.fakeplayer.ai;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 智能运动控制器 (V3)
 */
public class MovementController {

	/** 噪声采样时间步进（全局共享，每个 tick 递增） */
	private static double noiseTime = 0;

	/**
	 * 简化 Perlin 噪声（1D）
	 * 基于多频率正弦叠加，模拟 Perlin 噪声的自然漂浮感
	 */
	private static float perlinLike(double phase, double time, float amp) {
		double v = Math.sin(phase + time * 0.013) * 0.5
				+ Math.sin(phase * 1.7 + time * 0.047) * 0.3
				+ Math.sin(phase * 2.3 + time * 0.11) * 0.2;
		return (float) (v * amp);
	}

	/** 每 tick 递增噪声时间 */
	public static void tickNoise() {
		noiseTime += 1.0;
	}

	/** 获取当前噪声时间 */
	public static double getNoiseTime() {
		return noiseTime;
	}

	/**
	 * 停止所有移动输入
	 * V3.2 1.21.11 适配：通过 Access Widener 直接设置 LivingEntity 的 forwardSpeed/sidewaysSpeed 字段
	 */
	private static void stopMovement(ServerPlayerEntity p) {
		p.forwardSpeed = 0.0f;
		p.sidewaysSpeed = 0.0f;
		p.setSprinting(false);
	}

	/**
	 * 设置前进输入
	 * @param forward 前进速度 (-1.0 ~ 1.0)
	 * @param sideways 横向速度 (-1.0 ~ 1.0)
	 */
	private static void setMovement(ServerPlayerEntity p, float forward, float sideways) {
		com.maohi.fakeplayer.Personality pers =
			com.maohi.fakeplayer.Personality.get(p);
		
		if (pers != null) {
			// V5.2 Keyboard Fingerprint: WASD 松键间隙模拟
			if (pers.keyReleaseMicroGapTicks > 0) {
				pers.keyReleaseMicroGapTicks--;
				p.forwardSpeed = 0.0f;
				p.sidewaysSpeed = 0.0f;
				return;
			}
			
			// 方向大切换时产生随机停顿
			if (Math.abs(forward - p.forwardSpeed) > 0.5f || Math.abs(sideways - p.sidewaysSpeed) > 0.5f) {
				if (ThreadLocalRandom.current().nextInt(10) == 0) {
					pers.keyReleaseMicroGapTicks = 1 + ThreadLocalRandom.current().nextInt(2); // 50-100ms
					return;
				}
			}
		}

		p.forwardSpeed = forward;
		p.sidewaysSpeed = sideways;
	}

	/**
	 * 智能执行一帧的位移计算
	 * V4: 接入 A* 路径缓存，遇到障碍时绕路而不是放弃
	 * @return true 表示到达目标或无路可走，需要重新分配目标点
	 */
	public static boolean doSmartMove(ServerPlayerEntity p, BlockPos target, double moveStep,
			double noisePhaseYaw, double noisePhasePitch) {
		if (target == null) { stopMovement(p); return true; }

		com.maohi.fakeplayer.Personality pers =
			com.maohi.fakeplayer.Personality.get(p);

		// V5.0 A: 物理跳跃检测 (识别 1 格坑)
		BlockPos ahead = p.getBlockPos().offset(p.getHorizontalFacing());
		if (p.isOnGround() && p.getEntityWorld().getBlockState(ahead).isAir() 
			&& !p.getEntityWorld().getBlockState(ahead.offset(p.getHorizontalFacing())).isAir()) {
			p.jump();
		}

		// 平滑转向逻辑
		if (pers != null && pers.sightseeingTicks > 0) {
			pers.sightseeingTicks--;
			stopMovement(p);
			p.setYaw(p.getYaw() + perlinLike(noisePhaseYaw * 0.8, noiseTime, 1.5f));
			return false;
		}
		if (pers != null && ThreadLocalRandom.current().nextInt(300) == 0) {
			pers.sightseeingTicks = 60 + ThreadLocalRandom.current().nextInt(100);
			stopMovement(p);
			return false;
		}

		ServerWorld world = p.getEntityWorld();
		Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());

		// 到达目标
		double dx = target.getX() + 0.5 - pos.x;
		double dz = target.getZ() + 0.5 - pos.z;
		if (dx * dx + dz * dz <= 1.5) { stopMovement(p); return true; }

		// ★ A* 路径跟随：如果有缓存路径且目标未变，沿路径走
		BlockPos nextWaypoint = target;
		if (pers != null) {
			// 目标变了就清路径
			if (!target.equals(pers.pathGoal)) {
				pers.currentPath.clear();
				pers.pathGoal = null;
			}
			// 路径为空时尝试计算
			if (pers.currentPath.isEmpty()) {
				java.util.List<BlockPos> path = PathfindingNavigation.findPath(world, p.getBlockPos(), target);
				if (!path.isEmpty()) {
					pers.currentPath.addAll(path);
					pers.pathGoal = target;
				}
			}
			// 消费已到达的路径点
			while (!pers.currentPath.isEmpty()) {
				BlockPos wp = pers.currentPath.peek();
				double wdx = wp.getX() + 0.5 - pos.x;
				double wdz = wp.getZ() + 0.5 - pos.z;
				if (wdx * wdx + wdz * wdz <= 2.25) pers.currentPath.poll();
				else break;
			}
			if (!pers.currentPath.isEmpty()) nextWaypoint = pers.currentPath.peek();
		}

		// 朝向下一个路径点
		double ndx = nextWaypoint.getX() + 0.5 - pos.x;
		double ndz = nextWaypoint.getZ() + 0.5 - pos.z;
		double ndist = Math.sqrt(ndx * ndx + ndz * ndz);
		if (ndist < 0.01) ndist = 0.01;

		// V5.2 Mouse Fingerprint: Fitts' Law 拟真视角曲线 (快速逼近 -> 减速微调)
		float targetYaw = (float) (MathHelper.atan2(ndz, ndx) * (180F / Math.PI)) - 90.0F;
		float targetPitch = (float) (MathHelper.atan2(-p.getY() + nextWaypoint.getY(), ndist) * (180F / Math.PI));
		
		float currentYaw = p.getYaw();
		float currentPitch = p.getPitch();
		
		float diff = MathHelper.wrapDegrees(targetYaw - currentYaw);
		float absDiff = Math.abs(diff);
		
		float lerpFactor = com.maohi.fakeplayer.ai.BehavioralDistributionValidator.getAlignedRotationLerp();
		
		// V5.7 P0 鼠标轨迹模拟：菲茨定律 (Fitts' Law) + 过冲 (Overshoot)
		if (absDiff > 15.0f) {
			// 阶段 1 & 2: 快速逼近与减速
			lerpFactor *= 2.5f; 
			// 10% 概率产生过冲 (Overshoot)
			if (ThreadLocalRandom.current().nextInt(100) < 10 && absDiff > 45.0f) {
				targetYaw += (diff > 0 ? 5.0f : -5.0f); // 故意转过头 5 度
			}
		} else if (absDiff < 2.0f) {
			// 阶段 3 & 4: 微调与确认
			lerpFactor *= 0.3f;
		}

		float newYaw = MathHelper.lerp(lerpFactor, currentYaw, targetYaw);
		float newPitch = MathHelper.lerp(lerpFactor, currentPitch, targetPitch);
		
		// 叠加高斯噪声模拟生理手抖 (Tremor)
		newYaw += (float)(ThreadLocalRandom.current().nextGaussian() * 0.05) + perlinLike(noisePhaseYaw, noiseTime, 1.2f);
		newPitch += (float)(ThreadLocalRandom.current().nextGaussian() * 0.05) + perlinLike(noisePhasePitch, noiseTime, 1.5f);
		
		p.setYaw(newYaw);
		p.setPitch(newPitch);

		// V5.2 Keyboard Fingerprint: 双击方向键冲刺误触发模拟
		if (p.isOnGround() && !p.isSprinting() && ThreadLocalRandom.current().nextInt(1000) == 0) {
			p.setSprinting(true); // 突然手滑冲刺一下
		}

		// 前方碰撞检测
		double nx = pos.x + ndx / ndist;
		double nz = pos.z + ndz / ndist;
		BlockPos nextPos = BlockPos.ofFloored(nx, pos.y, nz);

		if (PathfindingNavigation.isDangerAhead(world, nextPos)) {
			stopMovement(p);
			if (pers != null) pers.currentPath.clear();
			return true;
		}

		boolean isBlocked = !world.getBlockState(nextPos).getCollisionShape(world, nextPos).isEmpty()
			|| !world.getBlockState(nextPos.up()).getCollisionShape(world, nextPos.up()).isEmpty();

		if (isBlocked) {
			boolean canJump = world.getBlockState(nextPos.up(1)).getCollisionShape(world, nextPos.up(1)).isEmpty()
				&& world.getBlockState(nextPos.up(2)).getCollisionShape(world, nextPos.up(2)).isEmpty()
				&& world.getBlockState(p.getBlockPos().up(2)).getCollisionShape(world, p.getBlockPos().up(2)).isEmpty();
			if (canJump) {
				if (p.isOnGround()) {
					p.setSprinting(ndist > 4.0);
					setMovement(p, 1.0f, 0.0f);
					p.jump();
					p.addVelocity(ndx / ndist * 0.1, 0, ndz / ndist * 0.1);
				}
			} else {
				// 无法跳越：清路径，下次重新计算
				if (pers != null) pers.currentPath.clear();
				stopMovement(p);
				return false; // 不放弃任务，等下次 tick 重算路径
			}
		} else {
			p.setSprinting(ndist > 4.0);
			float lateralDrift = perlinLike(noisePhaseYaw * 1.2, noiseTime, 0.5f);
			if (ThreadLocalRandom.current().nextInt(150) == 0)
				lateralDrift += ThreadLocalRandom.current().nextFloat() * 0.8f - 0.4f;
			
			// V4.4 情绪修正：死后 5 分钟内速度降低 30% (跑尸沮丧模拟)
			float speedFactor = 1.0f;
			long serverTicks = p.getEntityWorld().getServer().getTicks();
			if (pers != null && serverTicks - pers.lastDeathTick < 6000) {
				speedFactor = 0.7f;
			}
			
			setMovement(p, (0.8f + ThreadLocalRandom.current().nextFloat() * 0.2f) * speedFactor, lateralDrift * speedFactor);
			p.travel(new Vec3d(p.sidewaysSpeed, 0, p.forwardSpeed));
		}

		return false;
	}
}
