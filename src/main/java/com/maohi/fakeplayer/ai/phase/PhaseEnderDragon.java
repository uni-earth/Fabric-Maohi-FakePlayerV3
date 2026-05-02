package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第五阶段：末影龙 (V3)
 *
 * 目标：进入末地，挑战并击败末影龙
 * 进入条件：背包有下界合金装备
 * 毕业条件：末影龙被击败（服务器广播）
 *
 * 任务优先级（待完善）：
 *   1. 寻找要塞激活末地传送门
 *   2. 进入末地
 *   3. 摧毁末地水晶
 *   4. 击败末影龙
 *
 * 待完善：
 *   - 寻找要塞（投掷末影之眼追踪）
 *   - 激活末地传送门
 *   - 末地战斗逻辑（摧毁水晶、攻击龙）
 *   - 击败后返回主世界
 */
public final class PhaseEnderDragon {

    private PhaseEnderDragon() {}

    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.Supplier<net.minecraft.entity.mob.HostileEntity> findHunt) {
        // TODO: 末地专属逻辑（寻找要塞、激活传送门、击败末影龙）
        // 当前占位：探索+打猎
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 60) {
            net.minecraft.entity.mob.HostileEntity huntTarget = findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
                return;
            }
        }
        personality.currentTask = TaskType.EXPLORING;
        personality.taskTarget = player.getBlockPos().add(rnd(100) - 50, 0, rnd(100) - 50);
        personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
