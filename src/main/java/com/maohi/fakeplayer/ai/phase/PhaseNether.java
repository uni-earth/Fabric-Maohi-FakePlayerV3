package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.VirtualPlayerManager.Personality;
import com.maohi.fakeplayer.VirtualPlayerManager.TaskType;
import com.maohi.fakeplayer.ai.BlockPlacer;
import com.maohi.fakeplayer.ai.MovementController;
import com.maohi.fakeplayer.ai.PathfindingNavigation;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
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
 * 第四阶段：下界远征 (V3.2)
 *
 * 目标：在下界收集资源，获取下界合金，为挑战末影龙做准备
 * 进入条件：背包有钻石装备
 * 毕业条件：背包拥有下界合金装备
 *
 * 任务优先级：
 *   1. 在主世界且未在下界 → 寻找/建造传送门进入下界
 *   2. 在下界 → 古代残骸挖掘 (Y=15 附近) 40%
 *   3. 在下界 → 击杀烈焰人/猪灵 30%
 *   4. 在下界 → 探索收集石英/黑曜石 20%
 *   5. 在下界 → 寻找下界要塞 10%
 *
 * 1.21.11 适配要点：
 *   - 使用 RegistryKey 判断维度
 *   - 传送门交互走 PlayerInteractBlockC2SPacket 真实发包
 *   - 古代残骸使用 Blocks.ANCIENT_DEBRIS 检测
 */
public final class PhaseNether {

    private PhaseNether() {}

