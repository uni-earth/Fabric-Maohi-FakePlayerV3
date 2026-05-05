package com.maohi.fakeplayer;

import java.util.UUID;

/**
 * 假人持久化数据载体(Gson 序列化)
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 *
 * Gson 按字段名序列化 → 提取后 JSON 结构不变,旧存档兼容。
 */
public class SavedPlayer {
	public volatile UUID uuid;
	public volatile String name;
	public volatile Personality personality;
	public volatile long totalPlaytime;
	public volatile double x, y, z;
	public volatile String dimension;
	public java.util.List<String> unlockedAdvancements = new java.util.ArrayList<>();

	public SavedPlayer() {} // 2.78 Gson 兼容构造

	public SavedPlayer(UUID u, String n, Personality p) {
		this.uuid = u;
		this.name = n;
		this.personality = p;
	}
}
