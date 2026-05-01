package com.maohi.fakeplayer.ai;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.WeaponComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 物资进阶追踪器 (V3.3 全链路真实 — 1.21.11 组件系统适配版)
 * 
 * 1.21.11 变更：
 * - SwordItem/ArmorItem 子类被移除，改用组件系统
 * - 武器检测：stack.contains(DataComponentTypes.WEAPON)
 * - 护甲检测：stack.get(DataComponentTypes.EQUIPPABLE)
 * - 护甲槽位：equippable.slot()
 * - 武器等级：按 Items 常量判断（组件系统无材质类）
 * 
 * 保留的功能（属于假人"智能"行为，真人也会做）：
 * - tryAutoEquipNearby：捡到装备自动穿戴（真人也会把捡到的铁头盔戴上）
 * - 自动穿戴/换装逻辑
 */
public class LootTracker {

	// ========== 智能穿戴：捡到装备后自动换装 ==========

	/**
	 * 检测附近掉落物，如果捡到更好的装备就自动穿戴
	 */
	public static void tryAutoEquipNearby(ServerPlayerEntity player) {
		if (player.getInventory().getEmptySlot() == -1) return;

		java.util.List<net.minecraft.entity.Entity> nearby = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(2.5)
		);

		for (net.minecraft.entity.Entity entity : nearby) {
			if (entity instanceof ItemEntity itemEntity && !itemEntity.cannotPickup() && itemEntity.isAlive()) {
				ItemStack stack = itemEntity.getStack();
				if (stack.isEmpty()) continue;

				// 检查是否值得穿戴
				if (shouldEquip(player, stack)) {
					// 走原版碰撞拾取（服务端自动入背包+动画+销毁）
					itemEntity.onPlayerCollision(player);
					
					// 拾取后尝试穿戴
					tryAutoEquip(player, stack);
				}
				return; // 每次只处理一个
			}
		}
	}

	/**
	 * 判断是否值得穿戴/换装
	 * 只在捡到比自己当前装备更好的东西时才换
	 */
	private static boolean shouldEquip(ServerPlayerEntity player, ItemStack stack) {
		if (stack.isEmpty()) return false;

		// 武器：检测 WeaponComponent（1.21.11 组件系统替代 SwordItem）
		if (stack.contains(DataComponentTypes.WEAPON)) {
			ItemStack currentWeapon = player.getMainHandStack();
			if (currentWeapon.isEmpty() || !currentWeapon.contains(DataComponentTypes.WEAPON)) {
				return true; // 没武器，换
			}
			return getWeaponTier(stack) > getWeaponTier(currentWeapon); // 更好的才换
		}

		// 护甲：检测 EquippableComponent（1.21.11 组件系统替代 ArmorItem）
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		if (equippable != null) {
			EquipmentSlot slot = equippable.slot();
			// 只关心护甲槽位（不替换主手/副手）
			if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) return false;
			
			ItemStack current = player.getEquippedStack(slot);
			if (current.isEmpty()) return true; // 没护甲，穿
			EquippableComponent currentEquippable = current.get(DataComponentTypes.EQUIPPABLE);
			if (currentEquippable != null) {
				return getArmorTier(stack) > getArmorTier(current); // 更好的才换
			}
		}

		return false;
	}

	/**
	 * 自动穿戴装备
	 */
	public static void tryAutoEquip(ServerPlayerEntity player, ItemStack stack) {
		if (stack.isEmpty()) return;

		// 武器：捡到更好的武器就换
		if (stack.contains(DataComponentTypes.WEAPON)) {
			ItemStack currentWeapon = player.getMainHandStack();
			if (currentWeapon.isEmpty() || !currentWeapon.contains(DataComponentTypes.WEAPON)) {
				player.equipStack(EquipmentSlot.MAINHAND, stack.copy());
			} else if (getWeaponTier(stack) > getWeaponTier(currentWeapon)) {
				player.equipStack(EquipmentSlot.MAINHAND, stack.copy());
			}
			return;
		}

		// 护甲：捡到就穿上（1.21.11 用 EquippableComponent.slot() 获取槽位）
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		if (equippable != null) {
			EquipmentSlot slot = equippable.slot();
			if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) return;
			
			ItemStack current = player.getEquippedStack(slot);
			if (current.isEmpty()) {
				player.equipStack(slot, stack.copy());
			} else if (getArmorTier(stack) > getArmorTier(current)) {
				player.equipStack(slot, stack.copy());
			}
		}
	}

	/** 武器材质等级：木0 石1 铁2 钻3 下界合金4（按 Items 常量判断） */
	private static int getWeaponTier(ItemStack stack) {
		net.minecraft.item.Item item = stack.getItem();
		if (item == Items.NETHERITE_SWORD) return 4;
		if (item == Items.DIAMOND_SWORD) return 3;
		if (item == Items.IRON_SWORD) return 2;
		if (item == Items.STONE_SWORD) return 1;
		if (item == Items.WOODEN_SWORD) return 0;
		if (item == Items.GOLDEN_SWORD) return 0; // 金剑和木剑同级
		// 非剑武器（斧等）也按材质分级
		if (item == Items.NETHERITE_AXE) return 4;
		if (item == Items.DIAMOND_AXE) return 3;
		if (item == Items.IRON_AXE) return 2;
		if (item == Items.STONE_AXE) return 1;
		if (item == Items.WOODEN_AXE) return 0;
		if (item == Items.GOLDEN_AXE) return 0;
		// 三叉戟和锤另算
		if (item == Items.TRIDENT) return 3;
		if (item == Items.MACE) return 3;
		return 0;
	}

	/** 护甲材质等级（1.21.11：ArmorItem.getToughness() 不存在了，按 Items 常量判断） */
	private static int getArmorTier(ItemStack stack) {
		net.minecraft.item.Item item = stack.getItem();
		// 下界合金
		if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
			|| item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) return 4;
		// 钻石
		if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
			|| item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return 3;
		// 铁
		if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
			|| item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return 2;
		// 链甲
		if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE
			|| item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) return 1;
		// 龟壳算2级（和铁同级）
		if (item == Items.TURTLE_HELMET) return 2;
		return 0; // 皮革/金/其他
	}
}
