package org.ruoyi.factory;

import org.ruoyi.factory.impl.DeepSeekAdapter;
import org.ruoyi.factory.impl.QwenAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模型适配工厂类
 * 
 * @author antigravity
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final Map<String, LlmAdapter> adapterCache = new ConcurrentHashMap<>();

    @Value("${ai.model.active:deepseek}")
    private String activeModel;

    @Value("${ai.model.deepseek.url:https://api.deepseek.com/v1}")
    private String deepseekUrl;

    @Value("${ai.model.deepseek.key:}")
    private String deepseekKey;

    @Value("${ai.model.deepseek.name:deepseek-chat}")
    private String deepseekModelName;

    @Value("${ai.model.qwen.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String qwenUrl;

    @Value("${ai.model.qwen.key:}")
    private String qwenKey;

    @Value("${ai.model.qwen.name:qwen-max}")
    private String qwenModelName;

    /**
     * 获取当前默认激活的大模型适配器
     */
    public LlmAdapter getActiveAdapter() {
        return getAdapter(activeModel);
    }

    /**
     * 根据指定的模型类型获取大模型适配器
     *
     * @param modelType 模型类型 (deepseek / qwen)
     * @return LlmAdapter 实例
     */
    public LlmAdapter getAdapter(String modelType) {
        if (modelType == null) {
            throw new IllegalArgumentException("模型类型不能为空");
        }

        String type = modelType.toLowerCase().trim();
        String cacheKey = type + "_" + getModelKeySignature(type);

        return adapterCache.computeIfAbsent(cacheKey, key -> {
            log.info("初始化大模型适配器驱动, 类型: {}, CacheKey: {}", type, cacheKey);
            switch (type) {
                case "deepseek":
                    return new DeepSeekAdapter(
                            deepseekUrl,
                            deepseekKey,
                            deepseekModelName
                    );
                case "qwen":
                    return new QwenAdapter(
                            qwenUrl,
                            qwenKey,
                            qwenModelName
                    );
                default:
                    log.warn("未识别的模型类型: {}, 默认降级为 DeepSeek 驱动", type);
                    return new DeepSeekAdapter(deepseekUrl, deepseekKey, deepseekModelName);
            }
        });
    }

    private String getModelKeySignature(String type) {
        if ("deepseek".equals(type)) {
            return String.valueOf((deepseekUrl + deepseekKey + deepseekModelName).hashCode());
        } else if ("qwen".equals(type)) {
            return String.valueOf((qwenUrl + qwenKey + qwenModelName).hashCode());
        }
        return "default";
    }

    /**
     * 手动清除缓存实现热更新
     */
    public void clearCache() {
        adapterCache.clear();
        log.info("已清空大模型适配器缓存。");
    }
}
