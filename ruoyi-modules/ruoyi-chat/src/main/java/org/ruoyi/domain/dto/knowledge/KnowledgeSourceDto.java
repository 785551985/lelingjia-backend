package org.ruoyi.domain.dto.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库检索来源传输对象
 *
 * @author ageerle
 * @date 2026-07-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSourceDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档关联ID
     */
    private String docId;

    /**
     * 附件/文件名称
     */
    private String name;

    /**
     * 知识库名称
     */
    private String knowledgeName;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * OSS 存储对象ID
     */
    private Long ossId;

    /**
     * 下载/预览相对链接
     */
    private String downloadUrl;

    /**
     * 匹配得分 (0~100)
     */
    private Double score;

    /**
     * 匹配的原文片段摘要
     */
    private String snippet;
}
