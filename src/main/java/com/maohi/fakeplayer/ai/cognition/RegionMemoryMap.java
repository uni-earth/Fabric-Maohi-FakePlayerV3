package com.maohi.fakeplayer.ai.cognition;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * P0: per-bot 区域记忆地图 — 三档评分(EMPTY/MEDIUM/RICH)，替代 V5.42 单向黑名单。
 *
 * 核心改进:
 *   旧系统只有「黑名单」——扫过且没东西的 region 被标记，下次跳过。
 *   新系统同时记录「好地方」——bot 发现资源的 region 获得 MEDIUM/RICH 评分，
 *   下次 setExplore 时，RICH region 被优先选择（加权抽签）。
 *
 * 关于负坐标:
 *   NOTE: 必须用 Math.floorDiv 而不是 >> 5 或 / 32 ！
 *   Java 的整数除法是截断：-1 / 32 == 0，但 Math.floorDiv(-1, 32) == -1。
 *   用 >> 5 或 / 32 会让负坐标 (-1, 0) 和 (0, 0) 落到同一 region，Minecraft 地图西/北象限全部错位。
 *
 * 容量保护:
 *   单 bot 最多 256 条 entry，超了按 LRU（最久未访问）淘汰。
 *   防止跑 16 小时后内存漫无边际增长。
 */
public final class RegionMemoryMap {

    /** 每个 32×32 region 的评分记录 */
    private static final class Entry {
        RegionScore score;
        long expireAt;     // 过期时间戳 (System.currentTimeMillis)
        long lastAccessAt; // 上次访问时间，用于 LRU 淘汰
        boolean hearsay;   // 是否来自共享情报（可信度低，TTL缩半）

        Entry(RegionScore score, boolean hearsay) {
            long now = System.currentTimeMillis();
            this.score = score;
            this.hearsay = hearsay;
            this.lastAccessAt = now;
            long ttl = hearsay ? score.ttlMs / 2 : score.ttlMs; // 道听途说 TTL 缩半
            this.expireAt = now + ttl;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private static final int MAX_ENTRIES = 256;

    // NOTE: 不需要线程安全 — RegionMemoryMap 是 per-bot 实例，只在 server main thread 访问
    private final Map<Long, Entry> map = new HashMap<>();

    // ==================== 核心 API ====================

    /**
     * 把一个 region 标记为指定评分。
     * 已有更高评分时不降级（发现 RICH 后不会因为后来没找到树就变 MEDIUM）。
     *
     * @param rx      regionX（用 blockToRegion 转换）
     * @param rz      regionZ
     * @param score   EMPTY / MEDIUM / RICH
     * @param hearsay 是否来自共享情报（非亲眼所见）
     */
    public void mark(int rx, int rz, RegionScore score, boolean hearsay) {
        long key = packKey(rx, rz);
        Entry existing = map.get(key);
        // 不降级：已有更高评分时保留（RICH > MEDIUM > EMPTY）
        if (existing != null && !existing.isExpired() && existing.score.weight >= score.weight) {
            existing.lastAccessAt = System.currentTimeMillis();
            return;
        }
        enforceCapacity();
        map.put(key, new Entry(score, hearsay));
    }

    /**
     * 查询一个 region 的当前评分。
     * 返回 null 表示从未记录或已过期（等同于未知区域，可以去探）。
     */
    public RegionScore query(int rx, int rz) {
        long key = packKey(rx, rz);
        Entry e = map.get(key);
        if (e == null) return null;
        if (e.isExpired()) {
            map.remove(key);
            return null;
        }
        e.lastAccessAt = System.currentTimeMillis();
        return e.score;
    }

    /**
     * 降级一个 region 的评分（bot 发现资源已被消耗）。
     * RICH → MEDIUM → EMPTY，到达 EMPTY 后记录 EMPTY 重新计时。
     */
    public void degrade(int rx, int rz) {
        long key = packKey(rx, rz);
        Entry e = map.get(key);
        if (e == null) return;
        RegionScore next = switch (e.score) {
            case RICH   -> RegionScore.MEDIUM;
            case MEDIUM -> RegionScore.EMPTY;
            case EMPTY  -> RegionScore.EMPTY;
        };
        map.put(key, new Entry(next, e.hearsay));
    }

    /**
     * P0 核心决策：给定一组候选 (tx, tz)，按评分加权抽签选出最优目标。
     *
     * 抽签权重映射:
     *   RICH   → 5  （强力倾向好地方）
     *   null   → 3  （未知区域，值得探索，中等权重）
     *   MEDIUM → 2  （曾经去过，资源一般，低优先级）
     *   EMPTY  → 0  （已扫空，跳过）
     *
     * 在所有候选都是 EMPTY 时回退到随机（不然 bot 无路可走）。
     *
     * @param candidates 候选坐标数组，每个元素是 [tx, tz]（世界坐标，非 region 坐标）
     * @return 选中的候选索引，-1 表示全部 EMPTY 需要兜底
     */
    public int weightedPick(int[][] candidates) {
        if (candidates == null || candidates.length == 0) return -1;

        int[] weights = new int[candidates.length];
        int totalWeight = 0;

        for (int i = 0; i < candidates.length; i++) {
            int rx = blockToRegion(candidates[i][0]);
            int rz = blockToRegion(candidates[i][1]);
            RegionScore s = query(rx, rz);
            int w = switch (s == null ? null : s) {
                case RICH   -> 5;
                case MEDIUM -> 2;
                case EMPTY  -> 0;
                case null   -> 3; // 未知区域，优先探索
            };
            weights[i] = w;
            totalWeight += w;
        }

        if (totalWeight == 0) return -1; // 全 EMPTY，需要兜底

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (roll < acc) return i;
        }
        return candidates.length - 1;
    }

    /** 清理过期 entry，防止 map 缓慢膨胀（setExplore 入口顺手调用） */
    public void prune() {
        map.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // ==================== 静态工具 ====================

    /**
     * 把世界坐标转换为 region 坐标。
     * NOTE: 必须用 Math.floorDiv，不能用 >> 5 或 / 32！
     */
    public static int blockToRegion(int blockCoord) {
        return Math.floorDiv(blockCoord, 32);
    }

    /** 把 (regionX, regionZ) 打包为 long key */
    public static long packKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    // ==================== 内部工具 ====================

    /** LRU 容量保护：超过 MAX_ENTRIES 时淘汰最久未访问的 entry */
    private void enforceCapacity() {
        if (map.size() < MAX_ENTRIES) return;
        // 先尝试清过期的
        map.entrySet().removeIf(e -> e.getValue().isExpired());
        if (map.size() < MAX_ENTRIES) return;
        // 仍然超了 → 淘汰最老的 entry（简单 O(N) 扫描，256 条不是性能瓶颈）
        long oldestAccess = Long.MAX_VALUE;
        long oldestKey = 0;
        for (Map.Entry<Long, Entry> e : map.entrySet()) {
            if (e.getValue().lastAccessAt < oldestAccess) {
                oldestAccess = e.getValue().lastAccessAt;
                oldestKey = e.getKey();
            }
        }
        map.remove(oldestKey);
    }
}
