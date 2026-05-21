package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * V5.58 N1: 假人 receiver 跳过 EntityTracker 注册路径。
 *
 * 背景:
 *   vanilla 每 tick 给附近所有 player 构造 entity 包(EntityPositionS2CPacket /
 *   EntityTrackerUpdateS2CPacket 等)。假人参与 receivers 集合后,所有附近 entity 的
 *   update 都会为假人构造一份 packet,最终到 FakeClientConnection.send() 被丢弃 —
 *   但 vanilla 主线程上 packet 实例化 / 字段拷贝 / 字节缓冲分配的 CPU 已经花了。
 *
 *   人多 / mob 多的场景下,baseline mspt 里有显著一部分(~5-15ms × bot 数)花在
 *   "为假人构造完包再扔垃圾桶"。
 *
 * 方案:
 *   mixin EntityTrackerEntry.startTracking 在假人 receiver 进入时 cancel,
 *   vanilla 不会把假人加入 receivers 集合,后续该 entity 的 update 不为它构造包。
 *
 * 方向安全性(只跳"假人当 receiver",不跳"假人当 entity"):
 *   - 假人作为 entity 时:vanilla 为它创建独立的 EntityTrackerEntry,该 entry 的
 *     receivers 集合按距离自动收纳附近的真人 → 真人能正常看到假人移动 / 攻击 / 挖矿。
 *   - 假人作为 receiver 时:vanilla 跳过本 mixin → 假人不收到任何 entity update。
 *     假人 AI 不依赖 S2C 包(读 server-side world state 直接拿 entity 列表),功能不受影响。
 *
 * 抓包安全性:
 *   假人收的包都是 server → 假人 EmbeddedChannel 内部链路,从未经过任何真实网卡,
 *   反作弊 / 抓包工具(Wireshark / 客户端 PCAP)无从感知。/list / Tab 列表
 *   走 PlayerManager.getPlayerList(),与 EntityTracker 完全独立,假人仍正常显示。
 *
 * yarn 1.21.11 兼容性:
 *   - 类位置 net.minecraft.server.network.EntityTrackerEntry — yarn 1.21 系列稳定
 *   - 方法 startTracking(ServerPlayerEntity) — yarn 1.21 系列稳定
 *   - 如果未来 yarn 改名:mixin loader 报 "target method not found",此时把本 mixin
 *     从 maohi.mixins.json 移除即可恢复 baseline,功能不受影响(只是优化失效)。
 *
 * 收益预估:
 *   - 1 bot:省下 ~0.5-2ms baseline mspt(收益小)
 *   - 10 bot:省下 ~5-15ms baseline mspt
 *   - 100 bot:省下 ~50-150ms baseline mspt(显著)
 *   - 不直接缓解 5min autosave burst,但 baseline 降下来后 burst 相对幅度变小,
 *     "Can't keep up" 触发阈值更难达成。
 */
@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin {

    @Inject(at = @At("HEAD"), method = "startTracking", cancellable = true)
    private void maohiSkipBotReceiver(ServerPlayerEntity player, CallbackInfo ci) {
        if (player == null) return;
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null && mgr.isVirtualPlayer(player.getUuid())) {
            ci.cancel();
        }
    }
}
