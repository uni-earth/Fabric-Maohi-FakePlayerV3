package com.maohi.mixin;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * V3.3: ServerCommonNetworkHandler 的 latency 字段访问器
 * 告别反射，使用 Mixin @Accessor 编译期安全地访问 private 字段
 * 
 * yarn 1.21.11: latency 在 ServerCommonNetworkHandler (class_8609, field_45019)
 * 不是在 ServerPlayerEntity 上！
 */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerLatencyAccessor {

	@Accessor("latency")
	void maohi$setLatency(int value);
}
