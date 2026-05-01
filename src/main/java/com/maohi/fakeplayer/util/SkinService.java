package com.maohi.fakeplayer.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maohi.common.HttpUtils;
import com.maohi.common.JsonUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mojang 皮肤服务封装（从 Maohi.java 剥离）
 * V3.3: 加入缓存 + 频率限制 + 429 退避
 */
public final class SkinService {

	private SkinService() {} // 工具类禁止实例化

	/** 皮肤属性记录，用于注入 GameProfile */
	public record SkinProperty(String name, String value, String signature) {}

	// === 缓存：同一名字不重复请求 ===
	private static final Map<String, SkinProperty> cache = new ConcurrentHashMap<>();
	private static final Map<String, Long> fetchTime = new ConcurrentHashMap<>();
	private static final long CACHE_TTL_MS = 3600_000L; // 1 小时缓存

	// === 频率限制：最多每 10 秒 1 次请求 ===
	private static final AtomicLong lastRequestTime = new AtomicLong(0);
	private static final long MIN_INTERVAL_MS = 10_000L; // 10 秒间隔

	// === 429 退避：被限流后冷却 5 分钟 ===
	private static volatile long cooldownUntil = 0;
	private static final long COOLDOWN_MS = 300_000L; // 5 分钟

	/**
	 * 异步获取皮肤数据（Mojang API）
	 * V3.3: 缓存优先 + 频率限制 + 429 退避
	 *
	 * @param name 玩家名
	 * @return 皮肤属性，失败返回 null
	 */
	public static SkinProperty fetchSkinProperties(String name) {
		// 1. 缓存命中且未过期 → 直接返回
		SkinProperty cached = cache.get(name);
		if (cached != null) {
			Long ft = fetchTime.get(name);
			if (ft != null && System.currentTimeMillis() - ft < CACHE_TTL_MS) return cached;
			// 过期，清理
			cache.remove(name);
			fetchTime.remove(name);
		}
		// 缓存 null 标记（上次失败）在 5 分钟内不重试
		Long ft = fetchTime.get(name);
		if (ft != null && cached == null && System.currentTimeMillis() - ft < COOLDOWN_MS) return null;

		// 2. 429 退避期内 → 跳过
		if (System.currentTimeMillis() < cooldownUntil) return null;

		// 3. 频率限制：10 秒内最多 1 次
		long now = System.currentTimeMillis();
		long last = lastRequestTime.get();
		if (now - last < MIN_INTERVAL_MS) return null;
		if (!lastRequestTime.compareAndSet(last, now)) return null; // CAS 失败 = 别的线程抢先了

		// 4. 真正请求
		try {
			// Step 1: 获取 UUID
			String uuidJson = HttpUtils.fetchText("https://api.mojang.com/users/profiles/minecraft/" + name, 8000);
			if (uuidJson == null) { markFailed(name); return null; }
			String uuid = JsonUtils.extractJson(uuidJson, "id");
			if (uuid == null) { markFailed(name); return null; }

			// Step 2: 获取皮肤属性
			String profileJson = HttpUtils.fetchText("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false", 8000);
			if (profileJson == null) { markFailed(name); return null; }

			JsonObject profileObj = JsonParser.parseString(profileJson).getAsJsonObject();
			if (profileObj.has("properties")) {
				JsonArray props = profileObj.getAsJsonArray("properties");
				if (props.size() > 0) {
					JsonObject prop = props.get(0).getAsJsonObject();
					String pName = prop.get("name").getAsString();
					String pValue = prop.get("value").getAsString();
					String pSig = prop.has("signature") ? prop.get("signature").getAsString() : null;
					SkinProperty result = new SkinProperty(pName, pValue, pSig);
					// 成功 → 写缓存
					cache.put(name, result);
					fetchTime.put(name, System.currentTimeMillis());
					return result;
				}
			}
			markFailed(name);
		} catch (Exception e) {
			// 429 Too Many Requests → 进入退避
			if (e.getMessage() != null && e.getMessage().contains("429")) {
				cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
				org.slf4j.LoggerFactory.getLogger("Server thread").warn("Skin API rate limited, cooling {}s", COOLDOWN_MS / 1000);
			} else {
				org.slf4j.LoggerFactory.getLogger("Server thread").debug("Skin fetch failed {}: {}", name, e.getMessage());
			}
			markFailed(name);
		}
		return null;
	}

	/** 标记失败（避免短时间内反复请求同一个失败的名字） */
	private static void markFailed(String name) {
		fetchTime.put(name, System.currentTimeMillis());
		// 不写 cache，让 cached=null + fetchTime 存在 → 下次走冷却逻辑
	}

	/** 清理过期缓存（可由外部定时调用） */
	public static void evictExpired() {
		long now = System.currentTimeMillis();
		fetchTime.entrySet().removeIf(e -> {
			if (now - e.getValue() > CACHE_TTL_MS) {
				cache.remove(e.getKey());
				return true;
			}
			return false;
		});
	}
}
