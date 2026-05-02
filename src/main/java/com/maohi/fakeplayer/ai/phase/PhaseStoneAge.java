package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第一阶段：石器时代 (V3)
 *
 * 目标：活过第一个夜晚，建立基础生存保障
 * 进入条件：背包无铁器及以上装备
 * 毕业条件：背包拥有铁镐或铁剑
 *
 * 任务优先级：
 *   1. 没有镐子且木头不足 → 砍树
 *   2. 没有石器且圆石不足 → 挖石头（地表）
 *   3. 夜晚且没有剑 → 打猎
 *   4. 默认 → 砍树60% / 挖石头40%
 *
 * 待完善：
 *   - 找羊制作床（跳过夜晚）
 *   - 制作熔炉烤肉
 *   - 搭建简易庇护所
 */
public final class PhaseStoneAge {

    private PhaseStoneAge() {}

    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.BiFunction<net.minecraft.server.world.ServerWorld, BlockPos, BlockPos> findBlock) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        boolean hasPickaxe = false, hasStoneTools = false, hasSword = false;
        int woodCount = 0, cobbleCount = 0;
        for (int i = 0; i < inv.size(); i++) {
            String id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem()).getPath();
            if (id.contains("pickaxe")) hasPickaxe = true;
            if (id.startsWith("stone_")) hasStoneTools = true;
            if (id.contains("sword")) hasSword = true;
            if (id.contains("log") || id.contains("planks")) woodCount += inv.getStack(i).getCount();
            if (id.equals("cobblestone") || id.equals("cobbled_deepslate")) cobbleCount += inv.getStack(i).getCount();
        }

        // 1. 没有镐子且木头不足 → 砍树
        if (!hasPickaxe && woodCount < 10) {
            BlockPos target = findBlock.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = player.getBlockPos().add(rnd(40) - 20, 0, rnd(40) - 20);
            set(personality, TaskType.WOODCUTTING, target);
            return;
        }

        // 2. 没有石器且圆石不足 → 挖石头
        if (!hasStoneTools && cobbleCount < 15) {
            BlockPos target = findBlock.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = player.getBlockPos().down(2);
            set(personality, TaskType.MINING, target);
            return;
        }

        // 3. 夜晚且没有剑 → 打猎
        if (player.getEntityWorld().isNight() && !hasSword) {
            set(personality, TaskType.HUNTING, null);
            return;
        }

        // 4. 默认：砍树60% / 挖石头40%
        if (ThreadLocalRandom.current().nextInt(100) < 60) {
            BlockPos target = findBlock.apply(player.getEntityWorld(), player.getBlockPos());
            if (target == null) target = player.getBlockPos().add(rnd(40) - 20, 0, rnd(40) - 20);
            set(personality, TaskType.WOODCUTTING, target);
        } else {
            BlockPos target = player.getBlockPos().down(2);
            set(personality, TaskType.MINING, target);
        }
    }

    private static void set(Personality p, TaskType type, BlockPos target) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_WORK;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
