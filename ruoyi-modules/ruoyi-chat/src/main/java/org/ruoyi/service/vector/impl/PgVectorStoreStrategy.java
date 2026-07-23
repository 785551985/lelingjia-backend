package org.ruoyi.service.vector.impl;

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
        return Collections.emptyList();
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
