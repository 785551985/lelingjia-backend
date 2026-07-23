package org.ruoyi.common.chat.domain.bo.chat;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.chat.entity.chat.ChatModel;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Map;

/**
 * 模型管理业务对象 chat_model
 *
 * @author ageerle
 * @date 2025-12-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = ChatModel.class, reverseConvertGenerate = false)
public class ChatModelBo extends BaseEntity {

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 模型分类
     */
    @NotBlank(message = "模型分类不能为空", groups = { AddGroup.class, EditGroup.class })
    private String category;

    /**
     * 模型名称
     */
    @NotBlank(message = "模型名称不能为空", groups = { AddGroup.class, EditGroup.class })
    private String modelName;

    /**
     * 模型供应商
     */
    @NotBlank(message = "模型供应商不能为空", groups = { AddGroup.class, EditGroup.class })
    private String providerCode;

    /**
     * 模型描述
     */
    private String modelDescribe;

    /**
     * 是否显示
     */
    private String modelShow;

    /**
     * 向量维度
     */
    private Integer modelDimension;

    /**
     * 请求地址
     */
    private String apiHost;

    /**
     * 密钥
     */
    private String apiKey;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private java.util.Date createTime;

    /**
     * 更新时间
     */
    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private java.util.Date updateTime;

    @Override
    public Map<String, Object> getParams() {
        Map<String, Object> params = super.getParams();
        return params != null ? params : new java.util.HashMap<>();
    }
}
