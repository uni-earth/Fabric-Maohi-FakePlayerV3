package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 冶炼行为(raw_iron → iron_ingot)
 * 从原 SurvivalMechanics 拆分(V5.20)
 *
 * V5.28.2 A.4 完整迁移:
 *   旧: 假装"野外便携小窑",纯计时器,到时 inv.setStack 转换原料 → 产物(凭空,无熔炉痕迹)
 *   新: 真双阶段熔炉协议
 *     阶段 1 (autoSmeltOres):
 *       - 找熔炉 + 检查原料/燃料
 *       - interactBlock 开 FurnaceScreenHandler
 *       - moveOneToHandlerSlot 把 1 raw_iron → input slot 0
 *       - moveOneToHandlerSlot 把 1 fuel → fuel slot 1
 *       - closeScreen
 *       - 记 smeltingFurnacePos + 设 smeltingTicks(略短于 vanilla 200 tick 烧炼周期)
 *     阶段 2 (tickSmelting 在 ticks==0 时):
 *       - 校验熔炉仍在 + 距离 < 5 格
 *       - interactBlock 重开 FurnaceScreenHandler
 *       - quickMove 输出 slot 2 → 自动转移到背包
 *       - closeScreen
 *       - 清 smeltingFurnacePos
 *
 *   每周期吞吐: 1 ingot / ~210 tick (~10.5s),比旧版本(8 ingots / 25s)慢但完全协议化。
 *   throughput 不足以支撑高强度铁器需求时,用户可调 autoSmeltOres 的 nextInt(500) 节流。
 */
public final class SmeltingBehavior {

	private SmeltingBehavior() {} // 工具类

	/** NOTE: V5.80 将搜扣半径从 6 扩大到 24 格。
	 *   原 6 格不够：假人放下燃炉后承接挖矿/砍树去了，回来时距炬特远超过 6 格导致
	 *   autoSmeltOres 永远找不到炬炉， raw_iron 堆山去也炼不了。
	 *   24 格 内进行层层扫描，匹配 PhaseIronAge 的 FURNACE_NEAR_SQ(12格)阈値。 */
	private static final int FURNACE_SCAN_RADIUS = 24;
	/** 阶段 2 重开熔炉的最大距离平方(5 格内,服务端 reach 5.5) */
	private static final double COLLECT_DIST_SQ = 25.0;

