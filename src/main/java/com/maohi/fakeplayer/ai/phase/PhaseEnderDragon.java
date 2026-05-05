package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.ai.MovementController;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第五阶段：末影龙 (V3.2)
 *
 * 目标：进入末地，挑战并击败末影龙
 * 进入条件：背包有下界合金装备
 * 毕业条件：末影龙被击败（服务器广播）
 *
 * 任务优先级：
 *   1. 在主世界 → 寻找要塞（使用末影之眼）
 *   2. 在要塞附近 → 寻找末地传送门并激活
 *   3. 在末地 → 摧毁末地水晶
 *   4. 在末地 → 攻击末影龙
 *   5. 末影龙击败 → 返回主世界
 *
 * 1.21.11 适配要点：
 *   - 使用 RegistryKey 判断维度
 *   - 末影之眼使用 Items.ENDER_EYE
 *   - 末地传送门框架使用 Blocks.END_PORTAL_FRAME
 *   - 末影龙实体使用 EnderDragonEntity
 */
public final class PhaseEnderDragon {

    private PhaseEnderDragon() {}

    /**
     * 分配末影龙阶段任务
     */
    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.Supplier<HostileEntity> findHunt) {
        ServerWorld world = player.getEntityWorld();
        boolean isEnd = world.getRegistryKey() == World.END;
        boolean isOverworld = world.getRegistryKey() == World.OVERWORLD;

        // 1. 在末地 → 战斗逻辑
        if (isEnd) {
            assignEndFightTask(player, personality);
            return;
        }

        // 2. 在主世界 → 寻找要塞
        if (isOverworld) {
            // 检查是否有末影之眼
            if (hasEnderEyes(player)) {
                // 尝试寻找/前往要塞
                if (tryFindStronghold(player, personality)) {
                    return;
                }
            } else {
                // 没有末影之眼，需要先去下界打烈焰人获取烈焰棒
                // 这里回退到探索任务，让 PhaseNether 处理
                set(personality, TaskType.EXPLORING,
                    player.getBlockPos().add(rnd(100) - 50, 0, rnd(100) - 50),
                    TimingConstants.TASK_TIMEOUT_EXPLORE);
                return;
            }
        }

        // 3. 在其他维度或 fallback → 探索
        set(personality, TaskType.EXPLORING,
            player.getBlockPos().add(rnd(100) - 50, 0, rnd(100) - 50),
            TimingConstants.TASK_TIMEOUT_EXPLORE);
    }

    /**
     * 分配末地战斗任务
     */
    private static void assignEndFightTask(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();

        // 1. 寻找末影龙
        EnderDragonEntity dragon = findEnderDragon(world);
        if (dragon == null) {
            // 末影龙可能已被击败，返回主世界
            findAndUseExitPortal(player, personality);
            return;
        }

        // 2. 寻找并摧毁末地水晶
        BlockPos crystalPos = findEndCrystal(world, player.getBlockPos());
        if (crystalPos != null) {
            double distSq = player.squaredDistanceTo(
                crystalPos.getX() + 0.5, crystalPos.getY(), crystalPos.getZ() + 0.5);
            if (distSq > 25.0) {
                // 走向水晶
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = crystalPos;
                personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
            } else {
                // 攻击水晶（用弓箭或近战）
                attackEndCrystal(player, crystalPos);
            }
            return;
        }

        // 3. 攻击末影龙
        double dragonDistSq = player.squaredDistanceTo(dragon);
        if (dragonDistSq > 64.0) {
            // 靠近末影龙
            personality.currentTask = TaskType.EXPLORING;
            personality.taskTarget = dragon.getBlockPos();
            personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
        } else {
            // 攻击末影龙
            attackEnderDragon(player, dragon);
        }
    }

    /**
     * 尝试寻找要塞
     * @return true 如果成功开始寻找要塞
     */
    private static boolean tryFindStronghold(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();

        // 1. 检查是否已经在要塞附近
        if (isNearStronghold(world, playerPos)) {
            // 寻找末地传送门
            BlockPos portalFrame = findEndPortalFrame(world, playerPos);
            if (portalFrame != null) {
                double distSq = player.squaredDistanceTo(
                    portalFrame.getX() + 0.5, portalFrame.getY(), portalFrame.getZ() + 0.5);
                if (distSq > 4.0) {
                    // 走向传送门
                    personality.currentTask = TaskType.EXPLORING;
                    personality.taskTarget = portalFrame;
                    personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
                } else {
                    // 激活传送门
                    activateEndPortal(player, portalFrame);
                }
                return true;
            }
        }

        // 2. 使用末影之眼寻找要塞方向
        // 简化：随机选择一个方向走一段距离，然后投掷末影之眼
        if (ThreadLocalRandom.current().nextInt(100) < 30) {
            // 投掷末影之眼
            throwEnderEye(player);
        }

        // 向随机方向探索
        set(personality, TaskType.EXPLORING,
            playerPos.add(rnd(200) - 100, 0, rnd(200) - 100),
            TimingConstants.TASK_TIMEOUT_EXPLORE);
        return true;
    }

    /**
     * 检查是否在要塞附近
     * 通过检测是否有末地传送门框架方块
     */
    private static boolean isNearStronghold(ServerWorld world, BlockPos center) {
        for (int dx = -32; dx <= 32; dx += 4) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -32; dz <= 32; dz += 4) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).isOf(Blocks.END_PORTAL_FRAME)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 寻找末地传送门框架
     */
    private static BlockPos findEndPortalFrame(ServerWorld world, BlockPos center) {
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dy = -12; dy <= 12; dy++) {
                for (int dz = -24; dz <= 24; dz += 2) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.END_PORTAL_FRAME)) {
                        // 找到未激活的框架
                        if (!state.get(net.minecraft.block.EndPortalFrameBlock.EYE)) {
                            return pos;
                        }
                        // 或者返回已激活的传送门位置
                        if (world.getBlockState(pos.up()).getBlock() instanceof EndPortalBlock) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 激活末地传送门
     * 在空的框架上放置末影之眼
     */
    private static void activateEndPortal(ServerPlayerEntity player, BlockPos framePos) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 找末影之眼槽位
        int eyeSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_EYE)) {
                eyeSlot = i;
                break;
            }
        }

        if (eyeSlot == -1) return;

        // 切换到末影之眼
        PacketHelper.setSelectedSlot(player, eyeSlot);

        // 面向框架
        Vec3d frameCenter = Vec3d.ofCenter(framePos);
        double dx = frameCenter.x - player.getX();
        double dz = frameCenter.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        player.setYaw(targetYaw);

        // 交互放置末影之眼
        BlockHitResult hitResult = new BlockHitResult(
            frameCenter,
            Direction.UP,
            framePos,
            false
        );
        PacketHelper.interactBlock(player, Hand.MAIN_HAND, hitResult);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    /**
     * 投掷末影之眼
     */
    private static void throwEnderEye(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 找末影之眼槽位
        int eyeSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_EYE)) {
                eyeSlot = i;
                break;
            }
        }

        if (eyeSlot == -1) return;

        // 切换到末影之眼并使用
        PacketHelper.setSelectedSlot(player, eyeSlot);
        PacketHelper.useItem(player, Hand.MAIN_HAND);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    /**
     * 寻找并攻击末地水晶
     */
    private static BlockPos findEndCrystal(ServerWorld world, BlockPos center) {
        // 末地水晶通常在黑曜石柱顶部 (Y=80~100)
        for (int dx = -64; dx <= 64; dx += 4) {
            for (int dz = -64; dz <= 64; dz += 4) {
                for (int dy = 60; dy <= 120; dy += 5) {
                    BlockPos pos = center.add(dx, dy - center.getY(), dz);
                    // 检查是否有末地水晶实体在附近
                    var entities = world.getOtherEntities(null,
                        new net.minecraft.util.math.Box(pos.getX() - 2, pos.getY() - 2, pos.getZ() - 2,
                                                        pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2));
                    for (var entity : entities) {
                        if (entity instanceof net.minecraft.entity.decoration.EndCrystalEntity) {
                            return entity.getBlockPos();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 攻击末地水晶
     */
    private static void attackEndCrystal(ServerPlayerEntity player, BlockPos crystalPos) {
        // 面向水晶
        Vec3d crystalCenter = Vec3d.ofCenter(crystalPos).add(0, 1, 0);
        double dx = crystalCenter.x - player.getX();
        double dz = crystalCenter.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        player.setYaw(targetYaw);

        // 尝试用弓箭射击
        if (tryUseBow(player)) {
            return;
        }

        // 靠近并近战攻击
        double distSq = player.squaredDistanceTo(crystalCenter);
        if (distSq > 9.0) {
            MovementController.doSmartMove(player, crystalPos, 1.0,
                ThreadLocalRandom.current().nextDouble() * 1000,
                ThreadLocalRandom.current().nextDouble() * 1000);
        } else {
            // 近战攻击水晶
            var entities = player.getEntityWorld().getOtherEntities(player,
                new net.minecraft.util.math.Box(crystalPos.getX() - 1, crystalPos.getY() - 1, crystalPos.getZ() - 1,
                                                crystalPos.getX() + 1, crystalPos.getY() + 3, crystalPos.getZ() + 1));
            for (var entity : entities) {
                if (entity instanceof net.minecraft.entity.decoration.EndCrystalEntity) {
                    PacketHelper.attackEntity(player, entity);
                    break;
                }
            }
        }
    }

    /**
     * 寻找末影龙
     */
    private static EnderDragonEntity findEnderDragon(ServerWorld world) {
        var entities = world.getEntitiesByClass(EnderDragonEntity.class,
            new net.minecraft.util.math.Box(-300, 0, -300, 300, 256, 300),
            entity -> entity.isAlive());
        return entities.isEmpty() ? null : entities.get(0);
    }

    /**
     * 攻击末影龙
     */
    private static void attackEnderDragon(ServerPlayerEntity player, EnderDragonEntity dragon) {
        // 面向末影龙
        Vec3d dragonPos = dragon.getEyePos();
        double dx = dragonPos.x - player.getX();
        double dz = dragonPos.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        player.setYaw(targetYaw);

        // 尝试用弓箭
        if (tryUseBow(player)) {
            return;
        }

        // 靠近并近战
        double distSq = player.squaredDistanceTo(dragon);
        if (distSq > 16.0) {
            MovementController.doSmartMove(player, dragon.getBlockPos(), 1.0,
                ThreadLocalRandom.current().nextDouble() * 1000,
                ThreadLocalRandom.current().nextDouble() * 1000);
        } else {
            // 攻击末影龙
            PacketHelper.attackEntity(player, dragon);
        }
    }

    /**
     * 尝试使用弓箭
     */
    private static boolean tryUseBow(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        int bowSlot = -1;
        boolean hasArrows = false;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (bowSlot == -1 && stack.isOf(Items.BOW)) {
                bowSlot = i;
            }
            if (!hasArrows && (stack.isOf(Items.ARROW) || stack.isOf(Items.SPECTRAL_ARROW))) {
                hasArrows = true;
            }
        }

        if (bowSlot == -1 || !hasArrows) return false;

        // 切换到弓并拉弓
        PacketHelper.setSelectedSlot(player, bowSlot);
        PacketHelper.useItem(player, Hand.MAIN_HAND);
        // 拉弓 1 秒后释放（在 tick 中处理）
        return true;
    }

    /**
     * 寻找并使用返回传送门
     */
    private static void findAndUseExitPortal(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();

        // 寻找返回传送门（基岩框架中心）
        for (int dx = -64; dx <= 64; dx += 4) {
            for (int dz = -64; dz <= 64; dz += 4) {
                BlockPos pos = playerPos.add(dx, 0, dz);
                // 返回传送门在 (0, 64, 0) 附近，由基岩组成
                if (world.getBlockState(pos).isOf(Blocks.BEDROCK)) {
                    // 检查下方是否有传送门方块
                    if (world.getBlockState(pos.down()).getBlock() instanceof EndPortalBlock) {
                        double distSq = player.squaredDistanceTo(
                            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        if (distSq > 4.0) {
                            personality.currentTask = TaskType.EXPLORING;
                            personality.taskTarget = pos;
                            personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
                        } else {
                            // 跳进传送门
                            player.setPosition(pos.getX() + 0.5, pos.getY() - 0.5, pos.getZ() + 0.5);
                        }
                        return;
                    }
                }
            }
        }

        // 找不到传送门，向 (0, 64, 0) 走
        personality.currentTask = TaskType.EXPLORING;
        personality.taskTarget = new BlockPos(0, 64, 0);
        personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
    }

    /**
     * 检查是否有末影之眼
     */
    private static boolean hasEnderEyes(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    private static void set(Personality p, TaskType type, BlockPos target, long timeout) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + timeout;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
