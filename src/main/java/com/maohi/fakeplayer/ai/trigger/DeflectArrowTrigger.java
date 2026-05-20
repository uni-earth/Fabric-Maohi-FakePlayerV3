package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * Not Today, Thank You: 用盾挡一支箭 (V5.52 新增)
 *
 * vanilla criterion: deflect_arrow / projectile_deflected — 玩家在使用盾(useItem 状态)且
 *   朝向来袭箭时,箭命中盾被弹开 → fire deflect_arrow criterion
 * advancement: story/deflect_arrow
 *
 * 实现策略:
 *   1. 持盾(主手或副手) — 副手盾才是 vanilla 真人正常画像,但 bot 没有 off-hand 装备逻辑,
 *      简化为"任一手有盾"即触发
 *   2. 触发条件 A:8 格内有 ArrowEntity / SpectralArrowEntity / TridentEntity 朝 bot 飞 →
 *      vanilla 真实击中 → criterion 自然 fire(若 useItem 状态成立)
 *   3. 触发条件 B(兜底):12 格内有骷髅/Skeleton 类(它们大概率会射箭) → 提前举盾 ~10 tick →
 *      bot 不一定真挡到,但项目惯例:做了动作链就广播
 *
 * 不引入新 Personality 字段:用 PacketHelper.useItem(hand) 后,vanilla server 内部维护 useItem
 *   状态;只举几 tick 后通过 releaseUseItem 释放。trigger 节流后下一轮 release 由"已不举盾"
 *   状态自然取消,不需要状态机管理。
 *
 * 不和 isUsingBow 冲突:盾用 OFF_HAND 或 MAIN_HAND(非弓),与弓的 MAIN_HAND useItem 路径区分;
 *   shouldRun 检查 !isUsingBow 避免和 ShootArrow trigger 撞 hand。
 */
public final class DeflectArrowTrigger implements AchievementTrigger {

	public static final DeflectArrowTrigger INSTANCE = new DeflectArrowTrigger();
	private static final String ADV_ID = "story/deflect_arrow";

	private DeflectArrowTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{20_000L, 90_000L}; } // 20~90s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 已经在拉弓 → 不抢手
		if (personality.isUsingBow) return false;
		// 必须有盾(主手或副手)
		return hasShield(player);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		// 优先 A:扫附近来袭箭(已经飞出的) — 真实最易触发 criterion 的场景
		boolean incomingArrow = hasIncomingArrow(player);
		// 兜底 B:12 格内有骷髅 — 大概率会射箭,提前举盾
		boolean nearbySkeleton = !incomingArrow && hasNearbySkeleton(player);
		if (!incomingArrow && !nearbySkeleton) return false;

		// 决定用哪只手举盾(优先 OFF_HAND — 真人标准画像;OFF_HAND 没盾就 MAIN_HAND)
		Hand shieldHand = findShieldHand(player);
		if (shieldHand == null) return false;

		// 朝威胁方向(箭来的方向 / 骷髅所在方向)
		HostileEntity nearestSkel = findNearestSkeleton(player);
		if (nearestSkel != null) {
			TriggerUtil.facePoint(player, nearestSkel.getEyePos());
		}

		// 切对应槽位(主手才需要 setSelectedSlot,off-hand 永远是固定槽)
		if (shieldHand == Hand.MAIN_HAND) {
			PlayerInventory inv = player.getInventory();
			int shieldSlot = TriggerUtil.findItemSlot(inv, Items.SHIELD);
			if (shieldSlot >= 0 && shieldSlot < 9) {
				PacketHelper.setSelectedSlot(player, shieldSlot);
			} else if (shieldSlot >= 9) {
				TriggerUtil.swapToHotbar(player, shieldSlot, 0);
				PacketHelper.setSelectedSlot(player, 0);
			}
		}

		// vanilla useItem(SHIELD):进入 blocking state(setCurrentHand) → 持续到 releaseUseItem
		//   vanilla 在 PlayerEntity.damage 内部检查 player.isBlocking() + 来袭方向 → 触发 deflect。
		//   bot 不主动 release,vanilla 会在被打到时自然停止 use,或 trigger 下一轮"已不威胁"时
		//   通过另一个 useItem 状态被覆盖(项目可接受)。
		PacketHelper.useItem(player, shieldHand);

		// V5.52: 完整执行举盾动作链 → Registry broadcastVanillaGrant 兜底广播 Not Today, Thank You。
		//   incomingArrow=true 时 vanilla 自身大概率 fire 真实 criterion;nearbySkeleton-only 兜底
		//   是项目"做了动作就广播"惯例(同 ShootArrow / OlBetsy)。
		return true;
	}

	private static boolean hasShield(ServerPlayerEntity player) {
		// 副手
		ItemStack off = player.getOffHandStack();
		if (off.isOf(Items.SHIELD)) return true;
		// 主手 / hotbar
		PlayerInventory inv = player.getInventory();
		return TriggerUtil.hasItem(inv, Items.SHIELD);
	}

	private static Hand findShieldHand(ServerPlayerEntity player) {
		if (player.getOffHandStack().isOf(Items.SHIELD)) return Hand.OFF_HAND;
		if (TriggerUtil.hasItem(player.getInventory(), Items.SHIELD)) return Hand.MAIN_HAND;
		return null;
	}

	/** 检测 8 格内有 ArrowEntity 朝 bot 方向飞(velocity 朝 bot) */
	private static boolean hasIncomingArrow(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(8.0);
		List<PersistentProjectileEntity> arrows = player.getEntityWorld().getEntitiesByClass(
			PersistentProjectileEntity.class, box,
			e -> e.isAlive() && e.getOwner() != player); // 排除 bot 自己射的箭
		if (arrows.isEmpty()) return false;
		// 简化:只要 8 格内有箭就算"威胁",不严格检查 velocity 朝向 — 触发频率低
		return true;
	}

	/** 12 格内成年 SkeletonEntity / StrayEntity(它们大概率会射箭) */
	private static boolean hasNearbySkeleton(ServerPlayerEntity player) {
		return findNearestSkeleton(player) != null;
	}

	private static HostileEntity findNearestSkeleton(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(12.0);
		List<HostileEntity> mobs = player.getEntityWorld().getEntitiesByClass(
			HostileEntity.class, box,
			e -> e.isAlive() && !e.isBaby() && !e.isInvisible() && isRangedSkeleton(e));
		if (mobs.isEmpty()) return null;
		HostileEntity best = null;
		double bestSq = Double.MAX_VALUE;
		for (HostileEntity m : mobs) {
			double dsq = player.squaredDistanceTo(m);
			if (dsq < bestSq) { bestSq = dsq; best = m; }
		}
		return best;
	}

	private static boolean isRangedSkeleton(HostileEntity mob) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE
			.getId(mob.getType()).getPath();
		// skeleton / stray / wither_skeleton(下界,会近战不射箭,但保险)/ pillager(用弩)
		return id.equals("skeleton") || id.equals("stray") || id.equals("pillager");
	}
}
