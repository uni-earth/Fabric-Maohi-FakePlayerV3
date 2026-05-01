package com.maohi.fakeplayer;

import com.maohi.fakeplayer.util.SkinService;
import java.util.concurrent.CompletableFuture;

/**
 * 皮肤抓取器 (V3 核心组件)
 * 专门负责异步请求 Mojang 接口获取皮肤，防止主线程卡顿。
 * 已解耦：不再依赖 Maohi 主类，改用 SkinService
 */
public class ProfileFetcher {
 
	/**
	 * 异步获取皮肤并生成假人
	 * M3 fix: 皮肤抓取失败时使用已知真实玩家皮肤作为 fallback，避免 Steve 默认皮肤暴露
	 * @param manager 假人管理器
	 * @param name 玩家名
	 */
	public static void fetchAndSpawn(VirtualPlayerManager manager, String name) {
		// 1. 如果已经有缓存，直接主线程安全生成
		if (manager.getSkinCache().containsKey(name)) {
			manager.getServer().execute(() -> PlayerSpawner.spawn(manager, name, manager.getSkinCache().get(name)));
		} 
		// 2. 如果正在抓取中，则不重复发起请求
		else if (!manager.getFetchingSkins().contains(name)) {
			manager.getFetchingSkins().add(name);
			
			// 3. 异步获取 Mojang 皮肤属性 (网络 IO)
			CompletableFuture.runAsync(() -> {
				try {
					SkinService.SkinProperty skin = SkinService.fetchSkinProperties(name);
					if (skin != null) {
						manager.getSkinCache().put(name, skin);
					}
					// 4. 抓取完成后，回到主线程执行实体生成
					// M3: 皮肤为 null 时用 fallback（随机已有缓存或 null 让 spawn 处理）
					SkinService.SkinProperty finalSkin = skin;
					if (finalSkin == null && !manager.getSkinCache().isEmpty()) {
						// 随机选一个已有缓存皮肤（比 Steve 默认皮肤好）
						java.util.List<SkinService.SkinProperty> cached = new java.util.ArrayList<>(manager.getSkinCache().values());
						finalSkin = cached.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(cached.size()));
					}
					final SkinService.SkinProperty resolvedSkin = finalSkin;
					manager.getServer().execute(() -> PlayerSpawner.spawn(manager, name, resolvedSkin));
				} finally {
					// 确保清理状态位，防止死锁
					manager.getFetchingSkins().remove(name);
				}
			});
		}
	}
}
