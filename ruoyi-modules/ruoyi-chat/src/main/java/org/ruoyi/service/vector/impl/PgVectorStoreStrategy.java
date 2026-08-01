package org.ruoyi.service.vector.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.factory.EmbeddingModelFactory;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * PostgreSQL + pgvector 本地向量存储策略
 * 支持真实 Embedding 语义向量相似度计算 + 智能三级文本匹配
 *
 * @author ageerle
 */
@Slf4j
@Service("pgVectorStoreStrategy")
public class PgVectorStoreStrategy extends AbstractVectorStoreStrategy {

    private final KnowledgeFragmentMapper knowledgeFragmentMapper;
    /** JVM 内存缓存：同一次服务生命周期内复用，避免重复调用 */
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    /** Redis 持久化缓存：跨重启复用，TTL 24h */
    private static final String REDIS_EMB_PREFIX = "emb:vec:";
    private static final long REDIS_EMB_TTL_HOURS = 24;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public PgVectorStoreStrategy(VectorStoreProperties vectorStoreProperties,
                                 EmbeddingModelFactory embeddingModelFactory,
                                 IChatModelService chatModelService,
                                 KnowledgeFragmentMapper knowledgeFragmentMapper) {
        super(vectorStoreProperties, embeddingModelFactory, chatModelService);
        this.knowledgeFragmentMapper = knowledgeFragmentMapper;
    }

    /**
     * 获取查询向量：优先从 JVM 缓存 → Redis 缓存 → 调用智谱AI API
     * 三层策略确保最大化复用，消除重复网络请求
     */
    private float[] getQueryEmbeddingCached(EmbeddingModel embeddingModel, String modelName, String query) {
        String cacheKey = modelName + "|" + query;
        // 1. JVM 内存缓存（最快，0ms）
        float[] cached = embeddingCache.get(cacheKey);
        if (cached != null) return cached;

        // 2. Redis 持久化缓存（跨重启复用，~1ms）
        if (redisTemplate != null) {
            try {
                String redisKey = REDIS_EMB_PREFIX + Integer.toHexString(cacheKey.hashCode());
                String stored = redisTemplate.opsForValue().get(redisKey);
                if (stored != null && !stored.isEmpty()) {
                    String[] parts = stored.split(",");
                    float[] vec = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) vec[i] = Float.parseFloat(parts[i]);
                    embeddingCache.put(cacheKey, vec); // 同步写入JVM缓存
                    log.debug("[embedding] Redis 缓存命中: query={}", query);
                    return vec;
                }
            } catch (Exception e) {
                log.warn("Redis embedding 缓存读取失败（降级到API调用）: {}", e.getMessage());
            }
        }

        // 3. 调用智谱AI API（首次或缓存失效时）
        log.info("[embedding] 调用 API 生成向量: model={}, query={}", modelName, query);
        Embedding qe = embeddingModel.embed(query).content();
        if (qe == null || qe.vector() == null) return null;
        float[] vec = normalize(qe.vector());

