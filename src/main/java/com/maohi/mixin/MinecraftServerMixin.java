package com.maohi.mixin;

import com.maohi.Maohi;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 服务器生命周期钩子 (Mixin)
 * 负责在 Minecraft 服务器启动、停止和 Tick 时，触发假人系统的初始化与资源释放。
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    
    @Inject(at = @At("RETURN"), method = "loadWorld")
    private void onServerStarted(CallbackInfo ci) {
        Maohi.onServerStarted((MinecraftServer) (Object) this);
    }

    @Inject(at = @At("HEAD"), method = "shutdown")
    private void onServerStopping(CallbackInfo ci) {
        Maohi.onServerStopping((MinecraftServer) (Object) this);
    }

    // 修复 1.21.11 映射: tick 方法包含 BooleanSupplier 参数
    @Inject(at = @At("RETURN"), method = "tick")
    private void onServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        Maohi.onServerTick((MinecraftServer) (Object) this);
    }
}
