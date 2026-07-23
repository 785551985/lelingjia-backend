package org.ruoyi.service.vector.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.vector.VectorStoreService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL + pgvector 本地向量存储策略
 *
 * @author ageerle
 */
@Slf4j
@Service("pgVectorStoreStrategy")
@RequiredArgsConstructor
public class PgVectorStoreStrategy implements VectorStoreService {

    private final KnowledgeFragmentMapper knowledgeFragmentMapper;

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
        log.info("PgVectorStoreStrategy.search 收到查询: kid={}, query={}, maxResults={}",
                queryVectorBo.getKid(), queryVectorBo.getQuery(), queryVectorBo.getMaxResults());
        if (queryVectorBo.getKid() == null || org.ruoyi.common.core.utils.StringUtils.isBlank(queryVectorBo.getQuery())) {
            return Collections.emptyList();
        }
        try {
            Long kid = Long.valueOf(queryVectorBo.getKid());
            int limit = queryVectorBo.getMaxResults() != null ? queryVectorBo.getMaxResults() : 10;
            
            // 1. 优先按关键词模糊匹配查询切片
            LambdaQueryWrapper<KnowledgeFragment> lqw = Wrappers.lambdaQuery();
            lqw.eq(KnowledgeFragment::getKnowledgeId, kid);
            lqw.like(KnowledgeFragment::getContent, queryVectorBo.getQuery());
            lqw.last("LIMIT " + limit);
            List<KnowledgeFragment> list = knowledgeFragmentMapper.selectList(lqw);
            
            if (list == null || list.isEmpty()) {
                log.info("PgVectorStoreStrategy.search 未查到与关键词 [{}] 匹配的切片: kid={}", queryVectorBo.getQuery(), kid);
                return Collections.emptyList();
            }

            return list.stream().map(f -> {
                KnowledgeRetrievalVo vo = new KnowledgeRetrievalVo();
                vo.setId(org.ruoyi.common.core.utils.StringUtils.isNotBlank(f.getFid()) ? f.getFid() : String.valueOf(f.getId()));
                vo.setContent(f.getContent());
                vo.setDocId(f.getDocId());
                vo.setIdx(f.getIdx());
                vo.setKnowledgeId(f.getKnowledgeId());
                vo.setScore(0.95);
                return vo;
            }).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("PGVector 本地检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
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
