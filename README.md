## Maohi FakePlayer V5.22 (Lag Guard Hardening Edition)

**打造终极图灵级拟真假人系统 - 完全对抗机器学习检测**

适用于 Minecraft Fabric 版本 1.21.11
Java 版本：必须是 Java 21
Fabric 配置：依赖 Fabric-API 0.136.0 与 Loader 0.19.2 及以上。

**安装说明：将自行编译产生的 `Maohi.jar` 连同下载好的 `fabric-api.jar` 均放置于服务器的 `mods` 文件夹内。**

---

## 🤖 虚拟玩家拟真系统 (Virtual Player System)

本模块构建了一套具备“数字灵魂”的玩家模拟引擎，在协议层、物理层和社交层实现全维度拟真，其唯一目标是：**在控制台日志、玩家社交、甚至是被击杀后的战利品掉落上，百分之百无限逼近真实的 Minecraft 玩家，杜绝任何"一眼假"的破绽。**

> **一句话总结**：
> 这是一个赋予了假人“数字灵魂”的终极拟真系统：他们不仅具备模拟公网 IP:端口 随机上线、正版皮肤抓取与老玩家记忆回流，更拥有 S 形拟真走位、场景关联型抱怨聊天、自动地下照明、随机驻足看风景以及假人间 PVP 切磋等极具“灵性”的人类行为；在全链路真实发包的保护下，无论是从反作弊后台、控制台日志还是真玩家第一视角观察，均已达到难以分辨的图灵级拟真水准。

### 🌟 核心拟真特性 (Core Realism Features)

#### 1. 全链路真实发包与物理律动
*   **真实 C2S 数据包**：所有假人操作（攻击、挖掘、使用物品、吃东西）均走真实数据包链路。服务端自动派发掉落物和经验，完美通过反作弊抓包检测。
*   **S 形曲线位移**：彻底抛弃僵硬的直线寻路。基于假人独立的 Perlin 噪声种子，在移动中产生自然的侧向漂移，形成人类视角的 S 形走位。
*   **动态网络延迟**：底层心跳包 (Keep-Alive) 天然具备 50ms~200ms 的动态网络延迟抖动，查 Ping 也查不出异常。
*   **驻足看风景与小动作**：假人在跑图时有极低概率停下脚步环视四周；闲置时也会随机触发蹲下、转头、扔物品等无聊操作。

#### 2. 具有情绪与记忆的场景化社交 (V4.4+)
*   **熟人识别与记忆**：假人会记住经常与其互动的玩家。遇到”熟人”时会优先打招呼，并根据互动历史（如是否曾被该玩家杀害）调整态度。
*   **成就联动炫耀**：当假人达成成就时，有 30% 概率在公屏发送类似 “Look at this!” 或 “Finally got it!” 的炫耀消息。
*   **死后沮丧情绪模拟**：假人死后复活的 5 分钟内会进入”沮丧期”，表现为行走速度降低 30%、聊天频率下降，完美模拟玩家跑尸时的郁闷感。
*   **对视与蹲起回礼**：当真玩家盯着假人看或对其按 Shift 时，假人会感知到注视并产生”对视”反应，甚至回以礼貌的蹲起。

#### 3. 进化型 AI 与全链路隐身合成 (V5.1+)
*   **全链路隐身合成**：升级工具不再是瞬间”变出”。假人会寻找（或放置）合成台，**在服务端真实开启 Crafting 窗口状态**，播放挥手动画与合成音效，并在 3-5 秒后完成结算。
*   **职业偏好与熟练度成长**：假人拥有类似 RPG 的挖掘熟练度，干活越多效率越高；且会随机产生职业倾向（如”金牌矿工”），使其 80% 的任务都围绕特定领域展开。
*   **物理跳跃避障 (Physics Jump)**：AI 能够识别 1 格宽的深坑并自动起跳通过，不再会被简单的地形缺陷卡住。
*   **PVP 矢量预判攻击**：战斗中不再死板瞄准当前位置，而是根据目标的运动速度向量，预判其 0.2 秒后的位置进行”提前量”打击。
*   **本能避险机制**：寻路代价函数中集成了火焰、岩浆、岩浆块（烫脚）及高处坠落检测，假人会像真人一样本能地绕开危险区域。


### ⚙️ 业务配置参数 (`mods/server-util.json`)

配置采用三层覆盖机制：`/tmp/maohi.properties`（隐蔽部署层，最高优先）→ `mods/server-util.json`（主业务配置）→ `jar:maohi.properties`（网络/隧道默认值）。