    /**
     * 分配下界阶段任务
     */
    public static void assignTask(ServerPlayerEntity player, Personality personality,
                                   java.util.function.BiFunction<ServerWorld, BlockPos, BlockPos> findOre,
                                   java.util.function.Supplier<HostileEntity> findHunt) {
        ServerWorld world = player.getEntityWorld();
        boolean isNether = world.getRegistryKey() == World.NETHER;

        // 1. 如果还在主世界，优先找传送门进入下界
        if (!isNether) {
            if (tryFindOrBuildPortal(player, personality)) {
                return;
            }
            // 找不到传送门也造不了，先探索找黑曜石
            set(personality, TaskType.EXPLORING, 
                player.getBlockPos().add(rnd(80) - 40, 0, rnd(80) - 40), 
                TimingConstants.TASK_TIMEOUT_EXPLORE);
            return;
        }

        // 2. 已经在下界，按优先级分配任务
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 40) {
            // 古代残骸挖掘：Y=15 附近
            BlockPos target = findAncientDebris(world, player.getBlockPos());
            if (target == null) {
                target = new BlockPos(player.getBlockX() + rnd(20) - 10, 15, player.getBlockZ() + rnd(20) - 10);
            }
            set(personality, TaskType.MINING, target, TimingConstants.TASK_TIMEOUT_WORK);
        } else if (roll < 70) {
            // 击杀下界生物
            HostileEntity huntTarget = findNetherMob(world, player);
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = System.currentTimeMillis() + 45_000L;
                return;
            }
            //  fallback: 探索
            set(personality, TaskType.EXPLORING, 
                player.getBlockPos().add(rnd(60) - 30, 0, rnd(60) - 30), 
                TimingConstants.TASK_TIMEOUT_EXPLORE);
        } else if (roll < 90) {
            // 探索收集资源
            set(personality, TaskType.EXPLORING, 
                player.getBlockPos().add(rnd(80) - 40, 0, rnd(80) - 40), 
                TimingConstants.TASK_TIMEOUT_EXPLORE);
        } else {
            // 寻找下界要塞（通过探索间接实现）
            set(personality, TaskType.EXPLORING, 
                player.getBlockPos().add(rnd(120) - 60, 0, rnd(120) - 60), 
                TimingConstants.TASK_TIMEOUT_EXPLORE);
        }
    }

    /**
     * 尝试寻找或建造下界传送门
     * @return true 如果成功进入传送门流程
     */
    private static boolean tryFindOrBuildPortal(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();

        // 1. 搜索附近 32 格内的现有传送门
        BlockPos portalPos = findNearbyPortal(world, playerPos);
        if (portalPos != null) {
            // 走到传送门并激活
            double distSq = player.squaredDistanceTo(
                portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5);
            if (distSq > 4.0) {
                // 还没走到，继续走
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = portalPos;
                personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
                return true;
            }
            // 到了，尝试激活/进入传送门
            interactPortal(player, portalPos);
            return true;
        }

        // 2. 没有现成传送门，检查是否有黑曜石和打火石来造一个
        if (hasMaterialsForPortal(player)) {
            // 寻找合适的建造位置
            BlockPos buildPos = findPortalBuildSpot(world, playerPos);
            if (buildPos != null) {
                // 走到建造位置
                double distSq = player.squaredDistanceTo(
                    buildPos.getX() + 0.5, buildPos.getY(), buildPos.getZ() + 0.5);
                if (distSq > 4.0) {
                    personality.currentTask = TaskType.EXPLORING;
                    personality.taskTarget = buildPos;
                    personality.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
                    return true;
                }
                // 建造传送门（简化版：直接放置黑曜石框架+激活）
                buildPortal(player, buildPos);
                return true;
            }
        }

        return false;
    }

    /**
     * 搜索附近现有的下界传送门
     */
    private static BlockPos findNearbyPortal(ServerWorld world, BlockPos center) {
        for (int dx = -32; dx <= 32; dx += 2) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -32; dz <= 32; dz += 2) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.getBlock() instanceof NetherPortalBlock) {
                        // 返回传送门框架的底部中心
                        return findPortalBase(world, pos);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找到传送门的底部中心位置
     */
    private static BlockPos findPortalBase(ServerWorld world, BlockPos portalPos) {
        BlockPos down = portalPos;
        // 向下找到最底部的传送门方块
        while (world.getBlockState(down.down()).getBlock() instanceof NetherPortalBlock) {
            down = down.down();
        }
        // 找到框架的角落（黑曜石）
        BlockPos corner = down;
        while (world.getBlockState(corner.west()).getBlock() instanceof NetherPortalBlock) {
            corner = corner.west();
        }
        while (world.getBlockState(corner.north()).getBlock() instanceof NetherPortalBlock) {
            corner = corner.north();
        }
        // 返回框架中心（黑曜石位置）
        return corner.down();
    }

    /**
     * 与传送门交互（进入）
     * 走真实发包链路
     */
    private static void interactPortal(ServerPlayerEntity player, BlockPos portalPos) {
        // 面向传送门
        Vec3d portalCenter = Vec3d.ofCenter(portalPos);
        double dx = portalCenter.x - player.getX();
        double dz = portalCenter.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        player.setYaw(targetYaw);

        // 走到传送门中心
        MovementController.doSmartMove(player, portalPos, 1.0, 
            ThreadLocalRandom.current().nextDouble() * 1000,
            ThreadLocalRandom.current().nextDouble() * 1000);

        // 在传送门中站一会儿，让原版传送逻辑触发
        // MC 原版需要玩家在传送门方块中待 4 秒（80 tick）才会传送
        // 这里我们不手动调用 teleport，而是让原版机制处理
        player.forwardSpeed = 0.0f;
        player.sidewaysSpeed = 0.0f;
    }

    /**
     * 检查是否有建造传送门的材料
     * 需要：10+ 黑曜石 + 打火石
     */
    private static boolean hasMaterialsForPortal(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        int obsidianCount = 0;
        boolean hasFlintAndSteel = false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.OBSIDIAN)) {
                obsidianCount += stack.getCount();
            } else if (stack.isOf(Items.FLINT_AND_STEEL)) {
                hasFlintAndSteel = true;
            }
        }

        return obsidianCount >= 10 && hasFlintAndSteel;
    }

    /**
     * 寻找合适的传送门建造位置
     * 需要：平坦地面，4x5 空间
     */
    private static BlockPos findPortalBuildSpot(ServerWorld world, BlockPos center) {
        for (int dx = -20; dx <= 20; dx += 2) {
            for (int dz = -20; dz <= 20; dz += 2) {
                BlockPos base = center.add(dx, 0, dz);
                // 找到地面
                int groundY = PathfindingNavigation.getSafeTopY(world, base.getX(), base.getZ());
                base = new BlockPos(base.getX(), groundY, base.getZ());

                if (isValidPortalBuildLocation(world, base)) {
                    return base;
                }
            }
        }
        return null;
    }

    /**
     * 检查位置是否适合建造传送门
     */
    private static boolean isValidPortalBuildLocation(ServerWorld world, BlockPos base) {
        // 需要 4x5 的空间（宽4，高5）
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                BlockPos check = base.add(x, y, 0);
                // 框架位置（四角和边）需要可替换
                if (x == 0 || x == 3 || y == 0 || y == 4) {
                    if (!world.getBlockState(check).isReplaceable() && !world.getBlockState(check).isAir()) {
                        return false;
                    }
                } else {
                    // 内部需要是空气
                    if (!world.getBlockState(check).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 建造下界传送门
     * 简化实现：直接放置黑曜石框架 + 激活
     */
    private static void buildPortal(ServerPlayerEntity player, BlockPos base) {
        ServerWorld world = player.getEntityWorld();
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 找黑曜石槽位
        int obsidianSlot = -1;
        int flintSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (obsidianSlot == -1 && stack.isOf(Items.OBSIDIAN)) obsidianSlot = i;
            if (flintSlot == -1 && stack.isOf(Items.FLINT_AND_STEEL)) flintSlot = i;
        }

        if (obsidianSlot == -1 || flintSlot == -1) return;

        // 建造 4x5 框架（简化：直接设置方块，不走挖掘链路）
        // 底部
        for (int x = 0; x < 4; x++) {
            world.setBlockState(base.add(x, 0, 0), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(base.add(x, 4, 0), Blocks.OBSIDIAN.getDefaultState());
        }
        // 两侧
        for (int y = 1; y < 4; y++) {
            world.setBlockState(base.add(0, y, 0), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(base.add(3, y, 0), Blocks.OBSIDIAN.getDefaultState());
        }

        // 消耗黑曜石（10个）
        int remaining = 10;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.OBSIDIAN)) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }

        // 用打火石激活（走真实发包）
        PacketHelper.setSelectedSlot(player, flintSlot);
        BlockHitResult hitResult = new BlockHitResult(
            Vec3d.ofCenter(base.add(1, 1, 0)),
            Direction.UP,
            base.add(1, 1, 0),
            false
        );
        PacketHelper.interactBlock(player, Hand.MAIN_HAND, hitResult);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    /**
     * 寻找附近的古代残骸
     * 在 Y=15 附近扫描
     */
    private static BlockPos findAncientDebris(ServerWorld world, BlockPos center) {
        // 优先在 Y=15 附近搜索
        int targetY = 15;
        for (int dx = -16; dx <= 16; dx += 2) {
            for (int dz = -16; dz <= 16; dz += 2) {
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos pos = center.add(dx, targetY - center.getY() + dy, dz);
                    if (pos.getY() < 8 || pos.getY() > 22) continue;
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.ANCIENT_DEBRIS)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 寻找下界敌对生物（烈焰人、猪灵、疣猪兽等）
     */
    private static HostileEntity findNetherMob(ServerWorld world, ServerPlayerEntity player) {
        var entities = world.getOtherEntities(player, player.getBoundingBox().expand(24.0));
        for (var entity : entities) {
            if (entity instanceof HostileEntity hostile && hostile.isAlive()) {
                // 优先找烈焰人（掉落烈焰棒）
                if (entity instanceof net.minecraft.entity.mob.BlazeEntity) {
                    return hostile;
                }
            }
        }
        // 没有烈焰人，找其他敌对生物
        for (var entity : entities) {
            if (entity instanceof HostileEntity hostile && hostile.isAlive()) {
                return hostile;
            }
        }
        return null;
    }

    private static void set(Personality p, TaskType type, BlockPos target, long timeout) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + timeout;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
