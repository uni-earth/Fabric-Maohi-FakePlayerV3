package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

/**
 * 进食 / 喝药 / 远程攻击行为(use-item 主题)
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class EatingBehavior {

	private EatingBehavior() {} // 工具类

	/**
	 * 统一处理假人的生存逻辑
	 * @param player 假人实体
	 * @param personality 假人个性状态 (包含进食状态位)
	 */
	public static void handleSurvival(ServerPlayerEntity player, Personality personality) {
		// 1. 如果当前不在进食，判断是否需要启动进食流程
		if (!personality.isEating) {
			// V3.1 优先级：低血量先用治疗药水，再吃东西
			if (player.getHealth() < player.getMaxHealth() * 0.3f) {
				// 紧急：低血量先尝试喝治疗药水
				int potionSlot = findPotionSlot(player.getInventory());
				if (potionSlot != -1) {
					// ★ V3.3: 走真实发包切换槽位+使用物品
					PacketHelper.setSelectedSlot(player, potionSlot);
					personality.isEating = true;
					personality.eatingTicks = 32; // 药水使用时间约 1.6 秒
					personality.isDrinkingPotion = true; // 标记为喝药水
					return;
				}
			}

			// 当血量不足 (丢失超过 2 点) 或者 饥饿度低于 10 时，寻找食物
			if (player.getHealth() < player.getMaxHealth() - 2.0f || player.getHungerManager().getFoodLevel() < 10) {
				int foodSlot = findFoodSlot(player.getInventory());
				if (foodSlot != -1) {
					// ★ V3.3: 走真实发包切换槽位+开始使用食物
					PacketHelper.setSelectedSlot(player, foodSlot);
					personality.isEating = true;
					personality.eatingTicks = 32; // MC 标准进食时间约 1.6 秒 (32 ticks)
					personality.isDrinkingPotion = false;

					// ★ 发包：开始使用物品
					PacketHelper.useItem(player, Hand.MAIN_HAND);
				}
			}
		} else {
			// 2. 进食中：递减计时器并模拟动作
			personality.eatingTicks--;
			if (personality.eatingTicks % 4 == 0) {
				// ★ V3.3: 发真实挥手包（模拟咀嚼动作）
				PacketHelper.swingHand(player, Hand.MAIN_HAND);
			}

			// 3. 进食完成：发释放包，服务端自动结算
			if (personality.eatingTicks <= 0) {
				personality.isEating = false;

				// ★ V3.3: 发包释放使用物品
				// 服务端自动处理：应用食物效果 + 消耗物品 + 播放动画
				PacketHelper.releaseUseItem(player);

				// V3.3: 删除了手动 eatFood() / finishUsing()
				// 原因：releaseUseItem 走真实链路后，服务端自动执行结算
				personality.isDrinkingPotion = false;
			}
		}
	}

	/**
	 * V3.1: 尝试使用弓箭远程攻击
	 * V3.3: 走真实发包链路（拉弓→等→射箭）
	 */
	public static boolean tryRangedAttack(ServerPlayerEntity player, double targetDistance) {
		if (targetDistance < 25.0 || targetDistance > 225.0) return false;

		PlayerInventory inv = player.getInventory();
		int bowSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isOf(Items.BOW) && bowSlot == -1) bowSlot = i;
		}

		if (bowSlot == -1) return false;

		// ★ V3.3: 走真实发包切换槽位+拉弓
		PacketHelper.setSelectedSlot(player, bowSlot);
		PacketHelper.useItem(player, Hand.MAIN_HAND);
		// 拉弓状态由 VPM tick 中的 personality 跟踪，达到足够拉力后 releaseUseItem
		return true;
	}

	/** 在快捷栏中寻找可食用的物品 */
	private static int findFoodSlot(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.getComponents().contains(DataComponentTypes.FOOD)) {
				return i;
			}
		}
		return -1;
	}

	/** 在快捷栏中寻找治疗药水 (1.21.11 组件化适配) */
	private static int findPotionSlot(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.POTION)) {
				// 获取药水组件数据
				net.minecraft.component.type.PotionContentsComponent contents = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
				if (contents != null && contents.potion().isPresent()) {
					String potionId = contents.potion().get().getIdAsString();
					// 只喝治疗或强效治疗药水
					if (potionId.contains("healing")) {
						return i;
					}
				}
			}
		}
		return -1;
	}
}
