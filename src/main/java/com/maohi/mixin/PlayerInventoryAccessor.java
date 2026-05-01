package com.maohi.mixin;

import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * V4: PlayerInventory 访问器
 * 解决在某些映射环境下 selectedSlot 字段为 private 的问题
 */
@Mixin(PlayerInventory.class)
public interface PlayerInventoryAccessor {

    @Accessor("selectedSlot")
    int getSelectedSlot();

    @Accessor("selectedSlot")
    void setSelectedSlot(int slot);
}
