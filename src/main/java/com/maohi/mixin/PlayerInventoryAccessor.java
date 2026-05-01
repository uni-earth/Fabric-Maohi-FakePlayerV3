package com.maohi.mixin;

import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 背包数据访问器 (Mixin Accessor)
 * 允许假人引擎绕过原版限制，直接操作和注入玩家的背包数据。
 */
@Mixin(PlayerInventory.class)
public interface PlayerInventoryAccessor {

    @Accessor("selectedSlot")
    int getSelectedSlot();

    @Accessor("selectedSlot")
    void setSelectedSlot(int slot);
}
