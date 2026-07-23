package org.ruoyi.domain.vo.knowledge;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;



/**
 * 知识库附件视图对象 knowledge_attach
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = KnowledgeAttach.class)
public class KnowledgeAttachVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 知识库ID
     */
    @ExcelProperty(value = "知识库ID")
    private Long knowledgeId;

    /**
     * 文档ID-用于关联文本块信息
     */
    @ExcelProperty(value = "文档ID")
    private String docId;

    /**
     * 附件名称
     */
    @ExcelProperty(value = "附件名称")
    private String name;

    /**
     * 附件类型
     */
    @ExcelProperty(value = "附件类型")
    private String type;

    /**
     * 对象存储ID
     */
    @ExcelProperty(value = "对象存储ID")
    private Long ossId;


    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 上传时间（来自 BaseEntity.createTime）
     */
    @ExcelProperty(value = "上传时间")
    private Date createTime;

    /**
     * 解析状态: 0待解析, 1解析中, 2已解析, 3解析失败
     */
    @ExcelProperty(value = "解析状态")
    private Integer status;

    /**
     * 审核状态: 0-待审核, 1-审核中, 2-审核通过, 3-已驳回
     */
    @ExcelProperty(value = "审核状态")
    private String approveStatus;

    /**
     * 作用域级别（1 集团 2 机构 3 部门 4 个人）
     */
    private Integer scopeLevel;

    /**
     * 绑定部门范围
     */
    private String deptScope;

    /**
     * 所属知识库名称
     */
    private String knowledgeName;

    /**
     * 创建人/上传者姓名
     */
    private String createByName;

    /**
     * 分块数（统计字段，非数据库列）
     */
    private Integer fragmentCount;

}
