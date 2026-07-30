package org.ruoyi.service.knowledge.impl;

import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.knowledge.DocumentSplitConfig;
import org.ruoyi.common.core.service.OssService;

import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 知识库Service业务层处理
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeInfoServiceImpl implements IKnowledgeInfoService {

    private final KnowledgeInfoMapper baseMapper;

    private final KnowledgeAttachMapper knowledgeAttachMapper;

    private final org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper knowledgeFragmentMapper;

    private final org.ruoyi.service.vector.VectorStoreService vectorStoreService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final OssService ossService;

    /**
     * 查询知识库
     *
     * @param id 主键
     * @return 知识库
     */
    @Override
    public KnowledgeInfoVo queryById(Long id){
        return baseMapper.selectVoById(id);
    }

    /**
     * 分页查询知识库列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库分页列表
     */
    @Override
    public TableDataInfo<KnowledgeInfoVo> queryPageList(KnowledgeInfoBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildQueryWrapper(bo);
        Page<KnowledgeInfoVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        // 批量填充文档数
        fillDocumentCount(result.getRecords());
        return TableDataInfo.build(result);
    }

    /**
     * 查询符合条件的知识库列表
     *
     * @param bo 查询条件
     * @return 知识库列表
     */
    @Override
    public List<KnowledgeInfoVo> queryList(KnowledgeInfoBo bo) {
        LambdaQueryWrapper<KnowledgeInfo> lqw = buildQueryWrapper(bo);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<KnowledgeInfo> buildQueryWrapper(KnowledgeInfoBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<KnowledgeInfo> lqw = Wrappers.lambdaQuery();
        lqw.orderByDesc(KnowledgeInfo::getId);
        lqw.eq(bo.getUserId() != null, KnowledgeInfo::getUserId, bo.getUserId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), KnowledgeInfo::getName, bo.getName());
        lqw.eq(bo.getShare() != null, KnowledgeInfo::getShare, bo.getShare());
        lqw.eq(StringUtils.isNotBlank(bo.getDescription()), KnowledgeInfo::getDescription, bo.getDescription());
        lqw.eq(StringUtils.isNotBlank(bo.getSeparator()), KnowledgeInfo::getSeparator, bo.getSeparator());
        lqw.eq(bo.getOverlapChar() != null, KnowledgeInfo::getOverlapChar, bo.getOverlapChar());
        lqw.eq(bo.getRetrieveLimit() != null, KnowledgeInfo::getRetrieveLimit, bo.getRetrieveLimit());
        lqw.eq(bo.getTextBlockSize() != null, KnowledgeInfo::getTextBlockSize, bo.getTextBlockSize());
        lqw.eq(StringUtils.isNotBlank(bo.getVectorModel()), KnowledgeInfo::getVectorModel, bo.getVectorModel());
        lqw.eq(StringUtils.isNotBlank(bo.getEmbeddingModel()), KnowledgeInfo::getEmbeddingModel, bo.getEmbeddingModel());

        // 作用域与数据权限隔离：若非超级管理员，自动限定当前员工可见的知识库范围
        try {
            if (!org.ruoyi.common.satoken.utils.LoginHelper.isSuperAdmin()) {
                Long deptId = org.ruoyi.common.satoken.utils.LoginHelper.getDeptId();
                Long userId = org.ruoyi.common.satoken.utils.LoginHelper.getUserId();

                lqw.and(wrapper -> {
                    // 1. 集团级全局公共库 (scope_level = 1)
                    wrapper.eq(KnowledgeInfo::getScopeLevel, 1);

                    // 2. 机构/部门级知识库 (scope_level = 2 或 3，且 deptScope 包含员工的部门 ID)
                    if (deptId != null) {
                        wrapper.or(w -> w.in(KnowledgeInfo::getScopeLevel, List.of(2, 3))
                            .apply("(dept_scope IS NULL OR dept_scope = '' OR dept_scope LIKE {0})", "%" + deptId + "%"));
                    }

                    // 3. 个人专属私有库 (scope_level = 4 且 user_id 匹配)
                    if (userId != null) {
                        wrapper.or(w -> w.eq(KnowledgeInfo::getScopeLevel, 4).eq(KnowledgeInfo::getUserId, userId));
                    }
                });
            }
        } catch (Exception e) {
            log.warn("构建知识库作用域过滤条件时未获取到登录上下文, 将仅展示公共库", e.getMessage());
            lqw.eq(KnowledgeInfo::getScopeLevel, 1);
        }

        return lqw;
    }

    /**
     * 批量填充知识库列表每一条记录的文档数（documentCount）
     */
    private void fillDocumentCount(List<KnowledgeInfoVo> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(KnowledgeInfoVo::getId).toList();
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : knowledgeAttachMapper.countByKnowledgeIds(ids)) {
            Number kid = (Number) (row.get("knowledgeId") != null ? row.get("knowledgeId") : row.get("knowledgeid"));
            Number count = (Number) (row.get("documentCount") != null ? row.get("documentCount") : row.get("documentcount"));
            if (kid != null && count != null) counts.put(kid.longValue(), count.intValue());
        }
        records.forEach(vo -> vo.setDocumentCount(counts.getOrDefault(vo.getId(), 0)));
    }

    /**
     * 新增知识库
     *
     * @param bo 知识库
     * @return 是否新增成功
     */
    @Override
    public Boolean insertByBo(KnowledgeInfoBo bo) {
        KnowledgeInfo add = MapstructUtils.convert(bo, KnowledgeInfo.class);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改知识库
     *
     * @param bo 知识库
     * @return 是否修改成功
     */
    @Override
    public Boolean updateByBo(KnowledgeInfoBo bo) {
        KnowledgeInfo update = MapstructUtils.convert(bo, KnowledgeInfo.class);
        validEntityBeforeSave(update);
        boolean updated = baseMapper.updateById(update) > 0;
        if (updated) knowledgeRetrievalService.invalidateKnowledge(String.valueOf(bo.getId()));
        return updated;
    }

    /**
     * 保存前的数据校验
     */
    private void validEntityBeforeSave(KnowledgeInfo entity){
        int blockSize = entity.getTextBlockSize() == null
            ? DocumentSplitConfig.DEFAULT_BLOCK_SIZE : entity.getTextBlockSize().intValue();
        int overlap = entity.getOverlapChar() == null
            ? DocumentSplitConfig.DEFAULT_OVERLAP : entity.getOverlapChar().intValue();
        new DocumentSplitConfig(entity.getSeparator(), blockSize, overlap, "");

        // 智能同主体防重校验：同一作用域级别与主体范围内，禁止创建同名知识库
        if (StringUtils.isNotBlank(entity.getName())) {
            LambdaQueryWrapper<KnowledgeInfo> checkLqw = Wrappers.lambdaQuery(KnowledgeInfo.class)
                .eq(KnowledgeInfo::getName, entity.getName().trim())
                .eq(entity.getScopeLevel() != null, KnowledgeInfo::getScopeLevel, entity.getScopeLevel())
                .ne(entity.getId() != null, KnowledgeInfo::getId, entity.getId());

            // 个人私有级 (scopeLevel = 4)：限定同一创建者个人不可重名
            if (Integer.valueOf(4).equals(entity.getScopeLevel()) && entity.getUserId() != null) {
                checkLqw.eq(KnowledgeInfo::getUserId, entity.getUserId());
            } 
            // 部门/机构级 (scopeLevel = 2 或 3)：限定同一部门/机构主体下不可重名
            else if ((Integer.valueOf(2).equals(entity.getScopeLevel()) || Integer.valueOf(3).equals(entity.getScopeLevel())) && StringUtils.isNotBlank(entity.getDeptScope())) {
                checkLqw.eq(KnowledgeInfo::getDeptScope, entity.getDeptScope());
            }

            if (baseMapper.exists(checkLqw)) {
                throw new org.ruoyi.common.core.exception.ServiceException("当前主体/部门范围内已存在同名知识库【" + entity.getName() + "】，请使用区分度更高的名称！");
            }
        }
    }

    /**
     * 校验并批量删除知识库信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if(isValid){
            //TODO 做一些业务上的校验,判断是否需要校验
        }
        for (Long kid : ids) {
            KnowledgeInfo info = baseMapper.selectById(kid);
            // 1. 删除向量库中该知识库的所有向量（按文档逐个清理，三种向量库行为一致）
            List<org.ruoyi.domain.entity.knowledge.KnowledgeAttach> attaches = knowledgeAttachMapper.selectList(
                Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeAttach.class)
                    .eq(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getKnowledgeId, kid));
            try {
                vectorStoreService.removeById(String.valueOf(kid), info == null ? null : info.getVectorModel());
            } catch (Exception ex) {
                log.error("删除知识库关联的向量数据失败，kid={}, 异常原因: {}", kid, ex.getMessage());
            }
            List<Long> ossIds = attaches.stream()
                    .map(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getOssId)
                    .filter(java.util.Objects::nonNull).toList();
            if (!ossIds.isEmpty()) {
                for (Long ossId : ossIds) {
                    ossService.deleteFile(ossId);
                }
            }
            // 2. 删除该知识库下的附件与片段记录
            knowledgeAttachMapper.delete(Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeAttach.class)
                .eq(org.ruoyi.domain.entity.knowledge.KnowledgeAttach::getKnowledgeId, kid));
            knowledgeFragmentMapper.delete(Wrappers.lambdaQuery(org.ruoyi.domain.entity.knowledge.KnowledgeFragment.class)
                .eq(org.ruoyi.domain.entity.knowledge.KnowledgeFragment::getKnowledgeId, kid));
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(kid));
        }
        return baseMapper.deleteByIds(ids) > 0;
    }
}
