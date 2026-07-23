package org.ruoyi.factory;

import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

/**
 * 大模型驱动适配器接口
 * 
 * @author antigravity
 */
public interface LlmAdapter {

    /**
     * 流式对话接口
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户当前输入
     * @param history      对话历史
     * @return 响应流
     */
    Flux<String> chatStream(String systemPrompt, String userMessage, List<Map<String, String>> history);

    /**
     * 同步对话接口
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户当前输入
     * @param history      对话历史
     * @return 模型回复文本
     */
    String chat(String systemPrompt, String userMessage, List<Map<String, String>> history);
}
