package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第三阶段：钻石时代 (V3)
 */
public final class PhaseDiamondAge {

    private PhaseDiamondAge() {}

    /** 识别是否为钻石矿 (V5.5) */
    public static boolean isDiamondOre(BlockState state) {
        return state != null && (state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE));
    }

    /** 标记钻石挖掘成功 (V5.17: 仅记录叙事性字段，phase 推进交给 detectPhase 自然完成) */
    public static void markDiamondOreMined(ServerPlayerEntity player, Personality personality) {
        if (player == null || personality == null) return;

        // 记录"曾经亲手挖到钻石矿"的事实，用于后续 chat 叙事或自定义触发
        // 不再用于成就门禁（贴合 vanilla：只看 inventory，不看 source）
        personality.hasMinedDiamondOre = true;
        personality.lastDiamondOreMinedAt = System.currentTimeMillis();

        // phase 推进由 detectPhase 在下次任务分配时根据背包状态自动完成
        // （挖到钻石矿后，钻石进入背包，detectPhase 自然识别为 DIAMOND_AGE）
    }

    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findOre,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findLog,
                                   java.util.function.Supplier<net.minecraft.entity.mob.HostileEntity> findHunt) {
        // V5.17: 材料齐全时优先建造/寻找下界传送门，打破 DIAMOND_AGE → NETHER 的鸡蛋死锁
        // 黑曜石 ≥ 10 + 打火石 → 调用 PhaseNether 的传送门逻辑
        if (PhaseNether.hasMaterialsForPortal(player)) {
            if (PhaseNether.tryFindOrBuildPortal(player, personality)) {
                return;
            }
        }

        // 钻石层挖矿：Y=-50 ~ -60
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 50) {
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
