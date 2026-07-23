package org.ruoyi.factory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.common.json.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.ruoyi.factory.LlmAdapter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里通义千问大模型驱动适配器（基于百炼 OpenAI 兼容协议）
 * 
 * @author antigravity
 */
public class QwenAdapter implements LlmAdapter {

    private static final Logger log = LoggerFactory.getLogger(QwenAdapter.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper = JsonUtils.newMapper();

    public QwenAdapter(String apiUrl, String apiKey, String model) {
        String baseUrl = (apiUrl == null || apiUrl.trim().isEmpty()) 
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : apiUrl;
        
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Flux<String> chatStream(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userMessage, history, true);
            String jsonRequest = objectMapper.writeValueAsString(requestBody);

            return webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(jsonRequest)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .filter(line -> line != null && !line.trim().isEmpty())
                    .flatMap(line -> {
                        String data = line.trim();
                        if (data.startsWith("data:")) {
                            data = data.substring(5).trim();
                        }
                        if ("[DONE]".equals(data)) {
                            return Flux.empty();
                        }
                        try {
                            JsonNode jsonNode = objectMapper.readTree(data);
                            JsonNode choices = jsonNode.get("choices");
                            if (choices != null && choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).get("delta");
                                if (delta != null && delta.has("content")) {
                                    return Flux.just(delta.get("content").asText());
                                }
                            }
                        } catch (Exception e) {
                            log.error("解析通义千问流式响应失败, line: {}", line, e);
                        }
                        return Flux.empty();
                    });
        } catch (Exception e) {
            log.error("构建通义千问流式请求失败", e);
            return Flux.error(e);
        }
    }

    @Override
    public String chat(String systemPrompt, String userMessage, List<Map<String, String>> history) {
        try {
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userMessage, history, false);
            String jsonRequest = objectMapper.writeValueAsString(requestBody);

            String response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(jsonRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode jsonNode = objectMapper.readTree(response);
            return jsonNode.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            log.error("同步调用通义千问失败", e);
            throw new RuntimeException("调用通义千问服务失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userMessage, 
                                                List<Map<String, String>> history, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", stream);

        List<Map<String, String>> messages = new ArrayList<>();
        
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }

        if (history != null) {
            for (Map<String, String> hist : history) {
                messages.add(hist);
            }
        }

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.put("messages", messages);
        return body;
    }
}
