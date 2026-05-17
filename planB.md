# Plan C: STRIP_MINE 子阶段 — 让 STONE_AGE 假人主动挖到铁矿层

> **目标**:让持有石镐的 STONE_STABLE 假人在累计若干 cycle 仍无 iron 后,自动挖梯下降到 Y≈15 strip-mine 找铁矿,获 iron 后自然棘轮升 IRON_AGE,异常时紧急上浮。
>
> **场景**:Peaceful + 平原/沙漠/海岛 bot 长期(>14h) 卡 STONE_AGE 的死锁。A/B 方案在该场景效果≈0,C 是唯一能跑通的路径。
>
> **预算**:~250 LOC + 1 个新 helper 类 + Personality/TaskType/PhaseStoneAge 增量改动。

---

## 0. 背景:死锁链路

### 现状

[PhaseStoneAge.java:233-240](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L233-L240) STONE_STABLE 分支:
```java
if (ThreadLocalRandom.current().nextInt(100) < 60) {
    assignChopTree(player, personality, ctx);   // 60% 砍树
} else {
    assignMineStone(player, personality, ctx);  // 40% 挖石(只挖 stone/cobble/deepslate)
}
```

`assignMineStone` 走 `ctx.findStone` (type="stone"),[BlockScanCache.java:106-109](src/main/java/com/maohi/fakeplayer/tick/BlockScanCache.java#L106-L109) 该类型 yMin=-2 / yMax=+2,bot Y=63 时只扫 Y=61~65。
[PhaseStoneAge.java:346-358](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L346-L358) `scanDownForStone` 白名单为 `stone/cobblestone/deepslate/cobbled_deepslate`,**iron_ore 物理排除**。

[VPM.java:1528](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1528) IRON_AGE 触发条件:`id.startsWith("iron_") || iron_ingot || raw_iron`。
[VPM.java:1463-1466](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1463) 单向棘轮:phase 只升不降。

`ctx.findOre` 仅在 [PhaseIronAge.java:48](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseIronAge.java#L48) 被调用 → 鸡生蛋蛋生鸡。

### 实证(2026-05-16 跑测)

| Bot | uptime | phase | 成就 | 背包 | 诊断 |
|---|---|---|---|---|---|
| AxolotlFriend35 | 15h57m | [STONE] EXPLORING | 5(含 upgrade_tools) | L0 P0 S0 C0 Pk:- | 曾有石镐,丢光后流浪 |
| GhostGhost | 14h27m | [STONE] EXPLORING | 4 | L0 P0 S0 C0 Pk:- | 同款破产 |
| Ryan123 | 1h14m | [STONE] WOODCUTTING | 1 | L0 P2 S4 C0 Pk:- | 新生 bot |

3/3 bot **永生 STONE_AGE**,Peaceful 模式 14h+ 0 iron achievement = 实证。

---

## 1. 总体设计

### 三段式状态机

```
STONE_STABLE (持石镐)
    │ stoneStableCyclesNoIron >= TRIGGER (默认 5 个 cycle ≈ 10~15 min)
    │ + 当前 chunk 已加载 + bot HP > 14
    ▼
┌─────────────────────────────────────────────────────────┐
│ STRIP_MINE_DESCEND                                      │
│   挖 2×1 楼梯下降,每下 3 格挖 1 格平台休息              │
│   每 5 格放 1 个火把(若有,Peaceful 可省)                │
│   pickaxe 耐久 < 30 → 紧急 ASCEND                        │
│   遇 lava/water → 放 cobble 封堵 → 改向                  │
│   到达 targetY (15±2) → 转 LAYER                         │
└─────────────────────────────────────────────────────────┘
    │ Y ≤ 17 && 落脚平台稳定
    ▼
┌─────────────────────────────────────────────────────────┐
│ STRIP_MINE_LAYER                                        │
│   2×1 横向挖隧道(每 tick 挖前方 1 格 + 头顶 1 格)         │
│   每 8 格分叉一次(主隧道 + ±90° 支洞)                    │
│   ctx.findOre 半径 8 格扫 → 命中立刻挖目标块              │
│   背包 iron_*/raw_iron 出现 → ratchet IRON_AGE → 退出     │
│   隧道总长 > MAX_TUNNEL_LEN (默认 64) → 转 ASCEND         │
│   pickaxe 耐久 < 20 / HP < 10 → 紧急 ASCEND              │
└─────────────────────────────────────────────────────────┘
    │ 获 iron / 超长 / 低耐久 / 低血
    ▼
┌─────────────────────────────────────────────────────────┐
│ STRIP_MINE_ASCEND                                       │
│   方案 A:回溯 stripMineStartPos (如果路径还在)           │
│   方案 B:就地竖井挖上去(粗暴但可靠) — 实现优先 B          │
│   每挖 1 格上升 1 格 + 脚下放 cobble (1.21 placement)    │
│   到达 Y >= startY - 3 / 露天 → 退出 STRIP_MINE          │
│   置 stripMineCooldownUntil = now + 30min(失败/成功都设) │
└─────────────────────────────────────────────────────────┘
```

### 关键判定

| 判定 | 阈值 | 出处 |
|---|---|---|
| 触发 cycle 数 | `STRIP_MINE_TRIGGER_CYCLES = 5` | 新常量 |
| 目标 Y | `TARGET_MINING_Y = 15` (vanilla 1.18+ iron peak) | 新常量 |
| 最大隧道长度 | `MAX_TUNNEL_LEN = 64` 格 | 新常量 |
| 紧急上浮 HP 阈值 | `EMERGENCY_HP = 10.0f` | 新常量 |
| 紧急上浮 pickaxe 耐久阈值 | 30 (DESCEND) / 20 (LAYER) | 新常量 |
| 冷却时长(避免反复 strip-mine) | 30 min | 新常量 |
| 底线 Y(防钻基岩) | -56 (留 8 格 buffer) | 新常量 |

---

## 2. 文件改动清单

### 2.1 新文件:`StripMineBehavior.java`

`src/main/java/com/maohi/fakeplayer/ai/StripMineBehavior.java` (~150 LOC)

职责:
- 持有三个静态方法 `tickDescend / tickLayer / tickAscend`,VPM 主循环按当前 stripMineState 分发
- 内部封装挖块/放块协议包(复用 `PacketHelper.attackBlock` + `BlockPlacer.placeAt`)
- 危险检测复用 `PathfindingNavigation.isHazardousBlock` + `getFallDepth`
- iron_ore 探测复用 `BlockScanCache.findNearestBlock(world, pos, 8, "ore", uuid)`

公开 API:
```java
public static void tick(ServerPlayerEntity player, Personality pers);      // 主入口
public static void abort(Personality pers, String reason);                  // 紧急退出
public static boolean isActive(Personality pers);                            // VPM 主循环判定
```

### 2.2 修改 `Personality.java`

[Personality.java:30](src/main/java/com/maohi/fakeplayer/Personality.java#L30) 附近新增字段:
```java
public PhaseStoneAge.SubPhase stripMineState = null;   // null = 不在 strip-mine
public BlockPos stripMineStartPos = null;              // 入口坐标(用于回溯)
public int stripMineStartY = 64;
public int stoneStableCyclesNoIron = 0;                // STONE_STABLE 累计 cycle
public long stripMineCooldownUntil = 0L;               // wall-clock ms
public int stripMineTunnelLen = 0;                     // 当前 LAYER 累计长度
public Direction stripMineFacing = Direction.NORTH;    // LAYER 当前推进方向
public int stripMineConsecutiveFails = 0;              // 用于 abort 判定
```

NBT 序列化:`stripMineState` 跨 session 不持久化(进游戏重新评估),其余 cooldown/stoneStableCyclesNoIron 需要持久化。

### 2.3 修改 `TaskType.java`

[TaskType.java](src/main/java/com/maohi/fakeplayer/TaskType.java) 新增:
```java
STRIP_MINE   // 单一类型,内部状态机由 Personality.stripMineState 区分
```

### 2.4 修改 `PhaseStoneAge.java`

**(a) SubPhase enum** [PhaseStoneAge.java:73](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L73)
```java
private enum SubPhase {
    WOOD_START, WOOD_CRAFT, STONE_START, STONE_TOOL, STONE_STABLE,
    STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND
}
```

**(b) classify()** [PhaseStoneAge.java:119-128](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L119-L128) 顶部插入:
```java
if (personality.stripMineState != null) return personality.stripMineState;
```

**(c) STONE_STABLE 分支** [PhaseStoneAge.java:233-240](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L233-L240):
```java
case STONE_STABLE -> {
    long now = System.currentTimeMillis();
    boolean cooldownActive = personality.stripMineCooldownUntil > now;
    if (!cooldownActive) {
        personality.stoneStableCyclesNoIron++;
        if (personality.stoneStableCyclesNoIron >= STRIP_MINE_TRIGGER_CYCLES
                && player.getHealth() > 14.0f
                && hasReadyPickaxe(d)) {
            personality.stripMineState = SubPhase.STRIP_MINE_DESCEND;
            personality.stripMineStartPos = player.getBlockPos().toImmutable();
            personality.stripMineStartY = player.getBlockY();
            personality.stripMineTunnelLen = 0;
            personality.currentTask = TaskType.STRIP_MINE;
            TaskLogger.log(player, "stripmine_enter",
                "startY", personality.stripMineStartY, "cycles", personality.stoneStableCyclesNoIron);
            return;
        }
    }
    if (ThreadLocalRandom.current().nextInt(100) < 60) {
        assignChopTree(player, personality, ctx);
    } else {
        assignMineStone(player, personality, ctx);
    }
}
```

**(d) 三个 STRIP_MINE_* 分支**全部 delegate:
```java
case STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND -> {
    personality.currentTask = TaskType.STRIP_MINE;
    personality.taskTarget = player.getBlockPos();  // dummy,实际由 StripMineBehavior 驱动
    personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
}
```

### 2.5 修改 `VirtualPlayerManager.java`

主 tick 循环([VPM.java](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java) `manageLoop` 内,在 task 分发处)新增:
```java
if (StripMineBehavior.isActive(pers)) {
    StripMineBehavior.tick(player, pers);
    return;  // 跳过常规 task 派发(MovementController/CraftingBehavior 等)
}
```

放置位置:在 `MovementController.doSmartMove` 之前(strip-mine 自己驱动位移,不走寻路)。

### 2.6 修改 `MaohiCommands.java`

[MaohiCommands.java:380](src/main/java/com/maohi/MaohiCommands.java#L380) 状态行 `EXPLORING` 之类的 task 后加 strip-mine 子状态,例如 `[STONE] STRIP_MINE_LAYER y=14 len=24/64`。

---

## 3. StripMineBehavior 实现细节

### 3.1 tickDescend

```
每 N tick 触发一次(限速,约 5~10 tick/动作 模拟真人节奏):
  1. 当前脚下方块 = b1, b1 下方 = b2
  2. 朝向 (默认 NORTH) 前方挖 2 格高(头顶 + 脚下)→ 走过去 → 现在站在脚下被挖处
     等价于"挖 1 格阶梯下楼",vanilla 楼梯感
  3. 检查脚下:
       - 是 lava 流体 → place cobble 封堵 + abort("lava_floor")
       - getFallDepth > 3 → place cobble 落地 + 继续
       - 是 ore (iron_ore/coal_ore/copper_ore/diamond_ore/etc.) → 顺手挖,刷新 stoneStableCyclesNoIron=0
  4. Y 已 ≤ TARGET_MINING_Y → 转 LAYER,记录入口为 stripMineLayerStartPos
  5. Y ≤ BEDROCK_BUFFER_Y (-56) → abort("near_bedrock")
  6. pickaxe 耐久 < 30 → 转 ASCEND
  7. 周围 4 格内有 lava 流(任一面) → 退一步 + abort("lava_adjacent")
```

挖块协议复用 [PacketHelper.attackBlock](src/main/java/com/maohi/fakeplayer/network/PacketHelper.java),放块复用 BlockPlacer.placeAt。

### 3.2 tickLayer

```
每 N tick:
  1. ctx.findOre 半径 8 格扫 → 命中:
       - 若距离 ≤ 4 格 → 直接挖
       - 若距离 4~8 格 → 沿当前隧道挖向最近接近点
  2. 没扫到:沿当前 facing 挖前方 2 格高
  3. 每 8 格(tunnelLen % 8 == 0)随机选 ±90° 一个方向开支洞
       支洞独立 facing,长度 16 格后回主隧道
  4. tunnelLen >= MAX_TUNNEL_LEN → 转 ASCEND
  5. 背包 detectPhase() → IRON_AGE → abort("got_iron"),清空 stripMineState,
     设 cooldown = 0(成功不需要冷却)
  6. pickaxe 耐久 < 20 / HP < 10 → 转 ASCEND
  7. 检测 hazard:面前是 lava/water → place cobble 封堵 + 转 90° 继续(累计 fails+1,3 次 → ASCEND)
```

### 3.3 tickAscend

方案 B(竖井):
```
每 N tick:
  1. 头顶方块 → 挖
  2. 脚下放一个 cobble (从 inventory 找 1 个 cobblestone,没的话挖周围墙补)
  3. bot.y += 1 (vanilla 物理跳跃 + 脚下方块支撑,自然上升)
  4. 头顶 = 露天(world.isSkyVisible) 或 Y >= startY - 3 → 退出 STRIP_MINE
       清空 stripMineState
       置 stripMineCooldownUntil = now + 30min
       清空 stoneStableCyclesNoIron
  5. 没有 cobble + 周围墙也挖不到(全 air) → 紧急放置任何固体方块,真没货 → kick or 站原地等死(罕见)
  6. 超过 200 ticks(10s)没上升 1 格 → fail 累加,3 次 → 放弃(stripMineState=null,bot 卡死自然由 stuck_teleport 救)
```

### 3.4 abort 路径

`StripMineBehavior.abort(pers, reason)` 行为:
- 清空 `stripMineState`,`stripMineTunnelLen=0`
- 设 `stripMineCooldownUntil = now + 30min`
- `TaskLogger.log("stripmine_abort", "reason", reason, "y", currentY)`
- 触发紧急上浮:除非 reason 是 "got_iron",否则强制走 ASCEND 子流程

---

## 4. 边界与安全

| 风险 | 缓解 |
|---|---|
| Lava 在隧道前方 | `isHazardousBlock` 检测 + place cobble 封堵 + 转向 |
| Lava 在脚下(掉进去) | 背包 1 桶水自救?**MVP 不做**,直接 abort + cobble 封堵 |
| Bedrock 防钻 | Y > -56 才允许下挖 |
| 石镐爆 | 耐久监控,DESCEND 时<30 / LAYER 时<20 立刻 ASCEND |
| Bot 死亡 | vanilla 死亡走 respawn,Personality 持久化 cooldown,30min 内不再 strip-mine |
| Chunk 未加载 | `isChunkFullyLoaded` 检测,失败则 IDLE 1s 等加载 |
| 多 bot 撞同一隧道 | `BlockScanCache` 已有 UUID 排除 + 入口坐标加 5 格缓冲(每个 bot 起点偏移随机角度) |
| 紧急上浮卡住 | 200 tick 不上升 → 3 次 fail → 放弃,交给 stuck_teleport stage 2 救 |
| 反作弊误判 | 挖块/放块都走 PacketHelper 标准路径,与真人 1:1。节奏控制 5~10 tick/动作,不超过真人极限 |
| Peaceful 切回 Normal 危险 | LAYER 子流程光照<7 时尝试放火把(若有);没火把不点光,接受怪生成风险 |

---

## 5. 配置项([MaohiConfig.java](src/main/java/com/maohi/MaohiConfig.java) 新增)

```java
public boolean enableStripMine = false;          // 默认关闭,稳定后开启
public int stripMineTriggerCycles = 5;           // 触发的 STONE_STABLE cycle 数
public int stripMineTargetY = 15;                // strip 层 Y
public int stripMineMaxTunnelLen = 64;           // 单次 LAYER 最大长度
public int stripMineCooldownMinutes = 30;        // 退出后冷却
public boolean stripMineRequireTorches = false;  // 是否需要火把才下去(Peaceful 关掉)
```

---

## 6. 实施步骤(顺序执行)

| 步 | 内容 | 文件 | 估时 |
|---|---|---|---|
| 1 | 加 enum 值 + Personality 字段 + TaskType.STRIP_MINE | PhaseStoneAge / Personality / TaskType | 15 min |
| 2 | classify() 顶部 stripMineState 优先返回 | PhaseStoneAge:119 | 5 min |
| 3 | STONE_STABLE 触发逻辑(cycle 计数 + 进 STRIP_MINE_DESCEND) | PhaseStoneAge:233 | 20 min |
| 4 | 新建 StripMineBehavior 骨架(三个 tick 方法 + abort) | StripMineBehavior.java | 30 min |
| 5 | 实现 tickDescend(挖梯 + lava 检测 + 耐久检测) | StripMineBehavior | 60 min |
| 6 | 实现 tickLayer(横向挖 + ore 优先 + 分叉) | StripMineBehavior | 90 min |
| 7 | 实现 tickAscend(竖井 + 放 cobble) | StripMineBehavior | 45 min |
| 8 | VPM 主循环挂入 StripMineBehavior.tick | VirtualPlayerManager | 10 min |
| 9 | 状态行加 strip-mine 子状态显示 | MaohiCommands:380 | 15 min |
| 10 | 配置项 + 默认 false 开关 | MaohiConfig | 10 min |
| 11 | NBT 序列化 cooldown/stoneStableCyclesNoIron | Personality save/load | 20 min |
| 12 | 跑测:1 bot in 平原 Peaceful + 配 enableStripMine=true,观察 30 min | 跑测 | 30 min |
| 13 | 跑测:5 bot 同图,验证不撞隧道 | 跑测 | 30 min |
| 14 | 跑测:1 bot 全程 lava 边缘,验证 abort + 上浮 | 跑测 | 30 min |
| **总计** | **~6 小时**(含跑测) | | |

---

## 7. 验收标准

- [ ] 1 个 Peaceful + 平原 bot,启用 STRIP_MINE,**60 分钟内**触发 IRON_AGE
- [ ] strip-mine 过程 0 死亡(lava/掉落都被 abort 拦)
- [ ] 5 bot 并发不撞同一隧道(各自独立 startPos + facing)
- [ ] strip-mine 中途若被反作弊判异常(theoretical),回归后能从 cooldown 状态恢复正常
- [ ] 老 bot(已经 STONE_STABLE 多 cycle)进游戏后下个 reassign 周期内触发 STRIP_MINE
- [ ] cooldown 期间 bot 正常走 STONE_STABLE 60/40 分布,不阻塞砍树/挖石
- [ ] 触发后 [STONE] 显示 `STRIP_MINE_DESCEND` / `STRIP_MINE_LAYER y=14 len=12/64` / `STRIP_MINE_ASCEND` 子状态

---

## 8. 不做的事(MVP 范围外)

- 火把摆放(Peaceful 不需要,Normal 模式下让玩家手动给火把,或后续 V5.45 加)
- 水桶自救岩浆(MVP 直接 abort + 封堵)
- 真实的回溯路径(走 ASCEND 方案 B 竖井,简单可靠)
- 多层 strip-mine(只挖 Y=15 一层)
- 自动制作熔炉/冶炼 raw_iron(交给已有 [CraftingBehavior.java:96](src/main/java/com/maohi/fakeplayer/ai/CraftingBehavior.java#L96) W2S 收尾链)
- 收集挖出来的 cobble/ore 掉落物(交给已有 PICKUP_DROP 机制 [TaskType.java:16](src/main/java/com/maohi/fakeplayer/TaskType.java#L16))
- 主动找熔炉/煤炭 — 进 IRON_AGE 后 PhaseIronAge 自然推

---

## 9. 后续(V5.45+ 可能演进)

- MVP 跑通 + 1 周稳定后,默认开启 enableStripMine=true
- 加 IRON_HUNT 主子阶段(现有 STONE_STABLE 60/40 改成 50/30/20:砍树/挖石/strip-mine 触发评估)
- 真实 cave-spelunk 模式(找天然洞穴入口下钻,比 strip-mine 更像真人)
- 多 bot 协作:同 region 多 bot 共享 strip-mine 隧道,A 挖 B 跟在后面捡(高级)

---

## 附:Plan A / Plan B 备忘(若不走 C)

**Plan A** (已分析,跳过) — 1 行扩 [PhaseStoneAge.java:353](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L353) `scanDownForStone` 接受 ore。Peaceful 平原 bot 几乎无效,因 ore 不在 ±2 Y 扫描窗。

**Plan B** (已分析,跳过) — 改 STONE_STABLE 30% 走 findOre + 同时调宽 BlockScanCache "ore" 类型 yMax 到 +60。能命中山体/河岸裸露 ore,平原 bot 仍受限。

C 是 Peaceful 平原场景下唯一有效路径。
