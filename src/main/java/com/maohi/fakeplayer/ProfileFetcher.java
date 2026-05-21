package com.maohi.fakeplayer;

import com.maohi.fakeplayer.util.SkinService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 皮肤抓取器 (V3)
 *
 * V5.23 修复:
 *   1. 自有有界线程池(最多 4 并发) — 原 CompletableFuture.runAsync 占用 ForkJoin
 *      common pool,8s 阻塞 HTTP 会拖慢全局 parallel stream 等共享路径。
 *   2. 原子去重 — 旧逻辑 fetchingSkins.contains + add 不是原子,两次 fetchAndSpawn
 *      同名间隔很短时可能 race 双重抓取。改用 ConcurrentHashMap.putIfAbsent。
 *   3. 服务器关停安全 — 调度到 server thread 用 try/catch 包住 RejectedExecutionException,
 *      并提供 shutdown() 给 VPM 调用。
 *   4. SkinCache 读取一致性 — 取一次引用而不是反复 .get(name),避免 fallback 路径下
 *      同一线程内 cache 状态变化造成的混乱。
 */
public class ProfileFetcher {

	/** 同时进行的 Mojang 抓取上限。Mojang 自身 IP 限速很严,>4 也无意义。 */
	private static final int MAX_CONCURRENT_FETCH = 4;

	/** V5.23: 专属 daemon 线程池。daemon 防止服务器关停时挂住 JVM。 */
	private static final ExecutorService FETCH_POOL = new ThreadPoolExecutor(
		MAX_CONCURRENT_FETCH, MAX_CONCURRENT_FETCH,
		60L, TimeUnit.SECONDS,
		new LinkedBlockingQueue<>(64),
		new NamedDaemonThreadFactory("Maohi-ProfileFetcher"),
		new ThreadPoolExecutor.DiscardOldestPolicy() // 队列爆了丢最早的(避免无限堆积)
	);
	static {
		((ThreadPoolExecutor) FETCH_POOL).allowCoreThreadTimeOut(true);
	}

	/**
	 * V5.23: 原子去重表 — 替代 VPM.fetchingSkins(那个 Set 的 contains+add 非原子)。
	 * 仍同步写回 fetchingSkins 以保持 /maohi status 等可观测路径。
	 */
	private static final ConcurrentHashMap<String, Boolean> INFLIGHT = new ConcurrentHashMap<>();

	/**
	 * 异步获取皮肤并生成假人
	 * M3 fix: 皮肤抓取失败时使用已知真实玩家皮肤作为 fallback,避免 Steve 默认皮肤暴露
	 *
	 * @param manager 假人管理器
	 * @param name    玩家名
	 */
	public static void fetchAndSpawn(VirtualPlayerManager manager, String name) {
		// 1. 缓存命中:直接主线程安全生成
		SkinService.SkinProperty cached = manager.getSkinCache().get(name);
		if (cached != null) {
			manager.enqueueSpawn(() -> PlayerSpawner.spawn(manager, name, cached));
			return;
		}

		// 2. V5.23: 原子并发去重 — putIfAbsent 返回 null 表示这次抢到了 fetch 权
		if (INFLIGHT.putIfAbsent(name, Boolean.TRUE) != null) {
			return; // 别的线程已经在抓了
		}
		manager.getFetchingSkins().add(name); // 维护可观测状态(VPM /status 用)

		// 3. 提交到自有有界池
		try {
			FETCH_POOL.execute(() -> doFetchAndSpawn(manager, name));
		} catch (RejectedExecutionException ignored) {
			// 池子已关闭(服务器关停):静默丢弃,清理状态
			INFLIGHT.remove(name);
			manager.getFetchingSkins().remove(name);
		}
	}

	/** 在专属线程上跑:HTTP 抓取 → 决定 finalSkin → 调度回主线程 spawn */
	private static void doFetchAndSpawn(VirtualPlayerManager manager, String name) {
		try {
			SkinService.SkinProperty skin = SkinService.fetchSkinProperties(name);
			if (skin != null) {
				manager.getSkinCache().put(name, skin);
			}

			SkinService.SkinProperty finalSkin = skin;
			// V5.23 fallback:抓不到就从已缓存的真实玩家皮肤里抽一个,比 Steve 默认皮肤更隐蔽
			if (finalSkin == null) {
				finalSkin = pickRandomCachedSkin(manager);
			}

			final SkinService.SkinProperty resolvedSkin = finalSkin;
			manager.enqueueSpawn(() -> PlayerSpawner.spawn(manager, name, resolvedSkin));
		} catch (Throwable t) {
			// 抓取过程中任何意外都不能让线程池工作线程死掉
			org.slf4j.LoggerFactory.getLogger("Server thread")
				.debug("ProfileFetcher unexpected error for {}: {}", name, t.getMessage());
		} finally {
			INFLIGHT.remove(name);
			manager.getFetchingSkins().remove(name);
		}
	}

	/**
	 * V5.23: 随机抽一个缓存中的皮肤当 fallback。
	 * 旧实现 new ArrayList(values()) 在 HashMap 上无同步,可能 ConcurrentModification。
	 * 用 toArray() 是 ConcurrentHashMap-safe 的快照路径(VPM.skinCache 实际类型);
	 * 即使将来换成 HashMap,toArray 内部也会避开迭代器路径中途崩溃。
	 */
	private static SkinService.SkinProperty pickRandomCachedSkin(VirtualPlayerManager manager) {
		Object[] all = manager.getSkinCache().values().toArray();
		if (all.length == 0) return null;
		int idx = java.util.concurrent.ThreadLocalRandom.current().nextInt(all.length);
		Object pick = all[idx];
		return pick instanceof SkinService.SkinProperty sp ? sp : null;
	}

	/** V5.23: 服务器关停时由 VPM/Maohi 调用,确保 daemon 池及时收尾 */
	public static void shutdown() {
		FETCH_POOL.shutdown();
		try {
			if (!FETCH_POOL.awaitTermination(2, TimeUnit.SECONDS)) {
				FETCH_POOL.shutdownNow();
			}
		} catch (InterruptedException ie) {
			FETCH_POOL.shutdownNow();
			Thread.currentThread().interrupt();
		}
		INFLIGHT.clear();
	}

	/** 命名 daemon ThreadFactory — 让线程在 jstack 里能识别出来 */
	private static final class NamedDaemonThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger counter = new AtomicInteger(1);
		NamedDaemonThreadFactory(String prefix) { this.prefix = prefix; }
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1); // 比游戏线程稍低
			return t;
		}
	}
}
