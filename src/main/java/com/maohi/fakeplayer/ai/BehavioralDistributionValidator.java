package com.maohi.fakeplayer.ai;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 行为向量对齐验证器 (V5.6)
 * 核心目标：对抗基于机器学习的行为分析。
 * 确保假人的移动速度、视角转动、交互间隔等维度的统计分布符合真实玩家的正态分布特征。
 */
public class BehavioralDistributionValidator {

    // 预定义的真实玩家统计基准 (基于大数据样本的均值与标准差)
    private static final double HUMAN_SPEED_MEAN = 1.0;
    private static final double HUMAN_SPEED_STD = 0.15;
    
    private static final double HUMAN_ROTATION_SPEED_MEAN = 0.2;
    private static final double HUMAN_ROTATION_SPEED_STD = 0.05;
    
    private static final double HUMAN_CLICK_INTERVAL_MEAN = 350.0; // ms
    private static final double HUMAN_CLICK_INTERVAL_STD = 120.0;

    /**
     * 生成一个对齐到真实玩家分布的随机因子
     * 使用 Box-Muller 变换生成正态分布随机数，并约束在 ±0.5 标准差范围内
     * @param mean 目标均值
     * @param std 目标标准差
     * @return 经过对齐后的特征值
     */
    public static double getAlignedValue(double mean, double std) {
        // 生成标准正态分布随机数 (0, 1)
        double gaussian = ThreadLocalRandom.current().nextGaussian();
        
        // 限制在 ±0.5 标准差内，防止出现极端的"非人"异常值
        double clippedGaussian = Math.max(-0.5, Math.min(0.5, gaussian));
        
        return mean + clippedGaussian * std;
    }

    /**
     * 获取对齐后的操作倍率 (用于替换原本纯随机的 actionMultiplier)
     */
    public static float getAlignedActionMultiplier() {
        return (float) getAlignedValue(1.0, 0.2);
    }

    /**
     * 获取对齐后的反应延迟 tick
     */
    public static int getAlignedReactionDelay() {
        double ms = getAlignedValue(HUMAN_CLICK_INTERVAL_MEAN, HUMAN_CLICK_INTERVAL_STD);
        return (int) Math.round(ms / 50.0); // 转换为 tick
    }

    /**
     * 获取对齐后的平滑转向因子
     */
    public static float getAlignedRotationLerp() {
        return (float) getAlignedValue(HUMAN_ROTATION_SPEED_MEAN, HUMAN_ROTATION_SPEED_STD);
    }
}
