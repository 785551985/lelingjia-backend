package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 知识片段Mapper接口
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Mapper
public interface KnowledgeFragmentMapper extends BaseMapperPlus<KnowledgeFragment, KnowledgeFragmentVo> {

    /**
     * 批量统计各文档的分块数（强类型接收，避免 Map key 大小写问题）
     *
     * @param docIds 文档 ID 列表
     * @return 每个 docId 对应的分块数列表
     */
    @Select("<script>" +
            "SELECT doc_id AS docId, COUNT(*) AS fragmentCount " +
            "FROM knowledge_fragment " +
            "WHERE doc_id IN " +
            "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY doc_id" +
            "</script>")
    List<DocFragmentCountVo> selectFragmentCountByDocIds(@Param("docIds") List<String> docIds);

    @Select("<script>" +
            "SELECT id, fid, doc_id AS docId, content, idx, knowledge_id AS knowledgeId " +
            "FROM knowledge_fragment " +
            "WHERE knowledge_id = #{knowledgeId} " +
            "<if test='keywords != null and keywords.size() > 0'>" +
            "  <foreach collection='keywords' item='kw'>" +
            "    AND content ILIKE '%' || #{kw} || '%' " +
            "  </foreach>" +
            "</if>" +
            "LIMIT #{limit}" +
            "</script>")
    List<KnowledgeFragmentVo> searchByKeywords(@Param("knowledgeId") Long knowledgeId, @Param("keywords") List<String> keywords, @Param("limit") Integer limit);

    /**
     * 使用 pgvector 原生 <=> 余弦距离操作符进行向量相似度检索
     * 直接在数据库侧计算，性能远超 Java 内存遍历方式（全量拉取200条 → 纯SQL 2ms）
     *
     * @param knowledgeId 知识库 ID
     * @param queryVector 查询向量的字符串（PostgreSQL vector 格式：[0.1,0.2,...]）
     * @param limit       最多返回条数
     * @return 按余弦相似度从高到低排序的切片列表
     */
    @Select("SELECT id, fid, doc_id AS docId, content, idx, knowledge_id AS knowledgeId, " +
            "       ROUND(CAST(1 - (embedding_vec <=> #{queryVector}::vector) AS NUMERIC), 6) AS score " +
            "FROM knowledge_fragment " +
            "WHERE (#{knowledgeId} IS NULL OR knowledge_id = #{knowledgeId}) AND embedding_vec IS NOT NULL " +
            "ORDER BY embedding_vec <=> #{queryVector}::vector " +
            "LIMIT #{limit}")
    List<KnowledgeFragmentVo> searchByVector(@Param("knowledgeId") Long knowledgeId,
                                              @Param("queryVector") String queryVector,
                                              @Param("limit") Integer limit);

    @Select("SELECT DISTINCT knowledge_id FROM knowledge_fragment WHERE knowledge_id IS NOT NULL")
    List<Long> selectAllKnowledgeIds();

    default List<KnowledgeFragmentVo> searchByKeyword(Long knowledgeId, String query, Integer limit) {
        if (query == null || query.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<String> keywords = new java.util.ArrayList<>();
        String trimmed = query.trim();
        if (trimmed.contains(" ")) {
            for (String s : trimmed.split("\\s+")) {
                if (!s.trim().isEmpty()) {
                    keywords.add(s.trim());
                }
            }
        } else {
            keywords.add(trimmed);
        }
        return searchByKeywords(knowledgeId, keywords, limit);
    }
}
