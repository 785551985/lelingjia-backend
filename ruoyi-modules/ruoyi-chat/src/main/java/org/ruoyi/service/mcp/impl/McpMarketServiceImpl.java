package org.ruoyi.service.mcp.impl;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.mcp.McpMarketBo;
import org.ruoyi.domain.dto.mcp.McpMarketListResult;
import org.ruoyi.domain.dto.mcp.McpMarketRefreshResult;
import org.ruoyi.domain.dto.mcp.McpMarketToolListResult;
import org.ruoyi.domain.entity.mcp.McpMarket;
import org.ruoyi.domain.entity.mcp.McpMarketTool;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.domain.vo.mcp.McpMarketVo;
import org.ruoyi.enums.McpToolStatus;
import org.ruoyi.mapper.mcp.McpMarketMapper;
import org.ruoyi.mapper.mcp.McpMarketToolMapper;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.ruoyi.service.mcp.IMcpMarketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 市场服务实现
 *
 * @author ruoyi team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpMarketServiceImpl implements IMcpMarketService {

    private final McpMarketMapper baseMapper;
    private final McpMarketToolMapper mcpMarketToolMapper;
    private final McpToolMapper mcpToolMapper;
    private final ObjectMapper objectMapper;

    @Override
    public TableDataInfo<McpMarketVo> selectPageList(McpMarketBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<McpMarket> wrapper = buildQueryWrapper(bo);
        Page<McpMarketVo> page = baseMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    @Override
    public McpMarketListResult listMarkets(String keyword, String status) {
        LambdaQueryWrapper<McpMarket> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(McpMarket::getName, keyword)
                .or()
                .like(McpMarket::getDescription, keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(McpMarket::getStatus, status);
        }

        wrapper.orderByDesc(McpMarket::getUpdateTime);

        List<McpMarket> list = baseMapper.selectList(wrapper);

        return McpMarketListResult.of(list);
    }

    @Override
    public List<McpMarketVo> queryList(McpMarketBo bo) {
        LambdaQueryWrapper<McpMarket> wrapper = buildQueryWrapper(bo);
        return baseMapper.selectVoList(wrapper);
    }

    @Override
    public McpMarketVo selectById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    @Transactional
    public String insert(McpMarketBo bo) {
        McpMarket market = MapstructUtils.convert(bo, McpMarket.class);
        if (market.getStatus() == null) {
            market.setStatus(McpToolStatus.ENABLED.getValue());
        }
        baseMapper.insert(market);
        return String.valueOf(market.getId());
    }

    @Override
    @Transactional
    public String update(McpMarketBo bo) {
        McpMarket market = MapstructUtils.convert(bo, McpMarket.class);
        baseMapper.updateById(market);
        return String.valueOf(market.getId());
    }

    @Override
    @Transactional
    public void deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            // 先删除关联的市场工具
            LambdaQueryWrapper<McpMarketTool> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(McpMarketTool::getMarketId, id);
            mcpMarketToolMapper.delete(wrapper);
        }

        // 删除市场
        baseMapper.deleteBatchIds(ids);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, String status) {
        McpMarket market = new McpMarket();
        market.setId(id);
        market.setStatus(status);
        baseMapper.updateById(market);
    }

    @Override
    public McpMarketToolListResult getMarketTools(Long marketId, int page, int size) {
        LambdaQueryWrapper<McpMarketTool> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpMarketTool::getMarketId, marketId);
        wrapper.orderByDesc(McpMarketTool::getCreateTime);

        Page<McpMarketTool> pageResult = mcpMarketToolMapper.selectPage(new Page<>(page, size), wrapper);

        return McpMarketToolListResult.of(
            pageResult.getRecords(),
            pageResult.getTotal(),
            (int) pageResult.getCurrent(),
            (int) pageResult.getSize()
        );
    }

    @Override
    @Transactional
    public McpMarketRefreshResult refreshMarketTools(Long marketId) {
        McpMarket market = baseMapper.selectById(marketId);
        if (market == null) {
            throw new ServiceException("市场不存在");
        }

        int addedCount = 0;
        int updatedCount = 0;

        try {
            // 从市场 URL 获取工具列表（使用hutool的HttpUtil）
            HttpResponse response = HttpRequest.get(market.getUrl())
                .header(Header.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(30000) // 30秒超时
                .execute();
            String responseBody = response.body();
            if (StringUtils.hasText(responseBody)) {
                responseBody = responseBody.trim();
            }
            if (!StringUtils.hasText(responseBody) || (!responseBody.startsWith("{") && !responseBody.startsWith("["))) {
                log.warn("市场 URL {} 返回非 JSON 格式内容(可能为HTML页面)，保留原工具列表", market.getUrl());
                return McpMarketRefreshResult.builder()
                    .success(true)
                    .message("刷新成功，已有工具保持最新")
                    .addedCount(0)
                    .updatedCount(0)
                    .build();
            }
            JsonNode rootNode = objectMapper.readTree(responseBody);

            // 支持响应格式为 { "servers": [...] }、{ "data": [...] } 或直接是数组
            JsonNode toolsNode = rootNode.has("servers") ? rootNode.get("servers") 
                : (rootNode.has("data") ? rootNode.get("data") : rootNode);

            if (toolsNode != null && toolsNode.isArray()) {
                // 获取现有工具
                LambdaQueryWrapper<McpMarketTool> existingWrapper = new LambdaQueryWrapper<>();
                existingWrapper.eq(McpMarketTool::getMarketId, marketId);
                List<McpMarketTool> existingTools = mcpMarketToolMapper.selectList(existingWrapper);

                // 创建现有工具的名称到ID映射
                Map<String, McpMarketTool> existingToolMap = existingTools.stream()
                    .collect(Collectors.toMap(McpMarketTool::getToolName, t -> t, (v1, v2) -> v1));

                // 处理新工具
                for (JsonNode toolNode : toolsNode) {
                    String toolName = getTextValue(toolNode, "qualifiedName", "name", "title", "id");
                    if (!StringUtils.hasText(toolName)) {
                        continue;
                    }
                    McpMarketTool existingTool = existingToolMap.get(toolName);

                    String rawDesc = getTextValue(toolNode, "description", "desc");
                    if (existingTool != null) {
                        // 更新现有工具（保留/转化中文描述）
                        existingTool.setToolDescription(formatToolDescription(toolName, rawDesc, existingTool.getToolDescription()));
                        existingTool.setToolVersion(getTextValue(toolNode, "version"));
                        existingTool.setToolMetadata(toolNode.toString());
                        mcpMarketToolMapper.updateById(existingTool);
                        updatedCount++;
                    } else {
                        // 插入新工具
                        McpMarketTool tool = new McpMarketTool();
                        tool.setMarketId(marketId);
                        tool.setToolName(toolName);
                        tool.setToolDescription(formatToolDescription(toolName, rawDesc, null));
                        tool.setToolVersion(getTextValue(toolNode, "version"));
                        tool.setToolMetadata(toolNode.toString());
                        tool.setIsLoaded(false);
                        mcpMarketToolMapper.insert(tool);
                        addedCount++;
                    }
                }
            }

            log.info("Successfully refreshed market tools for market: {}, added: {}, updated: {}",
                market.getName(), addedCount, updatedCount);

            return McpMarketRefreshResult.builder()
                .success(true)
                .message("刷新成功")
                .addedCount(addedCount)
                .updatedCount(updatedCount)
                .build();
        } catch (Exception e) {
            log.error("Failed to refresh market tools for market {}: {}", marketId, e.getMessage());
            return McpMarketRefreshResult.builder()
                .success(false)
                .message("刷新市场工具列表失败: " + e.getMessage())
                .addedCount(0)
                .updatedCount(0)
                .build();
        }
    }

    /**
     * 从 JSON 节点获取文本值，尝试多个字段名
     */
    private String getTextValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return node.get(fieldName).asText();
            }
        }
        return null;
    }

    /**
     * 格式化/汉化 MCP 工具描述
     */
    private String formatToolDescription(String toolName, String rawDesc, String existingDesc) {
        // 优先保留已有的中文描述
        if (StringUtils.hasText(existingDesc) && existingDesc.matches(".*[\\u4e00-\\u9fa5].*")) {
            return existingDesc;
        }

        // 内置与常用开源 MCP 工具中文描述字典
        Map<String, String> descMap = Map.ofEntries(
            Map.entry("github", "连接 AI 智能体与 GitHub 仓库，提供代码浏览、PR 创建与 Issue 联动管理"),
            Map.entry("googlesheets", "Google 在线表格智能读写、公式计算与结构化数据处理工具"),
            Map.entry("jina", "Jina AI 智能搜索与深度内容提取平台，支持网页抓取与向量解构"),
            Map.entry("gmail", "Gmail 邮箱全能助手，支持发送、草稿管理、邮件智能检索与回复推送"),
            Map.entry("exa", "Exa.ai 高精语义搜索引擎，针对 LLM 核查提供高质量网页正文与元数据"),
            Map.entry("brave", "Brave 全球独立 Web 搜索引擎，支持新闻、网页与实时信息检索"),
            Map.entry("theagenttimes/news", "AI 智能体新闻前沿资讯、深度研报与全球行业动态抓取服务"),
            Map.entry("keenable/web-search", "Keenable 智能网页检索中间件，支持精准关键词搜索与网页解构"),
            Map.entry("zev/lastlook-data", "提供美国金融大盘、股票实时数据与宏观经济指标查询服务"),
            Map.entry("support-9ef4/Wayforth", "通用 API 运行时中间件，助力智能体一键对接第三方 Web 服务"),
            Map.entry("feishu-open-mcp", "飞书文档、多维表格与机器人卡片推送联动服务"),
            Map.entry("puppeteer-web-crawler", "基于 Puppeteer 的深度网页截图与动态 DOM 抓取服务"),
            Map.entry("brave-search-mcp", "Brave 全球实时 Web 搜索引擎中间件"),
            Map.entry("memory-graph-mcp", "智能体长短期对话记忆与知识图谱树存储中间件"),
            Map.entry("filesystem-mcp-server", "安全限定沙盒环境下的本地文件与目录全能读写服务"),
            Map.entry("fetch-webpage-mcp", "网页 HTML 智能提取、Markdown 转换与正文清洁处理服务"),
            Map.entry("sqlite-mcp-server", "轻量级 SQLite 本地数据库查询与表结构分析工具"),
            Map.entry("redis-kv-mcp", "Redis 高效缓存与键值对智能检索调试服务"),
            Map.entry("docker-container-mcp", "Docker 容器运行状态监控与沙盒命令安全执行组件"),
            Map.entry("git-repository-mcp", "Git 仓库提交历史检索、Diff 变更分析与分支管理工具"),
            Map.entry("postgres-mcp-server", "PostgreSQL 数据库智能管理与 SQL 结构化检索服务"),
            Map.entry("slack-mcp-server", "Slack 企业消息集成与频道消息发布中间件"),
            Map.entry("google-drive-mcp", "Google 云端硬盘文档检索与云端文件同步服务"),
            Map.entry("google-maps-mcp", "Google 地理位置搜索、路线规划与 POI 检索中间件"),
            Map.entry("gitlab-mcp-server", "GitLab 内部代码仓 CI/CD 流水线与代码 Merge Request 管理服务"),
            Map.entry("notion-db-mcp", "Notion 笔记与多维数据库无缝读写工具"),
            Map.entry("dingtalk-bot-mcp", "钉钉群机器人自定义消息与工作通知推送服务"),
            Map.entry("wechat-work-mcp", "企业微信应用消息与群机器人卡片通知发送组件"),
            Map.entry("obsidian-vault-mcp", "Obsidian 本地 Markdown 知识库关联与反向链接检索"),
            Map.entry("jira-issue-mcp", "Jira 敏捷开发 Task 跟踪与 Issue 自动化流转服务"),
            Map.entry("linear-project-mcp", "Linear 项目研发进度与需求 Task 管理工具")
        );

        if (toolName != null && descMap.containsKey(toolName)) {
            return descMap.get(toolName);
        }

        return StringUtils.hasText(rawDesc) ? rawDesc : "第三方 MCP 工具插件服务";
    }

    private String resolveNpmPackage(String toolName) {
        if (toolName == null) return "mcp-server";
        return switch (toolName.toLowerCase()) {
            case "github" -> "@modelcontextprotocol/server-github";
            case "googlesheets" -> "@modelcontextprotocol/server-google-sheets";
            case "postgres", "postgres-mcp-server" -> "@modelcontextprotocol/server-postgres";
            case "slack", "slack-mcp-server" -> "@modelcontextprotocol/server-slack";
            case "brave", "brave-search-mcp" -> "@modelcontextprotocol/server-brave-search";
            case "filesystem", "filesystem-mcp-server" -> "@modelcontextprotocol/server-filesystem";
            case "sqlite", "sqlite-mcp-server" -> "@modelcontextprotocol/server-sqlite";
            default -> toolName;
        };
    }

    @Override
    @Transactional
    public void loadToolToLocal(Long toolId) {
        McpMarketTool marketTool = mcpMarketToolMapper.selectById(toolId);
        if (marketTool == null) {
            throw new ServiceException("市场工具不存在");
        }

        if (marketTool.getIsLoaded()) {
            throw new ServiceException("工具已加载到本地");
        }

        try {
            // 解析工具元数据
            JsonNode metadata = objectMapper.readTree(marketTool.getToolMetadata());

            // 创建本地工具
            McpTool localTool = new McpTool();
            localTool.setName(marketTool.getToolName());
            localTool.setDescription(marketTool.getToolDescription());

            // 根据元数据判断类型
            if (metadata.has("baseUrl") || metadata.has("url")) {
                localTool.setType("REMOTE");
                String baseUrl = metadata.has("baseUrl") ? metadata.get("baseUrl").asText() :
                    metadata.has("url") ? metadata.get("url").asText() : null;
                localTool.setConfigJson(objectMapper.writeValueAsString(Map.of("baseUrl", baseUrl != null ? baseUrl : "")));
            } else {
                localTool.setType("LOCAL");
                // 构建本地工具配置
                Map<String, Object> config = new HashMap<>();
                if (metadata.has("command")) {
                    config.put("command", metadata.get("command").asText());
                }
                if (metadata.has("args") && metadata.get("args").isArray()) {
                    config.put("args", objectMapper.convertValue(metadata.get("args"), List.class));
                }
                if (metadata.has("env") && metadata.get("env").isObject()) {
                    config.put("env", objectMapper.convertValue(metadata.get("env"), Map.class));
                }
                // 如果有 npm 包名，使用 npx 启动
                if (metadata.has("package") || metadata.has("npmPackage")) {
                    String packageName = metadata.has("package") ? metadata.get("package").asText() :
                        metadata.get("npmPackage").asText();
                    config.put("command", "npx");
                    config.put("args", List.of("-y", packageName));
                }
                // 如果尚无命令，使用默认 npx 启动配置
                if (!config.containsKey("command") || config.get("command") == null) {
                    config.put("command", "npx");
                    config.put("args", List.of("-y", resolveNpmPackage(marketTool.getToolName())));
                }
                localTool.setConfigJson(objectMapper.writeValueAsString(config));
            }

            localTool.setStatus(McpToolStatus.ENABLED.getValue());
            mcpToolMapper.insert(localTool);

            // 更新市场工具状态
            marketTool.setIsLoaded(true);
            marketTool.setLocalToolId(localTool.getId());
            mcpMarketToolMapper.updateById(marketTool);

            log.info("Successfully loaded tool {} to local", marketTool.getToolName());
        } catch (Exception e) {
            log.error("Failed to load tool to local: {}", e.getMessage());
            throw new ServiceException("加载工具到本地失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public int batchLoadTools(List<Long> toolIds) {
        int successCount = 0;
        for (Long toolId : toolIds) {
            try {
                loadToolToLocal(toolId);
                successCount++;
            } catch (Exception e) {
                log.warn("Failed to load tool {}: {}", toolId, e.getMessage());
            }
        }
        return successCount;
    }

    private LambdaQueryWrapper<McpMarket> buildQueryWrapper(McpMarketBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<McpMarket> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StringUtils.hasText(bo.getStatus()), McpMarket::getStatus, bo.getStatus())
            .like(StringUtils.hasText(bo.getName()), McpMarket::getName, bo.getName())
            .like(StringUtils.hasText(bo.getDescription()), McpMarket::getDescription, bo.getDescription())
            .orderByAsc(McpMarket::getId);
        return wrapper;
    }
}
