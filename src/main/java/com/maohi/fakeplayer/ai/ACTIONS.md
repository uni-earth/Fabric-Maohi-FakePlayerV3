# ai/ 动作清单(给写 Phase 决策的人看)

**这份文档解决的问题**:加新 Phase 或改决策时,不知道现成有哪些动作能调用,只能去翻代码。

## 怎么用

1. Phase 类(`ai/phase/PhaseXxx.java`)**只做决策**:选目标 + 设 `Personality.currentTask` + 设 `taskTarget`。
2. 真正干活的动作分两路触发:
   - **VPM 按 TaskType 派发**:`VirtualPlayerManager.tickWorldInteraction` 看 `currentTask` 调到对应模块(挖矿/砍树/探索/吃饭/合成 等)。
   - **每 tick 无条件触发**:VPM 在 `tickSurvivalAndProgression` 等钩子里直接调用的模块(战斗反射 / 自动放火把 / 自动换装 / 拾取 / 成就触发器)。
3. Phase 决策时**不需要**直接调下面的动作函数。**只在 Phase 决策里需要查询"现在能不能"或者"附近有没有"时**才用工具方法(比如 `CraftingBehavior.findCraftingTable`、`PathfindingNavigation.getSafeTopY`)。

下面按动作主题分组,每条 = `[文件:行号] 方法签名 — 干什么 / 什么时候被触发`。

---

## 1. 移动 / 寻路 / 物理

