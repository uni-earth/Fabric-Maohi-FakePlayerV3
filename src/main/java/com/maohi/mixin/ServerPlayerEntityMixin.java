package com.maohi.mixin;

import com.maohi.Maohi;
// 适配 V3 模块化架构：引入 fakeplayer 包下的核心管理类
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 玩家实体事件钩子 (Mixin)
 * 监听原版玩家的死亡事件，用于触发假人的抱怨台词、掉落物模拟以及自动复活逻辑。
 */
@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {
    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onMaohiDeath(DamageSource source, CallbackInfo ci) {
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null) {
            ServerPlayerEntity player = (ServerPlayerEntity)(Object)this;
            UUID uuid = player.getUuid();
            
            // 1. 如果是假人死亡，触发延迟复活，并生成被击杀的战利品(InventorySimulator 已内置在引擎中)
            if (mgr.isVirtualPlayer(uuid)) {
                mgr.onVirtualPlayerDeath(uuid);
            }
            
            // 2. 如果是真人在附近死亡，触发周围假人的“围观嘲讽”反应
            mgr.onPlayerDeathNearby(player);
        }
    }
}