| 参数名 | 默认值 | 描述 |
|--------|--------|------|
| `botEnabled` | `true` | **假人总开关** (true: 开启, false: 禁用并清场) |
| `maxVirtualPlayers` | `10` | 假人最大并发在线容量 |
| `minVirtualPlayers` | `5` | 任何时刻的最少保底在线人数 |
| `sessionMinMinutes` | `120` | 常规会话时长下限（约 98% 假人落在这段，默认 2h） |
| `sessionMaxMinutes` | `240` | 常规会话时长上限（约 98% 假人落在这段，默认 4h） |
| `sessionShortPercent` / `sessionShortMin/MaxMinutes` | `1%` / `45~75` | 短会话段：约 1% 假人一小时内就下线（模拟"上线看看就走"） |
| `sessionLongPercent` / `sessionLongMin/MaxMinutes` | `1%` / `240~600` | 长会话段：约 1% 假人挂 4~10h，上限硬卡 10h 防止露馅 |
| `offlineMinMinutes` | `30` | 假人下线休息最短时长（分钟） |
| `offlineMaxMinutes` | `120` | 假人下线休息最长时长（分钟） |
| `respawnDelayMinSec` | `5` | 假人死亡后复活最短延迟（秒） |
| `respawnDelayMaxSec` | `20` | 假人死亡后复活最长延迟（秒） |
| `explorationRadius` | `500` | 活动范围限制（以出生点为圆心的半径） |
| `maxKnownPlayers` | `100` | 假人名单库最大容量（老玩家记忆池） |
| `nodeUuid` | `UUID` | **服务器唯一指纹**：决定专属假人名单库 |

### ⏱️ 时间常量参数 (`TimingConstants.java`)

| 常量 | 值 | 描述 |
|------|-----|------|
| `GLOBAL_CHAT_COOLDOWN` | 10s | 全局聊天熔断：全服假人消息最少间隔 |
| `FAREWELL_LOCK_DURATION` | 15s | 语义隔离锁：道别后多久不再回应同一玩家 |
| `NEARBY_GREET_COOLDOWN` | 10s | 附近打招呼冷却 |
| `TASK_TIMEOUT_EXPLORE` | 15s | 探索/闲逛任务超时 |
| `TASK_TIMEOUT_WORK` | 10s | 工作（挖矿/砍树）任务超时 |
| `JITTER_MIN_MS` | 2s | 操作抖动延迟下限 |
| `JITTER_MAX_MS` | 15s | 操作抖动延迟上限 |

### 🛠️ 管理命令 (OP 4)

| 命令 | 描述 |
|------|------|
| `/maohi status` | 查看系统运行负载、总开关状态与 AI tick 耗时概况 |
| `/maohi on` | 启用假人系统(按调度策略陆续上线) |
| `/maohi off` | **紧急清场总闸**:置 botEnabled=false 并立即踢出全部假人 |
| `/maohi list` | 深度列表:任务、Ping、维度坐标、在线时长、成就数 |
| `/maohi list <name>` | 单假人详细:阶段/职业/血量/经验/食物/任务目标 + 完整成就 ID 列表 |
| `/maohi spawn <name>` | 指定召唤(异步获取正版皮肤) |
| `/maohi kick <name>` | 踢出指定假人(按真实 disconnect 流程下线) |
| `/maohi reload` | 热重载业务配置 |
| `/maohi metrics` | 性能看板:Tick 平均耗时 + 生成/复活成功失败统计 |

### 🌳 模块化架构树状图 (Architecture Tree)