| 动作 | 入口 | 说明 |
|---|---|---|
| 走 + 跳 + 绕坑 + 游泳 + 开门 + 卡死自救 | [MovementController.java:125](MovementController.java#L125) `doSmartMove(p, target, moveStep, noisePhaseYaw, noisePhasePitch)` | **位移总入口**。VPM `manageLoop` 每 tick 调用。内置:1 格跳坑、水里 swim-up、门/栅栏门 interactBlock、blocked → A* 重算、sink_guard 远征 teleport、stuck 3 级阶梯(blacklist→teleport→kick) |
| 噪声时间步进 | [MovementController.java:59](MovementController.java#L59) `tickNoise()` | 每 tick 调一次,给视角抖动用 |
| A* 寻路 | [PathfindingNavigation.java:218](PathfindingNavigation.java#L218) `findPath(world, start, goal)` | 2048 节点上限。`doSmartMove` 内部已经用了,Phase 不用直接调 |
| 取地表 y(防 cave) | [PathfindingNavigation.java:88](PathfindingNavigation.java#L88) `getSafeTopY(world, x, z, fallbackY)` | **常用**。`setExplore` 选 target 必须用,否则 target 锚 cave 高度会导致死循环 |
| spawn 安全 y | [PathfindingNavigation.java:108](PathfindingNavigation.java#L108) `getSafeSpawnY(world, x, z, fallbackY)` | spawn 用,比 `getSafeTopY` 严(避水/避岩浆) |
| 前方有危险吗 | [PathfindingNavigation.java:123](PathfindingNavigation.java#L123) `isDangerAhead(world, pos)` | 落差/岩浆/火。**Phase setExplore 选第一步时建议查一下** |
| 这格能走吗 | [PathfindingNavigation.java:187](PathfindingNavigation.java#L187) `isWalkable(world, pos)` | 头顶空 + 脚下固体 + 当前格不是固体 |
| chunk 加载好了吗 | [PathfindingNavigation.java:171](PathfindingNavigation.java#L171) `isChunkFullyLoaded(world, pos)` | 未加载就别推物理,否则误判 air → 自由落体 |

**Phase 想"派 bot 走过去某个点"**:不要自己写位移,只设 `currentTask = EXPLORING` + `taskTarget = pos`,VPM 会调 `doSmartMove`。

---

## 2. 放方块(状态机,跨 tick)

所有放方块动作都是 **3 阶段状态机**(切槽 → 等 150-300ms → 交互 → 等 200-400ms → 切回原槽),由 VPM 每 tick 推进。Phase **不调用**,只确保 `currentTask` 进入触发态(`MINING`/`EXPLORING` 等)。

| 动作 | 入口 | 触发条件 |
|---|---|---|
| 放火把 | [BlockPlacer.java:50](BlockPlacer.java#L50) `tryPlaceTorch(p, pers)` | currentTask=MINING/EXPLORING + 亮度 < 7 + 包里有火把,5% 概率/tick |
| 放工作台 | [BlockPlacer.java:200](BlockPlacer.java#L200) `tryPlaceCraftingTable(p, pers)` | 由 `CraftingBehavior` 链路触发 |
| 放熔炉 | [BlockPlacer.java:517](BlockPlacer.java#L517) `tryPlaceFurnace(p, pers)` | 由 `CraftingBehavior` 链路触发 |

**Phase 想"让 bot 在这放方块"**:目前没有通用 `placeBlock(pos, item)` 入口 — 全是特定方块的状态机。如果新阶段要放新方块(比如末影箱),需要在 BlockPlacer 加新方法 + 在 Personality 加状态字段。

---

## 3. 生存(吃饭 / 喝药 / 拉弓 / AFK)

| 动作 | 入口 | 说明 |
|---|---|---|
| 吃饭/喝治疗药水 | [EatingBehavior.java:31](EatingBehavior.java#L31) `handleSurvival(p, pers)` | HP<30%→药水;HP<max-2 或饥饿<10→食物。状态机(`pers.isEating` + `eatingTicks=32`)。VPM 每 tick 调 |
| 远程攻击拉弓 | [EatingBehavior.java:90](EatingBehavior.java#L90) `tryRangedAttack(p, pers, distSq)` | 距离 5~15 格 + 包里有弓。拉满 20-30 tick 后由 `tickBowRelease` 自动松 |
| 释放弓 | [EatingBehavior.java:114](EatingBehavior.java#L114) `tickBowRelease(p, pers)` | VPM 每 tick 调,到时自动 release |
| AFK 状态机 | [AFKManager.java:30](AFKManager.java#L30) `tick(p, pers, uuid, tickNow, scheduleMsg)` | 长期无动作时进入 AFK,返回 true 表示当前在 AFK |

---

## 4. 战斗 / PVP

| 动作 | 入口 | 说明 |
|---|---|---|
| 战斗反射(扫敌+持盾+换武器+攻击+苦力怕逃跑) | [CombatReflex.java:46](CombatReflex.java#L46) `executeCombatLogic(p)` | 半径 12 格扫敌对实体,返回 true 表示正在逃跑(寻路应让位) |
| 假人间切磋 PVP | [PvpSparring.java:41](PvpSparring.java#L41) `tickSparring(p, pers, tickNow)` | 15 分钟冷却,只在 IDLE/EXPLORING 触发,10% 概率/20tick |
| 找村庄定居 | [VillageDefender.java:27](VillageDefender.java#L27) `tryFindHomeVillage(p, pers)` | 扫附近村庄 |
| 加入村庄保卫战 | [VillageDefender.java:60](VillageDefender.java#L60) `tryParticipateRaid(p, pers)` | 检测 raid 进行中就过去 |

---

## 5. 合成 / 烧炼(状态机,跨 tick)

| 动作 | 入口 | 说明 |
|---|---|---|
| 自动合成石器链 | [CraftingBehavior.java:63](CraftingBehavior.java#L63) `autoCraftStoneTools(p)` | log→plank→stick→wooden_pickaxe→stone_*。VPM `tickSurvivalAndProgression` 调 |
| 自动升级工具 | [CraftingBehavior.java:166](CraftingBehavior.java#L166) `autoUpgradeTools(p)` | 铁/钻级 |
| 推进合成状态机 | [CraftingBehavior.java:201](CraftingBehavior.java#L201) `tickCrafting(p, pers)` | VPM 每 tick 调,推 ClickSlot 包序列 |
| 找附近工作台 | [CraftingBehavior.java:568](CraftingBehavior.java#L568) `findCraftingTable(p, radius)` | **Phase 决策用**(STONE_TOOL 子状态判断 "near workbench" 就靠它) |
| 自动烧矿石 | [SmeltingBehavior.java:59](SmeltingBehavior.java#L59) `autoSmeltOres(p)` | 包里有矿石就找附近熔炉 |
| 推进烧炼状态机 | [SmeltingBehavior.java:97](SmeltingBehavior.java#L97) `tickSmelting(p, pers)` | VPM 每 tick 调 |

---

## 6. 装备 / 拾取 / 背包

| 动作 | 入口 | 说明 |
|---|---|---|
| 拾取附近物品+经验球(节奏式) | [ActionSimulator.java:27](ActionSimulator.java#L27) `simulateEntityInteraction(p)` | 8 格半径,30% 概率/tick 拾物,经验球 100%。VPM 每 tick 调 |
| 全力拾取(PICKUP_DROP) | [ActionSimulator.java:86](ActionSimulator.java#L86) `pickupAllNearbyDrops(p)` | 12 格半径,5 件/tick。mine_done 后 3s 专用 |
| 拾后换装 | [LootTracker.java:33](LootTracker.java#L33) `tryAutoEquipFromInventory(p)` | 比对背包里更好的武器/护甲并穿上 |
| 按任务切工具 | [EquipmentBehavior.java:34](EquipmentBehavior.java#L34) `autoSwitchTool(p, currentTask)` | MINING→镐,WOODCUTTING→斧,HUNTING→剑 |
| 自动穿护甲 | [EquipmentBehavior.java:94](EquipmentBehavior.java#L94) `autoEquipArmor(p)` | 包里有更好的护甲就换上 |
| 清理垃圾(扔泥土等) | [InventorySimulator.java:181](InventorySimulator.java#L181) `cleanupJunk(p)` | 高级 bot 才会扔低价值物 |
| 整理背包(OCD 模拟) | [InventorySimulator.java:220](InventorySimulator.java#L220) `simulateInventoryOCD(p, pers)` | 真人偶尔会整理,低频触发 |
| 初始注入合理 loot | [InventorySimulator.java:57](InventorySimulator.java#L57) `injectRealisticLoot(p, pers)` | spawn 时按 GrowthPhase 给一些合理物品 |

---

## 7. 拟人小动作(蹲/挥手/转头/告示牌/审美)

| 动作 | 入口 | 说明 |
|---|---|---|
| Idle 时随机交互 | [ActionSimulator.java:221](ActionSimulator.java#L221) `simulateIdleInteraction(p)` | 0.5% 概率/tick。开门 / 蹲下 / 空挥 / 转头 / 丢物 五选一 |
| 与真人对视+蹲起回礼 | [ActionSimulator.java:303](ActionSimulator.java#L303) `interactWithRealPlayer(fake, real)` | 6 格内有真人时,40% 概率回望,30% 蹲起回礼 |
| 偶尔放告示牌留言 | [ActionSimulator.java:334](ActionSimulator.java#L334) `tryPlaceRandomSign(p)` | 极低频率(1/2000),留"我在过"痕迹 |
| 审美建造(填苦力怕坑) | [ActionSimulator.java:368](ActionSimulator.java#L368) `tickAestheticBuilding(p, pers)` | 0.2% 概率/tick,持续 5-10s 修地形 |
| 挖错偏移 | [ActionSimulator.java:200](ActionSimulator.java#L200) `maybeMistakeDig(intendedPos)` | 3% 概率挖错相邻方块,真人画像 |

---

## 8. 拟人参数(给视角/操作生成"像真人"的数值)

| 入口 | 说明 |
|---|---|
| [BehavioralDistributionValidator.java:29](BehavioralDistributionValidator.java#L29) `getAlignedValue(mean, std)` | 高斯随机值 |
| [BehavioralDistributionValidator.java:42](BehavioralDistributionValidator.java#L42) `getAlignedActionMultiplier()` | 操作速度系数 |
| [BehavioralDistributionValidator.java:49](BehavioralDistributionValidator.java#L49) `getAlignedReactionDelay()` | 反应延迟 tick |
| [BehavioralDistributionValidator.java:57](BehavioralDistributionValidator.java#L57) `getAlignedRotationLerp()` | 视角 lerp 系数 |

---

## 9. 成就系统(自动触发器)

完全自动,Phase **不需要**碰。`TriggerRegistry.tickAll(p, pers)` 每 tick 跑所有触发器:

- [trigger/AdventuringTimeTrigger.java](trigger/AdventuringTimeTrigger.java) — 探索不同生物群系
- [trigger/BlazeRodTrigger.java](trigger/BlazeRodTrigger.java) — 杀烈焰人拿棒
- [trigger/BreedAnimalsTrigger.java](trigger/BreedAnimalsTrigger.java) — 繁殖动物
- [trigger/EnchantItemTrigger.java](trigger/EnchantItemTrigger.java) — 附魔
- [trigger/EyeSpyTrigger.java](trigger/EyeSpyTrigger.java) — 投掷末影之眼
- [trigger/FormObsidianTrigger.java](trigger/FormObsidianTrigger.java) — 黑曜石生成
- [trigger/HotStuffTrigger.java](trigger/HotStuffTrigger.java) — 桶装岩浆
- [trigger/KillMobTrigger.java](trigger/KillMobTrigger.java) — 击杀怪物
- [trigger/PlantSeedTrigger.java](trigger/PlantSeedTrigger.java) — 种庄稼
- [trigger/SleepInBedTrigger.java](trigger/SleepInBedTrigger.java) — 床上睡觉
- [trigger/AchievementTrigger.java](trigger/AchievementTrigger.java) — 接口
- [trigger/TriggerUtil.java](trigger/TriggerUtil.java) — 工具(`findItemSlot`/`hasItem`/`swapToHotbar`/`facePoint`)

| 同步真实成就 | [AchievementSimulator.java:69](AchievementSimulator.java#L69) `syncFromVanilla(server, p, pers)` | vanilla 颁发后同步进 Personality |
| 查询成就 | [AchievementSimulator.java:338](AchievementSimulator.java#L338) `hasUnlocked(pers, advId)` | Phase 决策用("拿没拿过钻石"等) |
| 信标任务 | [BeaconQuest.java:36](BeaconQuest.java#L36) `tickBeaconQuest(p, pers)` | EndGame 阶段才用 |

---

## 10. Phase 决策时常用的查询

只列**给 Phase 写决策时直接用**的查询函数(不是动作,只读):

```java
// 查"我现在在哪种地形 / 该选什么方向走"
PathfindingNavigation.getSafeTopY(world, x, z, fallback)   // 选 EXPLORING target 必用
PathfindingNavigation.isDangerAhead(world, pos)            // 第一步是不是坑/岩浆
PathfindingNavigation.isWalkable(world, pos)               // 这格能站吗

// 查"附近有没有 X"
CraftingBehavior.findCraftingTable(p, radius)              // 找工作台
TriggerUtil.hasItem(inv, item)                             // 背包有没有某物
TriggerUtil.findItemSlot(inv, item)                        // 找物品在哪个槽

// 查"成就拿过了吗 / 能去下一阶段了吗"
AchievementSimulator.hasUnlocked(pers, advId)
AchievementSimulator.canGrantDiamondAchievement(pers)
PhaseDiamondAge.isDiamondOre(state)
PhaseNether.hasMaterialsForPortal(p)
```

---

## 怎么加新动作

### 一次性动作(IMMEDIATE,本 tick 跑完)

直接在主题相关的类里加 `public static` 方法,比如想加"喝牛奶解毒":

```java
// 加到 EatingBehavior.java
public static boolean tryDrinkMilk(ServerPlayerEntity p, Personality pers) {
    if (!p.hasStatusEffect(...)) return false;
    int slot = TriggerUtil.findItemSlot(p.getInventory(), Items.MILK_BUCKET);
    if (slot == -1) return false;
    // ... 切槽、useItem、记 cooldown
    return true;
}
```

VPM `tickSurvivalAndProgression` 加一行调用,Phase 不动。

### 多 tick 状态机动作(MULTI_TICK)

参考 `BlockPlacer.tryPlaceTorch` 模式:
1. 在 `Personality` 加状态字段(`xxxStage` + `xxxAtTick` + `xxxRestoreAtTick`)
2. 在主题类里加两个方法:`tryXxx`(触发入口,stage=0 时检查条件)+ `advanceXxxStateMachine`(stage>0 时推进)
3. VPM 每 tick 调 `tryXxx`,它自己判断要触发还是要推进

### 需要 Phase 触发的动作(OBSTACLE / 任务级)

走 TaskType 派发链:
1. 加新 `TaskType` 枚举值(如果现有不够用)
2. Phase 决策时设 `currentTask = NEW_TYPE` + `taskTarget`
3. VPM `tickWorldInteraction` 加 case 调到具体实现

---

## 不要做的事

- ❌ Phase 里**不要直接调位移**(`MovementInputHelper.sendMovement`、`p.refreshPositionAndAngles`)— 全部走 `doSmartMove`
- ❌ Phase 里**不要写状态机**(`pers.xxxStage++` 那一套)— 状态机归动作模块所有,Phase 只切 task
- ❌ **不要在多个 Phase 里复制相同动作代码** — 提到 `ai/` 下的主题类共用
- ❌ **不要给"查询"方法挂副作用** — `findCraftingTable` 不该改 Personality,只读
