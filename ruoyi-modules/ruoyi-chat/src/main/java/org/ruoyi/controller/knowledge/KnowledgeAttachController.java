package org.ruoyi.controller.knowledge;

import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.ruoyi.domain.bo.knowledge.KnowledgeAttachBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoUploadBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;
import org.ruoyi.service.knowledge.IKnowledgeAttachService;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;

/**
 * 知识库附件
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/attach")
public class KnowledgeAttachController extends BaseController {

    private final IKnowledgeAttachService knowledgeAttachService;

    /**
     * 查询知识库附件列表
     */
    @cn.dev33.satoken.annotation.SaCheckLogin
    @GetMapping("/list")
    public TableDataInfo<KnowledgeAttachVo> list(KnowledgeAttachBo bo, PageQuery pageQuery) {
        return knowledgeAttachService.queryPageList(bo, pageQuery);
    }

    /**
     * 导出知识库附件列表
     */
    @SaCheckPermission("system:attach:export")
    @Log(title = "知识库附件", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(KnowledgeAttachBo bo, HttpServletResponse response) {
        List<KnowledgeAttachVo> list = knowledgeAttachService.queryList(bo);
        ExcelUtil.exportExcel(list, "知识库附件", KnowledgeAttachVo.class, response);
    }

    /**
     * 获取知识库附件详细信息
     *
     * @param id 主键
     */
    @cn.dev33.satoken.annotation.SaCheckLogin
    @GetMapping("/{id}")
    public R<KnowledgeAttachVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(knowledgeAttachService.queryById(id));
    }

    /**
     * 新增知识库附件
     */
    @SaCheckPermission("system:attach:add")
    @Log(title = "知识库附件", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody KnowledgeAttachBo bo) {
        return toAjax(knowledgeAttachService.insertByBo(bo));
    }

    /**
     * 修改知识库附件
     */
    @SaCheckPermission("system:attach:edit")
    @Log(title = "知识库附件", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody KnowledgeAttachBo bo) {
        return toAjax(knowledgeAttachService.updateByBo(bo));
    }

    /**
     * 删除知识库附件
     *
     * @param ids 主键串
     */
    @SaCheckPermission(value = {"system:info:remove", "system:attach:remove"}, mode = SaMode.OR)
    @Log(title = "知识库附件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(knowledgeAttachService.deleteWithValidByIds(List.of(ids), true));
    }

    /**
     * 上传知识库附件
     * 注意：multipart 上传不能加 @RepeatSubmit（其参数序列化不支持 MultipartFile）
     */
    @SaCheckPermission(value = {"system:info:add", "system:attach:add"}, mode = SaMode.OR)
    @Log(title = "知识库附件", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload")
    public R<String> upload(KnowledgeInfoUploadBo bo){
        knowledgeAttachService.upload(bo);
        return R.ok("上传成功!");
    }

    /**
     * 手动解析附件内容
     *
     * @param id 附件ID
     */
    @SaCheckPermission(value = {"system:info:edit", "system:attach:edit"}, mode = SaMode.OR)
    @Log(title = "知识库附件", businessType = BusinessType.UPDATE)
    @PostMapping("/parse/{id}")
    @RepeatSubmit()
    public R<Void> parse(@PathVariable Long id) {
        knowledgeAttachService.parse(id);
        return R.ok();
    }

    @SaCheckPermission(value = {"system:info:edit", "system:attach:edit"}, mode = SaMode.OR)
    @Log(title = "知识库附件批量重新解析", businessType = BusinessType.UPDATE)
    @PostMapping("/reparse/knowledge/{knowledgeId}")
    @RepeatSubmit()
    public R<KnowledgeReparseVo> reparseKnowledge(@PathVariable Long knowledgeId) {
        return R.ok(knowledgeAttachService.reparseKnowledge(knowledgeId));
    }

    /**
     * 初始化预设模板文档（创建内置范本并触发向量化）
     */
    @SaCheckPermission(value = {"system:info:add", "system:attach:add"}, mode = SaMode.OR)
    @Log(title = "知识库附件", businessType = BusinessType.INSERT)
    @PostMapping("/init-template")
    public R<Void> initTemplate(@RequestParam Long knowledgeId,
                                @RequestParam String templateKey,
                                @RequestParam String docName) {
        knowledgeAttachService.initTemplate(knowledgeId, templateKey, docName);
        return R.ok();
    }
}