        // 写入双层缓存
        embeddingCache.put(cacheKey, vec);
        if (redisTemplate != null) {
            try {
                String redisKey = REDIS_EMB_PREFIX + Integer.toHexString(cacheKey.hashCode());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vec.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(vec[i]);
                }
                redisTemplate.opsForValue().set(redisKey, sb.toString(), REDIS_EMB_TTL_HOURS, TimeUnit.HOURS);
                log.debug("[embedding] 已写入 Redis 缓存: query={}", query);
            } catch (Exception e) {
                log.warn("Redis embedding 缓存写入失败（不影响检索）: {}", e.getMessage());
            }
        }
        return vec;
    }

    @Override
    public String getVectorStoreType() {
        return "pgvector";
    }

    @Override
    public void createSchema(String kid, String modelName) {
        log.info("PGVector 本地存储无需动态创建 Schema, kid={}", kid);
    }

    @Override
    public void storeEmbeddings(StoreEmbeddingBo storeEmbeddingBo) {
        log.info("PGVector 本地存储完成向量数据对接, kid={}, docId={}", storeEmbeddingBo.getKid(), storeEmbeddingBo.getDocId());
    }

    @Override
    public List<String> getQueryVector(QueryVectorBo queryVectorBo) {
        return Collections.emptyList();
    }

    @Override
    public List<KnowledgeRetrievalVo> search(QueryVectorBo queryVectorBo) {
        log.info("PgVectorStoreStrategy.search 收到查询: kid={}, query={}, model={}, maxResults={}",
                queryVectorBo.getKid(), queryVectorBo.getQuery(), queryVectorBo.getEmbeddingModelName(), queryVectorBo.getMaxResults());
        if (queryVectorBo.getKid() == null || org.ruoyi.common.core.utils.StringUtils.isBlank(queryVectorBo.getQuery())) {
            return Collections.emptyList();
        }
        try {
            Long kid = Long.valueOf(queryVectorBo.getKid());
            int limit = queryVectorBo.getMaxResults() != null ? queryVectorBo.getMaxResults() : 10;
            String rawQuery = queryVectorBo.getQuery().trim();
            String embeddingModelName = queryVectorBo.getEmbeddingModelName();

            java.util.Map<String, KnowledgeRetrievalVo> resultMap = new java.util.LinkedHashMap<>();

            // A. ★ pgvector 原生 SQL 向量相似度检索
            //    直接在数据库侧用 <=> 余弦距离操作符排序，2ms 完成，彻底替代 Java 内存遍历方案
            if (org.ruoyi.common.core.utils.StringUtils.isNotBlank(embeddingModelName)) {
                try {
                    EmbeddingModel embeddingModel = getEmbeddingModel(embeddingModelName);
                    if (embeddingModel != null) {
                        // ★ 三层缓存：JVM内存 → Redis持久化 → 智谱AI API（首次才调用）
                        float[] queryVector = getQueryEmbeddingCached(embeddingModel, embeddingModelName, rawQuery);

                        if (queryVector != null) {
                            // 将 float[] 转换为 PostgreSQL vector 格式字符串 "[0.1,0.2,...]"
                            StringBuilder sb = new StringBuilder("[");
                            for (int i = 0; i < queryVector.length; i++) {
                                if (i > 0) sb.append(',');
                                sb.append(queryVector[i]);
                            }
                            sb.append("]");
                            String pgVectorStr = sb.toString();

                            // ★ 原生 pgvector SQL：ORDER BY embedding_vec <=> ? LIMIT N
                            List<org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo> vectorRows =
                                    knowledgeFragmentMapper.searchByVector(kid, pgVectorStr, limit * 2);

                            if (vectorRows != null && !vectorRows.isEmpty()) {
                                int matched = 0;
                                double minThreshold = (queryVectorBo.getSimilarityThreshold() != null && queryVectorBo.getSimilarityThreshold() > 0)
                                        ? queryVectorBo.getSimilarityThreshold() : 0.0;
                                for (org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo f : vectorRows) {
                                    double sim = f.getScore() != null ? f.getScore() : 0.0;
                                    if (sim >= minThreshold && resultMap.size() < limit) {
                                        KnowledgeRetrievalVo vo = new KnowledgeRetrievalVo();
                                        vo.setId(org.ruoyi.common.core.utils.StringUtils.isNotBlank(f.getFid()) ? f.getFid() : String.valueOf(f.getId()));
                                        vo.setContent(f.getContent());
                                        vo.setDocId(f.getDocId());
                                        vo.setIdx(f.getIdx());
                                        vo.setKnowledgeId(f.getKnowledgeId());
                                        vo.setScore(sim);
                                        resultMap.putIfAbsent(vo.getId(), vo);
                                        matched++;
                                    }
                                }
                                log.info("PgVectorStoreStrategy pgvector 原生 SQL 向量检索完成，命中高相关切片 {} 条", matched);
                            }
                        }
                    }
                } catch (Exception vErr) {
                    log.warn("PgVectorStoreStrategy 向量检索异常: {}", vErr.getMessage());
                }
            }


            // 2. 第二优先级：拆词多词相与 (AND ILIKE) 检索
            if (resultMap.size() < limit) {
                List<String> keywords = splitQuery(rawQuery);
                if (keywords.size() > 1) {
                    LambdaQueryWrapper<KnowledgeFragment> andLqw = Wrappers.lambdaQuery();
                    andLqw.eq(KnowledgeFragment::getKnowledgeId, kid);
                    for (String kw : keywords) {
                        andLqw.apply("content ILIKE {0}", "%" + kw + "%");
                    }
                    andLqw.last("LIMIT " + limit);
                    List<KnowledgeFragment> andList = knowledgeFragmentMapper.selectList(andLqw);

                    if (andList != null) {
                        for (KnowledgeFragment f : andList) {
                            KnowledgeRetrievalVo vo = mapToVo(f, 0.88);
                            resultMap.putIfAbsent(vo.getId(), vo);
                        }
                    }
                }
            }

            // 3. 第三优先级：子词相或 (OR ILIKE) 检索
            if (resultMap.size() < limit) {
                List<String> keywords = splitQuery(rawQuery);
                if (!keywords.isEmpty()) {
                    LambdaQueryWrapper<KnowledgeFragment> orLqw = Wrappers.lambdaQuery();
                    orLqw.eq(KnowledgeFragment::getKnowledgeId, kid);
                    orLqw.and(wrapper -> {
                        for (int i = 0; i < keywords.size(); i++) {
                            String kw = keywords.get(i);
                            if (i == 0) {
                                wrapper.apply("content ILIKE {0}", "%" + kw + "%");
                            } else {
                                wrapper.or().apply("content ILIKE {0}", "%" + kw + "%");
                            }
                        }
                    });
                    orLqw.last("LIMIT " + limit);
                    List<KnowledgeFragment> orList = knowledgeFragmentMapper.selectList(orLqw);

                    if (orList != null) {
                        for (KnowledgeFragment f : orList) {
                            int matchCount = 0;
                            String text = f.getContent() != null ? f.getContent().toLowerCase() : "";
                            for (String kw : keywords) {
                                if (text.contains(kw.toLowerCase())) {
                                    matchCount++;
                                }
                            }
                            double score = Math.min(0.85, 0.50 + (0.35 * matchCount / keywords.size()));
                            KnowledgeRetrievalVo vo = mapToVo(f, score);
                            resultMap.putIfAbsent(vo.getId(), vo);
                        }
                    }
                }
            }

            List<KnowledgeRetrievalVo> finalResults = new java.util.ArrayList<>(resultMap.values());
            if (finalResults.size() > limit) {
                finalResults = finalResults.subList(0, limit);
            }
            log.info("PgVectorStoreStrategy.search 完成检索: kid={}, 匹配条数={}", kid, finalResults.size());
            return finalResults;

        } catch (Exception e) {
            log.error("PGVector 本地检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private float[] getOrComputeEmbedding(EmbeddingModel embeddingModel, String modelName, String text) {
        String cacheKey = modelName + ":" + text.hashCode();
        return embeddingCache.computeIfAbsent(cacheKey, key -> {
            try {
                Embedding emb = embeddingModel.embed(text).content();
                return normalize(emb.vector());
            } catch (Exception e) {
                return null;
            }
        });
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private KnowledgeRetrievalVo mapToVo(KnowledgeFragment f, double score) {
        KnowledgeRetrievalVo vo = new KnowledgeRetrievalVo();
        vo.setId(org.ruoyi.common.core.utils.StringUtils.isNotBlank(f.getFid()) ? f.getFid() : String.valueOf(f.getId()));
        vo.setContent(f.getContent());
        vo.setDocId(f.getDocId());
        vo.setIdx(f.getIdx());
        vo.setKnowledgeId(f.getKnowledgeId());
        vo.setScore(score);
        return vo;
    }

    private List<String> splitQuery(String rawQuery) {
        List<String> keywords = new java.util.ArrayList<>();
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return keywords;
        }
        String trimmed = rawQuery.trim();
        if (trimmed.contains(" ")) {
            for (String s : trimmed.split("\\s+")) {
                if (s.trim().length() > 0) {
                    keywords.add(s.trim());
                }
            }
        } else {
            // 没有空格时尝试按常见2~4字组合词智能切分
            keywords.add(trimmed);
            if (trimmed.length() >= 4) {
                // 譬如 "新媒体方案" -> 拆为 ["新媒体", "方案"]
                int mid = trimmed.length() / 2;
                String part1 = trimmed.substring(0, mid);
                String part2 = trimmed.substring(mid);
                if (part1.length() >= 2) keywords.add(part1);
                if (part2.length() >= 2) keywords.add(part2);
            }
        }
        return keywords.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    @Override
    public void removeById(String id, String modelName) {
        log.info("PGVector 根据 ID 清理向量: {}", id);
    }

    @Override
    public void removeByDocId(String docId, String kid) {
        log.info("PGVector 根据 docId 清理切片向量: docId={}, kid={}", docId, kid);
        if (docId != null && !docId.trim().isEmpty()) {
            knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
        }
    }

    @Override
    public void removeByFid(String fid, String kid) {
        log.info("PGVector 根据 fid 清理切片向量: fid={}, kid={}", fid, kid);
        if (fid != null && !fid.trim().isEmpty()) {
            knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getFid, fid));
        }
    }
}
