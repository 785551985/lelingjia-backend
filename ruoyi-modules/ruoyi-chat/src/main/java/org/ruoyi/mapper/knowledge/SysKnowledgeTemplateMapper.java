package org.ruoyi.mapper.knowledge;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate;

/**
 * 平台统一知识库范本 Mapper 接口
 * (平台公共打底模版资产，使用 InterceptorIgnore 忽略多租户租户隔离)
 *
 * @author ruoyi
 */
@InterceptorIgnore(tenantLine = "true")
public interface SysKnowledgeTemplateMapper extends BaseMapperPlus<SysKnowledgeTemplate, SysKnowledgeTemplate> {

}
