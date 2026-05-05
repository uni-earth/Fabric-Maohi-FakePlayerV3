package com.maohi;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PersonalityTest {

    @Test
    public void testPersonalityFields() {
        Personality personality = new Personality();

        // 验证行动乘数是否在合理范围内 (0.8 - 1.2 左右)
        assertTrue(personality.actionMultiplier >= 0.8 && personality.actionMultiplier <= 1.5, 
            "Action multiplier out of range: " + personality.actionMultiplier);
        
        // 初始状态验证
 assertFalse(personality.isEating);
 assertFalse(personality.isMining);
    }

    @Test
    public void testPersonalityDistribution() {
        int activeCount = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            Personality p = new Personality();
            if (p.actionMultiplier > 1.0) activeCount++;
        }

        // 验证分布（只要不是极端的一边倒即可）
        assertTrue(activeCount > 0 && activeCount < iterations, 
            "Action multiplier distribution failed: " + activeCount);
    }
}
