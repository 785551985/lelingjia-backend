package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 知识片段对象 knowledge_fragment
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "knowledge_fragment", autoResultMap = true)
public class KnowledgeFragment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 向量库片段ID（与向量库中的 fid 元数据对应，用于向量定位与混合检索融合）
     */
    private String fid;

    /**
     * 文档ID-用于关联文本块信息
     */
    private String docId;

    /**
     * 片段索引下标
     */
    private Integer idx;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 备注
     */
    private String remark;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * 向量持久化数据 (Float 数组，兼容旧列)
     */
    @TableField(value = "embedding_vector", typeHandler = org.ruoyi.handler.PgFloatArrayTypeHandler.class)
    private Float[] embeddingVector;

    /**
     * pgvector 原生 vector 类型列（用于 <=> 余弦距离运算符高效检索）
     * 存储时通过 UPDATE SQL 同步写入，MyBatis-Plus 不直接管理此字段
     */
    @TableField(exist = false)
    private String embeddingVecStr;

}
