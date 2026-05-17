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
 * 设计:
 *   原 PhaseStoneAge.SubPhase.WOOD_START / WOOD_CRAFT 两档迁出,专档独立。
 *   两个子状态:
 *     WOOD_START : 没原木/木板/木棍/工作台 → 砍树
 *     WOOD_CRAFT : 有原木 或 已经放过工作台 → 推 craft 链(CraftingBehavior.autoCraftStoneTools 接手)
 *
 * 复用策略(零复制):
 *   - 共享背包扫描 PhaseStoneAge.Digest + scan()
 *   - 共享 helper:assignChopTree / set / setIdle / setExplore
 *   - 共享常量:WOOD_LOGS_TARGET / WORKBENCH_RETURN_RADIUS / WORKBENCH_NEARBY_SQ
 *   PhaseStoneAge 在 V5.44 已经把这些改成 package-private 供本类访问。
 *
 * 不复制原因:
 *   setExplore 200+ LOC 涉及 RegionMemoryMap/SharedResourceMap/ExecutionLayer,
 *   两个 Phase 复制一份会立刻漂移;改 pkg-private 复用是唯一干净路径。
 */
public final class PhaseWoodAge implements Phase {

    public static final Phase INSTANCE = new PhaseWoodAge();

    private PhaseWoodAge() {}

    /**
     * V5.44 WOOD_AGE 内部子状态。public 让 TaskLogger / debug 工具可查询。
     * 仅 2 档,不会再细分。
     */
    public enum SubPhase {
        WOOD_START,   // 没原木/木板/木棍 → 砍树
        WOOD_CRAFT    // 有原木 或 已有工作台 → 推 craft 链
    }

    /**
     * V5.42.5 严重修复(从 PhaseStoneAge 迁移): 如果已经有工作台了(d.hasTable),
     * 哪怕木头用光了也要留在 WOOD_CRAFT 寻找木头做镐子,
     * 不要退回 WOOD_START 导致重新去砍树/找树, 这样才能保住工作台不丢。
     */
    private static SubPhase classify(PhaseStoneAge.Digest d) {
        if (d.hasTable || d.logEquivalent() >= PhaseStoneAge.WOOD_LOGS_TARGET) {
            return SubPhase.WOOD_CRAFT;
        }
        return SubPhase.WOOD_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        PhaseStoneAge.Digest d = PhaseStoneAge.scan(player);

        // 防御: WOOD_AGE bot 不应该有任何镐(有镐就该是 STONE_AGE+,由 detectPhase 棘轮处理)。
        //   极端情况若到这里(detectPhase 还没跑),直接委托 PhaseStoneAge.INSTANCE 处理。
        if (d.hasAnyPickaxe) {
            PhaseStoneAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        SubPhase sub = classify(d);

        com.maohi.fakeplayer.TaskLogger.log(player, "wood_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "hasTable", d.hasTable);

        switch (sub) {
            case WOOD_START -> PhaseStoneAge.assignChopTree(player, personality, ctx);

            case WOOD_CRAFT -> {
                // V5.42 死锁 #1b(从 PhaseStoneAge 迁移):wooden_pickaxe 需要工作台 (3×3 配方)。
                //   bot 合完 crafting_table 后 plank 消耗,reassign 把 bot 派去远处砍树,
                //   走出工作台 6 格 → autoCraftStoneTools 的 wooden_pickaxe 分支 (要求 workbenchNearby)
                //   永远不命中 → 即使 plank=6 stick=6 也合不出木镐 → 永卡 WOOD_CRAFT。
                //   修复:有合木镐原料 (plank≥3 + stick≥2) 时,优先走回工作台。
                if (d.plankCount >= 3 && d.stickCount >= 2) {
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, PhaseStoneAge.WORKBENCH_RETURN_RADIUS);
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= PhaseStoneAge.WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        // 工作台 6 格内 → IDLE 等 autoCraftStoneTools 推 wooden_pickaxe
                        PhaseStoneAge.setIdle(personality, player, 100);
                        return;
                    } else if (workbench != null) {
                        // 工作台 6~32 格外 → 走回去
                        PhaseStoneAge.set(personality, player, TaskType.EXPLORING, workbench);
                        return;
                    }
                    // workbench == null:32 格内没有自己放过的工作台。
                    //   背包若有 plank≥4 → autoCraftStoneTools 会触发新工作台合成 (plank≥4 + !hasTable + !workbenchNearby);
                    //   若 plank=3(刚够 wooden_pickaxe 但不够新表),fall through 到下面砍树补料。
                }

                // 默认链路:CraftingBehavior 在 VPM tickSurvivalAndProgression 每 tick 调用,
                // 会自动按 plank → table → stick 顺序推链(全在背包),这里只需保证原料够。
                if (d.logEquivalent() < PhaseStoneAge.WOOD_LOGS_TARGET) {
                    PhaseStoneAge.assignChopTree(player, personality, ctx);
                } else {
                    // 原料齐了,IDLE 5s 等 craft 触发(下个 100-tick reassign 重新评估)
                    // V5.43.6 P-2: 如果放置一直失败(处于冷却中), 说明脚下不适合放表, 强迫 EXPLORE 换地方
                    if (player.getEntityWorld().getTime() < personality.tablePlaceRetryCooldownUntil) {
                        PhaseStoneAge.setExplore(personality, player);
                        return;
                    }
                    PhaseStoneAge.setIdle(personality, player, 100);
                }
            }
        }
    }
}
