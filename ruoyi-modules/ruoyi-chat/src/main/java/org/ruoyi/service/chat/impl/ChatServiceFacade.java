package org.ruoyi.service.chat.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.shell.ShellSkills;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.agent.ChartGenerationAgent;
import org.ruoyi.agent.ChitChatAgent;
import org.ruoyi.agent.EchartsAgent;
import org.ruoyi.agent.SkillsAgent;
import org.ruoyi.agent.SqlAgent;
import org.ruoyi.agent.WebSearchAgent;
import org.ruoyi.agent.tool.ExecuteSqlQueryTool;
import org.ruoyi.agent.tool.QueryAllTablesTool;
import org.ruoyi.agent.tool.QueryTableSchemaTool;
import org.ruoyi.common.chat.base.ThreadContext;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.dto.request.WorkFlowRunner;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.chat.IChatService;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.core.utils.ObjectUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.config.agent.SkillsPathResolver;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.mcp.service.core.LangChain4jMcpToolProviderService;
import org.ruoyi.mcp.service.core.ToolProviderFactory;
import org.ruoyi.observability.*;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.AbstractChatService;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.chat.impl.memory.PersistentChatMemoryStore;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.knowledge.retriever.CustomVectorRetriever;
import org.ruoyi.service.vector.VectorStoreService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.ruoyi.domain.dto.knowledge.KnowledgeSourceDto;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务门面层
 * <p>
 * 作为统一入口，负责：
 * 1. 构建对话上下文
 * 2. 路由到对应的处理器
 *
 * @author ageerle@163.com
 * @date 2025/12/13
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceFacade implements IChatService {

    private static final Integer DEFAULT_MAX_MESSAGES = 6;

    private final IChatModelService chatModelService;

    private final ChatServiceFactory chatServiceFactory;

    private final IKnowledgeInfoService knowledgeInfoService;

    private final VectorStoreService vectorStoreService;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    private final KnowledgeAttachMapper knowledgeAttachMapper;

    private final KnowledgeFragmentMapper knowledgeFragmentMapper;

    private final SseEmitterManager sseEmitterManager;

    private final IChatMessageService chatMessageService;

    private final IWorkFlowStarterService workFlowStarterService;

    private final ToolProviderFactory toolProviderFactory;

    private final IAgentService agentService;

    private final LangChain4jMcpToolProviderService langChain4jMcpToolProviderService;

    /**
     * Session 异步任务管理器：支持客户端关网页/暂停断开硬取消，以及连续快速提问自动打断上一条任务
     */
    private final Map<String, CompletableFuture<Void>> sessionTaskMap = new ConcurrentHashMap<>();

    /**
     * 内存实例缓存，避免同一会话重复创建
     * Key: sessionId, Value: MessageWindowChatMemory实例
     */
    private static final Map<Object, MessageWindowChatMemory> memoryCache = new ConcurrentHashMap<>();



    /**
     * 统一聊天入口 - SSE流式响应
     *
     * @param chatRequest 聊天请求
     * @return SseEmitter
     */
    public SseEmitter sseChat(ChatRequest chatRequest) {

        // 4. 具体的服务实现
        Long userId = LoginHelper.getUserId();
        String tokenValue = StpUtil.getTokenValue();
        SseEmitter emitter = sseEmitterManager.connect(userId, tokenValue);

        // 0. 智能体解析：传入 agentId 时按智能体绑定的模型覆盖 model 字段
        //    （前端默认走智能体；enableThinking 不再作为对话模式开关，Supervisor 多 Agent 编排成为默认智能体路径）
        AgentVo agentVo = null;
        if (chatRequest.getAgentId() != null) {
            agentVo = agentService.queryById(chatRequest.getAgentId());
            if (agentVo != null && agentVo.getModelId() != null) {
                ChatModelVo agentModel = chatModelService.queryById(agentVo.getModelId());
                if (agentModel != null) {
                    chatRequest.setModel(agentModel.getModelName());
                }
            } else {
                log.warn("智能体不存在或未配置模型，回退到 model 字段: agentId={}", chatRequest.getAgentId());
            }
        }

        // 1. 根据模型名称查询完整配置，若未指定或无法匹配则自动回退到系统可用模型
        ChatModelVo chatModelVo = null;
        if (StringUtils.isNotBlank(chatRequest.getModel())) {
            chatModelVo = chatModelService.selectModelByName(chatRequest.getModel());
        }
        if (chatModelVo == null) {
            List<ChatModelVo> models = chatModelService.queryList(new org.ruoyi.common.chat.domain.bo.chat.ChatModelBo());
            if (models != null && !models.isEmpty()) {
                chatModelVo = models.get(0);
                chatRequest.setModel(chatModelVo.getModelName());
                log.info("未匹配到指定模型，自动回退打底模型: {}", chatModelVo.getModelName());
            } else {
                throw new IllegalArgumentException("系统中尚未配置任何 AI 大模型，请在管理后台先添加模型配置");
            }
        }

        // 2. 构建上下文消息列表（系统提示词 + 历史消息 + 当前用户消息）
        //    注意：RAG 检索增强统一在 handleAgentChat 中执行一次，此处不再重复检索
        List<ChatMessage> contextMessages = buildContextMessages(chatRequest, agentVo);

        chatRequest.setEmitter(emitter);
        chatRequest.setUserId(userId);
        chatRequest.setTokenValue(tokenValue);
        chatRequest.setChatModelVo(chatModelVo);
        chatRequest.setContextMessages(contextMessages);

        // 保存用户消息
        chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(), chatRequest.getContent(), RoleType.USER.getName(), chatRequest.getModel());

        // 3. 路由对话模式：工作流对话 / 智能体对话（两者均返回各自的 SseEmitter）
        return handleSpecialChatModes(chatRequest, agentVo);
    }

    /**
     * 路由对话模式：仅两种情况——工作流对话 / 智能体对话。
     *
     * @param chatRequest 聊天请求
     * @param agentVo    智能体配置（可为 null）
     * @return 对应模式的 SseEmitter
     */
    private SseEmitter handleSpecialChatModes(ChatRequest chatRequest, AgentVo agentVo) {
        // 模式1：工作流对话（前端应用市场选工作流后携带 workFlowRunner）
        if (Boolean.TRUE.equals(chatRequest.getEnableWorkFlow())) {
            log.info("处理工作流对话,会话: {}", chatRequest.getSessionId());
            WorkFlowRunner runner = chatRequest.getWorkFlowRunner();
            if (ObjectUtils.isEmpty(runner)) {
                log.warn("工作流参数为空");
            }
            return workFlowStarterService.streaming(
                ThreadContext.getCurrentUser(),
                runner.getUuid(),
                runner.getInputs(),
                chatRequest.getSessionId()
            );
        }
        // 模式2：智能体对话（默认走 Supervisor 多 Agent 编排）
        return handleAgentChat(chatRequest, agentVo);
    }

    /**
     * 智能体对话模式（默认）：构建 Supervisor 多 Agent 编排并异步执行，结果通过 SSE 推送。
     *
     * @param chatRequest 聊天请求
     * @param agentVo    智能体配置（可为 null，无智能体时用请求 model 兜底）
     */
    private SseEmitter handleAgentChat(ChatRequest chatRequest, AgentVo agentVo) {
        ChatModelVo chatModelVo = chatRequest.getChatModelVo();

        // 配置监督者模型：统一按 providerCode 走对应 AbstractChatService.buildChatModel，
        // 兼容 ZhiPu/QianWen/Ollama/Dify/Coze/CustomApi 等非 OpenAI 协议；默认实现为 OpenAI 兼容。
        AbstractChatService chatService = chatServiceFactory.getOriginalService(chatModelVo.getProviderCode());
        ChatModel plannerModel = chatService.buildChatModel(chatModelVo);

        Long userId = chatRequest.getUserId();

        // 工具装配：智能体有关联工具ID时按ID装配，未配置时为 null（避免强推硬编码 Playwright 挂载抛错）
        ToolProvider toolProvider = null;
        if (agentVo != null && agentVo.getMcpToolIds() != null && !agentVo.getMcpToolIds().isEmpty()) {
            try {
                toolProvider = langChain4jMcpToolProviderService.getToolProvider(agentVo.getMcpToolIds());
            } catch (Exception e) {
                log.warn("构建指定 MCP 工具异常，跳过工具注入: err={}", e.getMessage());
            }
        }

        // Skills 装配：智能体有勾选技能名时按名过滤磁盘 skills，否则加载全部
        ShellSkills skills = buildShellSkills(agentVo);

        // 构建子 Agent 列表
        List<Object> subAgents = new ArrayList<>();

        if (toolProvider != null) {
            WebSearchAgent searchAgent = AgenticServices.agentBuilder(WebSearchAgent.class)
                .chatModel(plannerModel)
                .toolProvider(toolProvider)
                .listener(new MyAgentListener())
                .build();
            subAgents.add(searchAgent);
        }

        // SkillsAgent：仅当有可用 skills 时才注入 systemMessage + toolProvider
        var skillsAgentBuilder = AgenticServices.agentBuilder(SkillsAgent.class)
            .chatModel(plannerModel);
        if (skills != null) {
            skillsAgentBuilder
                .systemMessage("You have access to the following skills:\n" + skills.formatAvailableSkills()
                    + "\nWhen the user's request relates to one of these skills, activate it first using the `activate_skill` tool before proceeding.")
                .toolProvider(skills.toolProvider());
            subAgents.add(skillsAgentBuilder.build());
        }

        // 构建子 Agent: SqlAgent - 负责数据库查询
        SqlAgent sqlAgent = AgenticServices.agentBuilder(SqlAgent.class)
            .chatModel(plannerModel)
            .tools(new QueryAllTablesTool(), new QueryTableSchemaTool(), new ExecuteSqlQueryTool())
            .listener(new MyAgentListener())
            .build();
        subAgents.add(sqlAgent);

        // 构建子 Agent: ChartGenerationAgent - 负责图表生成
        ChartGenerationAgent chartGenerationAgent = AgenticServices.agentBuilder(ChartGenerationAgent.class)
            .chatModel(plannerModel)
            .listener(new MyAgentListener())
            .build();
        subAgents.add(chartGenerationAgent);

        // 构建子 Agent: EchartsAgent - 负责数据可视化（结合 SQL 查询生成 Echarts 图表）
        EchartsAgent echartsAgent = AgenticServices.agentBuilder(EchartsAgent.class)
            .chatModel(plannerModel)
            .tools(new QueryAllTablesTool(), new QueryTableSchemaTool(), new ExecuteSqlQueryTool())
            .listener(new MyAgentListener())
            .build();
        subAgents.add(echartsAgent);

        // 构建监督者 Agent - 管理多个专业子 Agent（SQL、图表、技能、网络搜索）
        // 注意：通用问答不经过带有硬编码闲聊人设的子 Agent，确保完全遵循配置的系统提示词（systemPrompt）
        var supervisorBuilder = AgenticServices.supervisorBuilder()
            .chatModel(plannerModel)
            .subAgents(subAgents.toArray())
            .supervisorContext("优先根据用户提问意图路由 Agent："
                + "涉及数据库表格与结构数据查询使用 sqlAgent；"
                + "生成图表或数据可视化使用 chartGenerationAgent / echartsAgent。"
                + "如有技能或搜索工具方可使用技能/搜索 Agent。")
            .responseStrategy(SupervisorResponseStrategy.LAST);
        SupervisorAgent supervisor = supervisorBuilder.build();

        // 知识库增强：使用 LLM 动态意图分类器进行智能意图路由与策略判定
        RagAugmentResult ragResult = augmentAgentInput(chatRequest, agentVo, plannerModel);
        String augmentedInput = ragResult.getAugmentedPrompt();
        List<KnowledgeSourceDto> sourcesList = ragResult.getSources();


        // 构建结构化 ChatMessage 列表（确保 SystemMessage 作为系统角色独立发送给大模型）
        List<ChatMessage> fullMessages = new ArrayList<>();
        
        String currentDateStr = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 E HH:mm", java.util.Locale.CHINA));

        StringBuilder finalSystemPrompt = new StringBuilder();
        finalSystemPrompt.append("【系统当前实时时间】：").append(currentDateStr).append("\n\n");
        if (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt())) {
            finalSystemPrompt.append(agentVo.getSystemPrompt()).append("\n\n");
        }
        finalSystemPrompt.append("【智能体执行与输出最高准则】：\n")
            .append("1. **绝对聚焦当前最新提问**：请仅针对用户发送的最后一条最新提问进行解答！历史对话仅作为参考上下文，绝对禁止在当前回答中复读或重新解答上一轮历史对话中探讨过的旧问题（例如之前的“你能做什么”、“王明介绍”、“今天是星期几”等）！\n")
            .append("2. **严禁自我介绍与套话**：无论用户问什么，绝对禁止在回答中输出“我是企业专属AI助手”、“我能为您做些什么”、“我来为您解答”等自我介绍模版！\n")
            .append("3. **严禁分点复读历史**：绝对禁止采用 1. 2. 3. 列表的形式把历史对话中的多条问题重新梳理复读一遍！必须直奔用户当前最新提问的核心答案！\n");

        fullMessages.add(SystemMessage.from(finalSystemPrompt.toString()));

        if (chatRequest.getContextMessages() != null && !chatRequest.getContextMessages().isEmpty()) {
            List<ChatMessage> ctx = chatRequest.getContextMessages();
            int total = ctx.size();
            if (total > 0 && ctx.get(total - 1) instanceof UserMessage) {
                total--;
            }
            // 保留最近最多 4 条历史消息 (2 轮)，彻底防止长历史引发格式记忆幻觉
            int start = Math.max(0, total - 4);
            for (int i = start; i < total; i++) {
                ChatMessage msg = ctx.get(i);
                if (!(msg instanceof SystemMessage)) {
                    fullMessages.add(msg);
                }
            }
        }
        fullMessages.add(UserMessage.userMessage(augmentedInput));

        // 组装供 Supervisor 使用的文本
        StringBuilder promptBuilder = new StringBuilder();
        if (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt())) {
            promptBuilder.append(agentVo.getSystemPrompt()).append("\n\n");
        }
        String historyText = formatHistoryMessages(chatRequest.getContextMessages(), chatRequest.getContent());
        if (StringUtils.isNotBlank(historyText)) {
            promptBuilder.append("以下是本次会话的历史对话，请结合上下文理解用户最新提问：\n")
                .append(historyText).append("\n\n");
        }
        promptBuilder.append(augmentedInput);
        String prompt = promptBuilder.toString();

        String tokenValue = chatRequest.getTokenValue();
        String sessionId = chatRequest.getSessionId() != null ? String.valueOf(chatRequest.getSessionId()) : null;
        SseEmitter emitter = chatRequest.getEmitter();

        // 场景二：检测连续快速提问，强行打断同 Session 上一条尚未完成的生成任务
        if (StringUtils.isNotBlank(sessionId)) {
            CompletableFuture<Void> prevTask = sessionTaskMap.get(sessionId);
            if (prevTask != null && !prevTask.isDone()) {
                log.info("[Session Task Manager] 检测到 Session [{}] 收到连续新提问，硬打断上一条未完成生成任务！", sessionId);
                prevTask.cancel(true);
            }
        }

        // 场景一：监听客户端网页关闭 / 断开事件，硬取消大模型生成任务，防止浪费 Token 额度
        if (emitter != null && StringUtils.isNotBlank(sessionId)) {
            emitter.onCompletion(() -> sessionTaskMap.remove(sessionId));
            emitter.onError((err) -> {
                log.warn("[Session Task Manager] 客户端网页断开/关闭，打断 Session [{}] 生成任务: err={}", sessionId, err.getMessage());
                CompletableFuture<Void> task = sessionTaskMap.remove(sessionId);
                if (task != null && !task.isDone()) {
                    task.cancel(true);
                }
            });
        }

        // 异步执行智能体对话，结果通过 SSE 推送
        CompletableFuture<Void> currentTask = CompletableFuture.runAsync(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (sourcesList != null && !sourcesList.isEmpty()) {
                    SseMessageUtils.sendSources(userId, JSONUtil.toJsonStr(sourcesList));
                }
                String result = null;
                // 仅当明确需要 SQL数据库、图表可视化、联网搜索或扩展技能等工具时，才通过 Supervisor 进行多 Agent 调度
                boolean needSpecialTools = isSpecialToolRequested(chatRequest.getContent());
                if (needSpecialTools && !subAgents.isEmpty()) {
                    try {
                        result = supervisor.invoke(prompt);
                    } catch (Exception agentEx) {
                        log.debug("Supervisor 调度异常，自动无缝切换为大模型直连回答: err={}", agentEx.getMessage());
                    }
                }

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                // 普通对话、日常问候与知识库 RAG 问答：直连大模型（包含完整系统提示词 SystemMessage，确保 100% 遵守人设与精炼控制）
                if (StringUtils.isBlank(result) || result.contains("已成功调用") || result.contains("根据您提供的角色")) {
                    ChatResponse resp = plannerModel.chat(fullMessages);
                    result = resp.aiMessage().text();
                }

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                result = sanitizeOutputText(result);

                SseMessageUtils.sendContent(userId, result);
                SseMessageUtils.sendDone(userId);
                // 保存助手回复到数据库 (带参考来源元数据)
                if (StringUtils.isNotBlank(result)) {
                    String messageToSave = result;
                    if (sourcesList != null && !sourcesList.isEmpty()) {
                        messageToSave += "\n<sources>" + JSONUtil.toJsonStr(sourcesList) + "</sources>";
                    }
                    chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(),
                        messageToSave, RoleType.ASSISTANT.getName(), chatRequest.getModel());
                }
            } catch (Exception e) {
                if (e instanceof java.util.concurrent.CancellationException || e instanceof InterruptedException) {
                    log.info("[Session Task Manager] 会话 [{}] 生成任务已成功中断取消！", sessionId);
                } else {
                    log.error("智能体对话执行失败", e);
                    SseMessageUtils.sendError(userId, e.getMessage());
                }
            } finally {
                SseMessageUtils.completeConnection(userId, tokenValue);
            }
        });

        if (StringUtils.isNotBlank(sessionId)) {
            sessionTaskMap.put(sessionId, currentTask);
            currentTask.whenComplete((v, ex) -> sessionTaskMap.remove(sessionId));
        }

        return chatRequest.getEmitter();
    }

    /**
     * 手动停止指定 Session 正在运行的大模型生成任务
     */
    public boolean stopSessionTask(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return false;
        CompletableFuture<Void> task = sessionTaskMap.remove(sessionId);
        if (task != null && !task.isDone()) {
            log.info("[Session Task Manager] 手动触发强行停止 Session [{}] 的生成任务", sessionId);
            return task.cancel(true);
        }
        return false;
    }

    /**
     * 兜底 MCP 工具装配（无智能体时使用，保留原有 3 个硬编码客户端逻辑）
     */
    private ToolProvider buildDefaultMcpToolProvider(Long userId) {
        String npxCommand = resolveNpxCommand();
        McpTransport playwrightTransport = new StdioMcpTransport.Builder()
            .command(List.of(npxCommand, "-y", "@playwright/mcp@latest"))
            .logEvents(true)
            .build();
        McpClient playwrightMcpClient = new DefaultMcpClient.Builder()
            .transport(playwrightTransport)
            .listener(new MyMcpClientListener(userId))
            .build();

        String userDir = System.getProperty("user.dir");
        McpTransport filesystemTransport = new StdioMcpTransport.Builder()
            .command(List.of(npxCommand, "-y",
                "@modelcontextprotocol/server-filesystem", userDir))
            .logEvents(true)
            .build();
        McpClient filesystemMcpClient = new DefaultMcpClient.Builder()
            .transport(filesystemTransport)
            .listener(new MyMcpClientListener(userId))
            .build();

        return McpToolProvider.builder()
            .mcpClients(List.of(playwrightMcpClient, filesystemMcpClient))
            .build();
    }

    private String resolveNpxCommand() {
        String configured = System.getProperty("mcp.npx.command");
        if (StringUtils.isNotBlank(configured)) return configured;
        String fromEnv = System.getenv("MCP_NPX_COMMAND");
        if (StringUtils.isNotBlank(fromEnv)) return fromEnv;
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "npx.cmd" : "npx";
    }

    /**
     * 装配磁盘 ShellSkills：智能体勾选了技能名时按名过滤，否则加载全部。
     * 无 skills 时返回 null（调用方据此跳过 SkillsAgent 的 toolProvider 注入）
     */
    private ShellSkills buildShellSkills(AgentVo agentVo) {
        try {
            java.nio.file.Path skillsPath = SkillsPathResolver.resolveSkillsPath();
            List<FileSystemSkill> skillsList = FileSystemSkillLoader.loadSkills(skillsPath);
            if (skillsList == null || skillsList.isEmpty()) {
                return null;
            }
            if (agentVo != null && agentVo.getSkillNames() != null && !agentVo.getSkillNames().isEmpty()) {
                skillsList = skillsList.stream()
                    .filter(s -> agentVo.getSkillNames().contains(s.name()))
                    .toList();
                if (skillsList.isEmpty()) {
                    return null;
                }
            }
            return ShellSkills.from(skillsList);
        } catch (Exception e) {
            log.warn("加载磁盘 Skills 发生异常，跳过技能加载: err={}", e.getMessage());
            return null;
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class RagAugmentResult {
        private String augmentedPrompt;
        private List<KnowledgeSourceDto> sources;
    }

    private static final java.util.regex.Pattern CASUAL_GREETING_PATTERN = java.util.regex.Pattern.compile(
        "^(你好|您好|hello|hi|hey|在吗|在不在|早上好|早安|下午好|晚上好|你是谁|你能做什么|你能干嘛|你是做什么的|谢谢|感谢|多谢|再见|拜拜|好的|收到|ok|OK)[!！?？~〜\\s\\.]*$",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );

    private boolean isCasualGreeting(String content) {
        if (org.ruoyi.common.core.utils.StringUtils.isBlank(content)) {
            return true;
        }
        String trimmed = content.trim();
        return trimmed.length() <= 12 && CASUAL_GREETING_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 用户动态意图枚举分类
     */
    public enum UserIntent {
        GREETING,        // 1. 日常打招呼 / 问候 / 闲聊 / 表达心情
        SYSTEM_IDENTITY, // 2. 询问 AI 身份 / 询问使用的模型 / 询问系统能做什么
        KNOWLEDGE_QUERY, // 3. 查询企业业务知识 / 规章制度 / 专家 / 流程 / SOP
        GENERAL_CHAT     // 4. 通用对话 / 辅助写作 / 代码撰写 / 泛问答
    }

    /**
     * 极速动态意图分类器 (零外网 API 开销，毫秒级响应)
     */
    private UserIntent classifyUserIntent(String content) {
        if (StringUtils.isBlank(content)) {
            return UserIntent.GREETING;
        }
        String trimmed = content.trim();

        // 毫秒级意图响应旁路 (0ms)
        if (trimmed.matches("^(你好|您好|hello|hi|hey|在吗|在不在|早上好|早安|下午好|晚上好|谢谢|感谢|再见|拜拜)[!！?？~〜\\s\\.]*$")) {
            return UserIntent.GREETING;
        }
        if (trimmed.matches("^(你是谁|你叫什么|你能做什么|你能干嘛|你用的是什么模型|你是什么模型|介绍一下你自己)[!！?？~〜\\s\\.]*$")) {
            return UserIntent.SYSTEM_IDENTITY;
        }

        return UserIntent.KNOWLEDGE_QUERY;
    }

    /**
     * 智能体对话下的输入增强：触发高精度向量检索与参考来源卡片挂载
     */
    private RagAugmentResult augmentAgentInput(ChatRequest chatRequest, AgentVo agentVo, ChatModel chatModel) {
        String content = chatRequest.getContent();

        // 🧠 1. 执行极速动态意图分类 (0ms)
        UserIntent intent = classifyUserIntent(content);
        log.info("动态意图分类结果: intent={}, userQuery={}", intent, content);

        // 策略 A: 日常问候/闲聊意图 -> 不检索知识库，不挂载任何附件来源，输出自然打招呼
        if (intent == UserIntent.GREETING) {
            return new RagAugmentResult(content, Collections.emptyList());
        }

        // 策略 B: 身份/模型/能力询问意图 -> 不检索知识库，不挂载任何附件来源，自然人设回答
        if (intent == UserIntent.SYSTEM_IDENTITY) {
            String identityPrompt = "你是企业专属 AI 智能助手，由内部大模型驱动。"
                + "请用自然、友好、自信的语气回答用户关于你身份或模型能力的询问，说明你可以帮助解答公司业务、规章制度与 SOP 问题。请勿引用或参考任何文档来源。";
            return new RagAugmentResult(identityPrompt + "\n\n用户提问：" + content, Collections.emptyList());
        }

        // 策略 C: 通用泛问答意图 -> 原生大模型解答
        if (intent == UserIntent.GENERAL_CHAT) {
            return new RagAugmentResult(content, Collections.emptyList());
        }

        // 策略 D: KNOWLEDGE_QUERY (企业业务知识/制度/SOP/专家/人名查询) -> 触发 pgvector 原生向量检索与参考来源卡片
        List<Long> knowledgeIds = collectKnowledgeIds(chatRequest, agentVo);
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return new RagAugmentResult(content, Collections.emptyList());
        }
        try {
            ContentRetriever retriever = buildMultiKnowledgeContentRetriever(knowledgeIds);
            if (retriever == null) {
                return new RagAugmentResult(content, Collections.emptyList());
            }
            List<Content> contents = retriever.retrieve(Query.from(content));
            if (contents == null || contents.isEmpty()) {
                return new RagAugmentResult(content, Collections.emptyList());
            }

            // ★ 强相关性精筛：
            // 1. 寻找全量切片中的最高得分 maxScore
            double maxScore = 0.0;
            for (Content c : contents) {
                if (c.textSegment() != null && c.textSegment().metadata() != null) {
                    String scoreStr = c.textSegment().metadata().getString("score");
                    if (StringUtils.isNotBlank(scoreStr)) {
                        try {
                            double sc = Double.parseDouble(scoreStr);
                            if (sc > maxScore) maxScore = sc;
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 基础绝对门槛设为 0.28 (保障“王明”、“王明简介”等短词/人名检索不被误拦)
            if (maxScore < 0.28) {
                log.info("意图分析：最高相关度得分 ({}) 低于 0.28 基础阈值，跳过知识库注入与来源挂载: {}", maxScore, content);
                return new RagAugmentResult(content, Collections.emptyList());
            }

            // 2. 只有绝对分值 >= 0.28 并且 与最高分差距在 0.08 范围内的切片，才判定为真正强相关业务切片！
            // 彻底剔除得分断崖下降的无关偏门混入文档 (如搜“专家”时误混入的“流量洞察.xlsx”或“报价单.md”)
            double scoreThreshold = Math.max(0.28, maxScore - 0.08);
            List<Content> validContents = new ArrayList<>();
            for (Content c : contents) {
                if (c.textSegment() != null && c.textSegment().metadata() != null) {
                    String scoreStr = c.textSegment().metadata().getString("score");
                    double score = 0.0;
                    if (StringUtils.isNotBlank(scoreStr)) {
                        try { score = Double.parseDouble(scoreStr); } catch (Exception ignored) {}
                    }
                    if (score >= scoreThreshold) {
                        validContents.add(c);
                    }
                }
            }

            StringBuilder sb = new StringBuilder("你是一位严谨、真实、专业的企业智能助手。\n");
            sb.append("请使用自然、友好、有温度的语气回答用户。\n\n");
            sb.append("【零幻觉与严格事实忠实性最高准则】：\n");
            sb.append("1. **绝对忠于参考资料**：回答必须 100% 严格基于下方提供的【参考资料】中明确记载的文字事实！\n");
            sb.append("2. **严禁凭空捏造与编造数据**：若【参考资料】中未记载用户所询问的特定属性信息（例如：具体的手机号码、电话分机、个人邮箱、身份证号、薪资水平等），绝对禁止凭空捏造、假想或填充任何假手机号（如 138-0000-1234）、假邮箱（如 wangming@company.com）或虚拟占位符！\n");
            sb.append("3. **据实说明并温和引导**：若参考资料中未记载相关具体细节，请直接如实告知：“参考资料中记载了该人物的基本信息，但未登记具体的电话或邮箱，建议您联系人力资源或行政部门查询。”\n\n");
            sb.append("【参考资料】\n");
            for (int i = 0; i < validContents.size(); i++) {
                sb.append("[").append(i + 1).append("] ")
                  .append(validContents.get(i).textSegment().text())
                  .append("\n\n");
            }
            sb.append("---------------------\n用户提问：").append(content);

            List<KnowledgeSourceDto> sources = extractSourcesFromContents(validContents);
            return new RagAugmentResult(sb.toString(), sources);
        } catch (Exception e) {
            log.warn("智能体对话 RAG 增强失败，回退原始输入: {}", e.getMessage());
            return new RagAugmentResult(content, Collections.emptyList());
        }
    }

    private List<KnowledgeSourceDto> extractSourcesFromContents(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, KnowledgeSourceDto> sourceMap = new LinkedHashMap<>();
        Set<String> docIds = new HashSet<>();

        for (Content c : contents) {
            if (c.textSegment() == null || c.textSegment().metadata() == null) {
                continue;
            }
            var meta = c.textSegment().metadata();
            String docId = meta.getString("docId");
            String kidStr = meta.getString("kid");
            String snippet = c.textSegment().text();
            if (StringUtils.isNotBlank(docId)) {
                docIds.add(docId);
            }
            Long kid = StringUtils.isNotBlank(kidStr) ? Long.valueOf(kidStr) : null;
            String kname = "企业知识库";
            if (kid != null) {
                try {
                    KnowledgeInfoVo kVo = knowledgeInfoService.queryById(kid);
                    if (kVo != null && StringUtils.isNotBlank(kVo.getName())) {
                        kname = kVo.getName();
                    }
                } catch (Exception ignored) {}
            }
            String key = StringUtils.isNotBlank(docId) ? docId : (snippet != null ? snippet : String.valueOf(sourceMap.size()));
            if (!sourceMap.containsKey(key)) {
                KnowledgeSourceDto dto = KnowledgeSourceDto.builder()
                    .docId(docId)
                    .knowledgeId(kid)
                    .knowledgeName(kname)
                    .snippet(snippet)
                    .name(kname + " 参考出处")
                    .score(92.0)
                    .build();
                sourceMap.put(key, dto);
            } else {
                KnowledgeSourceDto existing = sourceMap.get(key);
                if (existing != null && StringUtils.isNotBlank(snippet)) {
                    if (!existing.getSnippet().contains(snippet)) {
                        existing.setSnippet(existing.getSnippet() + "\n\n" + snippet);
                    }
                }
            }
        }

        if (!docIds.isEmpty()) {
            try {
                // 1. 查询关联附件名称与 OSS ID
                List<KnowledgeAttachVo> attaches = knowledgeAttachMapper.selectVoList(
                    Wrappers.<KnowledgeAttach>lambdaQuery().in(KnowledgeAttach::getDocId, docIds)
                );
                if (attaches != null) {
                    for (KnowledgeAttachVo vo : attaches) {
                        KnowledgeSourceDto dto = sourceMap.get(vo.getDocId());
                        if (dto != null) {
                            if (StringUtils.isNotBlank(vo.getName())) {
                                dto.setName(vo.getName());
                            }
                            dto.setOssId(vo.getOssId());
                            if (vo.getOssId() != null) {
                                dto.setDownloadUrl("/resource/oss/preview/" + vo.getOssId());
                            }
                        }
                    }
                }

                // 2. 自动拉取并按顺序拼接该文档下的全量切片文本（包含所有表格、工作表与全量段落），实现 100% 完整的受控原件文本预览！
                List<KnowledgeFragment> frags = knowledgeFragmentMapper.selectList(
                    Wrappers.<KnowledgeFragment>lambdaQuery()
                        .in(KnowledgeFragment::getDocId, docIds)
                        .orderByAsc(KnowledgeFragment::getIdx)
                );
                if (frags != null && !frags.isEmpty()) {
                    Map<String, List<KnowledgeFragment>> fragMap = frags.stream()
                        .filter(f -> StringUtils.isNotBlank(f.getDocId()) && StringUtils.isNotBlank(f.getContent()))
                        .collect(Collectors.groupingBy(KnowledgeFragment::getDocId));

                    for (Map.Entry<String, List<KnowledgeFragment>> entry : fragMap.entrySet()) {
                        KnowledgeSourceDto dto = sourceMap.get(entry.getKey());
                        if (dto != null) {
                            String fullDocText = entry.getValue().stream()
                                .map(f -> f.getContent().trim())
                                .collect(Collectors.joining("\n\n----------------------------------------\n\n"));
                            if (StringUtils.isNotBlank(fullDocText)) {
                                dto.setSnippet(fullDocText);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("查询关联附件及切片文本异常: err={}", e.getMessage());
            }
        }
        return new ArrayList<>(sourceMap.values());
    }

    /**
     * 支持外部 handler 的对话接口（跨模块调用）
     * 同时发送到 SSE 和外部 handler
     *
     * @param chatRequest     聊天请求
     * @param externalHandler 外部响应处理器（可为 null）
     */
    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler externalHandler) {
        // 1. 根据模型名称查询完整配置
        ChatModelVo chatModelVo = chatModelService.selectModelByName(chatRequest.getModel());
        if (chatModelVo == null) {
            throw new IllegalArgumentException("模型不存在: " + chatRequest.getModel());
        }

        // 3. 路由服务提供商
        String providerCode = chatModelVo.getProviderCode();
        log.info("跨模块调用 - 路由到服务提供商: {}, 模型: {}", providerCode, chatRequest.getModel());
        AbstractChatService chatService = chatServiceFactory.getOriginalService(providerCode);

        // 4. 获取用户信息
        Long userId = LoginHelper.getUserId();
        String tokenValue = StpUtil.getTokenValue();

        // 5. 建立 SSE 连接（用于前端监听）
        sseEmitterManager.connect(userId, tokenValue);

        // 保存用户消息
        chatMessageService.saveChatMessage(userId, chatRequest.getSessionId(), chatRequest.getContent(), RoleType.USER.getName(), chatRequest.getModel());

        // 6. 创建组合 handler：同时发送到 SSE 和外部 handler
        StreamingChatResponseHandler combinedHandler = createCombinedHandler(userId, tokenValue, externalHandler);

        // 7. 发起对话
        StreamingChatModel streamingChatModel = chatService.buildStreamingChatModel(chatModelVo, chatRequest);
        streamingChatModel.chat(chatRequest.getContent(), combinedHandler);
    }

    /**
     * 实现接口默认方法 - 不带 handler 的调用
     */
    @Override
    public SseEmitter chat(ChatRequest chatRequest) {
        return sseChat(chatRequest);
    }


    /**
     * 创建或获取聊天内存实例（缓存机制）
     * 同一个会话ID会返回同一个内存实例，避免重复创建和消息丢失
     *
     * @param memoryId 内存ID（会话ID）
     * @return MessageWindowChatMemory实例
     */
    private MessageWindowChatMemory createChatMemory(Object memoryId) {
        // 先从缓存中获取
        return memoryCache.computeIfAbsent(memoryId, key -> {
            try {
                PersistentChatMemoryStore store = new PersistentChatMemoryStore(chatMessageService);
                return MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(DEFAULT_MAX_MESSAGES)
                    .chatMemoryStore(store)
                    .build();
            } catch (Exception e) {
                log.warn("创建聊天内存失败: {}", e.getMessage());
                return null;
            }
        });
    }


    /**
     * 构建上下文消息列表
     * 消息顺序：系统提示词 → 历史消息 → 当前用户消息（确保 AI 正确理解对话上下文）
     *
     * @param chatRequest 聊天请求
     * @param agentVo     智能体配置（可为 null）
     * @return 上下文消息列表
     */
    private List<ChatMessage> buildContextMessages(ChatRequest chatRequest, AgentVo agentVo) {
        List<ChatMessage> messages = new ArrayList<>();

        // 0. 智能体自定义系统提示词（普通对话今天无 SystemMessage，这里新增注入点）
        if (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt())) {
            messages.add(SystemMessage.from(agentVo.getSystemPrompt()));
        }

        // 1. 从数据库查询历史对话消息（放在前面）
        if (chatRequest.getSessionId() != null) {
            MessageWindowChatMemory memory = createChatMemory(chatRequest.getSessionId());
            if (memory != null) {
                List<ChatMessage> historicalMessages = memory.messages();
                if (historicalMessages != null && !historicalMessages.isEmpty()) {
                    messages.addAll(historicalMessages);
                    log.debug("已加载 {} 条历史消息用于会话 {}", historicalMessages.size(), chatRequest.getSessionId());
                }
            }
        }

        // 2. 添加当前用户消息（放在最后；RAG 增强在 handleAgentChat 中统一执行，避免重复检索）
        messages.add(UserMessage.userMessage(chatRequest.getContent()));

        return messages;
    }

    /**
     * 将上下文消息格式化为多轮对话文本（供只接受 String 输入的 Supervisor 使用）。
     * 跳过 SystemMessage（系统提示词单独前置）与最后一条当前用户消息（单独做 RAG 增强后拼接）。
     */
    private String formatHistoryMessages(List<ChatMessage> contextMessages, String currentContent) {
        if (contextMessages == null || contextMessages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = contextMessages.size();
        // 最后一条是当前用户消息，不纳入历史（避免与增强后的输入重复）
        if (limit > 0 && contextMessages.get(limit - 1) instanceof UserMessage) {
            limit--;
        }
        for (int i = 0; i < limit; i++) {
            ChatMessage msg = contextMessages.get(i);
            if (msg instanceof UserMessage userMsg) {
                sb.append("用户: ").append(userMsg.singleText()).append("\n");
            } else if (msg instanceof AiMessage aiMsg) {
                sb.append("助手: ").append(aiMsg.text()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 对 AI 输出进行安全脱敏净化，强行剔除任何可能包含的服务器本地物理盘符与绝对目录路径（如 D:\czl\企业知识库...）
     */
    private String sanitizeOutputText(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        // 过滤 Windows/Linux 本地物理磁盘绝对路径，替换为通用描述
        return text.replaceAll("(?i)[a-z]:\\\\(?:[^\\s\\u4e00-\\u9fa5,，。！!？?)\\]]+)", "知识库系统")
                   .replaceAll("(?i)[a-z]:/(?:[^\\s\\u4e00-\\u9fa5,，。！!？?)\\]]+)", "知识库系统");
    }

    /**
     * 判断用户提问是否真正需要特殊的扩展工具子 Agent（如数据库SQL查询、图表生成、联网搜索、磁盘技能等）。
     * 只有明确提出画图、生成图表、查询数据库等指令时才触发。普通对话、名词检索与知识库 RAG 问答返回 false。
     */
    private boolean isSpecialToolRequested(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        String lower = content.toLowerCase();
        return lower.contains("sql") || lower.contains("数据库") || lower.contains("数据表") || lower.contains("执行sql")
            || lower.contains("生成图表") || lower.contains("echarts") || lower.contains("画图") || lower.contains("画柱状图")
            || lower.contains("柱状图") || lower.contains("折线图") || lower.contains("饼图") || lower.contains("数据可视化")
            || lower.contains("联网搜索") || lower.contains("全网检索") || lower.contains("网络搜索") || lower.contains("调用技能");
    }

    /**
     * 汇总本次对话要检索的知识库ID列表：
     * 1. 智能体显式绑定的 knowledgeIds 优先；
     * 2. 请求显式指定的 knowledgeId 次之；
     * 3. 若均为空（智能体【关联知识库】留空），自动回退为当前登录员工权限内可访问的所有知识库（集团/机构/部门/个人）。
     */
    private List<Long> collectKnowledgeIds(ChatRequest chatRequest, AgentVo agentVo) {
        if (agentVo != null && agentVo.getKnowledgeIds() != null && !agentVo.getKnowledgeIds().isEmpty()) {
            return agentVo.getKnowledgeIds();
        }
        if (StringUtils.isNotBlank(chatRequest.getKnowledgeId())) {
            try {
                return List.of(Long.valueOf(chatRequest.getKnowledgeId()));
            } catch (NumberFormatException ignored) {
            }
        }
        // 当【关联知识库】留空时（如通用 AI 助手），优先按数据权限获取当前员工权限范围内可见的知识库列表
        try {
            List<KnowledgeInfoVo> accessibleKbs = knowledgeInfoService.queryList(new org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo());
            if (accessibleKbs != null && !accessibleKbs.isEmpty()) {
                List<Long> kbIds = accessibleKbs.stream().map(KnowledgeInfoVo::getId).toList();
                log.info("通用智能体权限模式：自动限定在当前员工【数据权限范围内】的知识库，可见库数量: {}", kbIds.size());
                return kbIds;
            }
        } catch (Exception e) {
            log.warn("获取员工全量权限知识库异常: err={}", e.getMessage());
        }

        // 兜底防护：直接提取系统中全量包含向量切片的知识库 ID，保障问答不被中断
        try {
            List<Long> allKbIds = knowledgeFragmentMapper.selectAllKnowledgeIds();
            if (allKbIds != null && !allKbIds.isEmpty()) {
                log.info("通用智能体兜底模式：提取向量数据全量知识库，数量: {}", allKbIds.size());
                return allKbIds;
            }
        } catch (Exception e) {
            log.warn("全量向量库兜底查询异常: err={}", e.getMessage());
        }

        return List.of();
    }

    /**
     * 构建多知识库复合 ContentRetriever。
     * 单知识库直接用 CustomVectorRetriever；
     * 多知识库用一个 CompositeContentRetriever 合并各库检索结果。
     */
    private ContentRetriever buildMultiKnowledgeContentRetriever(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        List<ContentRetriever> retrievers = new ArrayList<>();
        for (Long kid : knowledgeIds) {
            try {
                KnowledgeInfoVo kb = knowledgeInfoService.queryById(kid);
                if (kb == null) {
                    continue;
                }
                ChatModelVo embModel = chatModelService.selectModelByName(kb.getEmbeddingModel());
                if (embModel == null) {
                    log.warn("知识库向量模型未配置或不存在: kid={}, embeddingModel={}", kid, kb.getEmbeddingModel());
                    continue;
                }
                retrievers.add(new CustomVectorRetriever(knowledgeRetrievalService, kb, embModel));
            } catch (Exception e) {
                log.warn("构建知识库检索器失败: kid={}, err={}", kid, e.getMessage());
            }
        }
        if (retrievers.isEmpty()) {
            return null;
        }
        return retrievers.size() == 1
            ? retrievers.get(0)
            : new CompositeContentRetriever(retrievers);
    }

    /**
     * 专属高并发知识库检索线程池（避免占用 ForkJoinPool 导致串行阻塞卡顿）
     */
    private static final java.util.concurrent.ExecutorService SEARCH_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(30);

    /**
     * 复合内容检索器：对多个知识库检索器并发查询并合并结果
     */
    private static class CompositeContentRetriever implements ContentRetriever {
        private final List<ContentRetriever> delegates;

        CompositeContentRetriever(List<ContentRetriever> delegates) {
            this.delegates = delegates;
        }

        @Override
        public List<Content> retrieve(Query query) {
            List<CompletableFuture<List<Content>>> futures = delegates.stream()
                    .map(r -> CompletableFuture.supplyAsync(() -> {
                        try {
                            List<Content> part = r.retrieve(query);
                            return part == null ? List.<Content>of() : part;
                        } catch (Exception e) {
                            log.warn("复合检索子检索器异常: {}", e.getMessage());
                            return List.<Content>of();
                        }
                    }, SEARCH_EXECUTOR)).toList();
            Map<String, Content> unique = new LinkedHashMap<>();
            for (CompletableFuture<List<Content>> future : futures) {
                try {
                    List<Content> partList = future.get(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    for (Content content : partList) {
                        String key = content.textSegment().metadata().getString("kid") + "|"
                                + content.textSegment().metadata().getString("docId") + "|"
                                + content.textSegment().metadata().getString("fid");
                        if (key.endsWith("null|null|null")) key = content.textSegment().text();
                        unique.putIfAbsent(key, content);
                    }
                } catch (Exception e) {
                    log.warn("复合检索子任务等待超时或异常: {}", e.getMessage());
                }
            }
            List<Content> bounded = new ArrayList<>();
            int chars = 0;
            for (Content content : unique.values()) {
                int next = content.textSegment().text().length();
                if (bounded.size() >= 20 || chars + next > 24000) break;
                bounded.add(content);
                chars += next;
            }
            return bounded;
        }
    }

    /**
     * 构建向量查询参数
     */
    private QueryVectorBo buildQueryVectorBo(ChatRequest chatRequest, KnowledgeInfoVo knowledgeInfoVo,
                                             ChatModelVo chatModel) {
        QueryVectorBo queryVectorBo = new QueryVectorBo();
        queryVectorBo.setQuery(chatRequest.getContent());
        queryVectorBo.setKid(chatRequest.getKnowledgeId());
        queryVectorBo.setApiKey(chatModel.getApiKey());
        queryVectorBo.setBaseUrl(chatModel.getApiHost());
        queryVectorBo.setVectorModelName(knowledgeInfoVo.getVectorModel());
        queryVectorBo.setEmbeddingModelName(knowledgeInfoVo.getEmbeddingModel());
        queryVectorBo.setMaxResults(knowledgeInfoVo.getRetrieveLimit());

        // 设置重排序参数
        queryVectorBo.setEnableRerank(knowledgeInfoVo.getEnableRerank() != null && knowledgeInfoVo.getEnableRerank() == 1);
        queryVectorBo.setRerankModelName(knowledgeInfoVo.getRerankModel());
        queryVectorBo.setRerankTopN(knowledgeInfoVo.getRerankTopN());
        queryVectorBo.setRerankScoreThreshold(knowledgeInfoVo.getRerankScoreThreshold());

        return queryVectorBo;
    }

    /**
     * 创建组合响应处理器 - 同时发送到 SSE 和外部 handler
     *
     * @param userId          用户ID
     * @param tokenValue      会话令牌
     * @param externalHandler 外部响应处理器（可为 null）
     * @return 组合的流式响应处理器
     */
    protected StreamingChatResponseHandler createCombinedHandler(Long userId, String tokenValue,
                                                                  StreamingChatResponseHandler externalHandler) {
        return new StreamingChatResponseHandler() {

            private final StringBuilder messageBuffer = new StringBuilder();

            @SneakyThrows
            @Override
            public void onPartialResponse(String partialResponse) {
                // 1. 追加到缓冲区
                messageBuffer.append(partialResponse);

                // 2. 发送内容事件到 SSE（前端可通过 SSE 监听）
                SseMessageUtils.sendContent(userId, partialResponse);

                // 3. 转发给外部 handler（Workflow 等模块可处理）
                if (externalHandler != null) {
                    externalHandler.onPartialResponse(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    // 1. 发送完成事件
                    SseMessageUtils.sendDone(userId);

                    // 2. 关闭 SSE 连接
                    SseMessageUtils.completeConnection(userId, tokenValue);

                    // 3. 转发给外部 handler
                    if (externalHandler != null) {
                        externalHandler.onCompleteResponse(completeResponse);
                    }
                } catch (Exception e) {
                    log.error("完成响应时出错: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onError(Throwable error) {
                // 发送错误事件
                SseMessageUtils.sendError(userId, error.getMessage());
                log.error("流式响应错误: {}", error.getMessage(), error);

                // 转发给外部 handler
                if (externalHandler != null) {
                    externalHandler.onError(error);
                }
            }
        };
    }
}

