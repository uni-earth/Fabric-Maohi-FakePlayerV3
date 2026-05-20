package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.ai.EatingBehavior;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Take Aim: 用弓射出一支箭 (V5.51 新增 / V5.53 重新分类为 A 类)
 *
 * vanilla 路径:BowItem 拉满弓 → onStoppedUsing 创建 ArrowEntity 投出 →
 *            Criteria.SHOT_CROSSBOW.trigger fire(1.21+ 弓弩共用同一 trigger 类型)→
 *            [Take Aim] (advancement: adventure/shoot_arrow)
 *
 * V5.53 分类纠正:vanilla `adventure/shoot_arrow` 的 criterion 只要求"用弓射出投射物",
 *   **不要求击中**。bot 当前 release 弓时 vanilla onStoppedUsing 自动创建 ArrowEntity 并
 *   fire criterion → 真广播(A 类语义)。Registry 兜底 broadcastVanillaGrant 仍调,但
 *   grantCriterion 已 done 时是 no-op,无副作用。
 *
 * 复用现成接口:
 *   - EatingBehavior.tryRangedAttack 自带"切弓槽 → useItem → 设 isUsingBow + bowReleaseTick"完整链
 *   - VPM.tickSurvivalAndProgression 每 tick 调 EatingBehavior.tickBowRelease 自动释放
 *   - 弓只看 hotbar(0-8 槽),trigger 入口先把弓 swap 到 hotbar 0,保证 tryRangedAttack 能找到
 *
 * 触发节奏:bot 攒了弓+箭就值得 roll,前期没箭直接 shouldRun false,不浪费 tick
 */
public final class ShootArrowTrigger implements AchievementTrigger {

	public static final ShootArrowTrigger INSTANCE = new ShootArrowTrigger();
	private static final String ADV_ID = "adventure/shoot_arrow";

	private ShootArrowTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{15_000L, 60_000L}; } // 15~60s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 已经在拉弓中(其它路径或上次本 trigger 触发) → 等释放,别打断
		if (personality.isUsingBow) return false;
		PlayerInventory inv = player.getInventory();
		if (!TriggerUtil.hasItem(inv, Items.BOW)) return false;
		// 任意箭种都接受,vanilla BowItem 内部也是 ARROW/TIPPED_ARROW/SPECTRAL_ARROW 一视同仁
		return TriggerUtil.hasItem(inv, Items.ARROW)
			|| TriggerUtil.hasItem(inv, Items.TIPPED_ARROW)
			|| TriggerUtil.hasItem(inv, Items.SPECTRAL_ARROW);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		// 把弓 swap 到 hotbar 0(EatingBehavior.tryRangedAttack 内部只扫 0-8 槽)
		PlayerInventory inv = player.getInventory();
		int bowSlot = TriggerUtil.findItemSlot(inv, Items.BOW);
		if (bowSlot == -1) return false;
		if (bowSlot >= 9) {
			TriggerUtil.swapToHotbar(player, bowSlot, 0);
		}

		// 5~15 格内找弱敌(distSq 范围 [25, 225] 对齐 tryRangedAttack 的内部阈值)
		HostileEntity target = findShootableHostile(player);
		if (target == null) return false;
		double distSq = player.squaredDistanceTo(target);
		if (distSq < 25.0 || distSq > 225.0) return false;

		// 朝目标的眼睛(避免 pitch=0 射到怪脚下被高度差吃掉)
		TriggerUtil.facePoint(player, target.getEyePos());

		// 调现成 tryRangedAttack:setSelectedSlot(bow) + useItem(MAIN_HAND) + 设 isUsingBow + bowReleaseTick。
		//   注:EatingBehavior 第二个参数实际语义是 distSq([25, 225] 是 5~15 格的平方),
		//   不是欧式距离;传 sqrt 会被永远拒绝。这里按真实语义传 distSq。
		if (!EatingBehavior.tryRangedAttack(player, personality, distSq)) return false;

		// V5.53 A 类:tickBowRelease 在 25~30 tick 后调 releaseUseItem,vanilla BowItem.onStoppedUsing
		//   会创建 ArrowEntity 投出 + fire Criteria.SHOT_CROSSBOW.trigger → adventure/shoot_arrow
		//   criterion 真 fire → 真广播。Registry broadcastVanillaGrant 兜底是 no-op(vanilla 已 done)。
		return true;
	}

	/** 5~16 格内任意 hostile(蜘蛛/僵尸/骷髅/苦力怕等),弓箭安全距离不挑食 */
	private static HostileEntity findShootableHostile(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(16.0);
		List<HostileEntity> mobs = player.getEntityWorld().getEntitiesByClass(
			HostileEntity.class, box,
			e -> e.isAlive() && !e.isInvisible() && isShootable(e));
		if (mobs.isEmpty()) return null;
		return mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
	}

	/** 排除"无法被弓箭有效命中"的怪(末影龙体型异常,凋零飞行高,跳过) */
	private static boolean isShootable(HostileEntity mob) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE
			.getId(mob.getType()).getPath();
		// 弓正面射肉敌对都算 — 苦力怕也接受(距离 5~15 远够安全)
		return !id.equals("ender_dragon") && !id.equals("wither")
			&& !id.equals("warden") && !id.equals("elder_guardian");
	}
}
