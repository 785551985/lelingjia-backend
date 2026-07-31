package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 平台统一知识库预设范本对象 sys_knowledge_template
 *
 * @author ruoyi
 */
@Data
@TableName("sys_knowledge_template")
public class SysKnowledgeTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId
    private Long id;

    /**
     * 范本标识 Key (如 common/sop/rule)
     */
    private String templateKey;

    /**
     * 范本名称
     */
    private String templateName;

    /**
     * 适用分类
     */
    private String category;

    /**
     * Markdown 富文本内容
     */
    private String content;

    /**
     * 状态 (0 正常 1 停用)
     */
    private String status;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 备注
     */
    private String remark;
}
