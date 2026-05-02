package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第四阶段：下界远征 (V3)
 *
 * 目标：在下界收集资源，获取下界合金，为挑战末影龙做准备
 * 进入条件：背包有钻石装备
 * 毕业条件：背包拥有下界合金装备
 *
 * 任务优先级（待完善）：
 *   1. 在下界挖矿（古代残骸 → 下界合金）
 *   2. 击杀下界生物（猪灵、烈焰人）
 *   3. 收集下界资源（石英、玄武岩）
 *
 * 待完善：
 *   - 进入下界传送门
 *   - 寻找古代残骸（Y=15 附近）
 *   - 与猪灵交易
 *   - 击杀烈焰人获取烈焰棒（末影之眼材料）
 *   - 寻找下界要塞
 */
public final class PhaseNether {

    private PhaseNether() {}

    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findOre,
                                   java.util.function.Supplier<net.minecraft.entity.mob.HostileEntity> findHunt) {
        // TODO: 下界专属逻辑（传送门、古代残骸、猪灵交易）
        // 当前占位：挖矿+打猎
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 60) {
            BlockPos target = findOre.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = new BlockPos(player.getBlockX() + rnd(10) - 5, 15, player.getBlockZ() + rnd(10) - 5);
            set(personality, TaskType.MINING, target, TimingConstants.TASK_TIMEOUT_WORK);
        } else if (roll < 85) {
            net.minecraft.entity.mob.HostileEntity huntTarget = findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
                return;
            }
            set(personality, TaskType.EXPLORING, player.getBlockPos().add(rnd(60) - 30, 0, rnd(60) - 30), TimingConstants.TASK_TIMEOUT_EXPLORE);
        } else {
            set(personality, TaskType.EXPLORING, player.getBlockPos().add(rnd(60) - 30, 0, rnd(60) - 30), TimingConstants.TASK_TIMEOUT_EXPLORE);
        }
    }

    private static void set(Personality p, TaskType type, BlockPos target, long timeout) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + timeout;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
