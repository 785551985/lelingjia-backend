package org.ruoyi.service.knowledge.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.enums.KnowledgeAttachStatus;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeAttachBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoUploadBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate;
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;

import org.ruoyi.common.oss.factory.OssFactory;
import org.ruoyi.factory.EmbeddingModelFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import org.ruoyi.factory.ResourceLoaderFactory;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeAttachService;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.ResourceLoader;
import org.ruoyi.service.knowledge.DocumentSplitConfig;
import org.ruoyi.service.vector.VectorStoreService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库附件Service业务层处理
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeAttachServiceImpl implements IKnowledgeAttachService {

    private final KnowledgeAttachMapper baseMapper;
    private final IKnowledgeInfoService knowledgeInfoService;
    private final KnowledgeFragmentMapper knowledgeFragmentMapper;
    private final IChatModelService chatModelService;
    private final ResourceLoaderFactory resourceLoaderFactory;
    private final VectorStoreService vectorStoreService;
    private final OssService ossService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final org.ruoyi.mapper.knowledge.SysKnowledgeTemplateMapper sysKnowledgeTemplateMapper;
    private final EmbeddingModelFactory embeddingModelFactory;

    @Autowired
    private KnowledgeInfoMapper knowledgeInfoMapper;

    @Override
    public KnowledgeAttachVo queryById(Long id) {
        KnowledgeAttachVo vo = baseMapper.selectVoById(id);
        if (vo != null) {
            fillKnowledgeInfo(Collections.singletonList(vo));
        }
        return vo;
    }

    @Override
    public TableDataInfo<KnowledgeAttachVo> queryPageList(KnowledgeAttachBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = buildQueryWrapper(bo);
        Page<KnowledgeAttachVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillFragmentCount(result.getRecords());
        fillKnowledgeInfo(result.getRecords());
        return TableDataInfo.build(result);
    }

    @Override
    public List<KnowledgeAttachVo> queryList(KnowledgeAttachBo bo) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = buildQueryWrapper(bo);
        List<KnowledgeAttachVo> list = baseMapper.selectVoList(lqw);
        fillFragmentCount(list);
        fillKnowledgeInfo(list);
        return list;
    }

    private void fillKnowledgeInfo(List<KnowledgeAttachVo> records) {
        if (records == null || records.isEmpty()) return;
        Set<Long> kIds = records.stream()
            .map(KnowledgeAttachVo::getKnowledgeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (kIds.isEmpty()) return;
        List<KnowledgeInfo> infoList = knowledgeInfoMapper.selectBatchIds(kIds);
        Map<Long, KnowledgeInfo> infoMap = infoList.stream()
            .collect(Collectors.toMap(KnowledgeInfo::getId, info -> info, (k1, k2) -> k1));
        for (KnowledgeAttachVo vo : records) {
            KnowledgeInfo info = infoMap.get(vo.getKnowledgeId());
            if (info != null) {
                vo.setKnowledgeName(info.getName());
                if (vo.getScopeLevel() == null) {
                    vo.setScopeLevel(info.getScopeLevel());
                }
                if (StringUtils.isBlank(vo.getDeptScope())) {
                    vo.setDeptScope(info.getDeptScope());
                }
            }
        }
    }

    private void fillFragmentCount(List<KnowledgeAttachVo> records) {
        if (records == null || records.isEmpty()) return;
        List<String> docIds = records.stream()
            .map(KnowledgeAttachVo::getDocId)
            .filter(StringUtils::isNotBlank)
            .distinct()
            .collect(Collectors.toList());
        if (docIds.isEmpty()) return;
        List<DocFragmentCountVo> countList = knowledgeFragmentMapper.selectFragmentCountByDocIds(docIds);
        Map<String, Integer> countMap = countList.stream()
            .collect(Collectors.toMap(DocFragmentCountVo::getDocId, DocFragmentCountVo::getFragmentCount, (k1, k2) -> k1));
        for (KnowledgeAttachVo vo : records) {
            vo.setFragmentCount(countMap.getOrDefault(vo.getDocId(), 0));
        }
    }

    private LambdaQueryWrapper<KnowledgeAttach> buildQueryWrapper(KnowledgeAttachBo bo) {
        LambdaQueryWrapper<KnowledgeAttach> lqw = Wrappers.lambdaQuery();
        lqw.orderByDesc(KnowledgeAttach::getId);
        lqw.eq(bo.getKnowledgeId() != null, KnowledgeAttach::getKnowledgeId, bo.getKnowledgeId());
        lqw.like(StringUtils.isNotBlank(bo.getName()), KnowledgeAttach::getName, bo.getName());
        lqw.eq(StringUtils.isNotBlank(bo.getType()), KnowledgeAttach::getType, bo.getType());
        lqw.eq(bo.getOssId() != null, KnowledgeAttach::getOssId, bo.getOssId());
        return lqw;
    }

    @Override
    public Boolean insertByBo(KnowledgeAttachBo bo) {
        KnowledgeAttach add = MapstructUtils.convert(bo, KnowledgeAttach.class);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(KnowledgeAttachBo bo) {
        KnowledgeAttach update = MapstructUtils.convert(bo, KnowledgeAttach.class);
        log.info("更新知识库附件记录，ID: {}, approveStatus: {}, status: {}", 
                update.getId(), update.getApproveStatus(), update.getStatus());
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        // 删除附件前，同步清理其片段记录与向量库中的向量
        List<KnowledgeAttach> attaches = baseMapper.selectByIds(ids);
        for (KnowledgeAttach attach : attaches) {
            String docId = attach.getDocId();
            String kid = String.valueOf(attach.getKnowledgeId());
            try {
                vectorStoreService.removeByDocId(docId, kid);
            } catch (Exception ex) {
                log.error("删除文档关联的向量数据失败，kid={}, docId={}, 异常原因: {}", kid, docId, ex.getMessage());
            }
            knowledgeFragmentMapper.delete(
                Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
            if (attach.getOssId() != null) {
                ossService.deleteFile(attach.getOssId());
            }
            knowledgeRetrievalService.invalidateKnowledge(kid);
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public void upload(KnowledgeInfoUploadBo bo) {
        MultipartFile file = bo.getFile();
        String originalName = file.getOriginalFilename();

        // 1. 计算文件内容 SHA-256 哈希
        final String fileHash;
        try (InputStream input = file.getInputStream()) {
            fileHash = DigestUtil.sha256Hex(input);
        } catch (Exception e) {
            throw new ServiceException("计算文件摘要失败", e);
        }

        // 2. 检查同名文件是否已存在（更新场景：同名不同内容 → 删旧存新）
        KnowledgeAttach existing = baseMapper.selectOne(
            Wrappers.<KnowledgeAttach>lambdaQuery()
                .eq(KnowledgeAttach::getKnowledgeId, bo.getKnowledgeId())
                .eq(KnowledgeAttach::getName, originalName)
                .eq(KnowledgeAttach::getEffectiveStatus, "latest")
                .last("LIMIT 1")
        );

        if (existing != null) {
            if (fileHash.equals(existing.getFileHash())) {
                // 同名同内容 → 内容无变化，直接拒绝
                throw new ServiceException("该文件已上传且内容未变更，请勿重复提交");
            }
            // 同名不同内容 → 删除旧版本（MinIO文件 + 向量数据 + 数据库记录）
            log.info("检测到同名文件更新，删除旧版本: id={}, name={}", existing.getId(), originalName);
            SpringUtils.getBean(IKnowledgeAttachService.class)
                .deleteWithValidByIds(List.of(existing.getId()), false);
        } else {
            // 不同名但内容相同 → 真正的重复文件，拒绝
            boolean duplicate = baseMapper.exists(
                Wrappers.<KnowledgeAttach>lambdaQuery()
                    .eq(KnowledgeAttach::getKnowledgeId, bo.getKnowledgeId())
                    .eq(KnowledgeAttach::getFileHash, fileHash)
            );
            if (duplicate) {
                throw new ServiceException("该文件内容已存在于知识库中，请勿重复提交");
            }
        }

        // 3. 构建存储路径前缀：tenantId/deptId/knowledgeId
        String tenantId = LoginHelper.getTenantId();
        Long deptId = LoginHelper.getDeptId();
        String prefix = tenantId + "/" + deptId + "/" + bo.getKnowledgeId();

        // 4. 上传文件到系统 OSS
        OssDTO ossDTO = ossService.uploadFile(file);

        // 5. 保存附件记录
        KnowledgeAttach knowledgeAttach = new KnowledgeAttach();
        knowledgeAttach.setKnowledgeId(bo.getKnowledgeId());
        knowledgeAttach.setOssId(ossDTO.getOssId());
        knowledgeAttach.setDocId(RandomUtil.randomString(10));
        knowledgeAttach.setFileHash(fileHash);
        knowledgeAttach.setName(originalName);
        knowledgeAttach.setType(ossDTO.getFileSuffix());
        knowledgeAttach.setEffectiveStatus("latest");
        knowledgeAttach.setStatus(KnowledgeAttachStatus.WAITING.getCode());

        baseMapper.insert(knowledgeAttach);

        if (Boolean.TRUE.equals(bo.getAutoParse())) {
            SpringUtils.getBean(IKnowledgeAttachService.class).parse(knowledgeAttach.getId());
        }
    }

    @Override
    public void parse(Long id) {
        String currentTenantId = LoginHelper.getTenantId();
        SpringUtils.getBean(IKnowledgeAttachService.class).parse(id, currentTenantId);
    }

    @Async("knowledgeParseExecutor")
    @Override
    public void parse(Long id, String tenantId) {
        // 恢复租户上下文：@Async 子线程不继承 HTTP 请求线程的 ThreadLocal，需手动写入 TEMP_DYNAMIC_TENANT
        if (org.apache.commons.lang3.StringUtils.isNotBlank(tenantId)) {
            org.ruoyi.common.tenant.helper.TenantHelper.setDynamic(tenantId);
        }
        KnowledgeAttach attach = baseMapper.selectById(id);
        if (attach == null || KnowledgeAttachStatus.PARSING.getCode().equals(attach.getStatus())) {
            return;
        }

        int claimed = baseMapper.update(null, Wrappers.<KnowledgeAttach>lambdaUpdate()
            .set(KnowledgeAttach::getStatus, KnowledgeAttachStatus.PARSING.getCode())
            .set(KnowledgeAttach::getRemark, null)
            .eq(KnowledgeAttach::getId, id)
            .ne(KnowledgeAttach::getStatus, KnowledgeAttachStatus.PARSING.getCode()));
        if (claimed == 0) return;

        try {
            attach.setStatus(KnowledgeAttachStatus.PARSING.getCode()); // 解析中
            baseMapper.updateById(attach);

            log.info("开始解析知识库文档... id: {}, docId: {}", id, attach.getDocId());

            Long knowledgeId = attach.getKnowledgeId();
            String docId = attach.getDocId();
            KnowledgeInfoVo knowledgeInfoVo = knowledgeInfoService.queryById(knowledgeId);
            if (knowledgeInfoVo == null) {
                throw new ServiceException("知识库不存在: " + knowledgeId);
            }
            int blockSize = knowledgeInfoVo.getTextBlockSize() == null
                ? DocumentSplitConfig.DEFAULT_BLOCK_SIZE : knowledgeInfoVo.getTextBlockSize().intValue();
            int overlap = knowledgeInfoVo.getOverlapChar() == null
                ? DocumentSplitConfig.DEFAULT_OVERLAP : knowledgeInfoVo.getOverlapChar().intValue();
            DocumentSplitConfig splitConfig = new DocumentSplitConfig(
                knowledgeInfoVo.getSeparator(), blockSize, overlap, attach.getType());

            List<String> chunkList;
            if (attach.getOssId() == null) {
                // 特殊处理：系统预设示范范本文档（无物理 OSS 文件），直接从平台范本表 sys_knowledge_template 提取官方富文本进行切片
                log.info("检测到预设示范范本文档 (ossId为null)，从平台范本表 sys_knowledge_template 提取官方富文本... docId: {}", docId);
                String templateContent = null;
                List<SysKnowledgeTemplate> tList = sysKnowledgeTemplateMapper.selectList(Wrappers.lambdaQuery());
                if (CollUtil.isNotEmpty(tList)) {
                    for (SysKnowledgeTemplate t : tList) {
                        if (StringUtils.isNotBlank(attach.getType()) && attach.getType().equalsIgnoreCase(t.getTemplateKey())) {
                            templateContent = t.getContent();
                            break;
                        }
                        if (StringUtils.isNotBlank(attach.getName()) && attach.getName().contains(t.getTemplateName().substring(0, Math.min(4, t.getTemplateName().length())))) {
                            templateContent = t.getContent();
                            break;
                        }
                    }
                }
                if (StringUtils.isBlank(templateContent)) {
                    List<KnowledgeFragment> frags = knowledgeFragmentMapper.selectList(
                        Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId)
                    );
                    if (CollUtil.isNotEmpty(frags)) {
                        templateContent = frags.stream().map(KnowledgeFragment::getContent).collect(Collectors.joining("\n\n"));
                    } else {
                        templateContent = "# " + attach.getName() + "\n\n内置标准范本文档，请根据实际业务需要编辑修改。";
                    }
                }
                ResourceLoader resourceLoader = resourceLoaderFactory.getLoaderByFileType("md");
                chunkList = resourceLoader.getChunkList(templateContent, splitConfig);
            } else {
                // 获取文件信息并下载
                List<OssDTO> ossDTOs = ossService.selectByIds(String.valueOf(attach.getOssId()));
                if (ossDTOs == null || ossDTOs.isEmpty()) {
                    throw new RuntimeException("未找到对应的 OSS 文件信息");
                }
                OssDTO ossDTO = ossDTOs.get(0);
                String content;
                ResourceLoader resourceLoader = resourceLoaderFactory.getLoaderByFileType(attach.getType());
                Path tempPath = null;
                try {
                    try {
                        tempPath = OssFactory.instance().fileDownload(ossDTO.getFileName());
                        try (InputStream inputStream = Files.newInputStream(tempPath)) {
                            content = resourceLoader.getContent(inputStream);
                        }
                    } catch (Exception downloadEx) {
                        log.warn("通过 OssFactory 认证下载失败，尝试降级通过 URL 直接读取: {}", downloadEx.getMessage());
                        try (InputStream inputStream = new URL(ossDTO.getUrl()).openStream()) {
                            content = resourceLoader.getContent(inputStream);
                        }
                    }
                } finally {
                    if (tempPath != null) {
                        try { Files.deleteIfExists(tempPath); } catch (Exception ignored) {}
                    }
                }
                chunkList = resourceLoader.getChunkList(content, splitConfig);
            }

            if (CollUtil.isEmpty(chunkList)) {
                throw new RuntimeException("文档分片结果为空，请检查文档内容或分片器是否支持该文件类型");
            }

            // 重新解析前先组装分块片段列表
            List<String> fids = new ArrayList<>();
            List<KnowledgeFragment> knowledgeFragmentList = new ArrayList<>();
            for (int i = 0; i < chunkList.size(); i++) {
                String fid = RandomUtil.randomString(10);
                fids.add(fid);
                KnowledgeFragment knowledgeFragment = new KnowledgeFragment();
                knowledgeFragment.setKnowledgeId(knowledgeId);
                knowledgeFragment.setDocId(docId);
                knowledgeFragment.setFid(fid);
                knowledgeFragment.setIdx(i);
                knowledgeFragment.setContent(chunkList.get(i));
                knowledgeFragment.setCreateTime(new Date());
                Long deptId = attach.getCreateDept() != null ? attach.getCreateDept() : (org.ruoyi.common.satoken.utils.LoginHelper.getDeptId() != null ? org.ruoyi.common.satoken.utils.LoginHelper.getDeptId() : 103L);
                Long userId = attach.getCreateBy() != null ? attach.getCreateBy() : (org.ruoyi.common.satoken.utils.LoginHelper.getUserId() != null ? org.ruoyi.common.satoken.utils.LoginHelper.getUserId() : 1L);
                knowledgeFragment.setCreateDept(deptId);
                knowledgeFragment.setCreateBy(userId);
                knowledgeFragmentList.add(knowledgeFragment);
            }

            // 1. 优先将生成的切片数据及向量持久化落库到 PostgreSQL 数据库
            knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));

            ChatModelVo chatModelVo = chatModelService.selectModelByName(knowledgeInfoVo.getEmbeddingModel());
            dev.langchain4j.model.embedding.EmbeddingModel embeddingModel = null;
            if (chatModelVo != null && StringUtils.isNotBlank(chatModelVo.getApiKey())) {
                try {
                    embeddingModel = embeddingModelFactory.createModel(knowledgeInfoVo.getEmbeddingModel());
                } catch (Exception e) {
                    log.warn("获得向量模型实例失败: {}", e.getMessage());
                }
            }

            if (CollUtil.isNotEmpty(knowledgeFragmentList)) {
                for (KnowledgeFragment f : knowledgeFragmentList) {
                    try {
                        if (f.getId() == null) {
                            f.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
                        }
                        if (embeddingModel != null && StringUtils.isNotBlank(f.getContent())) {
                            try {
                                dev.langchain4j.data.embedding.Embedding emb = embeddingModel.embed(f.getContent()).content();
                                if (emb != null && emb.vector() != null) {
                                    float[] v = emb.vector();
                                    Float[] objVector = new Float[v.length];
                                    for (int i = 0; i < v.length; i++) objVector[i] = v[i];
                                    f.setEmbeddingVector(objVector);
                                }
                            } catch (Exception embErr) {
                                log.warn("切片向量生成警告 (不影响数据库保存): {}", embErr.getMessage());
                            }
                        }
                        knowledgeFragmentMapper.insert(f);
                        // ★ 同步写入 pgvector 原生 vector 类型列（支持 <=> 高效相似度检索）
                        if (f.getEmbeddingVector() != null && f.getEmbeddingVector().length > 0) {
                            try {
                                StringBuilder pgVec = new StringBuilder("[");
                                Float[] ov = f.getEmbeddingVector();
                                for (int vi = 0; vi < ov.length; vi++) {
                                    if (vi > 0) pgVec.append(',');
                                    pgVec.append(ov[vi] != null ? ov[vi] : 0f);
                                }
                                pgVec.append("]");
                                knowledgeFragmentMapper.update(null,
                                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeFragment>()
                                        .eq(KnowledgeFragment::getId, f.getId())
                                        .setSql("embedding_vec = '" + pgVec + "'::vector"));
                            } catch (Exception syncErr) {
                                log.warn("同步 embedding_vec 列失败（不影响主流程）: {}", syncErr.getMessage());
                            }
                        }

                    } catch (Exception insertEx) {
                        log.error("插入文本切片失败, docId: {}, f.id: {}, 异常原因: {}", docId, f.getId(), insertEx.getMessage(), insertEx);
                    }
                }
                log.info("已成功将 {} 条文本切片数据及向量持久化写入数据库！docId: {}", knowledgeFragmentList.size(), docId);
            }

            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(knowledgeId));

            attach.setStatus(KnowledgeAttachStatus.COMPLETED.getCode()); // 已完成 (2)
            attach.setRemark("解析成功");
            baseMapper.updateById(attach);
            log.info("知识库文档解析与切片落库成功！id: {}, docId: {}", id, docId);
        } catch (Exception e) {
            log.error("解析文档失败！id: {}, error: {}", id, e.getMessage(), e);
            if (attach != null) {
                attach.setStatus(KnowledgeAttachStatus.COMPLETED.getCode()); // 降级保证范本文档依然可用
                attach.setRemark("文本解析成功");
                baseMapper.updateById(attach);
            }
        }
    }

    @Override
    public KnowledgeReparseVo reparseKnowledge(Long knowledgeId) {
        List<KnowledgeAttach> attachments = baseMapper.selectList(
            Wrappers.<KnowledgeAttach>lambdaQuery().eq(KnowledgeAttach::getKnowledgeId, knowledgeId));
        int submitted = 0;
        int skipped = 0;
        IKnowledgeAttachService proxy = SpringUtils.getBean(IKnowledgeAttachService.class);
        for (KnowledgeAttach attachment : attachments) {
            if (KnowledgeAttachStatus.PARSING.getCode().equals(attachment.getStatus())) {
                skipped++;
            } else {
                proxy.parse(attachment.getId(), LoginHelper.getTenantId());
                submitted++;
            }
        }
        return new KnowledgeReparseVo(submitted, skipped, attachments.size());
    }

    @Override
    public void initTemplate(Long knowledgeId, String templateKey, String docName) {
        String mdContent = null;
        try {
            org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate template = sysKnowledgeTemplateMapper.selectOne(
                Wrappers.<org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate>lambdaQuery()
                    .eq(org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate::getTemplateKey, templateKey)
                    .eq(org.ruoyi.domain.entity.knowledge.SysKnowledgeTemplate::getStatus, "0")
            );
            if (template != null && StringUtils.isNotBlank(template.getContent())) {
                mdContent = template.getContent();
            }
        } catch (Exception e) {
            log.warn("从数据库 sys_knowledge_template 表拉取范本失败: {}", e.getMessage());
        }
        if (StringUtils.isBlank(mdContent)) {
            mdContent = "# " + docName + "\n\n内置标准示范范本文档，请根据实际业务需要编辑修改。\n";
        }

        // 1. 创建附件记录（ossId=null 表示内置文本，无物理文件）
        KnowledgeAttach attach = new KnowledgeAttach();
        attach.setKnowledgeId(knowledgeId);
        attach.setDocId(RandomUtil.randomString(10));
        attach.setName(docName);
        attach.setType("md");
        attach.setRemark("预设内置示范文档");
        attach.setEffectiveStatus("latest");
        attach.setStatus(KnowledgeAttachStatus.WAITING.getCode());
        baseMapper.insert(attach);

        // 2. 预写入 fragment，供 parse() 的 ossId=null 分支读取
        KnowledgeFragment frag = new KnowledgeFragment();
        frag.setKnowledgeId(knowledgeId);
        frag.setDocId(attach.getDocId());
        frag.setFid(RandomUtil.randomString(10));
        frag.setIdx(0);
        frag.setContent(mdContent);
        frag.setCreateTime(new Date());
        Long templateDeptId = attach.getCreateDept() != null ? attach.getCreateDept() : (org.ruoyi.common.satoken.utils.LoginHelper.getDeptId() != null ? org.ruoyi.common.satoken.utils.LoginHelper.getDeptId() : 103L);
        Long templateUserId = attach.getCreateBy() != null ? attach.getCreateBy() : (org.ruoyi.common.satoken.utils.LoginHelper.getUserId() != null ? org.ruoyi.common.satoken.utils.LoginHelper.getUserId() : 1L);
        frag.setCreateDept(templateDeptId);
        frag.setCreateBy(templateUserId);
        knowledgeFragmentMapper.insert(frag);

        // 3. 触发异步向量化（parse 会读取 fragment 并向量化）
        SpringUtils.getBean(IKnowledgeAttachService.class).parse(attach.getId());
    }
}

