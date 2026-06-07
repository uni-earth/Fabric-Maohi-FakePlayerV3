package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 假人区块票据性能优化 (Mixin)
 *
 * <p>拦截 {@code ServerChunkLoadingManager.doesNotGenerateChunks}(原版仅对 spectator 返回 true):
 * 假人一律返回 true,使其不参与 vanilla PLAYER 票据跟踪 —— updatePosition 不再把假人 add/remove
 * 进 distanceManager,DistanceFromNearestPlayerTracker 的 Long2ByteOpenHashMap 级别传播也就不会
 * 因假人长途移动反复重算,斩断 1s+ 主线程 watchdog stall(get() 卡顿源)。
 *
 * <p>安全前提:假人区块加载不靠 vanilla PLAYER 票据,而靠 {@code MovementController.maohiBotForceLoadRing}
 * 给假人周围 3x3 注册的 FORCED(level 31)票据并跟随移动。所以砍掉 PLAYER 票据贡献后假人不会
 * "出不了当前区块"。代价:实体 tick / 刷怪范围缩到该 FORCED 区(3x3 实体 tick / ~7x7 FULL 加载);
 * 资源扫描(≤32 格)与冶炼(炉 ≤5 格)均在加载范围内,不受影响。
 *
 * <p>real player 走 null/false 分支,零影响(仅一次 HashSet 查询开销)。
 */
@Mixin(ServerChunkLoadingManager.class)
public final class ServerChunkLoadingManagerMixin {

    @Inject(method = "doesNotGenerateChunks", at = @At("HEAD"), cancellable = true)
    private void onMaohiDoesNotGenerateChunks(ServerPlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null && player != null && mgr.isVirtualPlayer(player.getUuid())) {
            // 假人已通过 FORCED 票据 3x3 强载,无需原版 PLAYER 视距票据
            cir.setReturnValue(true);
        }
    }
}
