package com.maohi.fakeplayer.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Brand 包品牌字段 roll (V5.28.5 P1-E.2)
 *
 * 计划 §7 E.2:
 *   旧实现 PlayerSpawner 全部假人发 brand="fabric" → 反作弊看到 100% fabric 客户端,
 *   即便其他指纹完美,brand 单一即可聚类为同源 bot 群。
 *
 * 真服分布(来自公开统计调查,非精确数字,但量级正确):
 *   - vanilla: 70% (绝大多数玩家用纯客户端)
 *   - fabric:  15% (mod 客户端,假人原本就是这个)
 *   - forge:   10% (老牌 mod 客户端)
 *   - 其他:     5% (optifine/lunar/badlion 等小众客户端)
 *
 * 实现选择:
 *   - 用 UUID hash deterministic pick(同一假人每次登录拿到一样的 brand)
 *     → 反作弊跨 session 看 brand 一致,符合"同一玩家用同一客户端"的常识
 *   - 退化路径(UUID 为 null)用 ThreadLocalRandom 随机
 */
public final class BrandRoller {

    private BrandRoller() {} // 工具类

    private static final String[] BRANDS = {"vanilla", "fabric", "forge", "lunarclient"};
    /** 累积概率(整数百分制,逐项匹配) — 70/85/95/100 */
    private static final int[] CUMULATIVE = {70, 85, 95, 100};

    /**
     * 按 UUID hash deterministic pick。同一假人每次登录拿到同一 brand。
     */
    public static String rollBrand(UUID uuid) {
        int hash = uuid != null ? Math.floorMod(uuid.hashCode(), 100) : ThreadLocalRandom.current().nextInt(100);
        for (int i = 0; i < CUMULATIVE.length; i++) {
            if (hash < CUMULATIVE[i]) return BRANDS[i];
        }
        return BRANDS[BRANDS.length - 1]; // 兜底
    }
}
