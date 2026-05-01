package com.maohi.mixin;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 延迟数据访问器 (Mixin Accessor)
 * 强制打通原版私有字段，用于读取经过隧道转发后的玩家真实网络延迟。
 */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerLatencyAccessor {

	@Accessor("latency")
	void maohi$setLatency(int value);
}
