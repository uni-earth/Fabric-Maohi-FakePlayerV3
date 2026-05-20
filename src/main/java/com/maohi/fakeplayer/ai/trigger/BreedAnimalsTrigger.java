package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * The Parrots and the Bats: 喂养动物繁殖 (V5.22 / V5.51 扩展 / V5.53 升级到 A 类)
 *
 * vanilla AnimalEntity.interactMob 内部:消耗饲料 → 进 love mode →
 *   2 头同种 8 格内都 love mode → vanilla 自动配对 → spawnChildFromBreeding →
 *   bred_animals criterion fire → [The Parrots and the Bats]
 *
 * V5.51: 从 3 种(cow/chicken/pig)扩展到 6 种主世界常见动物(+sheep/goat/mooshroom) —
 *   行为画像更接近真人。所有 6 种都不需先驯服,小麦 + 麦种 + 胡萝卜三种饲料就能覆盖。
 *
 * V5.53: 从 B 类升级到 A 类 — 改成"连喂 2 头同种",vanilla 真触发繁殖。
 *   旧版本只喂第一头(只有它进 love mode,第二头不 love → vanilla 不繁殖 → criterion
 *   不 fire → 项目兜底强行打钩),改成单 tick 内连喂两头(都进 love mode → vanilla 自动
 *   繁殖生小动物 → criterion 真 fire → 真广播)。需 ≥2 个饲料。
 *
 *   按背包饲料按优先级 try,任一组(食物+附近 2+ 成年同种动物 + 饲料 ≥2)成立就喂。
 *   注意:还没主动 broadcast "husbandry/bred_all_animals" — 那个 vanilla 要求 22 种全做过,
 *   bot 累积达成靠 vanilla 自然 fire(criterion 内部累计);本 trigger 保持 breed_an_animal 单 advId。
 *
 * 喂食 map(配饲料 → 目标 entity 类):
 *   小麦   → 牛 / 羊 / 哞菇 / 山羊
 *   小麦种子 → 鸡
 *   胡萝卜  → 猪
 *
 * 不覆盖的种类(及原因):
 *   - 马/驴/骡:vanilla 繁殖要 golden_apple/golden_carrot,bot 几乎不会主动喂金苹果
 *   - 羊驼:继承 AbstractHorseEntity,要先 tame(bot 不会做)
 *   - 狼/猫:vanilla 要先 tame(TameAnimalTrigger 协同后才能繁殖,留给长期累积)
 *   - 兔子/狐狸/熊猫/海龟/青蛙/嗅探兽:饲料或场景受限,留给后续 BredAllAnimalsTrigger
 *
 * 实测策略:trigger 按背包优先级走第一可行组,6 种轮询会让 bot 在长会话里
 *   涉及 4-5 种繁殖,与"真人 1-2 周 breed_all_animals"的画像吻合。
 */
public final class BreedAnimalsTrigger implements AchievementTrigger {

	public static final BreedAnimalsTrigger INSTANCE = new BreedAnimalsTrigger();
	private static final String ADV_ID = "husbandry/breed_an_animal";

