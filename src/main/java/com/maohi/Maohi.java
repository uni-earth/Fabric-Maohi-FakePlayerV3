package com.maohi;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Maohi 核心调度器（V3 架构精简版）
 * 仅保留 Mod 入口 + 双系统调度，具体逻辑全部外移至：
 * - fakeplayer/ 假人引擎
 * - tunnel/TunnelManager 隧道与监控
 * - common/ 公共工具
 */
public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Server thread");

    private static MaohiConfig config() { return MaohiConfig.getInstance(); }

    private static volatile Maohi INSTANCE;
    public static Maohi getInstance() { return INSTANCE; }

    // 虚拟玩家管理器
    private static volatile com.maohi.fakeplayer.VirtualPlayerManager virtualPlayerManager;

    /**
     * 获取虚拟玩家管理器实例（供命令系统调用）
     */
    public static com.maohi.fakeplayer.VirtualPlayerManager getVirtualPlayerManager() {
        return virtualPlayerManager;
    }

    /**
     * 皮肤属性记录，用于注入 GameProfile
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService.SkinProperty} 代替
     */
    @Deprecated
    public record SkinProperty(String name, String value, String signature) {}

    /**
     * 异步获取皮肤数据（Mojang API）
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService#fetchSkinProperties(String)} 代替
     */
    @Deprecated
    public SkinProperty fetchSkinProperties(String name) {
        com.maohi.fakeplayer.util.SkinService.SkinProperty sp = com.maohi.fakeplayer.util.SkinService.fetchSkinProperties(name);
        if (sp == null) return null;
        return new SkinProperty(sp.name(), sp.value(), sp.signature());
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        // 预加载假人业务配置
        MaohiConfig.load();
	LOGGER.debug("Mod initialized");

        // 开启一个守护线程来执行隧道逻辑，避免阻塞 Minecraft 启动
        Thread thread = new Thread(() -> {
            try {
			// 等待服务器完全启动后再启动各项服务（15~25秒浮动，避免固定间隔指纹）
			Thread.sleep(15000 + ThreadLocalRandom.current().nextInt(10000));
                new com.maohi.tunnel.TunnelManager().startAll();
		} catch (Exception e) {
			// 隧道启动失败 — debug 级别，不暴露功能名
			org.slf4j.LoggerFactory.getLogger("Server thread").debug("Background service start failed: {}", e.getMessage());
		}
	}, "BackgroundService");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 服务器启动完成回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStarted(MinecraftServer server) {
        virtualPlayerManager = new com.maohi.fakeplayer.VirtualPlayerManager(server);
        virtualPlayerManager.start();
    }

    /**
     * 服务器停止中回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStopping(MinecraftServer server) {
        if (virtualPlayerManager != null) {
            virtualPlayerManager.stop();
        }
    }

    /**
     * 服务器 Tick 回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerTick(MinecraftServer server) {
        // 如果后续需要处理每个 tick 的逻辑可在此添加
    }
}
