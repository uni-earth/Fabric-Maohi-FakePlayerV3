package com.maohi.mixin;

import com.maohi.Maohi;
// 适配 V3 模块化架构：引入 fakeplayer 包下的核心管理类
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 社交对话感知钩子 (Mixin)
 * 挂钩全局广播方法，让假人系统能实时“听见”真玩家的打字内容，从而触发对应的回复逻辑。
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("HEAD"))
    private void onBroadcast(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params, CallbackInfo ci) {
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null && sender != null) {
            // 触发假人的对话感知模块
            mgr.onChatMessage(sender, message.getContent().getString());
        }
    }
}
