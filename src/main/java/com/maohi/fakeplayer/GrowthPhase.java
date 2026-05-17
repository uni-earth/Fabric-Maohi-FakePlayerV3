package com.maohi.fakeplayer;

/**
 * 成长阶段:按背包装备 + 维度自动判断,无需手动设置(单向棘轮:只升不降)
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 */
public enum GrowthPhase {
	WOOD_AGE,     // 木器时代:无任何镐子,目标砍树合木镐(V5.44 新增,补齐 vanilla 玩家入门档)
	STONE_AGE,    // 石器时代:有木/石镐,目标铁矿
	IRON_AGE,     // 铁器时代:有铁器,目标钻石
	DIAMOND_AGE,  // 钻石时代:有钻石装备,目标下界
	NETHER,       // 下界远征:有下界合金,目标末影龙
	ENDGAME       // 挑战末影龙
}
