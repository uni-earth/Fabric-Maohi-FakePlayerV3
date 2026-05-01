package com.maohi.fakeplayer.ai;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 智能运动与环境感知控制器 (V3 核心 AI)
 * 解决 2.0 中假人只会直线推推、遇到墙壁"踩缝纫机"的智障问题。
 * 通过模拟人类跳跃和重新寻路，使其轨迹无限逼近真人在野外的跑酷。
 * 
 * V3.1 增强：Perlin 噪声视线漂浮 + 操作延迟模拟
 * V3.2 修复：噪声相位从 ThreadLocal 改为参数传入，避免主线程多假人共享冲突
 * V3.2 反作弊兼容：用 Access Widener 解锁的 LivingEntity 字段 + travel() 走物理引擎合法移动
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
		p.forwardSpeed = forward;
		p.sidewaysSpeed = sideways;
	}

	/**
	 * 智能执行一帧的位移计算
	 * V3.2: 通过 Access Widener 直接设置 LivingEntity 输入字段，然后调用 travel() 让物理引擎处理移动
	 * 这样移动会通过服务端的物理校验，兼容 Grim/Vulcan 等反作弊插件
	 * 
	 * @return true 表示到达目标或判定死路，需要重新分配目标点
	 */
	public static boolean doSmartMove(ServerPlayerEntity p, BlockPos target, double moveStep, 
			double noisePhaseYaw, double noisePhasePitch) {
	// V3.3 安全补丁：防止 target 为空导致 NPE
	if (target == null) {
		stopMovement(p);
		return true; // 返回 true 触发目标重置
	}

		com.maohi.fakeplayer.VirtualPlayerManager.Personality pers = com.maohi.fakeplayer.VirtualPlayerManager.Personality.get(p);
		
		// ★ P2-2: 驻足看风景逻辑
		if (pers != null && pers.sightseeingTicks > 0) {
			pers.sightseeingTicks--;
			stopMovement(p);
			// 看风景时缓慢转头观察四周
			float yawNoise = perlinLike(noisePhaseYaw * 0.8, noiseTime, 1.5f);
			p.setYaw(p.getYaw() + yawNoise);
			return false; // 假装还在赶路，不要让管理器重置任务
		}
		
		// 行走中有 0.3% 的极低概率突然停下来看风景 (3~8秒)
		if (pers != null && ThreadLocalRandom.current().nextInt(300) == 0) {
			pers.sightseeingTicks = 60 + ThreadLocalRandom.current().nextInt(100);
			stopMovement(p);
			return false;
		}

		ServerWorld world = p.getEntityWorld();
		Vec3d pos = p.getEntityPos();
		
		double dx = target.getX() + 0.5 - pos.x;
		double dz = target.getZ() + 0.5 - pos.z;
		double distSq = dx * dx + dz * dz;

		// 1. 如果已经到了目标点附近，通知管理器任务结束
		if (distSq <= 1.5) {
			stopMovement(p);
			return true; 
		}

		double dist = Math.sqrt(distSq);
		boolean isSprinting = dist > 4.0;

		// 2. 视线平滑追踪 + Perlin 噪声漂浮
		float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
		float lerpedYaw = MathHelper.lerpAngleDegrees(0.2f, p.getYaw(), targetYaw);

		float yawNoise = perlinLike(noisePhaseYaw, noiseTime, 1.5f);
		p.setYaw(lerpedYaw + yawNoise);

		float basePitch = p.getPitch();
		float pitchNoise = perlinLike(noisePhasePitch, noiseTime, 2.0f);
		p.setPitch(MathHelper.clamp(basePitch + pitchNoise, -90.0f, 90.0f));

		// 3. 预判下一步坐标
		double nextX = pos.x + (dx / dist * 1.0);
		double nextZ = pos.z + (dz / dist * 1.0);
		BlockPos nextPos = BlockPos.ofFloored(nextX, pos.y, nextZ);

		// 4. 路径规避：如果前方是岩浆或悬崖，坚决不走
		if (PathfindingNavigation.isDangerAhead(world, nextPos)) {
			stopMovement(p);
			return true;
		}

		// 5. 物理碰撞雷达
		boolean isBlocked = !world.getBlockState(nextPos).getCollisionShape(world, nextPos).isEmpty() 
			|| !world.getBlockState(nextPos.up()).getCollisionShape(world, nextPos.up()).isEmpty();

		if (isBlocked) {
			boolean canJumpOver = world.getBlockState(nextPos.up(1)).getCollisionShape(world, nextPos.up(1)).isEmpty()
				&& world.getBlockState(nextPos.up(2)).getCollisionShape(world, nextPos.up(2)).isEmpty()
				&& world.getBlockState(p.getBlockPos().up(2)).getCollisionShape(world, p.getBlockPos().up(2)).isEmpty();

			if (canJumpOver) {
				// 跳跃跑酷：设置前进输入 + 跳跃
				if (p.isOnGround()) {
					p.setSprinting(isSprinting);
					setMovement(p, 1.0f, 0.0f);
					p.jump();
					// V3.2: 跳跃加速仍需 addVelocity（这是 MC 的标准做法，原版的跳跃冲刺也这样）
					p.addVelocity(dx / dist * 0.1, 0, dz / dist * 0.1);
				}
			} else {
				// 死路
				stopMovement(p);
				return true; 
			}
		} else {
			// 道路畅通：通过输入字段控制移动（反作弊兼容）
			p.setSprinting(isSprinting);
			// 前进速度 0.8~1.0（模拟真人的行走/奔跑输入）
			float fwd = 0.8f + ThreadLocalRandom.current().nextFloat() * 0.2f;
			
			// ★ P0: 移动轨迹曲线化 (S形摇摆)
			// 利用假人独立的 noisePhaseYaw（每个假人都不同）生成平滑的侧向输入
			// 振幅设为 0.5f，意味着产生最大 50% 的侧滑速度，形成自然宽阔的曲线
			float lateralDrift = perlinLike(noisePhaseYaw * 1.2, noiseTime, 0.5f);
			
			// 6. 失误模拟：偶尔的脚步错乱（修复原版未生效的 Bug）
			if (ThreadLocalRandom.current().nextInt(150) == 0) {
				lateralDrift += ThreadLocalRandom.current().nextFloat() * 0.8f - 0.4f;
			}
			
			setMovement(p, fwd, lateralDrift);
			// 调用 travel() 让物理引擎处理位移（通过服务端校验）
			p.travel(new Vec3d(p.sidewaysSpeed, 0, p.forwardSpeed));
		}

		return false;
	}
}
