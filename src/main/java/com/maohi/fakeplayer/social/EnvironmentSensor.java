package com.maohi.fakeplayer.social;

import net.minecraft.block.BedBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人环境感知神经 (V3.1 社交引擎增强)
 * 能够感知天气、光照、自身着火等环境变化，并产生相应的吐槽。
 * 
 * V3.1 增强：从"只吐槽"升级为"吐槽+行动"
 * - 下雨时尝试找遮蔽物并移动过去
 * - 天黑时尝试找床睡觉（对床方块交互）
 * - 着火时尝试找水源并移动过去
 * 
 * V3.2 修复：接通行动逻辑，返回 BlockPos 让 VPM 接管移动
 */
public class EnvironmentSensor {

	/**
	 * 环境感知结果：包含可选的吐槽消息和可选的行动目标
	 */
	public static class SenseResult {
		public final String message;     // 可能为 null
		public final BlockPos moveTarget; // 可能为 null
		public final boolean interactBed; // 是否需要对目标方块交互（床）

		public SenseResult(String message, BlockPos moveTarget, boolean interactBed) {
			this.message = message;
			this.moveTarget = moveTarget;
			this.interactBed = interactBed;
		}

		public static SenseResult none() { return new SenseResult(null, null, false); }
		public static SenseResult chat(String msg) { return new SenseResult(msg, null, false); }
		public static SenseResult action(BlockPos target, boolean interact) { return new SenseResult(null, target, interact); }
		public static SenseResult chatAndAction(String msg, BlockPos target, boolean interact) { return new SenseResult(msg, target, interact); }
	}

	/**
	 * 感知环境并生成行动决策
	 * @return SenseResult 包含可选的吐槽消息和行动目标
	 */
	public static SenseResult senseEnvironment(ServerPlayerEntity player) {
		World world = player.getEntityWorld();
		java.util.Random r = ThreadLocalRandom.current();

		// 1. 感知下雨 → 吐槽 + 尝试找遮蔽物
		if (world.isRaining() && world.isSkyVisible(player.getBlockPos())) {
			if (r.nextInt(100) < 5) {
				// 吐槽 + 可能同时行动
				BlockPos shelter = findShelter(player);
				if (shelter != null) {
					return SenseResult.chatAndAction(VocabularyBank.getRainComplaint(), shelter, false);
				}
				return SenseResult.chat(VocabularyBank.getRainComplaint());
			}
			// 即使不吐槽，也有 3% 概率找避雨处
			if (r.nextInt(100) < 3) {
				BlockPos shelter = findShelter(player);
				if (shelter != null) {
					return SenseResult.action(shelter, false);
				}
			}
		}

		// 2. 感知黑夜 → 吐槽 + 尝试找床睡觉
		if (world.isNight() && world.isSkyVisible(player.getBlockPos())) {
			if (r.nextInt(100) < 3) {
				BlockPos bed = findBed(player);
				if (bed != null) {
					return SenseResult.chatAndAction(VocabularyBank.getNightComplaint(), bed, true);
				}
				return SenseResult.chat(VocabularyBank.getNightComplaint());
			}
			// 不吐槽但可能找床
			if (r.nextInt(100) < 2) {
				BlockPos bed = findBed(player);
				if (bed != null) {
					return SenseResult.action(bed, true);
				}
			}
		}

		// 3. 感知着火 → 吐槽 + 尝试找水（高优先级行动）
		if (player.isOnFire()) {
			BlockPos water = findWater(player);
			if (water != null) {
				// 着火时行动优先，30% 同时吐槽
				if (r.nextInt(100) < 30) {
					return SenseResult.chatAndAction(VocabularyBank.getFireComplaint(), water, false);
				}
				return SenseResult.action(water, false);
			}
			// 找不到水，只能吐槽
			if (r.nextInt(100) < 15) {
				return SenseResult.chat(VocabularyBank.getFireComplaint());
			}
		}

		return SenseResult.none();
	}

	/**
	 * 搜索周围 8 格内最近的遮蔽处（头顶不是天空的位置）
	 * @return 遮蔽处的坐标，如果已在遮蔽下或找不到则返回 null
	 */
	private static BlockPos findShelter(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		// 如果已经在遮蔽下，不用动
		if (!world.isSkyVisible(pos)) return null;

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		for (int dx = -8; dx <= 8; dx += 2) {
			for (int dz = -8; dz <= 8; dz += 2) {
				BlockPos check = pos.add(dx, 0, dz);
				if (!world.isSkyVisible(check)) {
					double distSq = dx * dx + (double) dz * dz;
					if (distSq < nearestDistSq) {
						nearestDistSq = distSq;
						nearest = check;
					}
				}
			}
		}
		return nearest;
	}

	/**
	 * 搜索周围 10 格内最近的床
	 * @return 床的坐标，找不到返回 null
	 */
	private static BlockPos findBed(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		for (int dx = -10; dx <= 10; dx += 2) {
			for (int dz = -10; dz <= 10; dz += 2) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos check = pos.add(dx, dy, dz);
					if (world.getBlockState(check).getBlock() instanceof BedBlock) {
						double distSq = dx * dx + (double)(dz * dz) + (double)(dy * dy);
						if (distSq < nearestDistSq) {
							nearestDistSq = distSq;
							nearest = check;
						}
					}
				}
			}
		}
		return nearest;
	}

	/**
	 * 对目标位置的床方块执行交互（真正使用床）
	 * V3.3: 走真实发包链路，反作弊能看到完整的右键交互包
	 * @return true 如果交互成功
	 */
	public static boolean interactBedAt(ServerPlayerEntity player, BlockPos bedPos) {
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
		net.minecraft.block.BlockState state = world.getBlockState(bedPos);
		if (state.getBlock() instanceof BedBlock) {
			// ★ V3.3: 走真实发包 — 反作弊能看到 PlayerInteractBlockC2SPacket
			net.minecraft.util.hit.BlockHitResult hitResult = 
				new net.minecraft.util.hit.BlockHitResult(
					net.minecraft.util.math.Vec3d.ofCenter(bedPos), 
					net.minecraft.util.math.Direction.UP, 
					bedPos, false
				);
			com.maohi.fakeplayer.network.PacketHelper.interactBlock(
				player, net.minecraft.util.Hand.MAIN_HAND, hitResult);
			return true;
		}
		return false;
	}

	/**
	 * 搜索周围 6 格内最近的水源
	 * @return 水源坐标，找不到返回 null
	 */
	private static BlockPos findWater(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		for (int dx = -6; dx <= 6; dx += 2) {
			for (int dz = -6; dz <= 6; dz += 2) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos check = pos.add(dx, dy, dz);
					if (world.getBlockState(check).getFluidState().isStill()) {
						double distSq = dx * dx + (double)(dz * dz) + (double)(dy * dy);
						if (distSq < nearestDistSq) {
							nearestDistSq = distSq;
							nearest = check;
						}
					}
				}
			}
		}
		return nearest;
	}
}
