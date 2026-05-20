package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ol' Betsy: 用弩射任意目标 (V5.51 新增,补 WOOD-IRON 阶段)
 *
 * vanilla 路径:CrossbowItem.use → 25 tick charge → onStoppedUsing 时 setCharged(true) →
 *            下次 use 触发射出 → shot_crossbow criterion → [Ol' Betsy]
 *            (advancement: adventure/ol_betsy)
 *
 * 单阶段实现(对齐项目"做动作链就广播"兜底口径):
 *   1. 切弩到 hotbar 0
 *   2. PacketHelper.useItem(MAIN_HAND) — vanilla 内部:未 charged → 开始 charge / 已 charged → 立刻射出
 *   3. 复用 personality.isUsingBow 状态机,bowReleaseTick = now + 25
 *      VPM.tickSurvivalAndProgression 调 tickBowRelease 会在 25 tick 后 releaseUseItem
 *      → vanilla onStoppedUsing 完成 charge(setCharged true)
 *   4. trigger 返回 true → Registry broadcastVanillaGrant 兜底广播 Ol' Betsy
 *
 * "射出"vs"使用"语义:vanilla 严格意义要"射出",这里"开始 charge"就广播。
 *   对齐 BreedAnimalsTrigger/HotStuffTrigger 的项目惯例 — 做了动作链就算。
 *   bot 长期跑下来弩会自然 charge → 下一轮 trigger 调用同一 useItem 会真射出,
 *   vanilla criterion 也会自然 fire 第二次(重复 grant 时 alreadyDone=true,no-op),无副作用。
 *
 * 复用 isUsingBow:Personality 没单独的 isUsingCrossbow 字段,弓和弩"拉远程武器"
 *   语义一致 — 同时只可能在一种远程武器中(主手只能拿一个)。
 */
public final class OlBetsyTrigger implements AchievementTrigger {

	public static final OlBetsyTrigger INSTANCE = new OlBetsyTrigger();
	private static final String ADV_ID = "adventure/ol_betsy";

	private OlBetsyTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{20_000L, 80_000L}; } // 20~80s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 已经在拉远程武器中(弓/弩) → 等释放
		if (personality.isUsingBow) return false;
		PlayerInventory inv = player.getInventory();
		if (!TriggerUtil.hasItem(inv, Items.CROSSBOW)) return false;
		// 弩接受任意箭种(同弓)
		return TriggerUtil.hasItem(inv, Items.ARROW)
			|| TriggerUtil.hasItem(inv, Items.TIPPED_ARROW)
			|| TriggerUtil.hasItem(inv, Items.SPECTRAL_ARROW);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		PlayerInventory inv = player.getInventory();
		int crossbowSlot = TriggerUtil.findItemSlot(inv, Items.CROSSBOW);
		if (crossbowSlot == -1) return false;

		// 5~15 格内找弱敌(同 ShootArrow 距离窗口)
		HostileEntity target = findShootableHostile(player);
		if (target == null) return false;
		double distSq = player.squaredDistanceTo(target);
		if (distSq < 25.0 || distSq > 225.0) return false;

		// 切弩到 hotbar 0
		if (crossbowSlot >= 9) {
			TriggerUtil.swapToHotbar(player, crossbowSlot, 0);
			crossbowSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, crossbowSlot);

		// 朝目标眼睛
		TriggerUtil.facePoint(player, target.getEyePos());

		// useItem: vanilla CrossbowItem.use 内部 — 未 charged 开始 charge / 已 charged 立刻射出
		PacketHelper.useItem(player, Hand.MAIN_HAND);

		// 复用 isUsingBow 状态机,25 tick 后 VPM 自动 releaseUseItem(完成 charge)
		long now = player.getEntityWorld().getServer().getTicks();
		personality.isUsingBow = true;
		personality.bowReleaseTick = now + 25 + ThreadLocalRandom.current().nextInt(6); // 25~30 tick

		// V5.53: charge 释放后 2 tick 自动触发第二次 useItem 完成 shoot,
		//   让 vanilla shot_crossbow criterion 真 fire — ol_betsy 从 B 类(强行打钩)升级为 A 类(真 shoot 触发)。
		//   时序:t=0 trigger 调 useItem(charge) → t=25~30 tickBowRelease 调 releaseUseItem(setCharged true)
		//        → t=27~32 tickCrossbowAutoShoot 调 useItem(shoot) → vanilla 真射箭 + criterion fire
		personality.crossbowAutoShootAtTick = personality.bowReleaseTick + 2;

		// V5.51: 完整执行 charge 动作链 → Registry broadcastVanillaGrant 兜底广播 Ol' Betsy。
		// V5.53: 兜底现在多余(vanilla shot_crossbow criterion 真 fire 后 grantCriterion 是 no-op),
		//   但保留以防 bot 切槽/丢弩/被打断导致 shoot 没发生 — 兜底仍然能让广播发出。
		return true;
	}

	/** 5~16 格内找任意 hostile(同 ShootArrow 的 isShootable 排除规则) */
	private static HostileEntity findShootableHostile(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(16.0);
		List<HostileEntity> mobs = player.getEntityWorld().getEntitiesByClass(
			HostileEntity.class, box,
			e -> e.isAlive() && !e.isInvisible() && isShootable(e));
		if (mobs.isEmpty()) return null;
		return mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
	}

	private static boolean isShootable(HostileEntity mob) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE
			.getId(mob.getType()).getPath();
		return !id.equals("ender_dragon") && !id.equals("wither")
			&& !id.equals("warden") && !id.equals("elder_guardian");
	}
}
