package org.ruoyi.domain.bo.knowledge;

import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;

/**
 * 知识库附件业务对象 knowledge_attach
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = KnowledgeAttach.class, reverseConvertGenerate = false)
public class KnowledgeAttachBo extends BaseEntity {

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 知识库ID
     */
    @NotNull(message = "知识库ID不能为空", groups = { AddGroup.class, EditGroup.class })
    private Long knowledgeId;


    /**
     * 文档ID-用于关联文本块信息
     */
    private String docId;

    /**
     * 附件名称
     */
    private String name;

    /**
     * 附件类型
     */
    @NotBlank(message = "附件类型不能为空", groups = { AddGroup.class, EditGroup.class })
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
     * 解析状态 (0-未解析 1-解析中 2-解析成功 3-解析失败)
     */
    private String status;

    /**
     * 审批状态 (0-未审批 1-待审核 2-已通过 3-已驳回)
     */
    private String approveStatus;


}
