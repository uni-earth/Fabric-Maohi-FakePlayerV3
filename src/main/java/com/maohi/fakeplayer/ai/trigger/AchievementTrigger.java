package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 成就触发器接口 (V5.22 新增)
 *
 * 设计目标:让"非阶段必修"的可选成就刷取行为彻底解耦,每个成就一个文件。
 * 每个 Trigger 自治三件事:阶段/前置判定、真实游戏行为发包、声明自己的检查节奏范围。
 * 节流分两层:
 *   1. Registry 层——按"下次检查时间戳"跳过还没到的;每个假人独立相位
 *   2. Trigger 层——tryTrigger 内部可再做 nextInt 细粒度随机,配合场景条件
 *
 * 错峰机制:Registry 按 personality.triggerPhaseSeed + per-trigger nextInterval()
 * 给每个 (假人 × trigger) 独立排期,确保 8 个假人同秒上线也不会挤在同一 tick roll。
 *
 * 与 phase/ 的边界:
 *   - phase/    阶段必修 + 任务分配,不发包刷成就
 *   - trigger/  可选成就的主动刷取,每个独立周期性 tick
 *   - 长线状态机(BeaconQuest / VillageDefender)保持在 ai/ 根,跨 tick 状态不适合本接口
 *
 * 触发方式:bot 在 tryTrigger 内部走 vanilla 协议执行真实行为(交互方块 / use 物品 /
 *          喂动物等)。理想上 vanilla advancement 系统会因此自动 fire criterion 广播,
 *          但 P23 实测 vanilla criterion 对 fake player 不可靠 —— 项目自己在
 *          AchievementSimulator.broadcastVanillaGrant 路径上补 grant 触发广播。
 *
 *   何时补 grant 由 tryTrigger 的返回值决定:
 *     true  = bot 真把所有动作做完了(走完整条 trigger 序列) → Registry 自动调 broadcastVanillaGrant
 *     false = 前置不满足提前 return(没物品 / 没场景 / 还在赶路) → 不 grant 不广播
 */
public interface AchievementTrigger {

	/**
	 * 对应的 vanilla 成就 ID(去掉 "minecraft:" 前缀)。
	 * 用于已解锁跳过 + 日志/调试。一个 Trigger 通常只关联一个成就。
	 */
	String advancementId();

	/**
	 * 阶段/前置条件判定。返回 false 时 TriggerRegistry 直接跳过 tryTrigger。
	 * 例如:扔末影之眼只在 NETHER 之后,睡觉只在主世界黑夜。
	 */
	boolean shouldRun(ServerPlayerEntity player, Personality personality);

	/**
	 * 下次检查的间隔范围(毫秒,[min, max] 均匀采样)。
	 * Registry 每次 tryTrigger 调用后会用这个值给该 (假人 × trigger) 重排时间表,
	 * 本方法返回的范围越大,同一批假人之间错峰越分散。
	 *
	 * 示例:
	 *   Hot Stuff  → [5_000, 30_000]  约 5~30 秒 roll 一次
	 *   Sleep Bed  → [2_000, 15_000]  黑夜短窗口要密一些
	 *   Eye Spy    → [120_000, 600_000] 后期成就,2~10 分钟 roll 一次
	 */
	long[] nextIntervalRange();

	/**
	 * 实际触发逻辑。实现方应自行处理:
	 *   - 检查物品/方块/实体是否存在(不满足 → return false)
	 *   - 真实发包(PacketHelper.interactBlock / useItem / attackEntity)
	 *   - 完整执行所有动作链 → return true(Registry 据此调 broadcastVanillaGrant)
	 *   - 失败安静返回,不抛异常,不写日志
	 * 细粒度概率(如"走进场景也只 20% 真去做")放在这里。
	 *
	 * @return true 表示 bot 完整执行了动作链(应广播);false 表示前置不满足提前退出(别广播)
	 */
	boolean tryTrigger(ServerPlayerEntity player, Personality personality);
}

