package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 合成行为(状态机驱动)
 * 从原 SurvivalMechanics 拆分(V5.20)
 *
 * V5.28.2 A.1 完整迁移:
 *   旧:状态机倒计时归零时 inv.setStack 凭空生成结果(无 PCAP 痕迹)
 *   新:倒计时归零时执行真实"interactBlock(workbench) → 验证 CraftingScreenHandler →
 *      对每个配方槽 PICKUP 序列摆原料 → QUICK_MOVE 槽 0 取结果 → CloseScreen"
 *
 *   配方表用静态 List<Placement> hardcode — 5 个石/铁工具 + 信标共 7 个配方,
 *   不引入额外 RecipeManager 依赖,也不需要让假人 "解锁" recipe book(更接近真人手动放料)。
 *
 *   每次合成产生 ~15-30 个 ClickSlot 包(stone tool 5 placements × 3 包 + 1 quickMove + 1 close;
 *   beacon 9 placements × 3 包 + 1 quickMove + 1 close = 29 包),全在同一 server tick 同步执行。
 */
public final class CraftingBehavior {

	private CraftingBehavior() {} // 工具类

	/**
	 * 石器时代初始合成：圆石够了就合成镐+剑+斧三件套
	 * 触发合成状态机，由 tickCrafting() 处理实际合成
	 */
	public static void autoCraftStoneTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;

		PlayerInventory inv = player.getInventory();