	private BreedAnimalsTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{8_000L, 30_000L}; } // 8~30s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		return player.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD;
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		PlayerInventory inv = player.getInventory();

		// 按背包持有的饲料优先级 try — 第一个能凑齐"饲料+2 成年同种"的组就执行。
		//   优先级:小麦(覆盖 cow/sheep/goat/mooshroom,最常见)→ 麦种(鸡)→ 胡萝卜(猪)
		//
		//   不覆盖的种类(及原因):
		//     - 马/驴/骡:vanilla 繁殖要 golden_apple/golden_carrot,bot 几乎不会主动喂金苹果
		//     - 羊驼:继承 AbstractHorseEntity,要先 tame(bot 不会做)
		//     - 狼/猫:vanilla 要先 tame(TameAnimalTrigger 协同后才能繁殖,留给长期累积)
		//     - 兔子/狐狸/熊猫/海龟/青蛙/嗅探兽:饲料或场景受限,留给后续 BredAllAnimalsTrigger
		if (tryFeed(player, personality, inv, Items.WHEAT, net.minecraft.entity.passive.CowEntity.class)) return true;
		if (tryFeed(player, personality, inv, Items.WHEAT, net.minecraft.entity.passive.SheepEntity.class)) return true;
		if (tryFeed(player, personality, inv, Items.WHEAT, net.minecraft.entity.passive.GoatEntity.class)) return true;
		if (tryFeed(player, personality, inv, Items.WHEAT, net.minecraft.entity.passive.MooshroomEntity.class)) return true;
		if (tryFeed(player, personality, inv, Items.WHEAT_SEEDS, net.minecraft.entity.passive.ChickenEntity.class)) return true;
		if (tryFeed(player, personality, inv, Items.CARROT, net.minecraft.entity.passive.PigEntity.class)) return true;
		return false;
	}

	/**
	 * 共用喂食流程:背包有 food(≥2) + 8 格内 ≥2 成年同种(active breeding age) →
	 *   切饲料 → 走近 → interactMob 喂第一头 + 立刻 interactMob 喂第二头。
	 *
	 * vanilla 检查 entity.getBreedingAge() == 0 才接受繁殖;baby 会被 isBaby() 过滤掉。
	 * 必须 ≥ 2 头同种 + 都进入 love mode 才会触发 vanilla 繁殖(否则第一头进 love 30s 内
	 *   没找到对象会过期),所以单 tick 喂两头比节流后第二次 trigger 更稳妥(love 期间撞概率)。
	 *
	 * V5.53: 升级 A 类 — 两头都 love mode 后 vanilla 自动 spawnChildFromBreeding 真生小动物,
	 *   bred_animals criterion 真 fire,真广播。bot 站着同 tick 连发两次 interactMob,
	 *   vanilla 内部 entity.interactMob 不做距离检查(距离限制在 ServerPlayNetworkHandler 的
	 *   onPlayerInteractEntity 层),bot 直接调 entity API 绕过 — 真人玩家也常这样"走到两头中
	 *   间一手一头同时喂"。
	 */
	private static <T extends AnimalEntity> boolean tryFeed(ServerPlayerEntity player,
	                                                        Personality personality,
	                                                        PlayerInventory inv,
	                                                        Item food,
	                                                        Class<T> targetType) {
		// V5.53: 至少 2 个饲料才喂(两头都要消耗 1 个)
		if (countItem(inv, food) < 2) return false;

		// 8 格内扫成年同类动物
		Box box = player.getBoundingBox().expand(8.0);
		List<T> animals = player.getEntityWorld().getEntitiesByClass(
			targetType, box, e -> e.isAlive() && !e.isBaby() && e.getBreedingAge() == 0);
		if (animals.size() < 2) return false;

		// 切饲料到 hotbar 0
		int foodSlot = TriggerUtil.findItemSlot(inv, food);
		if (foodSlot >= 9) {
			TriggerUtil.swapToHotbar(player, foodSlot, 0);
			foodSlot = 0;
		}
		if (foodSlot >= 0) PacketHelper.setSelectedSlot(player, foodSlot);

		T first = animals.get(0);
		T second = animals.get(1);

		// 距离第一头远了先派路 — 走到第一头身边(到了后 vanilla love mode 范围 8 格,第二头自然在范围内)
		double distSq = player.squaredDistanceTo(first);
		if (distSq > 9.0) {
			personality.taskTarget = first.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks
			return false;
		}

		// vanilla AnimalEntity.interactMob 第一头:消耗饲料 + love mode 30s
		TriggerUtil.facePoint(player, first.getEyePos());
		first.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);

		// V5.53: 立刻喂第二头(同 8 格内,bot 不动 vanilla AI 跑过来配对) →
		//   两头都 love mode → vanilla 自动 spawnChildFromBreeding → bred_animals criterion 真 fire
		TriggerUtil.facePoint(player, second.getEyePos());
		second.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);

		// V5.53: 走完连喂两头动作链 → vanilla 真触发繁殖 → criterion 真 fire(A 类)。
		//   兜底 broadcastVanillaGrant 在 Registry 调用,vanilla 已 done 时是 no-op,无副作用。
		return true;
	}

	/** 数背包某 item 总数(用于 V5.53 连喂"饲料 ≥2"前置检查) */
	private static int countItem(PlayerInventory inv, Item item) {
		int count = 0;
		for (int i = 0; i < inv.size(); i++) {
			net.minecraft.item.ItemStack s = inv.getStack(i);
			if (s.isOf(item)) count += s.getCount();
		}
		return count;
	}
}
