package com.maohi.fakeplayer.ai.cognition;

/**
 * P0: 区域资源评分枚举 — 三档(EMPTY/MEDIUM/RICH)，替代 V5.42 的单向黑名单。
 *
 * 为什么是三档而不是连续值:
 *   简单、稳定、加权抽签好算。连续分数在多 bot 场景容易产生\"雪崩效应\"——
 *   全部 bot 都冲最高分 region 造成同步化指纹。三档 + 噪声足够平衡。
 *
 * TTL 设计哲学:
 *   EMPTY 记 10 分钟(树被砍完、石头挖光后会慢慢重生)
 *   MEDIUM 记 30 分钟(资源部分存在，上次路过值得再看)
 *   RICH   记 60 分钟(记住好地方，符合真人\"我知道那边有矿\"的直觉)
 */
public enum RegionScore {

    /** 扫过，什么都没有或全被消耗。这块 region 暂时跳过。TTL=10min */
    EMPTY(-1, 10 * 60 * 1000L),

    /** 有少量资源，但不丰富。可以去，但优先级低。TTL=30min */
    MEDIUM(0, 30 * 60 * 1000L),

    /** 资源丰富，优先去！TTL=60min */
    RICH(2, 60 * 60 * 1000L);

    public final int weight;
    public final long ttlMs;

    RegionScore(int weight, long ttlMs) {
        this.weight = weight;
        this.ttlMs = ttlMs;
    }
}
