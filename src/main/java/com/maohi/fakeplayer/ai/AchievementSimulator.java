package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.GrowthPhase;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 成就观察器 (V5.18 - 重构)
 *
 * 设计理念变更（V5.18）：
 *   旧版本（V3-V5.17）会按"在线时长 + 概率"伪造成就广播。但假人现在已经能做真实任务
 *   （挖矿、合成、冶炼、建造传送门），vanilla 的 advancement 系统会基于
 *   inventory_changed / entity_summoned / location 等真实事件自动触发广播——
 *   伪造路径反而会造成"广播了但背包没东西"的穿帮。
 *
 * 新版本（V5.18）只保留两个职责：
 *   1. 把 vanilla 已自动触发的成就同步到 personality.unlockedAdvancements
 *      （供 phase 系统、chat 系统、存档系统读取）
 *   2. 提供静态查询方法，让其它模块判断假人是否已解锁某成就
 *
 * P22 重构(实事求是):
 *   旧 syncFromVanilla 在 ADV_SEQUENCE 上硬编码 6 个 advancement ID 一一查 loader,
 *   "story/mine_wood" 在 1.21.11 vanilla advancement registry 中找不到(log 证据:
 *   sync_entry_null id=story/mine_wood 必中,而 story/mine_stone 等同格式都 OK)。
 *   原因可能是 vanilla 改名 / mod 移除 / loader 加载顺序问题,根因无法 100% 锁定。
 *
 *   现在改为全量扫:不再依赖任何硬编码 ID,直接遍历 loader 所有 entry,
 *   只筛 display.isPresent()(过滤 recipe 类 advancement,只统计真实"成就"),
 *   personality 没记过 + isDone() 的就 sync。不管 1.21.11 把 mine_wood 叫什么,
 *   bot 真触发了 vanilla criterion 就抄写,robust to mapping/rename。
 *   副作用:ach 数字会自动覆盖 vanilla 所有 110+ advancement,而非仅 6 档里程碑。
 */
public final class AchievementSimulator {

	private AchievementSimulator() {} // 工具类

	/**
	 * 5 个里程碑成就 ID,用于 phase 系统 / chat 叙事时索引。
	 * 这些"逻辑 ID"是模糊匹配 key:hasUnlocked 接受 endsWith 命中,
	 * 即 vanilla 真名是 "minecraft:story/mine_wood" 也能匹配 "story/mine_wood" 查询。
	 * 不再用于 syncFromVanilla 硬编码循环。
	 */
	public static final String[] ADV_SEQUENCE = {
		"story/mine_wood",
		"story/mine_stone",
		"story/upgrade_tools",
		"story/smelt_iron",
		"story/mine_diamond",
		"nether/obtain_crying_obsidian"
	};

	/**
	 * P22 重构:全量扫 loader,不再依赖 ADV_SEQUENCE 硬编码 ID。
	 *
	 * 工作流:
	 *   1. 反射拿 loader 中所有 AdvancementEntry(兼容多 yarn build method name)
	 *   2. 筛 display.isPresent() — 过滤 recipe 类 advancement,只统计真成就
	 *   3. 每个 personality 没记过 + isDone() 的都 sync + count + log
	 *
	 * 性能:30s 一次 × ~110 个 advancement display check ≈ 3300 操作/30s/bot,
	 *   远低于挖矿/寻路开销;loader 调用走反射但只在 sync 入口一次,够便宜。
	 *
	 * @return 本次新观察到的成就数量
	 */
	public static int syncFromVanilla(MinecraftServer server, ServerPlayerEntity p, Personality personality) {
		if (server == null || p == null || personality == null) return 0;

		java.util.Collection<AdvancementEntry> allAdvs = enumerateLoader(server);
		if (allAdvs == null || allAdvs.isEmpty()) return 0;

		int newlyObserved = 0;
		for (AdvancementEntry entry : allAdvs) {
			String advId = entry.id().toString();

			// 过滤 recipe 类(display=empty):不统计 "minecraft:recipes/..." 这类自动解锁的非成就 advancement
			if (entry.value().display().isEmpty()) continue;

			if (personality.unlockedAdvancements.contains(advId)) continue;
			if (!p.getAdvancementTracker().getProgress(entry).isDone()) continue;

			personality.unlockedAdvancements.add(advId);
			personality.hasUnlockedThisSession = true;
			newlyObserved++;
			com.maohi.fakeplayer.TaskLogger.log(p, "achievement_unlocked", "id", advId);
			com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(p.getUuid());
		}
		return newlyObserved;
	}

