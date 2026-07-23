package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 知识库附件对象 knowledge_attach
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_attach")
public class KnowledgeAttach extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * 文档ID-用于关联文本块信息
     */
    private String docId;

    /** SHA-256 content digest used for upload idempotency. */
    private String fileHash;

    /**
     * 附件名称
     */
    private String name;

    /**
     * 附件类型
     */
    private String type;

    /**
     * 对象存储ID
     */
    private Long ossId;

    /**
     * 备注
     */
    private String remark;

    /**
     * 解析状态: 0待解析, 1解析中, 2已解析, 3解析失败
     */
    private Integer status;

    /**
     * 审核状态: 0-待审核, 1-审核中, 2-审核通过, 3-已驳回
     */
    private String approveStatus;

    /**
     * 关联的 Warm-Flow 审批实例 ID
     */
    private String flowInstanceId;

    /**
     * 文档版本号
     */
    private String version;

    /**
     * 时效状态: latest-最新, archive-历史归档
     */
    private String effectiveStatus;

    /**
     * 作用域级别（1 集团 2 机构 3 部门 4 个人）
     */
    private Integer scopeLevel;

    /**
     * 绑定部门范围
     */
    private String deptScope;

}
