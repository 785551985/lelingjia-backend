package org.ruoyi.service.knowledge;

import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.domain.bo.knowledge.KnowledgeAttachBo;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoUploadBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;

import java.util.Collection;
import java.util.List;

/**
 * 知识库附件Service接口
 *
 * @author ageerle
 * @date 2025-12-17
 */
public interface IKnowledgeAttachService {

    /**
     * 查询知识库附件
     *
     * @param id 主键
     * @return 知识库附件
     */
    KnowledgeAttachVo queryById(Long id);

    /**
     * 分页查询知识库附件列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库附件分页列表
     */
    TableDataInfo<KnowledgeAttachVo> queryPageList(KnowledgeAttachBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的知识库附件列表
     *
     * @param bo 查询条件
     * @return 知识库附件列表
     */
    List<KnowledgeAttachVo> queryList(KnowledgeAttachBo bo);

    /**
     * 新增知识库附件
     *
     * @param bo 知识库附件
     * @return 是否新增成功
     */
    Boolean insertByBo(KnowledgeAttachBo bo);

    /**
     * 修改知识库附件
     *
     * @param bo 知识库附件
     * @return 是否修改成功
     */
    Boolean updateByBo(KnowledgeAttachBo bo);

    /**
     * 校验并批量删除知识库附件信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);


    /**
     * 上传附件
     */
    void upload(KnowledgeInfoUploadBo bo);

    /**
     * 解析附件知识片段
     *
     * @param id 附件ID
     */
    void parse(Long id);

    /**
     * 解析附件知识片段（指定租户ID）
     *
     * @param id       附件ID
     * @param tenantId 租户ID
     */
    void parse(Long id, String tenantId);

    KnowledgeReparseVo reparseKnowledge(Long knowledgeId);

    /**
     * 初始化预设模板文档并自动解析入库
     *
     * @param knowledgeId 知识库ID
     * @param templateKey 模板 key（faq/rule/sop/product/contract/case/training）
     * @param docName     附件显示名称
     */
    void initTemplate(Long knowledgeId, String templateKey, String docName);
}
