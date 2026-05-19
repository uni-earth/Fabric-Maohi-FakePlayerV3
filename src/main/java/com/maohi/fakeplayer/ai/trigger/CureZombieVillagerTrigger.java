package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Zombie Doctor: 治愈僵尸村民 (V5.52 新增)
 *
 * vanilla 路径:
 *   1. 朝僵尸村民扔 SPLASH_POTION (weakness 弱化药水) → ThrownPotionEntity 命中 → zombie 上 WEAKNESS effect
 *   2. 玩家右键(interactMob)给 zombie GOLDEN_APPLE → ZombieVillagerEntity.setConverting(playerUuid, ticks)
 *   3. ~5 分钟后 finishConversion → cured_zombie_villager criterion → fire deflect_arrow ← 误,fire cure_zombie_villager
 * advancement: story/cure_zombie_villager
 *
 * 跨阶段依赖:弱化药水合成要烈焰粉(进过下界)+ 酿造台,金苹果合成要 8 金锭。
 *   bot 在 NETHER 阶段后才可能自然有这两样;若 op 管理员发放也可触发。
 *   项目酿造逻辑暂未实现,本 trigger 假设"bot 已有 splash_potion + golden_apple"。
 *
 * 简化策略(对齐项目"做了动作链就广播"惯例):
 *   - shouldRun:主世界 + 有 SPLASH_POTION + 有 GOLDEN_APPLE + 附近 8 格内有 ZombieVillagerEntity
 *   - tryTrigger:扔药水 + 喂金苹果(单 tick 走两个动作,vanilla 真实 cure 需 5 分钟,中途 zombie
 *     可能被怪杀)→ 不等 vanilla 真 cure 完成,直接 broadcast 兜底
 *   - 不验证 SPLASH_POTION 内 PotionContent 是否真是 weakness:vanilla DataComponent 检验
 *     在 mapping 上未在项目其它处验证过,避免耦合;非 weakness 药水扔了也无害(zombie 上其它效果),
 *     只是 vanilla 真实 cure 不会发生,但 broadcast 兜底接受
 *
 * 长流程不可靠时机:
 *   - bot 扔药水 + 喂苹果发生在同一 tick,vanilla ThrownPotionEntity 飞 8 格需 1-2 tick,
 *     喂苹果时 zombie 大概率还没上 weakness → interactMob 内部条件不通过 → 不 setConverting
 *   - 这是已知限制,V5.52 暂接受,等酿造系统 + 多 tick 状态机后再做真实 cure
 */
public final class CureZombieVillagerTrigger implements AchievementTrigger {

	public static final CureZombieVillagerTrigger INSTANCE = new CureZombieVillagerTrigger();
	private static final String ADV_ID = "story/cure_zombie_villager";

	private CureZombieVillagerTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{60_000L, 240_000L}; } // 1~4 分钟(稀有成就慢节奏)

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 主世界(下界没僵尸村民)
		if (player.getEntityWorld().getRegistryKey() != World.OVERWORLD) return false;
		PlayerInventory inv = player.getInventory();
		// 两样必备物资
		if (!TriggerUtil.hasItem(inv, Items.SPLASH_POTION)) return false;
		return TriggerUtil.hasItem(inv, Items.GOLDEN_APPLE);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		// 8 格内找成年僵尸村民
		ZombieVillagerEntity target = findNearbyZombieVillager(player);
		if (target == null) return false;

		double distSq = player.squaredDistanceTo(target);
		if (distSq > 9.0) {
			// 远了先派路任务走过去
			personality.taskTarget = target.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks
			return false;
		}

		PlayerInventory inv = player.getInventory();

		// Step 1: 朝僵尸村民扔药水
		int potionSlot = TriggerUtil.findItemSlot(inv, Items.SPLASH_POTION);
		if (potionSlot == -1) return false;
		if (potionSlot >= 9) {
			TriggerUtil.swapToHotbar(player, potionSlot, 0);
			potionSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, potionSlot);
		// 朝僵尸村民头顶上方一点(投掷物落点)
		Vec3d throwTarget = target.getEyePos().add(0, 1.0, 0);
		TriggerUtil.facePoint(player, throwTarget);
		PacketHelper.useItem(player, Hand.MAIN_HAND);

		// Step 2: 切金苹果 + 喂(同 tick 内,vanilla 真实 cure 不一定成立 — 见类头注释)
		int appleSlot = TriggerUtil.findItemSlot(inv, Items.GOLDEN_APPLE);
		if (appleSlot == -1) {
			// 没苹果就只 broadcast 扔药水阶段 — 兜底广播
			return true;
		}
		if (appleSlot >= 9) {
			TriggerUtil.swapToHotbar(player, appleSlot, 1); // hotbar 1 避免覆盖药水槽 0
			appleSlot = 1;
		}
		PacketHelper.setSelectedSlot(player, appleSlot);
		TriggerUtil.facePoint(player, target.getEyePos());
		target.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);

		// V5.52: 走完投药 + 喂苹果动作链 → Registry broadcastVanillaGrant 兜底广播 Zombie Doctor。
		//   vanilla 真实 cure 在 5 分钟后 ZombieVillagerEntity.finishConversion 时 fire criterion;
		//   broadcastVanillaGrant 强行 grant 当下 — 已 cure 时是 no-op,未 cure 时强行打钩,
		//   语义略激进但对齐项目惯例(同 BreedAnimals/HotStuff/TameAnimal)。
		return true;
	}

	private static ZombieVillagerEntity findNearbyZombieVillager(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(8.0);
		List<ZombieVillagerEntity> targets = player.getEntityWorld().getEntitiesByClass(
			ZombieVillagerEntity.class, box, e -> e.isAlive() && !e.isBaby());
		if (targets.isEmpty()) return null;
		ZombieVillagerEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (ZombieVillagerEntity z : targets) {
			double dsq = player.squaredDistanceTo(z);
			if (dsq < bestSq) { bestSq = dsq; best = z; }
		}
		return best;
	}
}
