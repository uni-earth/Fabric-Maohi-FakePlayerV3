package com.maohi.fakeplayer.network;

import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * PingPong 处理器
 * 专门负责处理与服务端的 KeepAlive (心跳) 交互。
 * 核心功能是加入模拟延迟，以此绕过那些检测 "0ms" Ping 值的反作弊插件。
 */
public class PingPongHandler {

    /**
     * 处理收到的 KeepAlive 包，并异步返回响应。
     * @param id 服务端发来的 KeepAlive ID
     * @param handler 假人当前的网络处理器
     * @param pool 用于调度的共享线程池
     */
    public static void respondToKeepAlive(long id, ServerPlayNetworkHandler handler, ScheduledExecutorService pool) {
        if (handler == null || pool == null) return;
        
        // 模拟真人网络延迟：50ms ~ 200ms
        long delay = 50 + (long)(ThreadLocalRandom.current().nextDouble() * 150);
        
        pool.schedule(() -> {
            try {
                // 回复服务端，证明客户端还活着
                handler.onKeepAlive(new KeepAliveC2SPacket(id));
            } catch (Throwable t) {
                // 静默处理，可能是连接已断开
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