		// 检查已有的工具
		boolean hasPickaxe = false, hasSword = false, hasAxe = false;
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem()).getPath();
			// V5.25: 精确匹配 stone+ 镐——wooden_pickaxe 含"pickaxe"会误命中,导致木镐起手假人永远不合成石镐,卡死石器时代
			if (id.equals("stone_pickaxe") || id.equals("iron_pickaxe")
				|| id.equals("diamond_pickaxe") || id.equals("netherite_pickaxe")) hasPickaxe = true;
			if (id.contains("sword")) hasSword = true;
			if (id.contains("axe") && !id.contains("pickaxe")) hasAxe = true;
		}

		// 按优先级：镐 > 剑 > 斧，有圆石就合成缺的那件
		Item target = null;
		Item material = Items.COBBLESTONE;
		int needed = 3;
		if (!hasPickaxe && hasMaterial(inv, material, needed)) target = Items.STONE_PICKAXE;
		else if (!hasSword && hasMaterial(inv, material, needed)) target = Items.STONE_SWORD;
		else if (!hasAxe && hasMaterial(inv, material, needed)) target = Items.STONE_AXE;

		if (target == null) return;

		// V5.28 P1-A.1: 没工作台就不进合成态(后续 executeCraft 会再扫一次,这里先快速排除)
		if (findCraftingTable(player, 6) == null) {
			pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
			return;
		}

		// 进入合成状态机
		pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = 40 + ThreadLocalRandom.current().nextInt(20);
	}

	/**
	 * 自动升级工具 (V5.1)：触发合成状态机
	 */
	public static void autoUpgradeTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;
		if (ThreadLocalRandom.current().nextInt(500) != 0) return;

		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < 9; i++) {
			ItemStack tool = inv.getStack(i);
			if (tool.isEmpty()) continue;
			String id = net.minecraft.registry.Registries.ITEM.getId(tool.getItem()).getPath();

			Item target = null;
			if (id.startsWith("stone_pickaxe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_PICKAXE;
			else if (id.startsWith("iron_pickaxe") && hasMaterial(inv, Items.DIAMOND, 3)) target = Items.DIAMOND_PICKAXE;
			else if (id.startsWith("stone_axe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_AXE;

			if (target != null) {
				if (findCraftingTable(player, 6) == null) return;

				pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
				pers.craftingTarget = target;
				pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40); // 3~5 秒
				return;
			}
		}
	}

	/**
	 * 合成状态机每 tick 逻辑 (V5.1, V5.28.2 全协议化)
	 *
	 * 倒计时阶段只挥手做动画;归零时执行 executeCraft 走真实工作台协议链。
	 * 入口的 findCraftingTable guard 已确保进入态时附近 6 格有工作台,executeCraft 会再扫一次防漂移。
	 */
	public static void tickCrafting(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		if (pers.craftingTicks <= 0) return;
		pers.craftingTicks--;

		// 倒计时期间:每 10 tick 挥一下手模拟在工作台前忙活
		if (pers.craftingTicks % 10 == 0) {
			PacketHelper.swingHand(player, Hand.MAIN_HAND);
		}

		// 归零:走真实合成协议
		if (pers.craftingTicks == 0 && pers.craftingTarget != null) {
			executeCraft(player, pers.craftingTarget);
			pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
			pers.craftingTarget = null;
		}
	}

	/**
	 * 真协议合成: 找工作台 → interactBlock 开窗 → ClickSlot 摆原料 → QUICK_MOVE 槽 0 取结果 → CloseScreen。
	 * 失败任意环节都尽量回滚(已摆原料 quickMove 还回背包)+ 关界面,避免影响下游 trigger。
	 */
	private static void executeCraft(ServerPlayerEntity player, Item target) {
		List<Placement> recipe = recipeFor(target);
		if (recipe.isEmpty()) return; // 无配方表,放弃

		BlockPos workbench = findCraftingTable(player, 6);
		if (workbench == null) return; // 工作台被破坏/移走

		// 1. 朝工作台看 + interactBlock 开窗
		Vec3d center = Vec3d.ofCenter(workbench);
		TriggerUtilFacePoint.face(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, workbench, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// 2. 校验 CraftingScreenHandler 已开启
		if (!(player.currentScreenHandler instanceof CraftingScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			return;
		}

		// 3. 摆原料: 每个 placement 走 PICKUP 3-packet 序列
		PlayerInventory inv = player.getInventory();
		boolean allPlaced = true;
		for (Placement p : recipe) {
			int srcInvSlot = findItemSlot(inv, p.ingredient);
			if (srcInvSlot < 0) { allPlaced = false; break; }
			int srcScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, srcInvSlot);
			if (srcScreenSlot < 0) { allPlaced = false; break; }
			InventoryActionHelper.moveOneToHandlerSlot(player, srcScreenSlot, p.gridSlot);
		}

		if (!allPlaced) {
			// 把已摆进网格的料拿回背包(网格槽 1-9),关界面
			for (int g = 1; g <= 9; g++) {
				InventoryActionHelper.quickMove(player, g);
			}
			InventoryActionHelper.closeScreen(player);
			return;
		}

		// 4. QUICK_MOVE 槽 0(result) - vanilla CraftingResultSlot 同步:
		//    - 校验配方匹配
		//    - 网格 1-9 各扣 1
		//    - 结果转移到玩家背包(自动找空槽或合并)
		InventoryActionHelper.quickMove(player, 0);

		// 5. 关界面
		InventoryActionHelper.closeScreen(player);

		// 反馈音效(贴合真人合成完成时的视觉/听觉强化,与旧版本一致)
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);
	}

	/** 配方原料 → 网格槽位 (网格槽编号 1..9 对应 3×3 行优先) */
	private record Placement(Item ingredient, int gridSlot) {}

	/**
	 * 配方表 — 与 vanilla recipes 对齐:
	 *
	 * Stone/Iron/Diamond Pickaxe (CCC/.S./.S.):  cobble@1,2,3 + stick@5,8
	 * Stone/Iron Axe (CC./CS./.S.):              cobble@1,2,4 + stick@5,8
	 * Stone Sword (.C./.C./.S.):                 cobble@2,5 + stick@8
	 * Beacon (GGG/GNG/OOO):                      glass@1,2,3,4,6 + nether_star@5 + obsidian@7,8,9
	 */
	private static List<Placement> recipeFor(Item target) {
		if (target == Items.STONE_PICKAXE) return List.of(
			new Placement(Items.COBBLESTONE, 1), new Placement(Items.COBBLESTONE, 2), new Placement(Items.COBBLESTONE, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.IRON_PICKAXE) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2), new Placement(Items.IRON_INGOT, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.DIAMOND_PICKAXE) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 2), new Placement(Items.DIAMOND, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.STONE_AXE) return List.of(
			new Placement(Items.COBBLESTONE, 1), new Placement(Items.COBBLESTONE, 2),
			new Placement(Items.COBBLESTONE, 4), new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.IRON_AXE) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2),
			new Placement(Items.IRON_INGOT, 4), new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.STONE_SWORD) return List.of(
			new Placement(Items.COBBLESTONE, 2), new Placement(Items.COBBLESTONE, 5),
			new Placement(Items.STICK, 8));
		if (target == Items.BEACON) return List.of(
			new Placement(Items.GLASS, 1), new Placement(Items.GLASS, 2), new Placement(Items.GLASS, 3),
			new Placement(Items.GLASS, 4), new Placement(Items.NETHER_STAR, 5), new Placement(Items.GLASS, 6),
			new Placement(Items.OBSIDIAN, 7), new Placement(Items.OBSIDIAN, 8), new Placement(Items.OBSIDIAN, 9));
		return List.of();
	}

	private static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	private static boolean hasMaterial(PlayerInventory inv, Item item, int count) {
		int found = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) found += inv.getStack(i).getCount();
		}
		return found >= count;
	}

	/**
	 * V5.28 P1-A.1: 同心壳扫工作台 — 切比雪夫距离 d 由近到远,Y 范围 ±3 覆盖楼上楼下基地。
	 * 与 EnchantItemTrigger.findEnchantingTable / HotStuffTrigger 同思路,贴脸 O(1) 命中。
	 */
	private static BlockPos findCraftingTable(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(Blocks.CRAFTING_TABLE)) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * 内联 facePoint(避免引用 ai/trigger 包,保持 ai 包内层无下游耦合)。
	 * 与 TriggerUtil.facePoint 实现一致,眼高近似 1.62。
	 */
	private static final class TriggerUtilFacePoint {
		static void face(ServerPlayerEntity player, Vec3d point) {
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
}
