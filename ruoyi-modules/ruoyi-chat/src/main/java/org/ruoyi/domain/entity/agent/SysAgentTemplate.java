package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 平台统一智能体预设模板对象 sys_agent_template
 *
 * @author ruoyi
 */
@Data
@TableName("sys_agent_template")
public class SysAgentTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId
    private Long id;

    /**
     * 智能体 Key (如 general/hr/sales)
     */
    private String agentKey;

    /**
     * 智能体显示名称
     */
    private String agentName;

    /**
     * 标签名称
     */
    private String tag;

    /**
     * 标签颜色
     */
    private String tagColor;

    /**
     * 图标名称
     */
    private String iconName;

    /**
     * 图标背景类名
     */
    private String iconBg;

    /**
     * 功能描述
     */
    private String description;

    /**
     * 自动匹配知识库关键字
     */
    private String matchKb;

    /**
     * 人设 System Message 提示词
     */
    private String systemPrompt;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 状态 (0 正常 1 停用)
     */
    private String status;

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
