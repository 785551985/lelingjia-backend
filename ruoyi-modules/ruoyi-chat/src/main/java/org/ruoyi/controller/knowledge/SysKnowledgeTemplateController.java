package org.ruoyi.controller.knowledge;

import cn.dev33.satoken.annotation.SaCheckLogin;
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
import org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate;
import org.ruoyi.mapper.knowledge.SysKnowledgeTemplateMapper;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 平台统一知识库范本管理 Controller
 *
 * @author ruoyi
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/knowledgeTemplate")
public class SysKnowledgeTemplateController extends BaseController {

    private final SysKnowledgeTemplateMapper baseMapper;

    /**
     * 分页查询平台知识库范本列表
     */
    @SaCheckPermission("system:knowledgeTemplate:list")
    @GetMapping("/list")
    public TableDataInfo<SysKnowledgeTemplate> list(SysKnowledgeTemplate bo, PageQuery pageQuery) {
        LambdaQueryWrapper<SysKnowledgeTemplate> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getTemplateName()), SysKnowledgeTemplate::getTemplateName, bo.getTemplateName());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), SysKnowledgeTemplate::getCategory, bo.getCategory());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), SysKnowledgeTemplate::getStatus, bo.getStatus());
        lqw.orderByAsc(SysKnowledgeTemplate::getSortOrder);
        Page<SysKnowledgeTemplate> page = baseMapper.selectPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    /**
     * 获取范本详情
     */
    @SaCheckPermission("system:knowledgeTemplate:query")
    @GetMapping("/{id}")
    public R<SysKnowledgeTemplate> getInfo(@PathVariable Long id) {
        return R.ok(baseMapper.selectById(id));
    }

    /**
     * 新增平台知识库范本
     */
    @SaCheckPermission("system:knowledgeTemplate:add")
    @PostMapping
    public R<Void> add(@RequestBody SysKnowledgeTemplate entity) {
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        return toAjax(baseMapper.insert(entity));
    }

    /**
     * 修改平台知识库范本
     */
    @SaCheckPermission("system:knowledgeTemplate:edit")
    @PutMapping
    public R<Void> edit(@RequestBody SysKnowledgeTemplate entity) {
        entity.setUpdateTime(new Date());
        return toAjax(baseMapper.updateById(entity));
    }

    /**
     * 删除平台知识库范本
     */
    @SaCheckPermission("system:knowledgeTemplate:remove")
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable Long[] ids) {
        return toAjax(baseMapper.deleteByIds(List.of(ids)));
    }
}
