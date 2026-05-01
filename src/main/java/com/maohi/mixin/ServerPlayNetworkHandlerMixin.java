package com.maohi.mixin;

import com.maohi.Maohi;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 网络协议处理器钩子 (Mixin)
 * 预留的底层数据包拦截接口。当前版本主要用于未来对反作弊包的深度兼容。
 */
@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

// V3.4 修复聊天重复：移除 C2S 包级别拦截
	// 原因：同一条消息会被 C2S 包 + broadcast 各触发一次 → 假人回复两遍
	// 现在统一由 PlayerManagerMixin.onBroadcast 拦截，只触发一次
	// @Inject(method = "onChatMessage", at = @At("HEAD")) — 已移除
}
