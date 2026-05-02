package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第三阶段：钻石时代 (V3)
 *
 * 目标：获取钻石，制作钻石装备，准备进入下界
 * 进入条件：背包有铁器（铁镐/铁剑/铁斧）
 * 毕业条件：背包拥有下界合金装备，或已进入下界
 *
 * 任务优先级（待完善）：
 *   1. 深层挖矿（Y=-58 钻石层）
 *   2. 打猎/战斗提升经验
 *   3. 探索寻找要塞/村庄
 *
 * 待完善：
 *   - 深层挖矿（Y=-58 附近）
 *   - 制作附魔台
 *   - 寻找要塞获取末影之眼
 *   - 制作下界传送门材料（黑曜石）
 */
public final class PhaseDiamondAge {

    private PhaseDiamondAge() {}

    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findOre,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findLog,
                                   java.util.function.Supplier<net.minecraft.entity.mob.HostileEntity> findHunt) {
        // TODO: 深层挖矿（Y=-58 钻石层）
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 50) {
            // 钻石层挖矿：Y=-50 ~ -60
            int mineY = -50 - ThreadLocalRandom.current().nextInt(10);
            BlockPos target = findOre.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = new BlockPos(player.getBlockX() + rnd(10) - 5, mineY, player.getBlockZ() + rnd(10) - 5);
            set(personality, TaskType.MINING, target, TimingConstants.TASK_TIMEOUT_WORK);
        } else if (roll < 70) {
            BlockPos target = findLog.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = player.getBlockPos().add(rnd(60) - 30, 0, rnd(60) - 30);
            set(personality, TaskType.WOODCUTTING, target, TimingConstants.TASK_TIMEOUT_WORK);
        } else if (roll < 85) {
            net.minecraft.entity.mob.HostileEntity huntTarget = findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
                return;
            }
            set(personality, TaskType.EXPLORING, player.getBlockPos().add(rnd(80) - 40, 0, rnd(80) - 40), TimingConstants.TASK_TIMEOUT_EXPLORE);
        } else {
            set(personality, TaskType.EXPLORING, player.getBlockPos().add(rnd(80) - 40, 0, rnd(80) - 40), TimingConstants.TASK_TIMEOUT_EXPLORE);
        }
    }

    private static void set(Personality p, TaskType type, BlockPos target, long timeout) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + timeout;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
