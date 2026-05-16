package com.maohi.fakeplayer;

import java.util.UUID;

/**
 * 假人持久化数据载体(Gson 序列化)
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 * V5.27:位置/维度字段移除 —— 改由 vanilla <uuid>.dat 单一权威存储,
 *        本类只剩"假人特有"信息(name / personality / playtime)
 *
 * Gson 按字段名序列化 → 老存档若残留 x/y/z/dimension 字段会被静默忽略,
 * 不再迁移(已经在 V5.27 装好的实例,vanilla auto-save 会接管)。
 */
public class SavedPlayer {
	public volatile UUID uuid;
	public volatile String name;
	public volatile Personality personality;
	public volatile long totalPlaytime;
	/**
	 * @deprecated P23: 这个外层 List 从未被任何写入路径写过(grep 全工程仅有读 + 兜底 new 空)。
	 *   成就记录单一权威已经迁移到 {@link Personality#unlockedAdvancements} Set。
	 *   保留字段是为了 Gson 反序列化旧存档时不报错;VPM.start() 一次性把它的内容并入
	 *   personality.unlockedAdvancements 后清空(参见 VirtualPlayerManager.loadData 后兜底块)。
	 *   不要再用这个字段做任何业务判断。
	 */
	@Deprecated
	public java.util.List<String> unlockedAdvancements = new java.util.ArrayList<>();

	public SavedPlayer() {} // 2.78 Gson 兼容构造

	public SavedPlayer(UUID u, String n, Personality p) {
		this.uuid = u;
		this.name = n;
		this.personality = p;
	}
}
