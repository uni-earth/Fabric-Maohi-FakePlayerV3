package com.maohi.fakeplayer;

/**
 * 假人当前执行的任务类型
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 */
public enum TaskType {
	IDLE,
	EXPLORING,
	WOODCUTTING,
	MINING,
	COLLECTING,
	AFK,
	RECONNECTING,
	HUNTING,
	CRAFTING
}
