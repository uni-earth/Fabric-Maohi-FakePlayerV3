package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.TimingConstants;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * 任务调度器 (V3)
 */
public final class TaskScheduler {

	private TaskScheduler() {} // 工具类

	/**
	 * 检查任务是否过期，过期则分配新任务
	 * @param p 假人玩家实体
	 * @param personality 假人个性数据
	 * @param tickNow 当前时间戳（毫秒）
	 * @param findNearestBlock 方块搜索回调（VPM 持有此方法，通过 lambda 传入）
	 */
	public static void tick(ServerPlayerEntity p, Personality personality, long tickNow, java.util.function.BiFunction<ServerWorld, BlockPos, BlockPos> findLogBlock) {
		if (tickNow < personality.taskExpireTime) return;

		personality.taskExpireTime = tickNow + TimingConstants.TASK_TIMEOUT_LONG;

		if (ThreadLocalRandom.current().nextInt(100) < 60) {
			personality.currentTask = ThreadLocalRandom.current().nextBoolean() ? TaskType.IDLE : TaskType.EXPLORING;
		} else {
			TaskType[] workTasks = {TaskType.WOODCUTTING, TaskType.MINING, TaskType.COLLECTING};
			personality.currentTask = workTasks[ThreadLocalRandom.current().nextInt(workTasks.length)];
		}

		if (personality.currentTask == TaskType.WOODCUTTING) {
			personality.taskTarget = findLogBlock.apply(p.getEntityWorld(), p.getBlockPos());
		}

		if (personality.taskTarget == null) {
			int rx = ThreadLocalRandom.current().nextInt(40) - 20;
			int rz = ThreadLocalRandom.current().nextInt(40) - 20;
			personality.taskTarget = new BlockPos(
				p.getBlockX() + rx,
				PathfindingNavigation.getSafeTopY(p.getEntityWorld(), p.getBlockX() + rx, p.getBlockZ() + rz),
				p.getBlockZ() + rz
			);
		}
	}
}
