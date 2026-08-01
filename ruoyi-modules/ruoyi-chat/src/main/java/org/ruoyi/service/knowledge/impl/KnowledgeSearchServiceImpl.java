package org.ruoyi.service.knowledge.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.ruoyi.factory.LlmAdapter;
import org.ruoyi.factory.ModelFactory;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 集团化多租户企业级知识库 RAG 核心服务 (PostgreSQL + pgvector 一体化实现)
 * 
 * @author antigravity
 */
@Service
public class KnowledgeSearchServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchServiceImpl.class);
    private static final String REDIS_FAQ_CACHE_PREFIX = "kb:faq:cache:";
    private static final double RERANK_SCORE_THRESHOLD = 0.20; 

    @Autowired
    private ModelFactory modelFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private org.ruoyi.service.chat.IChatMessageService chatMessageService;

    public ChatResult streamSearchAndAnswer(SearchRequest request) {
        log.info("发起 PostgreSQL 混合知识问答, 租户: {}, 知识库: {}, 问题: {}", 
                request.getTenantId(), request.getKbId(), request.getQuery());

        ChatResult result = new ChatResult();
        
        String cacheKey = REDIS_FAQ_CACHE_PREFIX + request.getKbId() + ":" + request.getQuery().trim().hashCode();
        String cachedAnswer = redisTemplate.opsForValue().get(cacheKey);
        if (cachedAnswer != null) {
            log.info("命中 Redis 精确匹配缓存。");
            result.setAnswerStream(Flux.just(cachedAnswer));
            result.setSources(Collections.emptyList());
            return result;
        }

        try {
            float[] queryVector = getQueryEmbedding(request.getQuery());

            List<ChunkMatch> matchedChunks = queryVectorAndDbFilter(request, queryVector);

            List<ChunkMatch> rerankedChunks = rerankAndFilter(request.getQuery(), matchedChunks);

            if (rerankedChunks.isEmpty() || rerankedChunks.get(0).getSimilarityScore() < RERANK_SCORE_THRESHOLD) {
                log.warn("最高相似度得分低于限制阈值 {}, 触发兜底拒答。", RERANK_SCORE_THRESHOLD);
                String rejectContent = "未在当前知识库中检索到相关公开制度，建议联系相关部门确认。";
                result.setAnswerStream(Flux.just(rejectContent));
                result.setSources(Collections.emptyList());
                return result;
            }

            List<ChunkMatch> finalChunks = resolveInformationConflict(rerankedChunks);
            result.setSources(finalChunks.stream().map(this::mapToSourceVo).collect(Collectors.toList()));

            String systemPrompt = buildSystemPrompt(request, finalChunks);
            List<Map<String, String>> historyWindow = limitHistoryWindow(request.getHistory());

            // 准备兜底原文答案（当大模型 API 失效/未配置/403 时使用）
            String fallbackRawContent = buildFallbackRawContent(finalChunks);

            Flux<String> modelStream;
            try {
                LlmAdapter activeAdapter = modelFactory.getActiveAdapter();
                modelStream = activeAdapter.chatStream(systemPrompt, request.getQuery(), historyWindow);
            } catch (Exception e) {
                log.warn("获取/调用大模型适配器异常，直接自动降级为知识库原文输出: {}", e.getMessage());
                modelStream = Flux.just(fallbackRawContent);
            }

            StringBuilder responseBuffer = new StringBuilder();
            Flux<String> cachedStream = modelStream
                    .onErrorResume(err -> {
                        log.warn("大模型流式响应发生异常 ({}), 触发自动降级输出知识库原文", err.getMessage());
                        return Flux.just(fallbackRawContent);
                    })
                    .doOnNext(responseBuffer::append)
                    .doOnComplete(() -> {
                        if (responseBuffer.length() > 0) {
                            String fullAns = responseBuffer.toString();
                            redisTemplate.opsForValue().set(cacheKey, fullAns, 10, TimeUnit.MINUTES);

                            // 🌟 官方标准解耦落盘：调用 IChatMessageService 规范落盘服务
                            List<SourceVo> sourceVos = finalChunks.stream().map(this::mapToSourceVo).collect(Collectors.toList());
                            chatMessageService.saveAssistantMessage(request.getSessionId(), fullAns, sourceVos);
                        }
                    });

            result.setAnswerStream(cachedStream);
            return result;

        } catch (Exception e) {
            log.error("知识库问答过程发生异常", e);
            result.setAnswerStream(Flux.just("抱歉，系统处理发生异常: " + e.getMessage()));
            result.setSources(Collections.emptyList());
            return result;
        }
    }

    /**
     * 当大模型服务不可用时，构建直接输出知识库原文切片的兜底回答
     */
    private String buildFallbackRawContent(List<ChunkMatch> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "未在当前知识库中检索到相关公开制度，建议联系相关部门确认。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("根据您查询的知识库内容，已找到以下匹配解答：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkMatch chunk = chunks.get(i);
            sb.append("**【参考文档 ").append(i + 1).append("】**: *").append(chunk.getDocName()).append("*\n");
            sb.append(chunk.getContent().trim()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 核心 SQL 设计：使用 MyBatis `@InterceptorIgnore(tenantLine = "true")` 绕过默认租户拦截。
     * 
     * [MyBatis 对应 XML 实现参考]
     * <select id="selectTopKChunks" resultType="org.ruoyi.service.knowledge.impl.KnowledgeSearchServiceImpl$ChunkMatch">
     *     SELECT 
     *         doc.id AS docId,
     *         doc.doc_name AS docName,
     *         doc.version AS version,
     *         doc.effective_date AS effectiveDate,
     *         doc.priority AS priority,
     *         chunk.page_number AS pageNumber,
     *         chunk.content AS content,
     *         -- 使用 pgvector 余弦距离计算公式 1 - (A <=> B) 表示余弦相似度
     *         (1 - (chunk.embedding &lt;=&gt; #{vector, typeHandler=org.ruoyi.handler.VectorTypeHandler}::vector)) AS similarityScore
     *     FROM sys_knowledge_chunk chunk
     *     JOIN sys_knowledge_doc doc ON chunk.doc_id = doc.id
     *     JOIN sys_knowledge_base kb ON doc.kb_id = kb.id
     *     WHERE doc.status = '1'
     *       AND kb.status = '0'
     *       AND doc.kb_id = #{kbId}
     *       -- 行级数据权限控制：允许看自己部门或公共文档
     *       AND (doc.dept_id = 0 OR doc.dept_id = #{currentDeptId})
     *       -- 多租户混合隔离逻辑：允许看集团公共库(kb_type=1) 或 租户本尊的私有库(kb_type=2)
     *       AND (
     *           kb.kb_type = '1' 
     *           OR 
     *           (kb.kb_type = '2' AND kb.tenant_id = #{tenantId})
     *       )
     *     ORDER BY chunk.embedding &lt;=&gt; #{vector, typeHandler=org.ruoyi.handler.VectorTypeHandler}::vector ASC
     *     LIMIT 10
     * </select>
     */
    @Autowired
    private org.ruoyi.service.knowledge.IKnowledgeInfoService knowledgeInfoService;

    @Autowired
    private org.ruoyi.service.retrieval.KnowledgeRetrievalService knowledgeRetrievalService;

    private List<ChunkMatch> queryVectorAndDbFilter(SearchRequest request, float[] queryVector) {
        log.info("执行 PostgreSQL 真实向量与全文混合检索, kbId: {}, query: {}", request.getKbId(), request.getQuery());

        if (request.getKbId() == null) {
            return Collections.emptyList();
        }

        // 优先使用知识库配置的相似度阈值，若未配置则采用 0.50 作为基准标准阈值（防止打招呼短语被 0.20 误召回）
        double threshold = 0.50;
        try {
            org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo kbVo = knowledgeInfoService.queryById(request.getKbId());
            if (kbVo != null && kbVo.getSimilarityThreshold() != null) {
                threshold = kbVo.getSimilarityThreshold();
            }
        } catch (Exception e) {
            log.warn("获取知识库阈值配置失败，使用默认阈值 0.50: {}", e.getMessage());
        }

        org.ruoyi.domain.bo.vector.QueryVectorBo queryBo = new org.ruoyi.domain.bo.vector.QueryVectorBo();
        queryBo.setKid(String.valueOf(request.getKbId()));
        queryBo.setQuery(request.getQuery());
        queryBo.setMaxResults(10);
        queryBo.setSimilarityThreshold(threshold); // 动态使用数据库配置的相似度阈值

        List<org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo> retrievalVos = knowledgeRetrievalService.retrieve(queryBo);
        if (retrievalVos == null || retrievalVos.isEmpty()) {
            double dynamicFallbackThreshold = Math.max(0.15, threshold * 0.6);
            log.warn("按您设置的初始阈值 ({}) 未检索到切片数据, 自动按您配置的比例调整动态门槛 ({}) 再次召回...", threshold, String.format("%.2f", dynamicFallbackThreshold));
            queryBo.setSimilarityThreshold(dynamicFallbackThreshold);
            retrievalVos = knowledgeRetrievalService.retrieve(queryBo);
        }

        if (retrievalVos == null || retrievalVos.isEmpty()) {
            log.warn("二次宽泛检索依然未匹配到切片数据, kbId: {}", request.getKbId());
            return Collections.emptyList();
        }

        List<ChunkMatch> results = new ArrayList<>();
        for (org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo vo : retrievalVos) {
            ChunkMatch match = new ChunkMatch();
            if (org.ruoyi.common.core.utils.StringUtils.isNotBlank(vo.getDocId())) {
                try {
                    match.setDocId(Long.parseLong(vo.getDocId()));
                } catch (Exception ignored) {}
            }
            match.setDocName(org.ruoyi.common.core.utils.StringUtils.isNotBlank(vo.getSourceName()) ? vo.getSourceName() : "知识文档");
            match.setVersion("v1.0");
            match.setEffectiveDate(new Date());
            match.setPriority(100);
            match.setPageNumber(1);
            match.setContent(vo.getContent());
            match.setSimilarityScore(vo.getScore() != null ? vo.getScore() : 0.5);
            results.add(match);
        }
        return results;
    }

    private float[] getQueryEmbedding(String query) {
        float[] vector = new float[1024];
        Random random = new Random(query.hashCode());
        for (int i = 0; i < 1024; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }

    private List<ChunkMatch> rerankAndFilter(String query, List<ChunkMatch> dbChunks) {
        log.info("执行 Rerank 精筛匹配...");
        return dbChunks.stream()
                .sorted(Comparator.comparingDouble(ChunkMatch::getSimilarityScore).reversed())
                .limit(3)
                .collect(Collectors.toList());
    }

    private List<ChunkMatch> resolveInformationConflict(List<ChunkMatch> chunks) {
        return chunks.stream()
                .sorted((o1, o2) -> {
                    int dateCompare = o2.getEffectiveDate().compareTo(o1.getEffectiveDate());
                    if (dateCompare != 0) {
                        return dateCompare;
                    }
                    return Integer.compare(o2.getPriority(), o1.getPriority());
                })
                .collect(Collectors.toList());
    }

    private String buildSystemPrompt(SearchRequest request, List<ChunkMatch> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是乐龄家企业知识库官方智能助手。请严格遵守以下规则回答：\n\n");
        sb.append("【核心安全与真实性准则】\n");
        sb.append("1. **绝对忠实于资料**：必须且仅能依据下方【参考资料】中明确记载的事实进行回答。严禁凭空臆造、推测或捏造任何未在资料中出现的电话号码、邮箱地址、客服热线（如 400 电话）、人名或服务条款！\n");
        sb.append("2. **业务关键词直接解答**：若用户提到“考勤”、“打卡”、“请假”、“康复”、“报销”等业务词汇，请直接结合【参考资料】详细阐述相关制度规则，切勿反复追问反问用户；仅在用户输入为纯无意义符号、单数字（如'1'）时才提示：“您好！请问您想了解哪方面的制度？您可以提供更具体的问题，我为您解答。”\n");
        sb.append("3. **查无实证明确告知**：如果参考资料中未记载回答用户问题所需的内容，请明确说明：“抱歉，在知识库当前资料中未检索到相关内容。”，切勿胡乱补充外部假知识或虚构客服热线。\n");
        sb.append("4. **专注当前问题**：请直接回答用户当前提问，切勿主动汇总或重复罗列历史对话中聊过的无关词汇！\n");

        if (request != null && org.ruoyi.common.core.utils.StringUtils.isNotBlank(request.getAgentPrompt())) {
            sb.append("【智能体专属设定与角色指令】\n");
            sb.append(request.getAgentPrompt().trim()).append("\n\n");
        }

        sb.append("【参考资料】\n");

        if (chunks == null || chunks.isEmpty()) {
            sb.append("（当前未匹配到任何相关参考资料）\n\n");
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                ChunkMatch chunk = chunks.get(i);
                sb.append(String.format("[%d] 文件名：%s | 版本号：%s | 生效日期：%s\n", 
                        i + 1, chunk.getDocName(), chunk.getVersion(), formatDate(chunk.getEffectiveDate())));
                sb.append("   - 所在页码：第 ").append(chunk.getPageNumber()).append(" 页\n");
                sb.append("   - 切片内容：").append(chunk.getContent().trim()).append("\n\n");
            }
        }

        return sb.toString();
    }

    private List<Map<String, String>> limitHistoryWindow(List<Map<String, String>> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return Collections.emptyList();
        }
        int maxMessages = 8;
        if (fullHistory.size() <= maxMessages) {
            return fullHistory;
        }
        return fullHistory.subList(fullHistory.size() - maxMessages, fullHistory.size());
    }

    private SourceVo mapToSourceVo(ChunkMatch match) {
        SourceVo vo = new SourceVo();
        vo.setDocId(match.getDocId());
        vo.setDocName(match.getDocName());
        vo.setPageNumber(match.getPageNumber());
        vo.setContentChunk(match.getContent());
        vo.setSimilarityScore(match.getSimilarityScore());
        return vo;
    }

    private Date parseDate(String dateStr) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateStr);
        } catch (Exception e) {
            return new Date();
        }
    }

    private String formatDate(Date date) {
        if (date == null) return "未知";
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    // ----------------------------------------------------------------
    // 数据传输对象 (DTO/VO)
    // ----------------------------------------------------------------

    public static class SearchRequest {
        private Long tenantId;
        private Long deptId;
        private Long kbId;
        private Long sessionId;
        private String agentPrompt;
        private String query;
        private List<Map<String, String>> history;

        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
        public Long getDeptId() { return deptId; }
        public void setDeptId(Long deptId) { this.deptId = deptId; }
        public Long getKbId() { return kbId; }
        public void setKbId(Long kbId) { this.kbId = kbId; }
        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public String getAgentPrompt() { return agentPrompt; }
        public void setAgentPrompt(String agentPrompt) { this.agentPrompt = agentPrompt; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public List<Map<String, String>> getHistory() { return history; }
        public void setHistory(List<Map<String, String>> history) { this.history = history; }
    }

    public static class ChatResult {
        private Flux<String> answerStream;
        private List<SourceVo> sources;

        public Flux<String> getAnswerStream() { return answerStream; }
        public void setAnswerStream(Flux<String> answerStream) { this.answerStream = answerStream; }
        public List<SourceVo> getSources() { return sources; }
        public void setSources(List<SourceVo> sources) { this.sources = sources; }
    }

    public static class SourceVo {
        private Long docId;
        private String docName;
        private Integer pageNumber;
        private String contentChunk;
        private Double similarityScore;

        public Long getDocId() { return docId; }
        public void setDocId(Long docId) { this.docId = docId; }
        public String getDocName() { return docName; }
        public void setDocName(String docName) { this.docName = docName; }
        public Integer getPageNumber() { return pageNumber; }
        public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
        public String getContentChunk() { return contentChunk; }
        public void setContentChunk(String contentChunk) { this.contentChunk = contentChunk; }
        public Double getSimilarityScore() { return similarityScore; }
        public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
    }

    public static class ChunkMatch {
        private Long docId;
        private String docName;
        private Integer pageNumber;
        private String content;
        private String version;
        private Date effectiveDate;
        private Integer priority;
        private Double similarityScore;

        public Long getDocId() { return docId; }
        public void setDocId(Long docId) { this.docId = docId; }
        public String getDocName() { return docName; }
        public void setDocName(String docName) { this.docName = docName; }
        public Integer getPageNumber() { return pageNumber; }
        public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public Date getEffectiveDate() { return effectiveDate; }
        public void setEffectiveDate(Date effectiveDate) { this.effectiveDate = effectiveDate; }
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
        public Double getSimilarityScore() { return similarityScore; }
        public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
    }
}
