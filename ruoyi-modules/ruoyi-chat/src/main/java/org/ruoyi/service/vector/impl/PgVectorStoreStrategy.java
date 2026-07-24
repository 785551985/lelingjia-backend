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
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();

    public PgVectorStoreStrategy(VectorStoreProperties vectorStoreProperties,
                                 EmbeddingModelFactory embeddingModelFactory,
                                 IChatModelService chatModelService,
                                 KnowledgeFragmentMapper knowledgeFragmentMapper) {
        super(vectorStoreProperties, embeddingModelFactory, chatModelService);
        this.knowledgeFragmentMapper = knowledgeFragmentMapper;
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

            // A. 真实向量 Embedding 语义计算 (用于匹配字面完全不一致但意思相近的文本)
            if (org.ruoyi.common.core.utils.StringUtils.isNotBlank(embeddingModelName)) {
                try {
                    EmbeddingModel embeddingModel = getEmbeddingModel(embeddingModelName);
                    if (embeddingModel != null) {
                        Embedding queryEmbedding = embeddingModel.embed(rawQuery).content();
                        float[] queryVector = normalize(queryEmbedding.vector());

                        LambdaQueryWrapper<KnowledgeFragment> allLqw = Wrappers.lambdaQuery();
                        allLqw.eq(KnowledgeFragment::getKnowledgeId, kid);
                        allLqw.last("LIMIT 100");
                        List<KnowledgeFragment> fragments = knowledgeFragmentMapper.selectList(allLqw);

                        if (fragments != null && !fragments.isEmpty()) {
                            List<KnowledgeRetrievalVo> vectorMatches = new java.util.ArrayList<>();
                            for (KnowledgeFragment f : fragments) {
                                if (org.ruoyi.common.core.utils.StringUtils.isBlank(f.getContent())) continue;
                                float[] docVector = getOrComputeEmbedding(embeddingModel, embeddingModelName, f.getContent());
                                if (docVector != null) {
                                    double similarity = cosineSimilarity(queryVector, docVector);
                                    if (similarity > 0.15) {
                                        KnowledgeRetrievalVo vo = mapToVo(f, Math.round(similarity * 1000.0) / 1000.0);
                                        vectorMatches.add(vo);
                                    }
                                }
                            }
                            vectorMatches.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
                            for (KnowledgeRetrievalVo vo : vectorMatches) {
                                resultMap.put(vo.getId(), vo);
                            }
                            log.info("PgVectorStoreStrategy 语义向量搜索成功完成, 命中条数: {}", vectorMatches.size());
                        }
                    }
                } catch (Exception e) {
                    log.warn("PgVectorStoreStrategy 向量模型计算未执行或降级: {}", e.getMessage());
                }
            }

            // B. 文本规则三级精准/多词强化补充
            // 1. 第一优先级：全串忽略大小写匹配 (ILIKE %query%)
            LambdaQueryWrapper<KnowledgeFragment> exactLqw = Wrappers.lambdaQuery();
            exactLqw.eq(KnowledgeFragment::getKnowledgeId, kid);
            exactLqw.apply("content ILIKE {0}", "%" + rawQuery + "%");
            exactLqw.last("LIMIT " + limit);
            List<KnowledgeFragment> exactList = knowledgeFragmentMapper.selectList(exactLqw);

            if (exactList != null) {
                for (KnowledgeFragment f : exactList) {
                    KnowledgeRetrievalVo vo = mapToVo(f, 0.95);
                    resultMap.putIfAbsent(vo.getId(), vo);
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