	/**
	 * 阶段 1: 找熔炉 + 摆原料 + 燃料 + 关界面 + 设倒计时。
	 * 与旧版本一致的节流: 每 ~25s 检查一次。
	 */
	public static void autoSmeltOres(ServerPlayerEntity player) {
		Personality pers = Personality.get(player);
		if (pers == null) return;
		if (pers.smeltingTicks > 0) return;             // 已在阶段 2 等待
		if (pers.smeltingFurnacePos != null) return;    // 阶段 1 已执行待 collect
		if (pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return; // 与合成串行
		// V5.83: 节流从 1/500 降到 1/40 —— 旧值批次间隔 ~25s，叠加 200tick 熔炼 → ~35s/锭，
		//   整套铁甲 24 锭要 ~14 分钟。1/40 后批次间隔 ~2s，~12s/锭（贴近 vanilla 10s/锭下限），
		//   整套铁甲 ~5 分钟。smeltingTicks/CRAFTING 守卫仍保证不洪泛、不超 vanilla 熔炼速度。
		if (ThreadLocalRandom.current().nextInt(40) != 0) return;

		PlayerInventory inv = player.getInventory();
		int rawIronSlot = findItemSlot(inv, Items.RAW_IRON);
		if (rawIronSlot < 0) return;
		int fuelSlot = findFuelSlot(inv);
		if (fuelSlot < 0) return;

		// V5.83: 优先用记忆熔炉坐标，避免现在 1/40 高频下每次都做 24³ 全量扫描（主线程开销）。
		//   记忆存在且已在熔炼范围(5格)内 → 直接用；否则才扫一次来发现/更新并回写。
		BlockPos furnace = pers.knownFurnacePos;
		// V5.83: 贴炉(≤5格,区块必加载，调 isFurnaceBlock 安全不会 park)时校验记忆坐标仍是熔炉；
		//   失效则清掉，避免对幽灵炉反复空驻留 / smelt_fail。远距离不校验（绝不读未加载区块）。
		if (furnace != null
				&& player.squaredDistanceTo(Vec3d.ofCenter(furnace)) <= COLLECT_DIST_SQ
				&& !isFurnaceBlock(player.getEntityWorld(), furnace)) {
			pers.knownFurnacePos = null;
			furnace = null;
		}
		if (furnace == null
				|| player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) {
			furnace = findFurnace(player, FURNACE_SCAN_RADIUS);
			if (furnace == null) return;
			pers.knownFurnacePos = furnace;
		}

		// 距离检查 — 只有真正贴炉(≤5格)才启动熔炼
		if (player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) return;

		// 真协议化阶段 1: 开熔炉 → 摆 1 raw_iron + 1 fuel → 关
		if (!placeIngredientsInFurnace(player, furnace, rawIronSlot, fuelSlot)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "place_ingredients_failed", "furnace", furnace);
			return;
		}

		// 标记阶段 2 状态
		pers.smeltingFurnacePos = furnace;
		// vanilla 烧炼周期 200 tick;给点抖动避免与多假人同步
		pers.smeltingTicks = 200 + ThreadLocalRandom.current().nextInt(40);
		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_start",
			"furnace", furnace, "ticks", pers.smeltingTicks);
	}

	/**
	 * 阶段 2 倒计时驱动: 归零时 collect。
	 */
	public static void tickSmelting(ServerPlayerEntity player, Personality pers) {
		if (pers.smeltingTicks <= 0) return;
		pers.smeltingTicks--;

		if (pers.smeltingTicks == 0) {
			BlockPos furnace = pers.smeltingFurnacePos;
			pers.smeltingFurnacePos = null; // 不论成败都清状态,避免卡死

			if (furnace == null) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_pos_lost");
				return;
			}
			// 假人可能在 200 tick 期间走远了,放弃 collect(产物留在熔炉里,下次再说)
			if (player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "walked_away", "furnace", furnace);
				return;
			}
			// 熔炉可能被破坏
			if (!isFurnaceBlock(player.getEntityWorld(), furnace)) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_destroyed", "furnace", furnace);
				return;
			}

			collectFromFurnace(player, furnace);
		}
	}

	// ---- internal: 真协议交互 ----

	private static boolean placeIngredientsInFurnace(ServerPlayerEntity player, BlockPos furnace,
	                                                 int rawIronInvSlot, int fuelInvSlot) {
		// 朝熔炉看 + 真实 interactBlock 包打开 GUI
		Vec3d center = Vec3d.ofCenter(furnace);
		facePoint(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, furnace, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// 校验 FurnaceScreenHandler 已开启
		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			return false;
		}

		int rawIronScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, rawIronInvSlot);
		int fuelScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, fuelInvSlot);
		if (rawIronScreenSlot < 0 || fuelScreenSlot < 0) {
			InventoryActionHelper.closeScreen(player);
			return false;
		}

		// 摆 1 raw_iron → input slot 0
		InventoryActionHelper.moveOneToHandlerSlot(player, rawIronScreenSlot, 0);
		// 摆 1 fuel → fuel slot 1
		InventoryActionHelper.moveOneToHandlerSlot(player, fuelScreenSlot, 1);

		InventoryActionHelper.closeScreen(player);
		return true;
	}

	private static void collectFromFurnace(ServerPlayerEntity player, BlockPos furnace) {
		Vec3d center = Vec3d.ofCenter(furnace);
		facePoint(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, furnace, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "screen_not_opened", "furnace", furnace);
			return;
		}

		// QUICK_MOVE 输出 slot 2 (产物) → vanilla AbstractFurnaceScreenHandler.quickMove
		// 自动把产物转移到玩家背包(找空槽或合并同物品)
		InventoryActionHelper.quickMove(player, 2);

		InventoryActionHelper.closeScreen(player);

		// 反馈音效(贴合真人成品出炉的视觉/听觉强化)
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 0.8f);

		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_done", "furnace", furnace);

		// P23 direct_grant: 当前 SmeltingBehavior 只烧 raw_iron → iron_ingot,
		//   所以 smelt_done 等同 story/smelt_iron 的实事求是观测。
		//   Set.add 自带去重,多次烧只首次记账。
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers != null && pers.unlockedAdvancements.add("story/smelt_iron")) {
			pers.hasUnlockedThisSession = true;
			pers.lastProgressAt = System.currentTimeMillis(); // V5.59 (idle-rescue)
			com.maohi.fakeplayer.TaskLogger.log(player, "achievement_unlocked",
				"id", "story/smelt_iron", "via", "direct_grant", "trigger", "smelt_done");
			com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(player.getUuid());
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null) mgr.markStorageDirty();
			// V5.50: 真触发 vanilla advancement,让 server 自动广播 chat 通知
			com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(player, "story/smelt_iron");
		}
	}

	// ---- internal: 工具方法 ----

	/** 找第一个匹配 item 的背包槽位 */
	private static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	/**
	 * V5.86: 燃料口径的单一真源 —— autoSmeltOres 摆料 与 各 Phase 的"有燃料?"前置判定都走这里,
	 *   杜绝两侧口径不一致(否则 Phase 用宽口径判"有燃料"驱去贴炉、autoSmeltOres 用窄口径判"无燃料"
	 *   → 反复 park 空转)。接受任意原木/木板(tag,vanilla 燃料,burn 300t)+ 煤/木炭(burn 1600t)。
	 *   旧版只认 oak/birch/spruce 原木 + oak 木板,jungle/dark_oak 等会被漏判。
	 */
	private static boolean isFuel(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.PLANKS)
			|| stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL);
	}

	/** 找第一个可烧炼燃料槽位(煤/木炭/任意原木/任意木板) */
	private static int findFuelSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			if (isFuel(inv.getStack(i))) return i;
		}
		return -1;
	}

	/**
	 * V5.86: 背包是否有任何可用熔炼燃料。供 PhaseStoneAge SA-P0 / PhaseIronAge 冶炼前置判定使用。
	 *   与 findFuelSlot 同口径 → "判定有燃料" 必然等于 "autoSmeltOres 真能摆进燃料槽",不会打架。
	 */
	public static boolean hasSmeltFuel(ServerPlayerEntity player) {
		return findFuelSlot(player.getInventory()) >= 0;
	}

	private static boolean isFurnaceBlock(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isOf(Blocks.FURNACE);
	}

	/** 同心壳扫熔炉 — 与 CraftingBehavior.findCraftingTable 同思路。 */
	private static BlockPos findFurnace(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						// V5.59+: chunk-ready 预检，跨 chunk 时跳过未就绪坐标
						if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
								world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
						if (world.getBlockState(mut).isOf(Blocks.FURNACE)) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/** 内联 facePoint(避免依赖 ai/trigger 子包),与 TriggerUtil.facePoint 一致。 */
	private static void facePoint(ServerPlayerEntity player, Vec3d point) {
		double dx = point.x - player.getX();
		double dy = point.y - (player.getY() + 1.62);
		double dz = point.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizDist)));
		player.setYaw(yaw);
		player.setPitch(pitch);
	}
}
