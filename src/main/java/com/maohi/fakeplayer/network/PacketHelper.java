package com.maohi.fakeplayer.network;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全链路发包工具类 (V3.3 真实链路)
 *
 * 核心原则：先发包（让反作弊看到完整包序列），再调方法（确保服务端逻辑执行）。
 * 这样既骗过包检测，又骗过服务端内部校验。
 *
 * 所有假人的"真实操作"都通过此类发包，确保：
 * 1. 包格式和时序与真实客户端一致
 * 2. 反作弊插件能看到完整的 C2S 包记录
 * 3. 服务端方法正常执行，结果正确
 *
 * V3.5: 屏蔽 Heightmap.Type 枚举的 @Deprecated 警告（过渡期）
 */
@SuppressWarnings("deprecation")
public class PacketHelper {

    /** 自增 sequence 计数器（1.21.11 要求每个操作包带 sequence） */
    private static final AtomicInteger sequenceCounter = new AtomicInteger(0);

    /** 获取下一个 sequence 值 */
    public static int nextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    // ==================== 1. 攻击实体 ====================

    /**
     * 真实攻击实体：发包 + 调方法 双保险
     *
     * 1. 发 PlayerInteractEntityC2SPacket.attack() — 反作弊能看到攻击包
     * 2. 调 player.attack() — 确保服务端逻辑执行（扣血、掉落、经验）
     * 3. 发 HandSwingC2SPacket — 反作弊能看到挥手动画包
     * 4. 重置攻击冷却 — 模拟真人的攻击节奏
     *
     * 服务端会自动处理：怪物死亡 → 掉落物 + 经验球 → 不需要手动加
     */
    public static void attackEntity(ServerPlayerEntity player, Entity target) {
        if (player == null || target == null || !target.isAlive()) return;

        // 1. 发攻击包（让反作弊看到）
        PlayerInteractEntityC2SPacket attackPacket = PlayerInteractEntityC2SPacket.attack(target, player.isSneaking());
        player.networkHandler.onPlayerInteractEntity(attackPacket);

        // 2. 调服务端原生攻击方法（确保逻辑执行）
        player.attack(target);

        // 3. 发挥手包（真实客户端攻击时会发）
        player.networkHandler.onHandSwing(new HandSwingC2SPacket(Hand.MAIN_HAND));

        // 4. 重置攻击冷却（模拟真人的攻击节奏）
        player.lastAttackedTime = 0;

        // 5. 挥手动画（让周围玩家看到）
        player.swingHand(Hand.MAIN_HAND, true);
    }

    // ==================== 2. 挖掘方块 ====================

    /**
     * 开始挖掘方块：发包通知服务端
     *
     * 服务端会自动：
     * - 记录挖掘状态到 ServerPlayerInteractionManager
     * - 开始计算挖掘进度
     * - 自动广播裂纹包给周围玩家
     */
    public static void startDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);

        // 同步调 gameMode 方法（双保险）
        player.interactionManager.processBlockBreakingAction(
            pos,
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            direction != null ? direction : Direction.NORTH,
            player.getEntityWorld().getBottomY() + player.getEntityWorld().getHeight() - 1,
            sequence
        );

        // 发挥手包（开始挖掘时客户端会发）
        player.networkHandler.onHandSwing(new HandSwingC2SPacket(Hand.MAIN_HAND));
        player.swingHand(Hand.MAIN_HAND, true);
    }

    /**
     * 取消挖掘方块
     */
    public static void abortDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);

        player.interactionManager.processBlockBreakingAction(
            pos,
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            direction != null ? direction : Direction.NORTH,
            player.getEntityWorld().getBottomY() + player.getEntityWorld().getHeight() - 1,
            sequence
        );
    }

    /**
     * 完成挖掘方块：发包 + 调方法
     *
     * 服务端会自动处理：
     * - 破坏方块
     * - 按原版掉落表生成掉落物
     * - 按原版经验表生成经验球
     * - 广播方块破坏效果
     * → 不需要手动 insertStack / addExperience
     */
    public static void finishDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);

        player.interactionManager.processBlockBreakingAction(
            pos,
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            direction != null ? direction : Direction.NORTH,
            player.getEntityWorld().getBottomY() + player.getEntityWorld().getHeight() - 1,
            sequence
        );
    }

    // ==================== 3. 使用物品 / 方块交互 ====================

    /**
     * 右键使用物品（吃东西、喝药水、拉弓等）
     *
     * 真实客户端流程：
     * 1. 发 PlayerInteractBlockC2SPacket（空命中=使用物品）
     * 2. 服务端开始使用物品（设置 isUsingItem）
     * 3. 使用完成后发 RELEASE_USE_ITEM
     */
    public static void useItem(ServerPlayerEntity player, Hand hand) {
        if (player == null) return;

        int sequence = nextSequence();
        // 空命中 = 使用物品（吃东西、拉弓等）
        BlockHitResult emptyHit = new BlockHitResult(
            Vec3d.ofCenter(player.getBlockPos()),
            Direction.UP,
            player.getBlockPos(),
            false
        );
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(hand, emptyHit, sequence);
        player.networkHandler.onPlayerInteractBlock(packet);
    }

    /**
     * 释放使用中的物品（停止吃/停止拉弓/射箭）
     */
    public static void releaseUseItem(ServerPlayerEntity player) {
        if (player == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
            BlockPos.ORIGIN,
            Direction.DOWN,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);

        // 服务端处理释放
        player.interactionManager.processBlockBreakingAction(
            BlockPos.ORIGIN,
            PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
            Direction.DOWN,
            player.getEntityWorld().getBottomY() + player.getEntityWorld().getHeight() - 1,
            sequence
        );

        // 也调原版释放方法（双保险）
        player.stopUsingItem();
    }

    /**
     * 右键交互方块（开门、用床、开箱子等）
     */
    public static void interactBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        if (player == null || hitResult == null) return;

        int sequence = nextSequence();
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(hand, hitResult, sequence);
        player.networkHandler.onPlayerInteractBlock(packet);

        // 同步调 gameMode 方法
        player.interactionManager.interactBlock(
            player,
            player.getEntityWorld(),
            player.getStackInHand(hand),
            hand,
            hitResult
        );
    }

    // ==================== 4. 快捷栏切换 ====================

    /**
     * 切换快捷栏槽位
     * 真实客户端切换槽位时会发 UpdateSelectedSlotC2SPacket
     */
    public static void setSelectedSlot(ServerPlayerEntity player, int slot) {
        if (player == null || slot < 0 || slot > 8) return;

        // 1. 发包（让反作弊看到）
        UpdateSelectedSlotC2SPacket packet = new UpdateSelectedSlotC2SPacket(slot);
        player.networkHandler.onUpdateSelectedSlot(packet);

        // 2. 同步设置（确保服务端状态一致）
        player.getInventory().setSelectedSlot(slot);
    }

    // ==================== 5. 挥手动画 ====================

    /**
     * 发挥手包（攻击/挖掘时客户端都会发）
     */
    public static void swingHand(ServerPlayerEntity player, Hand hand) {
        if (player == null) return;

        player.networkHandler.onHandSwing(new HandSwingC2SPacket(hand));
        player.swingHand(hand, true);
    }
}
