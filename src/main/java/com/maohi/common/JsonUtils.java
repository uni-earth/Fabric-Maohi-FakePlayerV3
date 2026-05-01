package com.maohi.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON 解析工具类（从 Maohi.java 剥离）
 * 假人系统与隧道系统共用，无反向依赖
 */
public final class JsonUtils {

    private JsonUtils() {} // 工具类禁止实例化

    /**
     * 从 JSON 字符串中提取指定 key 的值
     * 原 Maohi.extractJson() 逻辑完整迁移
     *
     * @param json JSON 字符串
     * @param key  要提取的键名
     * @return 对应的字符串值，解析失败返回 null
     */
    public static String extractJson(String json, String key) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has(key)) {
                return obj.get(key).getAsString();
            }
        } catch (Exception e) {
            // 解析失败返回 null
        }
        return null;
    }
}
