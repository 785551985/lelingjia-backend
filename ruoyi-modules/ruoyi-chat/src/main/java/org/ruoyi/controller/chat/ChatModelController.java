package org.ruoyi.controller.chat;

import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.domain.bo.chat.ChatModelBo;
import org.ruoyi.common.chat.domain.bo.chat.ModelBatchKeyBo;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.enums.ModelType;
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

import java.util.LinkedHashMap;

/**
 * 模型管理
 *
 * @author ageerle
 * @date 2025-12-14
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/model")
public class ChatModelController extends BaseController {

    private final IChatModelService chatModelService;

    /**
     * 查询模型管理列表
     */
    @SaCheckLogin
    @GetMapping("/list")
    public TableDataInfo<ChatModelVo> list(ChatModelBo bo, PageQuery pageQuery) {
        return chatModelService.queryPageList(bo, pageQuery);
    }

    /**
     * 查询用户聊天模型列表
     */
    @GetMapping("/modelList")
    public R<List<ChatModelVo>> modelList(ChatModelBo bo) {
        if (StringUtils.isBlank(bo.getCategory())) {
            bo.setCategory(ModelType.CHAT.getKey());
        }
        return R.ok(chatModelService.queryList(bo));
    }

    /**
     * 获取模型供应商枚举
     */
    @GetMapping("/providerOptions")
    public R<List<LinkedHashMap<String, String>>> providerOptions() {
        List<LinkedHashMap<String, String>> options = new java.util.ArrayList<>();
        for (ChatModeType type : ChatModeType.values()) {
            LinkedHashMap<String, String> item = new LinkedHashMap<>();
            item.put("label", type.getDescription());
            item.put("value", type.getCode());
            options.add(item);
        }
        return R.ok(options);
    }

    /**
     * 导出模型管理列表
     */
    @SaCheckPermission("system:model:export")
    @Log(title = "模型管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(ChatModelBo bo, HttpServletResponse response) {
        List<ChatModelVo> list = chatModelService.queryList(bo);
        ExcelUtil.exportExcel(list, "模型管理", ChatModelVo.class, response);
    }

    /**
     * 获取模型管理详细信息
     *
     * @param id 主键
     */
    @SaCheckPermission("system:model:query")
    @GetMapping("/{id}")
    public R<ChatModelVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(chatModelService.queryById(id));
    }

    /**
     * 新增模型管理
     */
    @SaCheckPermission("system:model:add")
    @Log(title = "模型管理", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody ChatModelBo bo) {
        return toAjax(chatModelService.insertByBo(bo));
    }

    /**
     * 修改模型管理
     */
    @SaCheckPermission("system:model:edit")
    @Log(title = "模型管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody ChatModelBo bo) {
        return toAjax(chatModelService.updateByBo(bo));
    }

    /**
     * 按厂商批量更新密钥
     */
    @SaCheckPermission("system:model:edit")
    @Log(title = "模型管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/batchKeyByProvider")
    public R<Void> batchKeyByProvider(@Validated @RequestBody ModelBatchKeyBo bo) {
        return toAjax(chatModelService.updateApiKeyByProvider(bo.getProviderCode(), bo.getApiKey()));
    }

    /**
     * 真实测试模型连通性（带真实握手与精确原因反馈）
     */
    @SaCheckPermission("system:model:query")
    @PostMapping("/test/{id}")
    public R<java.util.Map<String, Object>> testConnection(@NotNull(message = "模型ID不能为空") @PathVariable Long id) {
        ChatModelVo model = chatModelService.queryById(id);
        if (model == null) {
            return R.fail("未找到指定的模型记录");
        }

        long startTime = System.currentTimeMillis();
        try {
            // 真实触发连通性握手测试
            String provider = StringUtils.isNotBlank(model.getProviderCode()) ? model.getProviderCode().toLowerCase() : "";
            String modelName = model.getModelName();
            String apiKey = model.getApiKey();

            if (StringUtils.isBlank(apiKey) && !"ollama".equals(provider)) {
                return R.fail("模型未配置 API Key 密钥！");
            }

            // 根据不同的模型分类构建轻量级握手请求
            String apiHost = StringUtils.isNotBlank(model.getApiHost()) ? model.getApiHost() : "";
            if (StringUtils.isBlank(apiHost)) {
                if (provider.contains("qianwen") || provider.contains("bailian") || provider.contains("dashscope")) {
                    apiHost = "https://dashscope.aliyuncs.com/compatible-mode/v1";
                } else if (provider.contains("deepseek")) {
                    apiHost = "https://api.deepseek.com/v1";
                } else if (provider.contains("zhipu")) {
                    apiHost = "https://open.bigmodel.cn/api/paas/v4";
                } else if (provider.contains("siliconflow")) {
                    apiHost = "https://api.siliconflow.cn/v1";
                } else {
                    apiHost = "https://api.openai.com/v1";
                }
            }

            String baseUrl = apiHost.replaceAll("/+$", "");
            String url = baseUrl.endsWith("/models") ? baseUrl : baseUrl + "/models";

            cn.hutool.http.HttpResponse response = cn.hutool.http.HttpUtil.createGet(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(12000)
                    .execute();

            // 若厂商不支持 /models 列表接口返回 404，根据模型类型触发对应的轻量级握手测试
            if (response.getStatus() == 404) {
                boolean isRerank = "rerank".equalsIgnoreCase(model.getCategory()) || modelName.contains("rerank");
                if (isRerank) {
                    String rerankUrl;
                    com.alibaba.fastjson.JSONObject rerankBody = new com.alibaba.fastjson.JSONObject();
                    
                    if (provider.contains("qianwen") || provider.contains("bailian") || provider.contains("dashscope") || provider.contains("阿里云")) {
                        rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
                        rerankBody.put("model", modelName);
                        com.alibaba.fastjson.JSONObject inputObj = new com.alibaba.fastjson.JSONObject();
                        inputObj.put("query", "hi");
                        com.alibaba.fastjson.JSONArray docs = new com.alibaba.fastjson.JSONArray();
                        docs.add("hello");
                        inputObj.put("documents", docs);
                        rerankBody.put("input", inputObj);
                    } else {
                        rerankUrl = baseUrl.endsWith("/rerank") ? baseUrl : baseUrl + "/rerank";
                        rerankBody.put("model", modelName);
                        rerankBody.put("query", "hi");
                        com.alibaba.fastjson.JSONArray docs = new com.alibaba.fastjson.JSONArray();
                        docs.add("hello");
                        rerankBody.put("documents", docs);
                    }

                    response = cn.hutool.http.HttpUtil.createPost(rerankUrl)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .body(rerankBody.toJSONString())
                            .timeout(12000)
                            .execute();
                } else {
                    String chatUrl = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
                    com.alibaba.fastjson.JSONObject chatBody = new com.alibaba.fastjson.JSONObject();
                    chatBody.put("model", modelName);
                    chatBody.put("max_tokens", 1);
                    com.alibaba.fastjson.JSONArray messages = new com.alibaba.fastjson.JSONArray();
                    com.alibaba.fastjson.JSONObject msg = new com.alibaba.fastjson.JSONObject();
                    msg.put("role", "user");
                    msg.put("content", "hi");
                    messages.add(msg);
                    chatBody.put("messages", messages);

                    response = cn.hutool.http.HttpUtil.createPost(chatUrl)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .body(chatBody.toJSONString())
                            .timeout(12000)
                            .execute();
                }
            }

            long latency = System.currentTimeMillis() - startTime;

            if (response.getStatus() == 200) {
                java.util.Map<String, Object> resMap = new java.util.HashMap<>();
                resMap.put("latency", latency);
                resMap.put("msg", "API 响应正常，密钥与模型配置有效！");
                return R.ok(resMap);
            } else {
                String body = response.body();
                String errMsg = "HTTP " + response.getStatus();
                if (StringUtils.isNotBlank(body)) {
                    try {
                        com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(body);
                        if (json.containsKey("error")) {
                            Object errObj = json.get("error");
                            if (errObj instanceof com.alibaba.fastjson.JSONObject) {
                                errMsg += ": " + ((com.alibaba.fastjson.JSONObject) errObj).getString("message");
                            } else {
                                errMsg += ": " + errObj.toString();
                            }
                        } else if (json.containsKey("message")) {
                            errMsg += ": " + json.getString("message");
                        }
                    } catch (Exception ignored) {}
                }
                return R.fail("测试失败: " + errMsg);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("timed out") || msg.contains("Timeout"))) {
                return R.fail("连接超时：国内服务器无法直连该 API 域名，请在模型配置中填写中转代理地址 (API Host)！");
            }
            return R.fail("连接失败: " + msg);
        }
    }

    /**
     * 删除模型管理
     *
     * @param ids 主键串
     */
    @SaCheckPermission("system:model:remove")
    @Log(title = "模型管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(chatModelService.deleteWithValidByIds(List.of(ids), true));
    }
}
