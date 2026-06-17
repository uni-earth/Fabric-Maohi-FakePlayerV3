package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 成长阶段策略接口(V5.20)
 *
 * 每个 GrowthPhase 对应一个实现类(StoneAge / IronAge / DiamondAge / Nether / EnderDragon)。
 * VirtualPlayerManager 通过 Map<GrowthPhase, Phase> 派发,代替之前的 5-case switch。
 *
 * 实现要求:
 *   - 无状态(允许做成单例 INSTANCE)
 *   - 不直接持有 player/personality 引用
 *   - 通过 ctx 拿到查找方块/敌人的回调
 *
 * 文件归属(V5.117):
 *   实现类放 /phase/PhaseWoodAge.java / PhaseStoneAge.java / PhaseIronAge.java ...
 *   跨阶段共享 helper / setter / 常量 / 数据结构 → PhaseUtil
 */
public interface Phase {
	/**
	 * 给假人分配本阶段的合理任务。
	 * @param player 当前假人
	 * @param personality 假人状态
	 * @param ctx 查找方块/敌人的回调集
	 */
	void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx);
}