	/**
	 * P22 公开入口:让 VPM P11 grant 路径复用同一份反射枚举逻辑。
	 * 内部委派 enumerateLoader,private 实现不变。
	 */
	public static java.util.Collection<AdvancementEntry> enumerateLoaderPublic(MinecraftServer server) {
		return enumerateLoader(server);
	}

	/**
	 * 反射 + cast 拿 loader 所有 advancement,兼容多 yarn build。
	 * 优先 getAdvancements / getAll 几种 method name,
	 * 都失败则 fallback 用 Identifier.of(adv) 一个个查 ADV_SEQUENCE。
	 */
	@SuppressWarnings("unchecked")
	private static java.util.Collection<AdvancementEntry> enumerateLoader(MinecraftServer server) {
		Object loader = server.getAdvancementLoader();
		for (String m : new String[]{"getAdvancements", "getAll"}) {
			try {
				java.lang.reflect.Method method = loader.getClass().getMethod(m);
				Object result = method.invoke(loader);
				if (result instanceof java.util.Collection<?> c) {
					return (java.util.Collection<AdvancementEntry>) c;
				}
				if (result instanceof Iterable<?> it) {
					java.util.List<AdvancementEntry> list = new java.util.ArrayList<>();
					for (Object o : it) {
						if (o instanceof AdvancementEntry e) list.add(e);
					}
					return list;
				}
			} catch (NoSuchMethodException ignored) {
			} catch (Throwable t) {
				// 反射调用本身失败 — 单次 try 下一个 method name
			}
		}
		// fallback:用 ADV_SEQUENCE 硬编码 list 凑一份(可能丢 vanilla 后续新增 advancement,
		// 但 mine_wood/mine_stone/upgrade_tools 6 档里程碑保证能查)
		java.util.List<AdvancementEntry> fallback = new java.util.ArrayList<>();
		for (String id : ADV_SEQUENCE) {
			try {
				AdvancementEntry e = server.getAdvancementLoader().get(Identifier.of(id));
				if (e != null) fallback.add(e);
			} catch (Throwable ignored) {}
		}
		return fallback;
	}

	/**
	 * 查询 personality 是否已解锁某档里程碑。
	 *
	 * P22 重构:从精确匹配改为 "精确 + endsWith 模糊" 二段匹配。
	 *   背景:syncFromVanilla 现在抄写的是 vanilla 真实 ID(如 "minecraft:story/mine_wood"),
	 *   而 phase 系统调用方传入的是逻辑 ID(如 "story/mine_wood"),两者字符串不等。
	 *   精确 contains 永远 miss → phase 跃迁判定永远 false → bot 卡 STONE_AGE。
	 *   endsWith 让 "minecraft:story/mine_wood".endsWith("story/mine_wood") = true,自然命中。
	 */
	public static boolean hasUnlocked(Personality personality, String advId) {
		if (personality == null || advId == null) return false;
		if (personality.unlockedAdvancements.contains(advId)) return true;
		// 模糊匹配:vanilla 真实 ID 通常带 "minecraft:" 前缀,endsWith 覆盖
		for (String stored : personality.unlockedAdvancements) {
			if (stored.endsWith(advId) || stored.endsWith(":" + advId)) return true;
		}
		return false;
	}

	/** 仅用于 phase 系统的钻石阶段判定（保留旧 API 兼容） */
	public static boolean canGrantDiamondAchievement(Personality personality) {
		if (personality == null) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal();
	}
}