```text
Fabric-Maohi-FakePlayerV3/
├── src/main/java/com/maohi/
│   ├── Maohi.java 🧩 # 【总入口】连接服务器与假人系统的总开关
│   ├── MaohiConfig.java ✅ # V5.30 已扩展：+debugVirtualTasks 任务调试日志开关 / V5.21 三段会话时长分布
│   ├── MaohiCommands.java ✅ # V5.23 已优化：safeRun 安全网 + /maohi off 真清场 + /maohi list 深度行 + 单假人成就详情
│   │
│   ├── common/ # 📂 【底层工具包】
│   │   ├── HttpUtils.java ✅ # V5.23 已优化：disconnect 路径修复 + Retry-After 解析 + 4MB body 上限
│   │   └── JsonUtils.java 🧩 # 通用 JSON 解析工具
│   │
│   ├── tunnel/ # 📂 【隧道层】(隐蔽部署 / 节点上报相关,保留不动)
│   │   └── TunnelManager.java 🔒 # 隧道生命周期与上报；按要求保留,不在本轮优化范围
│   │
│   ├── fakeplayer/ # 📂 【假人引擎核心】
│   │   ├── VirtualPlayerManager.java ✅ # V5.30 已扩展：任务失败计数 + force_explore 兜底 + 挖矿时序对齐 vanilla(去 miningSkill 倍率)/ V5.22 会话分布/MSPT 背压/断连顺序
│   │   ├── TaskLogger.java ✅ # V5.30 新增：任务系统调试日志器,[MaohiTask] [bot] event k=v 格式,debugVirtualTasks 开关零成本
│   │   ├── TimingConstants.java ✅ # V5.21 清理累计在线时长成就门槛遗留
│   │   ├── PlayerSpawner.java ✅ # V5.22 已修：从 personality.unlockedAdvancements 回流老玩家成就
│   │   ├── ProfileFetcher.java ✅ # V5.23 已优化：自有 daemon 池(并发上限 4) + 原子去重 + 服务器关停 shutdown
│   │   ├── Personality.java ✅ # V5.30 已扩展：+taskFailCount/lastFailedTarget/tablePlace*/lastLoggedPhase / V5.22 triggerPhaseSeed
│   │   ├── SavedPlayer.java 🧩 # Gson 存档载体；当前保持兼容结构
│   │   ├── TaskType.java 🧩 # 任务枚举:IDLE/EXPLORING/MINING/HUNTING/WOODCUTTING/CRAFTING 等
│   │   ├── GrowthPhase.java 🧩 # 成长阶段枚举:STONE/IRON/DIAMOND/NETHER/ENDGAME
│   │   │
│   │   ├── storage/ # 📂 【持久化层】
│   │   │   └── PlayerStorage.java 🧩 # V5.20 已模块化：Gson 序列化 + 原子写入 + 容量裁剪
│   │   │
│   │   ├── tick/ # 📂 【tick 工具层】
│   │   │   └── BlockScanCache.java ✅ # V5.22 已优化：同心壳扫描 + 矿石 Y 深度压缩 + Mutable 降 GC
│   │   │
│   │   ├── network/ # 📂 【防检测网络层】
│   │   │   ├── FakeClientConnection.java 🧩 # EmbeddedChannel 网络面具；VPM 已修 closeChannel 调用顺序
│   │   │   ├── PingPongHandler.java ✅ # V5.22 已优化：per-player baseline + AR(1) 自相关 + 对数正态右偏 + 重传尖峰
│   │   │   ├── PacketHelper.java ✅ # V5.22 已优化：attackEntity 单次扣血 + processBlockBreaking 节流批处理(1.21.11 sequence)
│   │   │   ├── InventoryActionHelper.java 🧩 # V5.27+：真实 ClickSlot 协议(PICKUP/QUICK_MOVE/SWAP) + screen-to-inv 槽位映射
│   │   │   └── MovementInputHelper.java 🧩 # V5.28+：PlayerInputC2SPacket 直写 forward/sideways/jump,替代字段写入
│   │   │
│   │   ├── ai/ # 📂 【假人行为 AI】
│   │   │   ├── BehavioralDistributionValidator.java ✅ # V5.6 行为分布对齐：Box-Muller 正态分布
│   │   │   ├── MovementController.java ✅ # V5.22 已优化：A* 失败冷却 / 终点阈值 / 噪声取模 / 早期免看风景
│   │   │   ├── PathfindingNavigation.java ✅ # V5.23 已优化：5s 路径缓存(8 格分桶) + 邻居 cost 区分(平地/跳跃/跨越) + MAX_STEPS 64
│   │   │   ├── EatingBehavior.java ✅ # V5.22 已优化：进食/喝药/拉弓状态机互斥 + 持续时长/release 时序合规
│   │   │   ├── EquipmentBehavior.java ✅ # V5.23 已优化：autoEquipArmor 改用 equipStack 走原版装备链路
│   │   │   ├── CraftingBehavior.java ✅ # V5.30 W2S 已扩展：早期生存链 plank/table/stick/wood pickaxe + 背包内 2×2 合成(table 鸡蛋问题) / V5.28.2 真协议化
│   │   │   ├── SmeltingBehavior.java ✅ # V5.30 已埋点：smelt_start / smelt_done / smelt_fail 调试日志 / V5.20 真协议化冶炼
│   │   │   ├── CombatReflex.java ✅ # V5.22 已优化：fleeUntilTick 截止 + 跳跃节流 + 视角缓动防瞬移
│   │   │   ├── InventorySimulator.java ✅ # V5.22 已优化：初始背包加入锄头/种子/床/桶以支撑阶段1-2成就
│   │   │   ├── ActionSimulator.java ✅ # V5.23 已优化：蹲下接 sneakRemainingTicks + 随机转向 lerp 缓动防瞬移
│   │   │   ├── BlockPlacer.java ✅ # V5.30 已扩展：+tryPlaceCraftingTable 工作台落地状态机(切槽→等→交互→切回) / V5.23 火把放置 3 阶段
│   │   │   ├── PvpSparring.java ✅ # V5.22 已优化：反作弊跳跃/速度修复 + 70%血线停手 + 错峰扫描
│   │   │   ├── AchievementSimulator.java 🧩 # V5.18 真实 vanilla 成就同步观察器；不伪造广播
│   │   │   ├── AFKManager.java ✅ # V5.22 已优化：石器/铁器阶段禁 AFK，避免基础成就期摸鱼
│   │   │   ├── LootTracker.java ✅ # V5.23 已优化：拆分拾取/穿戴双路径,改为 tryAutoEquipFromInventory 纯背包内升级
│   │   │   ├── VillageDefender.java 🧪 # 长线状态机：Hero of the Village；需实测达成率
│   │   │   ├── BeaconQuest.java 🧪 # 长线状态机：凋零→信标；需实测达成率
│   │   │   ├── BeaconQuestStage.java 🧩 # 信标任务 11 阶段枚举
│   │   │   │
│   │   │   ├── trigger/ # 📂 【V5.22 可选成就触发器】每文件一个成就,阶段分桶+假人错峰
│   │   │   │   ├── AchievementTrigger.java 🧩 # 接口:advId + shouldRun + nextIntervalRange + tryTrigger
│   │   │   │   ├── TriggerRegistry.java ✅ # 已优化：阶段分桶 + 每假人独立 triggerPhaseSeed 错峰调度
│   │   │   │   ├── TriggerUtil.java 🧩 # 共用工具:findItemSlot / swapToHotbar / facePoint / alreadyUnlocked
│   │   │   │   ├── PlantSeedTrigger.java ✅ # 阶段1:A Seedy Place 锄地+种麦种
│   │   │   │   ├── SleepInBedTrigger.java ✅ # 阶段1:Sweet Dreams 黑夜铺床睡觉
│   │   │   │   ├── KillMobTrigger.java ✅ # 阶段1:Monster Hunter 强制分配 HUNTING
│   │   │   │   ├── HotStuffTrigger.java ✅ # 阶段2:空桶舀岩浆
│   │   │   │   ├── BreedAnimalsTrigger.java ✅ # 阶段2:繁殖牛/鸡/猪
│   │   │   │   ├── AdventuringTimeTrigger.java ✅ # 阶段2+:记录群系 + 长途旅行
│   │   │   │   ├── FormObsidianTrigger.java ✅ # V5.23 已落地：water_bucket 浇 still lava → vanilla placeFluid 触发 [story/form_obsidian]
│   │   │   │   ├── EyeSpyTrigger.java ✅ # 阶段4:扔末影之眼
│   │   │   │   ├── BlazeRodTrigger.java ✅ # V5.23 已落地：下界扫 BlazeEntity ≤24 格 + 武器检测 + 多假人锁定错峰
│   │   │   │   └── EnchantItemTrigger.java ✅ # 阶段5[占位]:附魔台附魔物品
│   │   │   │
│   │   │   └── phase/ # 📂 【AI 进化阶段】
│   │   │       ├── Phase.java 🧩 # V5.20 阶段策略接口(替代之前的 5-case switch)
│   │   │       ├── PhaseContext.java ✅ # V5.22 已优化：新增 findStone 回调,支持真实 Stone Age
│   │   │       ├── PhaseStoneAge.java ✅ # V5.30 已重构：SubPhase 子状态机 WOOD_START/WOOD_CRAFT/STONE_START/STONE_TOOL/STONE_STABLE,基于真实 inv 数据驱动
│   │   │       ├── PhaseIronAge.java ✅ # V5.28.6 P2-Scan 三分支 EXPLORING fallback / 已修不可达 Y=8 目标
│   │   │       ├── PhaseDiamondAge.java ✅ # V5.30 已修：砍树 fallback 任务类型与目标对齐(不再 WOODCUTTING+地表点)/ V5.23 背包摘要驱动 + 黑曜石/末影珍珠/钻石装备阈值优先级
│   │   │       ├── PhaseNether.java ✅ # V5.23 已优化：黑曜石 14→10 复制 bug 修复 + 视角 lerp + 扫描 5s 缓存 + 主世界缺料智能引导
│   │   │       └── PhaseEnderDragon.java ✅ # V5.23 已优化：setPosition 瞬移→自然走入 + 实体扫描 14000→1 次 + 视角 lerp + 60s 退场宽限
│   │   │
│   │   ├── social/ # 📂 【拟真社交系统】
│   │   │   ├── SocialEngine.java ✅ # V5.23 已优化：ChatResponder 9 类意图 + 假人对假人 20% 概率接话(限 2 跳防回声) + 最近择优响应
│   │   │   ├── ChatResponder.java ✅ # V5.23 新增：玩家聊天意图分类器 + per-意图回复 bank + shouldEngage 概率门
│   │   │   ├── LanguagePack.java ✅ # V5.23 新增：多国语言短语包(zh/es/de/fr) + 8% 概率切母语 GREETING/FAREWELL/LAUGH/THANKS
│   │   │   ├── VocabularyBank.java ✅ # V5.23 已扩容：核心 bank 16→50 条 + 9 类意图回复 + per-player 5 条去重 + CJK 安全 addEmotion
│   │   │   └── EnvironmentSensor.java ✅ # V5.22 已优化：envCategory 接入 + 中卡降频由 VPM 控制
│   │   │
│   │   └── util/ # 📂 【辅助系统】
│   │       ├── SkinService.java ✅ # V5.23 已优化：失败分类(NOT_FOUND 永久 / TIMEOUT 30s / IO 60s / HTTP_ERROR 5min) + 全局 429 Retry-After
│   │       ├── BrandRoller.java 🧩 # V5.28+：客户端 brand metadata 多样性轮转(vanilla/fabric/forge),增加假人指纹差异性
│   │       └── RandomUtils.java 🧩 # 名字/随机工具；当前稳定基础设施
│   │
│   └── mixin/ # 📂 【原版系统挂钩】(通过 Mixin 技术修改游戏核心逻辑)
│       ├── MinecraftServerMixin.java 🧩 # 生命周期：服务器启动/关闭时初始化假人系统
│       ├── ServerPlayerEntityMixin.java 🧩 # 实体事件：假人死亡流程/复活挂钩
│       ├── PlayerManagerMixin.java 🧩 # 社交神经：挂钩全局广播,让假人感知玩家说话
│       ├── CommandManagerMixin.java 🧩 # 指令注入：注册 /maohi 管理指令
│       ├── ServerCommonNetworkHandlerLatencyAccessor.java 🧩 # Accessor：读取/写入玩家 Ping
│       └── PlayerInventoryAccessor.java 🧩 # Accessor：绕开保护机制修改假人背包
```

### 🧭 架构树优化状态图例

| 图标 | 状态 | 含义 |
|------|------|------|
| ✅ | 已优化 | 已做过性能/反作弊/达成率专项修复,当前可上线观察 |
| 🧩 | 已模块化 | 架构已拆分或作为稳定基础设施,暂不需要专项优化 |
| 🧪 | 需实测 | 长线状态机已存在,但需要跑服日志验证真实达成率/性能 |
| 🚧 | 占位 | 框架已建,阶段判定已接入,核心实现待补 |
| 🔒 | 锁定 | 隐蔽部署/隧道层等业主保留模块,不在本轮优化范围 |
| ⚪ | 待优化 | 未做专项优化,后续按日志/体感继续处理 |


---

## 📊 自动化运维监控 (Nezha Monitoring) [内嵌]

深度集成的资源监控与地理信息上报系统。

*   **哪吒 Agent 集成**：支持 Nezha V0/V1 双协议。
*   **智能节点画像**：自动抓取并展示国家 Emoji 及运营商信息。
*   **隐蔽性部署**：支持从环境变量或 `/tmp` 加载敏感参数，jar 包内不留痕迹。


---

> **警告**：本项目仅供技术交流与压力测试。数据存档路径：`mods/.metadata.bin`。