package org.ruoyi.factory;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.service.vector.VectorStoreService;
import org.ruoyi.service.vector.impl.MilvusVectorStoreStrategy;
import org.ruoyi.service.vector.impl.PgVectorStoreStrategy;
import org.ruoyi.service.vector.impl.QdrantVectorStoreStrategy;
import org.ruoyi.service.vector.impl.WeaviateVectorStoreStrategy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量库策略工厂
 * 根据配置动态选择向量库实现
 *
 * @author Yzm
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorStoreStrategyFactory {

    private final VectorStoreProperties vectorStoreProperties;
    private final WeaviateVectorStoreStrategy weaviateStrategy;
    private final MilvusVectorStoreStrategy milvusStrategy;
    private final QdrantVectorStoreStrategy qdrantStrategy;
    private final PgVectorStoreStrategy pgVectorStrategy;

    private Map<String, VectorStoreService> strategies;

    @PostConstruct
    public void init() {
        strategies = new HashMap<>();
        strategies.put("weaviate", weaviateStrategy);
        strategies.put("milvus", milvusStrategy);
        strategies.put("qdrant", qdrantStrategy);
        strategies.put("pgvector", pgVectorStrategy);
        strategies.put("pg_vector", pgVectorStrategy);
        strategies.put("pg", pgVectorStrategy);
        log.info("向量库策略工厂初始化完成，支持的策略: {}", strategies.keySet());
    }

    /**
     * 获取当前配置的向量库策略
     */
    public VectorStoreService getStrategy() {
        return getStrategy(null);
    }

    public VectorStoreService getStrategy(String requestedType) {
        String vectorStoreType = requestedType;
        if (vectorStoreType == null || vectorStoreType.trim().isEmpty()) {
            vectorStoreType = vectorStoreProperties.getType();
        }
        if (vectorStoreType == null || vectorStoreType.trim().isEmpty()) {
            vectorStoreType = "pgvector"; // 默认使用 pgvector
        }
        VectorStoreService strategy = strategies.get(vectorStoreType.toLowerCase());
        if (strategy == null) {
            log.warn("未匹配到显式向量库策略 [{}], 降级使用 pgvector 本地向量策略", vectorStoreType);
            strategy = strategies.get("pgvector");
        }
        log.debug("使用向量库策略: {}", vectorStoreType);
        return strategy;
    }

}
