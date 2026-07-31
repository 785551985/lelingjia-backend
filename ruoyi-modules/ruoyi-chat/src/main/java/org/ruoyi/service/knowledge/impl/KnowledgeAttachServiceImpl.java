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
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;

import org.ruoyi.common.oss.factory.OssFactory;
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
            // 通过 SpringUtils 获取代理对象，确保 @Async 生效
            SpringUtils.getBean(IKnowledgeAttachService.class).parse(knowledgeAttach.getId());
        }
    }

    @Async("knowledgeParseExecutor")
    @Override
    public void parse(Long id) {
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
                // 特殊处理：系统预设示范范本文档（无物理 OSS 文件）
                log.info("检测到预设示范范本文档 (ossId为null)，从存量分块中读取内容进行重新解析与向量化... docId: {}", docId);
                List<KnowledgeFragment> frags = knowledgeFragmentMapper.selectList(
                    Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId)
                );
                if (CollUtil.isNotEmpty(frags)) {
                    chunkList = frags.stream().map(KnowledgeFragment::getContent).collect(Collectors.toList());
                } else {
                    chunkList = List.of("# " + attach.getName() + "\n\n内置标准范本文档，请根据实际业务需要编辑修改。");
                }
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

            // 重新解析前先清理旧的向量数据，避免向量重复累积
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
            ChatModelVo chatModelVo = chatModelService.selectModelByName(knowledgeInfoVo.getEmbeddingModel());

            StoreEmbeddingBo storeEmbeddingBo = new StoreEmbeddingBo();
            storeEmbeddingBo.setKid(String.valueOf(knowledgeId));
            storeEmbeddingBo.setDocId(docId);
            storeEmbeddingBo.setFids(fids);
            storeEmbeddingBo.setChunkList(chunkList);
            storeEmbeddingBo.setVectorStoreName(knowledgeInfoVo.getVectorModel());
            storeEmbeddingBo.setEmbeddingModelName(knowledgeInfoVo.getEmbeddingModel());
            storeEmbeddingBo.setApiKey(chatModelVo.getApiKey());
            storeEmbeddingBo.setBaseUrl(chatModelVo.getApiHost());
            try {
                // 写入新向量前，先按 docId 清理该文档的旧向量：
                // 历史数据的片段 fid 为迁移脚本回填的 MD5 值，与向量库中实际存储的 fid 不一致，
                // 按 fid 删除无法命中旧向量，会导致重复向量累积；按 docId 清理对三种向量库均一致有效。
                vectorStoreService.removeByDocId(docId, String.valueOf(knowledgeId));
                vectorStoreService.storeEmbeddings(storeEmbeddingBo);
            } catch (Exception vectorError) {
                for (String newFid : fids) {
                    try {
                        vectorStoreService.removeByFid(newFid, String.valueOf(knowledgeId));
                    } catch (Exception cleanupError) {
                        log.error("补偿删除新向量失败, kid={}, fid={}", knowledgeId, newFid, cleanupError);
                    }
                }
                throw vectorError;
            }

            knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
            knowledgeFragmentMapper.insertBatch(knowledgeFragmentList);
            knowledgeRetrievalService.invalidateKnowledge(String.valueOf(knowledgeId));

            attach.setStatus(KnowledgeAttachStatus.COMPLETED.getCode()); // 已完成
            baseMapper.updateById(attach);
            log.info("知识库文档解析、向量化并入库成功！id: {}", id);
        } catch (Exception e) {
            log.error("解析文档失败！id: {}, error: {}", id, e.getMessage(), e);
            // 失败时强行清理该文档在向量库与片段明细表中的残留数据，防止脏向量污染知识库
            if (attach != null) {
                try {
                    String docId = attach.getDocId();
                    Long knowledgeId = attach.getKnowledgeId();
                    if (docId != null && knowledgeId != null) {
                        vectorStoreService.removeByDocId(docId, String.valueOf(knowledgeId));
                        knowledgeFragmentMapper.delete(Wrappers.<KnowledgeFragment>lambdaQuery().eq(KnowledgeFragment::getDocId, docId));
                        knowledgeRetrievalService.invalidateKnowledge(String.valueOf(knowledgeId));
                        log.info("文档解析失败，已自动回滚并强制清理 docId={} 的向量库及切片数据", docId);
                    }
                } catch (Exception cleanupEx) {
                    log.error("文档解析失败后的补偿清理发生异常", cleanupEx);
                }
                attach.setStatus(KnowledgeAttachStatus.FAILED.getCode()); // 失败
                attach.setRemark(StringUtils.substring(e.getMessage(), 0, 255)); // 保存错误原因，截取防止溢出
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
                proxy.parse(attachment.getId());
                submitted++;
            }
        }
        return new KnowledgeReparseVo(submitted, skipped, attachments.size());
    }

    // ==================== 内置模板内容 ====================
    private static final Map<String, String> TEMPLATE_CONTENT_MAP;
    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("common", "# 企业公共基础知识库 - 示范指南规范手册\n\n## 一、企业简介与发展历程\n乐龄家大健康科技集团成立于 2018 年，致力于打造全国领先的智能化、标准化综合健康管理与企业数字化服务平台。集团总部位于深圳，在全国拥有超过 20 家分支机构与区域分公司，服务覆盖企事业单位员工与社区家庭超 100 万人次。\n\n## 二、企业使命、愿景与核心价值观\n- **使命**：让每一位客户享受到专业、温暖、智能的标准化健康与企服体验。\n- **愿景**：成为中国最具数字科技魅力与人文温度的大健康企业生态圈。\n- **核心价值观**：客户第一、专业至上、诚信担当、创新共赢。\n\n## 三、集团组织架构与职能分工\n| 部门/架构 | 核心职能 | 主要交付物 | 负责人 |\n| :--- | :--- | :--- | :--- |\n| 集团总部 | 战略规划、资金调度、合规风控 | 集团年度战略计划与风控批复 | CEO 办公室 |\n| 医疗健康事业部 | 专业健康方案制定、专家顾问团运营 | 个人/企业健康评估报告 | 医疗总监 |\n| 企服运营中心 | 客户服务、交付跟踪、客户满意度管理 | 服务交付SOP与客户满意度大盘 | 运营总监 |\n| 研发与数字中心 | 知识库RAG平台、智能体系统研发 | 集团数字化平台与 AI 知识库系统 | CTO 办公室 |\n\n## 四、员工公共行为准则\n1. **职业操守**：严禁泄露集团及客户商业机密，严禁私下收受供应商礼品或回扣。\n2. **办公礼仪**：工作时间须按规定穿戴工牌，接待客户须使用标准文明用语。\n3. **信息安全**：下班离开工位须锁定电脑屏幕，敏感文档严禁通过私人邮箱传输。\n");

        m.put("brand", "# 企业品牌与 VI 视觉规范标准手册\n\n## 一、品牌定位与核心话术\n乐龄家品牌定位为：**“科技赋能、专业严谨、人文关怀、值得信赖”**。在对外宣传与品牌推介中，统一使用“乐龄家大健康科技”作为企业主体品牌名称。\n\n## 二、品牌标准色彩规范表格\n| 色彩分类 | 色彩名称 | HEX 色号 | RGB 数值 | 使用场景 |\n| :--- | :--- | :--- | :--- | :--- |\n| 主品牌色 | 乐龄科技蓝 | `#1890FF` | `24, 144, 255` | 官方 Logo、标题文字、主按钮、App 主配色 |\n| 辅助配色 | 健康活力绿 | `#52C41A` | `82, 196, 26` | 成功提示、健康健康指标徽章、环保标识 |\n| 强调色彩 | 暖心橙 | `#FA8C16` | `250, 140, 22` | 促销标签、高亮强调说明、活动卡片 |\n| 背景底色 | 极简深蓝暗调 | `#001529` | `0, 21, 41` | 管理后台侧边栏、大屏看板黑夜模式 |\n\n## 三、Logo 使用规范与禁忌\n- **允许用法**：必须保持 Logo 原始宽高比例，浅色背景下使用蓝绿标准色彩版 Logo，深色背景下使用反白纯白版 Logo。\n- **严格禁止**：严禁任意拉伸或压缩 Logo 比例；严禁更改 Logo 固有字体；严禁在复杂图案背景上直接放置彩版 Logo。\n");

        m.put("expert", "# 外部专家顾问与智库智囊名录手册\n\n## 一、医疗健康与康复专家组\n| 专家姓名 | 职称/头衔 | 擅长领域 | 咨询预约规范 |\n| :--- | :--- | :--- | :--- |\n| 张建国 教授 | 主任医师 / 博士生导师 | 全科健康管理、心脑血管预防 | 每周二、四上午，须提前 3 天通过企服平台预约 |\n| 李美玲 博士 | 资深心理咨询专家 | 员工心理援助(EAP)、职场压力疏导 | 工作日预约制，支持线上视频一对一咨询 |\n| 魏振邦 医师 | 副主任医师 / 康复专家 | 运动损伤康复、颈腰椎慢性病干预 | 每周六全天，需要提供近期影像学检查报告 |\n\n## 二、法律合规与企服智库组\n| 专家姓名 | 机构/职务 | 合作领域 | 联系与对接流程 |\n| :--- | :--- | :--- | :--- |\n| 陈振华 律师 | 金融法律事务所 合伙人 | 企业劳动争议、商业合同合规 | 须通过集团法务部提交预约需求书 |\n| 董伟 注册会计师 | 税务会计师事务所 首席顾问 | 企业企业税务筹划、合规审计 | 每年财报季与专项审计期间预约对接 |\n");

        m.put("faq", "# 对外客服常见问题解答 (FAQ) 标准库\n\n## 一、系统账号与登录问题\n**Q1: 员工忘记系统登录密码该如何重置？**  \n*A1*: 点击登录页面的“忘记密码”链接，通过绑定的手机号码获取短信验证码后进行重置。若手机号码已变更，请联系本部门 HR 在管理后台协助修改关联手机号。\n\n**Q2: 登录系统提示“账号无当前租户访问权限”怎么处理？**  \n*A2*: 请联系企业超级管理员，在【系统管理-用户管理】中确认您的账号是否已正确分流至当前企业租户下，并分配了相对应的角色权限。\n\n## 二、服务预约与开票问题\n**Q3: 如何为企业员工批量预约健康体检或服务？**  \n*A3*: 企业 HR 可在【服务管理-批量预约】模块导入员工 Excel 名单，选择对应的服务套餐与预约日期，提交后系统将自动发送通知短信给员工。\n\n**Q4: 企业购买服务后如何索取发票？发票类型有哪些？**  \n*A4*: 订单支付完成后，可在【财务结算-发票管理】中申请开票。支持开具“增值税普通电子发票”与“增值税专用发票”，发票内容可选“健康管理服务费”或“咨询服务费”，申请后 2 个工作日内发送至指定邮箱。\n\n| 常见服务热线 | 服务时间 | 响应承诺 |\n| :--- | :--- | :--- |\n| 400-888-9999 (客服热线) | 周一至周日 08:30 - 20:30 | 30秒内人工接听 |\n| support@ylglxt.cn (技术支持) | 7x24 小时接收邮件 | 2小时内首次邮件回复 |\n");

        m.put("training", "# 分支机构新人入职与培训手册\n\n## 一、新人入职首周标准培训日程表\n| 时间 | 培训主题 | 主讲/责任人 | 考核/交付方式 |\n| :--- | :--- | :--- | :--- |\n| Day 1 09:00-12:00 | 入职手续办理、工牌发放、账号开通 | 人资行政部 | 确认完成系统登录与考勤打卡录入 |\n| Day 1 14:00-17:00 | 企业文化、使命价值观与发展历程 | 部门经理 | 完成线上企业文化试卷答题 (≥80分合格) |\n| Day 2 09:00-17:00 | 规章制度、考勤请假与信息安全合规 | 法务合规部 | 签署《员工合规手册确认书》与保密协议 |\n| Day 3-4 09:00-17:00 | 岗位业务 SOP 标准作业流程实操 | 导师/资深员工 | 导师一对一实操带教与现场流程演练 |\n| Day 5 14:00-17:00 | 首周通关答辩与试用期目标设定 | 部门负责人 | 制定《试用期30-60-90天绩效目标表》 |\n\n## 二、试用期转正考核机制\n1. **考勤指标**：试用期内无无故迟到早退，旷工次数为 0。\n2. **业务能力**：独立完成岗位 SOP 交付，试用期月度 KPI 达标率达到 85% 以上。\n3. **文化融入**：团队合作顺畅，无违纪或客户重大投诉记录。\n");

        m.put("sop", "# 业务 SOP 与标准作业流程手册\n\n## 一、客户接待与服务 5 步标准作业流程\n| 步骤 | 环节名称 | 标准动作要求 | 服务时限 | 责任岗位 |\n| :---: | :--- | :--- | :--- | :--- |\n| 1 | 接待迎接 | 微笑迎接，使用标准问候语，核验身份并引导入座 | 3 分钟内 | 前台接待专员 |\n| 2 | 需求倾听 | 详细询问并记录客户核心需求与偏好，填写沟通记录表 | 15 分钟内 | 健康管理顾问 |\n| 3 | 方案匹配 | 根据知识库规则匹配专属服务套餐，出具初步建议书 | 20 分钟内 | 资深专家顾问 |\n| 4 | 确认签署 | 讲解套餐条款、费用明细与权利义务，协助客户完成签约 | 15 分钟内 | 商务执行专员 |\n| 5 | 跟踪随访 | 签约后 24 小时内发送欢迎短信，定期回访健康干预进展 | 持续跟进 | 客服专员 |\n\n## 二、服务质量考核与 KPI 扣罚机制\n- **超时响应**：超时 15 分钟未接待，扣除当次服务绩效 20分。\n- **态度恶劣**：引致客户书面投诉经查证属实的，当月绩效清零并通报批评。\n- **隐私泄露**：未经客户授权擅自透露客户信息的，予以开除并追究法律责任。\n");

        m.put("product", "# 产品与服务项目手册与报价清单\n\n## 一、核心产品套餐与服务对比清单\n| 套餐名称 | 适用人群 | 核心服务内容 | 官方统一售价 | 企业团购优惠价 |\n| :--- | :--- | :--- | :--- | :--- |\n| 基础健康管理套餐 | 青年员工 / 初创团队 | 年度常规体检评估 + 电子健康档案 + 7x24 在线健康咨询 | ¥1,980 / 人/年 | ¥1,280 / 人/年 (≥50人) |\n| 高管深度定制套餐 | 企业的核心高管 / 创始人 | 全套深度基因检测 + 专车接送 + 1对1 专家团队年度私人干预 | ¥12,800 / 人/年 | ¥9,800 / 人/年 (≥10人) |\n| 企业 EAP 心理关怀套餐 | 全体员工 | 全员心理健康测评 + 现场心理讲座 4场/年 + 匿名咨询 | ¥28,000 / 企业/年 | ¥22,000 / 企业/年 |\n| 企业全员综合健康方案 | 500人以上大型企业 | 驻场医务室 + 批量体检 + 绿色就医通道 + 专属 AI 知识库 | ¥85,000起 / 企业/年 | 商务按需定制折扣 |\n\n## 二、退款与变更政策\n1. 服务未启动前申请退款的，无条件全额退还扣除 3% 手续费后的剩余款项。\n2. 已完成部分体检或服务的，按单项原价扣除已消费部分后退还余款。\n");

        m.put("rule", "# 分支机构通用管理制度与行为规范\n\n## 一、工时与考勤打卡管理规定\n- **标准工时**：周一至周五 09:00 - 18:00（午休时间：12:00 - 13:30）。\n- **打卡规范**：员工须通过企业微信或指定打卡设备完成每日上下班共 2 次打卡。\n\n| 考勤异常分类 | 判定标准 | 薪资扣减/处分标准 |\n| :--- | :--- | :--- |\n| 迟到/早退 (30分钟以内) | 09:01 - 09:30 到岗 | 扣减 50 元/次，每月前 2 次免扣 |\n| 重度迟到 (30-120分钟) | 09:31 - 11:00 到岗 | 扣减半日基本工资 |\n| 旷工 | 无故不到岗且未请假 | 扣减双倍当日基本工资，连续 3 天视作自动离职 |\n\n## 二、请假与审批权限管理\n| 请假类别 | 需提交材料 | 审批权限流转 |\n| :--- | :--- | :--- |\n| 病假 | 二级以上医院诊断证明与病历 | 1天内部门经理审批；>1天须总监及 HR 审批 |\n| 事假 | 详细书面事由说明 | 提前 1 天申请，部门经理及 HR 审批 |\n| 带薪年假 | 提前 3 天提交申请 | 部门经理及 HR 审批，需合理安排工作交接 |\n");

        m.put("contract", "# 资质合规与标准合同文本规范\n\n## 一、合同签署前置审查 checklist 表格\n| 审查要点 | 审查标准与要求 | 责任部门 |\n| :--- | :--- | :--- |\n| 相对人主体资质 | 须核验营业执照最新年报、法人身份证复印件及盖章授权委托书 | 法务部 |\n| 履约能力评估 | 查验企查查/天眼查风险记录，失信被执行人一票否决 | 风控部 |\n| 款项支付账期 | 标准付款账期不超过 30 天，预付比例不得超过总金额 30% | 财务部 |\n\n## 二、必备核心合规条款标准模板\n1. **商业保密条款**：双方应对在本合同签订与履行过程中知悉的对方商业秘密、技术数据及客户个人隐私承担永久保密义务。\n2. **违约责任计算**：任何一方无故单方解除合同的，须向守约方支付合同总金额 20% 的违约金，并赔偿由此造成的直接经济损失。\n3. **争议解决管辖**：因本合同引起的或与本合同有关的任何争议，双方应协商解决；协商不成的，统一向**合同签订地有管辖权的人民法院**提起诉讼。\n");

        m.put("case", "# 优秀案例与最佳实践复盘手册\n\n## 一、标杆案例：某知名上市科技公司全员健康管理项目\n- **项目背景**：客户拥有 2,000+ 名研发工程师，久坐加班导致颈椎病、高血糖及高血压患病率达 42%，员工健康隐患大。\n- **解决方案**：乐龄家为其定制了“驻场健康小屋 + 动态血糖监测 + AI 智能体健康咨询”综合方案。\n\n| 关键指标 | 项目实施前 | 项目实施 1 年后 | 改善提升比例 |\n| :--- | :--- | :--- | :--- |\n| 员工年度体检异常率 | 58.6% | 39.2% | ↓ 19.4% |\n| 高危慢病风险干预率 | 12.0% | 94.5% | ↑ 82.5% |\n| 员工健康服务满意度 | 71.0% | 96.8% | ↑ 25.8% |\n| 因病请假总工时(人均) | 6.5 天/年 | 2.8 天/年 | ↓ 56.9% |\n\n## 二、项目实施三大核心经验复盘\n1. **高管带头示范**：高管亲自参与健康测评打卡，带动全员参与氛围。\n2. **AI 知识库实时响应**：将考勤、福利与健康FAQ融入知识库，员工提问 3 秒内解答，极大降低行政客服压力。\n");

        m.put("talent", "# 内部人才档案与专家骨干名录\n\n## 一、集团核心技术与业务骨干名录\n| 姓名 | 部门 | 现任岗位 | 核心专业特长 | 项目代表作 |\n| :--- | :--- | :--- | :--- | :--- |\n| 王明 | 医疗健康事业部 | 高级健康管理师 | 慢病干预方案设计、营养膳食配餐 | 某知名科技公司全员健康管理项目 |\n| 陈丽 | 企服运营中心 | 交付总监 | 客户关系管理、大型项目服务交付 | 2025 全国 20 分支机构标准化交付体系 |\n| 赵磊 | 研发与数字中心 | 首席 AI 架构师 | 大模型 RAG 架构、PostgreSQL 向量检索 | 乐龄家 AI 企业级知识库与智能体系统 |\n| 刘芳 | 法务合规部 | 资深合规专家 | 企业劳动用工风控、合同文本审查 | 集团全套资质合规与知识产权保护体系 |\n");

        TEMPLATE_CONTENT_MAP = Collections.unmodifiableMap(m);
    }

    @Override
    public void initTemplate(Long knowledgeId, String templateKey, String docName) {
        String mdContent = TEMPLATE_CONTENT_MAP.getOrDefault(templateKey,
            "# " + docName + "\n\n本文档为预设示范范本，请根据实际业务需要编辑修改。\n");

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

