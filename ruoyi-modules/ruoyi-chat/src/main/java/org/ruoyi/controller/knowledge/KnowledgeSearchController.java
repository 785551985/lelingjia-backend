package org.ruoyi.controller.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.service.knowledge.impl.KnowledgeSearchServiceImpl;
import org.ruoyi.service.knowledge.impl.KnowledgeSearchServiceImpl.SearchRequest;
import org.ruoyi.service.knowledge.impl.KnowledgeSearchServiceImpl.ChatResult;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 知识库智能问答检索控制器
 * 
 * @author antigravity
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/knowledge/chat")
public class KnowledgeSearchController extends BaseController {

    private final KnowledgeSearchServiceImpl knowledgeSearchService;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    /**
     * 流式知识检索与大模型问答 (Server-Sent Events)
     *
     * 前置输出 [SOURCES]:JSON 协议，以便前端解析引用来源。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody SearchRequest request, HttpServletResponse response) {
        
        try {
            String tenantStr = LoginHelper.getTenantId();
            request.setRawTenantId(tenantStr);
        } catch (Exception e) {
            request.setRawTenantId(null);
        }
        
        try {
            Object deptObj = LoginHelper.getDeptId();
            if (deptObj instanceof Long) {
                request.setDeptId((Long) deptObj);
            } else if (deptObj != null) {
                request.setDeptId(Long.parseLong(deptObj.toString()));
            } else {
                request.setDeptId(0L);
            }
        } catch (Exception e) {
            request.setDeptId(0L);
        }

        ChatResult chatResult = knowledgeSearchService.streamSearchAndAnswer(request);

        // 1. 将 sources 序列化为 JSON
        String sourcesJson;
        try {
            sourcesJson = objectMapper.writeValueAsString(chatResult.getSources());
        } catch (Exception e) {
            sourcesJson = "[]";
        }

        // 2. 构造 sources 特殊流事件作为包头
        Flux<ServerSentEvent<String>> sourcesHeader = Flux.just(ServerSentEvent.<String>builder()
                .data("[SOURCES]:" + sourcesJson)
                .build());

        // 3. 构造大模型文本回答流
        Flux<ServerSentEvent<String>> answerStream = chatResult.getAnswerStream()
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content)
                        .build());

        // 4. 顺序拼接成完整 SSE 流
        return Flux.concat(sourcesHeader, answerStream)
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .comment("DONE")
                        .build()));
    }
}
