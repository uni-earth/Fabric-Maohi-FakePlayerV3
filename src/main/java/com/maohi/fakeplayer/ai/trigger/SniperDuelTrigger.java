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
 * Sniper Duel: 50 格以外用弓杀骷髅 (V5.51 新增)
 *
 * vanilla criterion: player_killed_entity {
 *   killing_blow: {tags: ["arrows"]},
 *   entity: {type: "skeleton", distance: {horizontal: {min: 50.0}}}
 * }
 * advancement: adventure/sniper_duel
 *
 * 不走 EatingBehavior.tryRangedAttack — 那个内部限 distSq ∈ [25, 225](5~15 格),
 *   sniper_duel 要 ≥ 50 格(distSq ≥ 2500),需要自己拉弓。状态机仍复用 isUsingBow,
 *   25~35 tick 后 VPM.tickBowRelease 自动 releaseUseItem。
 *
 * 兜底语义:trigger 完成"拉弓动作链"就 return true → broadcastVanillaGrant 强行 grant。
 *   vanilla criterion 要"真的杀死",bot 不一定射中,但同 BreedAnimals/HotStuff 的项目惯例:
 *   做了动作链就视为达成。
 */
public final class SniperDuelTrigger implements AchievementTrigger {

	public static final SniperDuelTrigger INSTANCE = new SniperDuelTrigger();
	private static final String ADV_ID = "adventure/sniper_duel";

	private SniperDuelTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{60_000L, 240_000L}; } // 1~4 分钟(成就稀有,慢节奏 roll)

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		if (personality.isUsingBow) return false;
		PlayerInventory inv = player.getInventory();
		if (!TriggerUtil.hasItem(inv, Items.BOW)) return false;
		return TriggerUtil.hasItem(inv, Items.ARROW)
			|| TriggerUtil.hasItem(inv, Items.TIPPED_ARROW)
			|| TriggerUtil.hasItem(inv, Items.SPECTRAL_ARROW);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		PlayerInventory inv = player.getInventory();
		int bowSlot = TriggerUtil.findItemSlot(inv, Items.BOW);
		if (bowSlot == -1) return false;
		if (bowSlot >= 9) {
			TriggerUtil.swapToHotbar(player, bowSlot, 0);
			bowSlot = 0;
		}

		// 50~80 格扫骷髅类(skeleton / stray) — 距离上限 80 防止"看不见的远怪"被选(发包后 vanilla
		//   也会拒绝);下限 50 是 vanilla criterion 硬阈值
		HostileEntity target = findFarSkeleton(player);
		if (target == null) return false;

		// 朝目标 — pitch 稍微高一点补偿箭的抛物线落差(distSq 越大补偿越多,简化为固定 +5 度)
		TriggerUtil.facePoint(player, target.getEyePos());
		player.setPitch(player.getPitch() - 5.0f);

		PacketHelper.setSelectedSlot(player, bowSlot);
		PacketHelper.useItem(player, Hand.MAIN_HAND);

		// 拉弓 25~35 tick(同 EatingBehavior 节奏) — VPM tickBowRelease 自动释放
		personality.isUsingBow = true;
		personality.bowReleaseTick = player.getEntityWorld().getServer().getTicks()
			+ 25 + ThreadLocalRandom.current().nextInt(11);

		// V5.51: 完整执行远程拉弓动作链 → Registry broadcastVanillaGrant 兜底
		//   bot 不一定真射中 50+ 格的骷髅,但 trigger 1~4 分钟 roll 一次,
		//   长会话内累积达成画像与"真人偶尔练神射手"吻合。
		return true;
	}

	/** 50~80 格内找 skeleton 类(不要 wither_skeleton 在下界 / skeleton_horse 被动) */
	private static HostileEntity findFarSkeleton(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(80.0);
		List<HostileEntity> mobs = player.getEntityWorld().getEntitiesByClass(
			HostileEntity.class, box,
			e -> e.isAlive() && !e.isBaby() && !e.isInvisible() && isSkeletonType(e));
		if (mobs.isEmpty()) return null;
		// 取距离最远的(更接近 50 格 criterion 阈值,且远怪不会立刻反击)
		HostileEntity farthest = null;
		double bestSq = 0;
		for (HostileEntity m : mobs) {
			double dsq = player.squaredDistanceTo(m);
			// 50~80 格 = distSq ∈ [2500, 6400]
			if (dsq >= 2500.0 && dsq <= 6400.0 && dsq > bestSq) {
				bestSq = dsq;
				farthest = m;
			}
		}
		return farthest;
	}

	/** vanilla advancement 接受 skeleton 系(stray 也是 skeleton 类型,wither_skeleton 不算) */
	private static boolean isSkeletonType(HostileEntity mob) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE
			.getId(mob.getType()).getPath();
		return id.equals("skeleton") || id.equals("stray");
	}
}
