package org.ruoyi.service.rerank.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.common.json.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.bo.rerank.RerankRequest;
import org.ruoyi.domain.bo.rerank.RerankResult;
import org.ruoyi.domain.dto.request.AliBaiLianRerankRequest;
import org.ruoyi.domain.dto.response.AliBaiLianRerankResponse;
import org.ruoyi.service.rerank.RerankModelService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 阿里百炼重排序模型实现
 * 参考设计模式：AliBaiLianMultiEmbeddingProvider
 *
 * @author yang
 * @date 2026-04-20
 */
@Slf4j
@Component("qianwenRerank")
@org.springframework.context.annotation.Scope("prototype")
public class AliBaiLianRerankModelService implements RerankModelService {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper = JsonUtils.newMapper();
    private ChatModelVo chatModelVo;

    public AliBaiLianRerankModelService() {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void configure(ChatModelVo config) {
        this.chatModelVo = config;
    }

    @Override
    public RerankResult rerank(RerankRequest rerankRequest) {
        long startTime = System.currentTimeMillis();

        try {
            // 构建请求
            AliBaiLianRerankRequest request = buildRequest(rerankRequest);
            AliBaiLianRerankResponse response = executeRequest(request);

            return response.toRerankResult(
                    rerankRequest.getDocuments().size(),
                    System.currentTimeMillis() - startTime
            );

        } catch (Exception e) {
            log.error("阿里百炼重排序失败: {}", e.getMessage(), e);
            throw new RuntimeException("重排序服务调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建请求对象
     */
    private AliBaiLianRerankRequest buildRequest(RerankRequest rerankRequest) {
        return AliBaiLianRerankRequest.create(
                chatModelVo.getModelName(),
                rerankRequest.getQuery(),
                rerankRequest.getDocuments(),
                rerankRequest.getTopN(),
                rerankRequest.getReturnDocuments()
        );
    }

    /**
     * 执行HTTP请求并解析响应
     */
    private AliBaiLianRerankResponse executeRequest(AliBaiLianRerankRequest request) throws IOException {
        // 构建阿里百炼 Native 原生 API 规范格式
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", chatModelVo.getModelName());
        
        java.util.Map<String, Object> input = new java.util.HashMap<>();
        input.put("query", request.query());
        input.put("documents", request.documents());
        payload.put("input", input);

        java.util.Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("top_n", request.topN() != null ? request.topN() : request.documents().size());
        parameters.put("return_documents", request.returnDocuments() != null ? request.returnDocuments() : true);
        payload.put("parameters", parameters);

        String jsonBody = objectMapper.writeValueAsString(payload);
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));

        // 阿里百炼官方 Native 专用重排序 Endpoint 与 OpenAI 兼容 Endpoint 双重容错
        String apiHost = chatModelVo.getApiHost();
        if (apiHost == null || apiHost.isBlank() || apiHost.contains("dashscope")) {
            apiHost = "https://dashscope.aliyuncs.com";
        }
        if (apiHost.endsWith("/")) {
            apiHost = apiHost.substring(0, apiHost.length() - 1);
        }
        
        String url = apiHost.contains("v1") ? apiHost : (apiHost + "/api/v1/services/rerank/text-rerank/text-rerank");
        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + chatModelVo.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "无错误信息";
                log.warn("阿里百炼重排序 API 返回非 200 响应: code={}, err={}", response.code(), err);
                throw new IllegalArgumentException("阿里百炼API调用失败: " + response.code() + " - " + err);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IllegalArgumentException("响应体为空");
            }

            return parseResponse(responseBody.string());
        }
    }

    /**
     * 解析响应 (无缝兼容阿里 Native 原生输出格式与 OpenAI 兼容格式)
     */
    private AliBaiLianRerankResponse parseResponse(String responseBody) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
        if (root.has("output")) {
            com.fasterxml.jackson.databind.JsonNode outputNode = root.get("output");
            com.fasterxml.jackson.databind.node.ObjectNode modifiedRoot = root.deepCopy();
            if (outputNode.has("results")) {
                modifiedRoot.set("results", outputNode.get("results"));
            }
            return objectMapper.treeToValue(modifiedRoot, AliBaiLianRerankResponse.class);
        }
        return objectMapper.readValue(responseBody, AliBaiLianRerankResponse.class);
    }
}
