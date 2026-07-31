package org.ruoyi.mapper.agent;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.agent.SysAgentTemplate;

/**
 * 平台统一智能体模板 Mapper 接口
 * (平台公共打底模版资产，使用 InterceptorIgnore 忽略多租户租户隔离)
 *
 * @author ruoyi
 */
@InterceptorIgnore(tenantLine = "true")
public interface SysAgentTemplateMapper extends BaseMapperPlus<SysAgentTemplate, SysAgentTemplate> {

}
