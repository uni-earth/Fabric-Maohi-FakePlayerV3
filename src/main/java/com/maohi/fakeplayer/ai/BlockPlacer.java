package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 方块放置模拟器 (V4 P1-1)
 * 专门处理假人"放置类"操作，特别是地下挖矿时的插火把照明，
 * 极大提升假人行为的拟真度（区别于开夜视的纯挂机脚本）。
 */
public class BlockPlacer {

	/**
	 * 检查并尝试放置火把
	 * 触发条件：正在挖矿 + 环境太暗 + 包里有火把
	 */
	public static void tryPlaceTorch(ServerPlayerEntity player, VirtualPlayerManager.Personality personality) {
		// 1. 只有挖矿或探索状态才会插火把
		if (personality.currentTask != VirtualPlayerManager.TaskType.MINING && 
			personality.currentTask != VirtualPlayerManager.TaskType.EXPLORING) {
			return;
		}

		// 2. 频率控制：每 20 个 tick (1秒) 大约只有 5% 的概率检查，避免密密麻麻全是火把
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		BlockPos pos = player.getBlockPos();
		
		// 3. 检查亮度：低于 7 才插火把 (1.21.11 机制，僵尸会在亮度 0 刷，但真人通常在 7 以下就觉得暗了)
		int lightLevel = player.getEntityWorld().getLightLevel(LightType.BLOCK, pos);
		if (lightLevel >= 7) return; // 已经够亮了

		// 如果露天且是白天，不需要插火把 (getLightLevel(SKY) 会返回天空光照)
		if (player.getEntityWorld().getLightLevel(LightType.SKY, pos) > 10 && player.getEntityWorld().isDay()) {
			return;
		}

		// 4. 检查快捷栏 (0-8) 是否有火把
		PlayerInventory inv = player.getInventory();
		int torchSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.TORCH)) {
				torchSlot = i;
				break;
			}
		}

		// 💡 回答你的问题：如果包里没有火把怎么办？
		// 答案：什么都不做。这非常真实，真人包里没火把的时候也只能"摸黑挖矿"。
		// 假人在上线时 LootTracker 会给他们发一部分初始火把。
		if (torchSlot == -1) return;

		// 5. 目标方块：假人脚下的方块
		BlockPos blockUnder = pos.down();
		
		// 确保脚下方块可以插火把 (简单的非空判断)
		if (player.getEntityWorld().getBlockState(blockUnder).isAir()) return;

		// 6. 执行放置 (全链路发包)
		int originalSlot = inv.selectedSlot;
		
		// 步骤 A：切到火把
		PacketHelper.setSelectedSlot(player, torchSlot);
		
		// 步骤 B：右键交互脚下方块的上表面
		BlockHitResult hitResult = new BlockHitResult(
			Vec3d.ofCenter(blockUnder).add(0, 0.5, 0), // 击中方块顶部中心
			Direction.UP, 
			blockUnder, 
			false
		);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hitResult);
		
		// 步骤 C：发挥手动画包
		PacketHelper.swingHand(player, Hand.MAIN_HAND);
		
		// 步骤 D：切回原来的工具
		PacketHelper.setSelectedSlot(player, originalSlot);
	}
}
