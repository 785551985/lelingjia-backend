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
        m.put("common", "# 企业公共基础知识库 - 示范指南规范手册\n\n## 一、企业简介\n示例健康科技集团成立于 2018 年，致力于打造全国领先的智能化、标准化综合健康管理与企业数字化服务平台。集团总部位于深圳，在全国拥有超过 20 家分支机构与区域分公司。\n\n## 二、企业使命与核心价值观\n- 使命：让每一位客户享受到专业、温暖、智能的标准化健康与企服体验。\n- 价值观：客户第一、专业至上、诚信担当、创新共赢。\n");
        m.put("brand", "# 企业品牌与 VI 视觉规范标准手册\n\n## 一、品牌定位\n科技赋能、专业严谨、人文关怀、值得信赖。\n\n## 二、品牌色彩规范\n- 品牌科技蓝：#1890FF\n- 品牌健康绿：#52C41A\n- 深紫暗调：#2F54EB\n");
        m.put("expert", "# 外部专家顾问与智库智囊名录手册\n\n## 一、医疗健康专家组\n- 张建国 教授 / 主任医师：集团首席医疗健康顾问\n- 李美玲 博士 / 心理咨询专家：集团心理健康智库专家\n\n## 二、企服合规专家组\n- 陈振华 律师：集团法律合规顾问\n");
        m.put("faq", "# 对外客服常见问题解答 (FAQ) 标准库\n\n## 常见问题集锦\nQ1: 忘记账号登录密码该如何重置？\nA: 点击登录页“忘记密码”，输入手机验证码后重置。\n\nQ2: 客服服务时间？\nA: 工作日 09:00 - 18:00，热线：400-888-9999。\n");
        m.put("training", "# 分支机构新人入职与培训手册\n\n## 一、入职第一周流程\n- Day 1: 办理入职手续与开通账号\n- Day 2: 规章制度学习\n- Day 3-4: 业务 SOP 培训\n");
        m.put("sop", "# 业务 SOP 与标准作业流程手册\n\n## 一、接待与服务标准流程\n1. 接待迎接 -> 2. 需求倾听 -> 3. 方案匹配 -> 4. 跟进反馈\n");
        m.put("product", "# 产品与服务项目手册与报价清单\n\n## 核心套餐\n1. 基础健康管理套餐：¥1,980 / 人/年\n2. 企业级综合服务方案：¥15,000起 / 企业/年\n");
        m.put("rule", "# 分支机构通用管理制度与行为规范\n\n## 考勤管理\n- 正常工作时间：周一至周五 09:00 - 18:00。\n");
        m.put("contract", "# 资质合规与标准合同文本规范\n\n## 核心条款\n包含知识产权保护、商业保密及争议解决管辖条款。\n");
        m.put("case", "# 优秀案例与最佳实践复盘手册\n\n## 标杆案例\n某知名科技公司全员健康管理项目，客户满意度提升至 96%。\n");
        m.put("talent", "# 内部人才档案与专家骨干名录\n\n## 骨干人才名录\n- 王明 (高级健康管理师)\n- 陈丽 (客服与交付总监)\n");
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
        frag.setCreateDept(attach.getCreateDept());
        frag.setCreateBy(attach.getCreateBy());
        knowledgeFragmentMapper.insert(frag);

        // 3. 触发异步向量化（parse 会读取 fragment 并向量化）
        SpringUtils.getBean(IKnowledgeAttachService.class).parse(attach.getId());
    }
}

