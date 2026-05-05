package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 装备 / 工具切换行为
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class EquipmentBehavior {

	private EquipmentBehavior() {} // 工具类

	/**
	 * V3.1: 根据当前任务自动切换工具
	 * V3.3: 走真实发包切换槽位
	 */
	public static void autoSwitchTool(ServerPlayerEntity player, TaskType currentTask) {
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		PlayerInventory inv = player.getInventory();
		switch (currentTask) {
			case WOODCUTTING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_AXE) || inv.getStack(i).isOf(Items.STONE_AXE)
						|| inv.getStack(i).isOf(Items.IRON_AXE) || inv.getStack(i).isOf(Items.DIAMOND_AXE)) {
						// ★ V3.3: 发真实切槽包
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				break;
			case MINING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_PICKAXE) || inv.getStack(i).isOf(Items.STONE_PICKAXE)
						|| inv.getStack(i).isOf(Items.IRON_PICKAXE) || inv.getStack(i).isOf(Items.DIAMOND_PICKAXE)) {
						// ★ V3.3: 发真实切槽包
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				break;
			default:
				break;
		}
	}

	/** 自动装备背包中防御值更高的护甲 */
	public static void autoEquipArmor(ServerPlayerEntity player) {
		// V4.1 限制触发频率，避免每个 tick 都扫描背包
		if (ThreadLocalRandom.current().nextInt(100) > 5) return;

		PlayerInventory inv = player.getInventory();
		int[] armorSlots = {36, 37, 38, 39};
		for (int armorSlot : armorSlots) {
			ItemStack equipped = inv.getStack(armorSlot);
			int equippedDef = getArmorDefense(equipped);
			for (int i = 0; i < 36; i++) {
				ItemStack candidate = inv.getStack(i);
				if (candidate.isEmpty() || !isArmorForSlot(candidate, armorSlot)) continue;
				if (getArmorDefense(candidate) > equippedDef) {
					inv.setStack(armorSlot, candidate.copy());
					inv.setStack(i, equipped.copy());
					equipped = inv.getStack(armorSlot);
					equippedDef = getArmorDefense(equipped);
				}
			}
		}
	}

	private static int getArmorDefense(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		var def = stack.getComponents().get(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (def == null) return 0;
		int total = 0;
		for (var entry : def.modifiers()) {
			if (entry.attribute().value() == net.minecraft.entity.attribute.EntityAttributes.ARMOR) {
				total += (int) entry.modifier().value();
			}
		}
		return total;
	}

	private static boolean isArmorForSlot(ItemStack stack, int armorSlot) {
		if (stack.isEmpty()) return false;
		String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
		return switch (armorSlot) {
			case 36 -> id.endsWith("_boots");
			case 37 -> id.endsWith("_leggings");
			case 38 -> id.endsWith("_chestplate");
			case 39 -> id.endsWith("_helmet");
			default -> false;
		};
	}
}
