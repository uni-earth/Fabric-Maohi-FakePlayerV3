package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 第零阶段：木器时代 (V5.44 新增)
 *
 * 进入条件：背包无任何镐子(默认起点 — 新生 bot 空背包出生)
 * 毕业条件：合出木镐(任何 *_pickaxe) → detectPhase 棘轮升 STONE_AGE → 下 tick 转 PhaseStoneAge
 *
 * V5.117: 通用 setter / Digest / 常量 / assignChopTree 等全部走 PhaseUtil 共享入口,
 *   本类仅保留 WOOD_AGE 自身的状态机(classify + 2 sub-phase 决策)。
 *
 * ======================= 文件分工契约(V5.117) =======================
 * - 本文件仅含: classify() / assignTask() / SubPhase 枚举
 * - 不放: setter / Digest / 通用 helper(→ PhaseUtil)
 * - 不放: 决策树 / 大段 if-else 嵌套(单方法 > 80 行 → 拆 helper 或迁别处)
 * - 不放: 与其它 age 共享的常量(→ PhaseUtil 常量区)
 * - 跨阶段共享代码必须先抽到 PhaseUtil，否则视为代码堆积。
 * =====================================================================
 */
public final class PhaseWoodAge implements Phase {

    public static final Phase INSTANCE = new PhaseWoodAge();

    private PhaseWoodAge() {}

    /**
     * V5.44 WOOD_AGE 内部子状态。
     */
    public enum SubPhase {
        WOOD_START,
        WOOD_CRAFT
    }

    private static SubPhase classify(PhaseUtil.Digest d) {
        if (d.hasTable || d.logEquivalent() >= PhaseUtil.WOOD_LOGS_TARGET) {
            return SubPhase.WOOD_CRAFT;
        }
        return SubPhase.WOOD_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        PhaseUtil.Digest d = PhaseUtil.scan(player);

        if (d.hasAnyPickaxe) {
            PhaseStoneAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        SubPhase sub = classify(d);

        com.maohi.fakeplayer.TaskLogger.log(player, "wood_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "hasTable", d.hasTable);

        switch (sub) {
            case WOOD_START -> PhaseUtil.assignChopTree(player, personality, ctx);

            case WOOD_CRAFT -> {
                if (d.plankCount >= 3 && d.stickCount >= 2) {
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, PhaseUtil.WORKBENCH_RETURN_RADIUS);
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= PhaseUtil.WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        PhaseUtil.setIdle(personality, player, 100);
                        return;
                    } else if (workbench != null) {
                        PhaseUtil.set(personality, player, TaskType.EXPLORING, workbench);
                        return;
                    }
                }

                if (d.logEquivalent() < PhaseUtil.WOOD_LOGS_TARGET) {
                    PhaseUtil.assignChopTree(player, personality, ctx);
                } else {
                    if (player.getEntityWorld().getTime() < personality.tablePlaceRetryCooldownUntil) {
                        PhaseUtil.setExplore(personality, player);
                        return;
                    }
                    PhaseUtil.setIdle(personality, player, 100);
                }
            }
        }
    }
}
