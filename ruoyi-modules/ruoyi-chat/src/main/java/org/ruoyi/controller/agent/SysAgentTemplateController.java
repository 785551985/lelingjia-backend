package org.ruoyi.controller.agent;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.domain.entity.agent.SysAgentTemplate;
import org.ruoyi.mapper.agent.SysAgentTemplateMapper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 平台统一预设智能体模板管理 Controller
 *
 * @author ruoyi
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/agentTemplate")
public class SysAgentTemplateController extends BaseController {

    private final SysAgentTemplateMapper baseMapper;

    /**
     * 分页查询平台智能体模板列表
     */
    @SaCheckPermission("system:agentTemplate:list")
    @GetMapping("/list")
    public TableDataInfo<SysAgentTemplate> list(SysAgentTemplate bo, PageQuery pageQuery) {
        LambdaQueryWrapper<SysAgentTemplate> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getAgentName()), SysAgentTemplate::getAgentName, bo.getAgentName());
        lqw.eq(StringUtils.isNotBlank(bo.getTag()), SysAgentTemplate::getTag, bo.getTag());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), SysAgentTemplate::getStatus, bo.getStatus());
        lqw.orderByAsc(SysAgentTemplate::getSortOrder);
        Page<SysAgentTemplate> page = baseMapper.selectPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    /**
     * 获取智能体模板详情
     */
    @SaCheckPermission("system:agentTemplate:query")
    @GetMapping("/{id}")
    public R<SysAgentTemplate> getInfo(@PathVariable Long id) {
        return R.ok(baseMapper.selectById(id));
    }

    /**
     * 新增平台智能体模板
     */
    @SaCheckPermission("system:agentTemplate:add")
    @PostMapping
    public R<Void> add(@RequestBody SysAgentTemplate entity) {
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        return toAjax(baseMapper.insert(entity));
    }

    /**
     * 修改平台智能体模板
     */
    @SaCheckPermission("system:agentTemplate:edit")
    @PutMapping
    public R<Void> edit(@RequestBody SysAgentTemplate entity) {
        entity.setUpdateTime(new Date());
        return toAjax(baseMapper.updateById(entity));
    }

    /**
     * 删除平台智能体模板
     */
    @SaCheckPermission("system:agentTemplate:remove")
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(baseMapper.deleteByIds(List.of(ids)));
    }
}
