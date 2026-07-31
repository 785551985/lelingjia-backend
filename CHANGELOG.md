# 变更日志 (CHANGELOG)

本文件记录集团化多租户企业级知识库系统开发过程中的所有代码与配置改动。

## [v2.5.0] - 2026-07-31

### 多租户资源克隆与类型修复

* **多租户 AI 资源全量克隆与类型兼容** (`chat_provider` / `chat_model` / `mcp_tool_info`)
  * 完成将平台管理 (`000000`) 下的 MCP 工具、LLM 厂商与 AI 大模型配置全量同步克隆至新租户 (`019466`)。
  * 修复数据库中 `create_by` / `update_by` 列包含空字符串 `''` 导致 MyBatis long 类型反序列化抛出 `PSQLException: Bad value for type long` 的问题，批量修补为默认长整型值 `1`。

### 知识库范本与数据权限根治

* **范本切片数据权限继承与预览恢复** (`KnowledgeAttachServiceImpl.java` / `20260731_fix_knowledge_fragment_permissions.sql`)
  * **后端数据权限字段补全**：修复 `KnowledgeAttachServiceImpl.initTemplate()` 创建范本切片时未继承 `createDept` 与 `createBy` 的 Bug，避免范本文本切片被若依框架 `DataPermission` 数据权限过滤器误杀截断导致预览返回空数据。
  * **数据库增量修复**：生成 `sql/update/20260731_fix_knowledge_fragment_permissions.sql`，修正现有存量范本切片的部门与创建人权限绑定，恢复预设范本的文字提取与在线预览全链路。

### UI 布局与 CI/CD 自动化构建升级

* **厂商管理列表操作列布局优化** (`apps/web-antd/src/views/chat/provider/data.ts`)
  * 调整厂商管理列表操作列宽度由 `180px` 扩展为 `240px`，解决【申请 API】、【编辑】、【删除】按钮挤压溢出与右侧固定列遮挡瑕疵。
* **GitHub Actions 增量 SQL 自动构建部署集成** (`.github/workflows/deploy.yml`)
  * 在 GitHub Actions 部署工作流中添加远程源码 `git pull origin master` 同步步骤，实现 GitHub 网页端点击触发构建时，自动拉取并执行 `sql/update/` 目录下的数据库增量 SQL 脚本。

---

## [v2.4.0] - 2026-07-30

### 权限隔离与安全防护

* **智能体五维管控字段扩展** (`agent_info` 表 / `Agent.java` / `AgentBo.java` / `AgentVo.java`)
  * 新增 `visible_scope`（可见范围）、`dept_ids`（部门白名单）、`role_ids`（角色白名单）、`is_public`（公开形态）、`scope_level`（作用域级别）五大核心字段。
  * 生成带 `IF NOT EXISTS` 防重复保护的增量 SQL 脚本 `sql/update/20260730_agent_permissions_and_scope.sql`，并同步更新全量建表文件 `ruoyi-ai-pg.sql`。
* **后端切片鉴权** (`AgentServiceImpl.java`)
  * `queryEnabledOptions()` 方法融入用户部门/角色切片过滤逻辑，非授权员工在客户端接口层面彻底隐藏敏感智能体。
* **管理后台作用域表单重构** (`data.tsx` / `agent-drawer.vue`)
  * 新增【是否公开】RadioGroup（对内公开 / 对外公开 / 仅自己可见）。
  * 新增【作用域级别】Select（集团级 / 机构级 / 部门级）。
  * 新增【可见机构/部门】ApiTreeSelect，当 `scopeLevel === 2 || 3` 时动态展开，支持多选指定分支机构或部门。
  * 全面清除表单与表格中的 Emoji 表情符号，恢复干净的企业级商务视觉规范。

### 界面与交互修复

* **关联知识库名称展示修复** (`agent-drawer.vue`)
  * 重写多层 DFS 递归树搜索算法 `findKeyInTree`，配合 `nextTick()` 等待 TreeSelect 挂载完成后再调用 `setValues`，彻底修复编辑弹窗中知识库名称渲染为 Snowflake 数字 ID 的问题。
* **智能体动态图标与色彩绑定** (`AgentSelect/index.vue`)
  * 新增 `getAgentIconInfo()` 映射函数，为 `AuditOutlined`（红）、`ShoppingOutlined`（紫）、`SolutionOutlined`（黄）、`CustomerServiceOutlined`（绿）、`RobotOutlined`（蓝）等内置图标配置专属色彩，并支持渲染自定义图片 URL 头像。
* **胶囊按钮像素级等高对齐** (`AgentSelect/index.vue` / `KnowledgeSelect/index.vue`)
  * 统一【切换智能体】与【全库智能检索】两个胶囊按钮的内边距（`px-10px py-5px`）、图标字号（`13px`）、文字字号（`12px`）与圆角（`8px`），实现完全精细化等高一致。

### 知识库选择器重构

* **智能体关联自动感知** (`KnowledgeSelect/index.vue`)
  * 新增 `watch` 监听 `agentStore.currentAgentInfo`，切换智能体时自动同步选中该智能体绑定的专属知识库。
  * 下拉选项仅保留【全库智能检索】与【当前智能体专属知识库】两项，标题超长时截断至 `max-w-120px`，消灭冗长全量平铺。

### 服务器自动化部署

* **Shell 部署脚本升级**：`git pull` 后自动扫描并执行 `sql/update/*.sql` 增量脚本，实现代码、数据库与前端静态资源的一键平滑部署。
* **Bug 修复**：修复 `LoginDialog/index.vue` 中 `toggleLoginMode` 未使用变量 ESLint 报错；在 `user.ts` 中补充 `fetchUserInfo` action 并在 `api/auth/index.ts` 中导出 `getUserInfo` 接口。

---

## [Unreleased] - 2026-07-24

### 架构与存储改造

* **MinIO 独立存储桶与组织架构隔离 (dazhao-kb)**
  * **独立桶隔离**：新增私有存储桶 `dazhao-kb`（带版本控制），将企业知识库附件从通用系统存储（`ruoyi` 桶）中隔离出来，增强文件存储安全性。
  * **组织架构多租户路径前缀**：知识库文件上传路径规范为 `{tenant_id}/{dept_id}/{knowledge_id}/日期/uuid.ext`，天然支持集团-机构-部门层级隔离与未来 SaaS 商业化拓展。
  * **存储与权限解耦**：MinIO 路径仅记录文件物理归属，实际访问权限由 PostgreSQL 数据库中的 `scopeLevel`（集团/机构/部门/个人）在业务层统一管理。
  * **同名文件版本覆盖**：在 `KnowledgeAttachServiceImpl.upload()` 中增加同名文件版本检测逻辑，检测到同名且内容变更的文件上传时，自动清理旧版本的 MinIO 文件、向量数据库切片及数据库记录，避免垃圾文件堆积与知识库冲突。
  * **后端 OSS 接口扩展**：`OssService` 与 `ISysOssService` 新增 `uploadToStore(file, configKey, prefix)` 扩展接口，支持业务代码显式指定存储桶配置与路径前缀。
  * **主主体组织名称与品牌标识更名**：将数据库 `sys_tenant`（租户表）与 `sys_dept`（部门表）中的默认顶级主体名称统一更名为“**乐龄家大健康科技 / 乐龄家大健康科技有限公司**”；同步将前端聊天系统 (`ruoyi-web-base`) 页面标题、侧边栏 Logo 文本及后台管理系统 (`ruoyi-admin`) 品牌名称全量更新为“**乐龄家大健康 AI 知识库**”与“**乐龄家 AI 管理后台**”。

### 智能体与对话容错修复

* **MCP 控制层列表接口返回值包装与防崩** (`McpToolController.java` / `index.ts`)
  * **后端接口规范化**：将 `McpToolController.listAll()` 接口返回值由原始 DTO 修改为标准的 `R<McpToolListResult>` 包装（`R.ok(...)`），解决前端 Axios 拦截器因缺失 `code: 200` 状态码而在打开智能体【新增】弹窗时误报 ❌ 红色网络错误的问题。
  * **前端 API 防用容错**：在 `agentMcpToolOptions` 及 `agentKnowledgeTreeOptions` 中加入 `try-catch` 及多类型解包防护。
* **知识库【机构级/部门级】作用域节点精确定位与展示优化** (`KnowledgeAddModal.vue` / `KnowledgeConfig.vue` / `index.vue`)
  * **基准组织深度 (`depth`) 严格切片算法**：重构了树选择器的 `computedDeptTreeData` 算法。在选择 **【机构级】** 时，物理切除 `depth >= 3` 的所有下属部门节点，彻底避免机构下拉框中混入具体部门；在选择 **【部门级】** 时，将 `depth <= 2` 的机构父节点设为灰色不可勾选标题，强制只能选择底层具体部门。
  * **个人私有级标签优化**：修复了知识库列表中归属主体列对 `scopeLevel = 4`（个人私有级）直接显示数字用户 ID（如 `1`）的问题，替换为优雅的紫色 `个人私有 (创建人)` 智能标签。
* **前端智能体选择器显示名称修复** (`AgentSelect/index.vue`)
  * 修复了聊天对话框顶部/输入框标签选中智能体时错误优先显示长描述（`agentDescribe`）而非智能体名称（`agentName`）的 Bug，使选择器胶囊标签和下拉菜单中均优先显示清晰的智能体名称。
* **智能体人设与系统提示词（SystemMessage）结构化直连修复** (`ChatServiceFacade.java`)
  * 修复了智能体对话时将人设 Prompt 作为普通字符串拼接给 UserMessage 导致 DeepSeek/Qwen 等大模型误将其作为检索素材分析、输出“根据我的调查，这是一个关于...项目”等第三人称尴尬答复的问题。重构为标准的 `List<ChatMessage>`（`SystemMessage` + `UserMessage`），使大模型 100% 融入智能体角色身份，输出第一人称专业回答。
  * **普通问答直连与 Supervisor 元元解析剥离**：精细化 `isSpecialToolRequested` 意图判别词。避免将“搜索词”、“搜索技巧”等普通检索词误判为图表/工具指令而触发 `EchartsAgent` 画假图表。当用户进行普通问答、简单问候或知识库制度查询（未明确触发生成图表、SQL查询或技能工具）时，直接使用包含 `SystemMessage` 的大模型全量对话接口，实现秒级响应与干练追问。
  * **未上传知识库空检索防幻觉强效守卫**：在 `ChatServiceFacade` 中增加了空来源强效防幻觉守卫逻辑。当智能体未绑定知识库或检索到的 `sourcesList` 为空时，动态注入拦截指令，硬性禁止大模型凭借自身预训练参数虚构“9:00-18:00上班、迟到扣全勤”等假的考勤细则，强制提示“抱歉，当前知识库中未检索到相关制度说明...”。
  * **智能体【关联知识库】留空自动解锁全量可见权限库**：修改了 `ChatServiceFacade.collectKnowledgeIds` 逻辑。当智能体配置弹窗中的【关联知识库】留空时，不再误判为放弃检索，而是自动按当前登录员工的数据权限（集团级、机构级、部门级、个人级）查询并覆盖所有可见知识库，实现“留空即全量开放”的预期业务规则。
* **聊天消息存库 Token 统计自动计算补全** (`ChatMessageServiceImpl.java`)
* **用户会话删除与数据库聊天消息级联物理清理** (`ChatSessionServiceImpl.java`)
* **AI 聊天参考来源卡片与附件下载/段落预览全链路支持** (`SourceReferenceCard.vue` / `ChatServiceFacade.java` / `SseMessageUtils.java`)
  * **后台管理系统品牌标识全量定制升级为“乐龄家知识库” (`ruoyi-admin/apps/web-antd/.env` / `preferences.ts` / `config.ts`)**：将后台管理系统全局 Logo、浏览器标签页 Title、系统 Header 菜单及加载屏的应用标题统一下发更新为 `乐龄家知识库`；并在 `preferences.ts` 中硬编码覆盖，同步将 `VITE_APP_NAMESPACE` 升级更新为 `lelingjia-kb-antd`，自动强制作废浏览器 LocalStorage 中缓存的中间态 `乐龄家 AI 管理后台` 偏好，实现免清除缓存即刻全量呈现“乐龄家知识库”！
  * **纯粹企业知识库与本产品使用交互指引全量融入 (`ChatServiceFacade.java`)**：根据产品定位重构全盘问答策略。明确 AI 助手涵盖【本企业知识库内容解答】与【本 AI 知识库系统本身的使用操作与交互指导】两大核心职能；当用户询问“怎么使用”、“如何提问”、“怎么预览原件”、“如何申请无水印”时，自动清晰分条解答本系统的各项操作流程，打造闭环的产品专属助手体验。
  * **后端 RAG 智能体 System Prompt 澄清引导机制升级 (`ChatServiceFacade.java`)**：重构了 RAG Prompt 提示词规范。当用户输入如“康复”、“护理”、“流程”等宽泛或未明确具体意图的简短关键词时，禁止模型直接一次性倾倒堆砌所有细节；强制模型先归纳概括知识库中关于该关键词的 2~3 个核心关注方向，并主动有礼貌地反问引导用户确认具体要了解的细分领域（如 1. SOP 卖点、2. 流量分析、3. 收费标准），实现智能化人机交互。
  * **后端 RAG 来源全量文本组装 (`ChatServiceFacade.java`)**：补全 `Wrappers`、`JSONUtil` 与 `Collectors` 导包；移除了先前构建 `KnowledgeSourceDto` 时将文本截断为 150 字的限制，新增根据 `docId` 自动从 `knowledge_fragment` 表拉取并按顺序拼接该文档下所有章节、工作表与全量表格文本的功能，使得在受控预览弹窗中能够 100% 完整展示原文档的所有真实内容。
  * **持久化卡片记录**：在向数据库保存 `chat_message` 助手回答时，将结构化 sources 元数据通过 `<sources>...</sources>` 节点附在消息尾部，确保用户刷新页面或切换历史会话时，参考来源卡片无缝恢复。
  * **后端回答脱敏与服务器物理路径屏蔽 (`ChatServiceFacade.java`)**：增加了 `sanitizeOutputText` 正则脱敏过滤机制。在 SSE 推送及数据库存库前，强行将 AI 答复中可能包含的服务器本地物理盘符与目录路径（如 `D:\czl\企业知识库...`）替换脱敏，彻底杜绝服务器敏感物理路径泄露给前端员工。
  * **后端在线受控 inline 预览接口与 MIME 类型修复 (`SysOssController.java` / `SysOssServiceImpl.java` / `SourceReferenceCard.vue`)**：修正了 PDF/图片预览时的 `Content-Type` 头输出规则（对 PDF 严格输出 `application/pdf`，禁止带有额外 `; charset=UTF-8` 后缀），解决了 Chrome PDF 插件因 MIME 类型后缀异常拒绝在 HTML `iframe` 中渲染并降级为触发物理下载的底层缺陷；并在前端 `SourceReferenceCard.vue` 的 `getFileUrl` 中增加了历史消息下载路径到预览路径的自动兼容替换，确保新老会话点击时全部无缝在受控弹窗中内嵌展示！
  * **卡片渲染时机修正 (`chatWithId/index.vue`)**：修正了卡片渲染条件为 `v-if="item.content && item.sources && item.sources.length"`，彻底解决了之前“没有回答文本就直接单独跳出卡片”的尴尬体验，确保卡片永远跟随在文本回答下方优雅出现。
  * **敏感资质受控预览多格式真实原件网页流式渲染引擎 (`WatermarkPreviewModal.vue`)**：全面实现了原件渲染架构升级：
    * **Excel 电子表格 (`.xlsx` / `.xls`)**：自动调取解析引擎在网页端流式渲染**真实 Excel 原件表格**（带单元格网格线、多 Sheet 工作表标签选卡），原汁原味重现 Excel 原件大局全貌；
    * **Word 文档 (`.docx`)**：自动调取 `docx-preview` 渲染引擎在网页端解析渲染**真实 Word 原件文档**（保留原排版、排字与列表结构）；
    * **首次点击空白问题终极修复**：新增 `waitForRef` 轮询监视器与 `Promise.all` 依赖并发加载，并在 `<el-dialog>` 上绑定 `@opened="loadFileContent"` 生命周期钩子。彻底攻克了首次点击时因 CDN 脚本加载延迟与弹窗动画导致 `docxContainerRef` 挂载点尚未就绪而渲染空白的竞态 Bug！
    * **PDF / HTML**：内嵌无工具栏网页渲染；
    * **图片与纯文本**：高清流式呈现。
    所有格式预览统一叠加 3x4 飞书/钉钉同款错位动态防伪水印，彻底攻克了此前 Excel / Word 文件落入降级段落渲染分支的体验缺陷！
  * 在 `ChatSessionServiceImpl.deleteWithValidByIds` 中补全级联清理逻辑。当用户在前台或后台删除某个历史会话时，系统将同步从数据库中物理删除该会话下的所有聊天消息记录（`chat_message`），防止废弃聊天记录不断堆积占用存储空间，实现内存与数据库记忆双重彻底清空。

* **SSE 连接并发冲突与顶替销毁修复** (`SseEmitterManager.java`)
  * 重构了 `SseEmitterManager.connect` 存储 Key 逻辑，使用 `token + "_" + nanoTime` 唯一识别连接，解决了前台全局 `GET /resource/sse` 与发送对话 `POST /chat/send` 共享同一 Token 导致的相互顶替、旧连接被强制 `complete()` 抛弃，进而导致智能体回答完成后前端页面无法收到流式响应、始终卡在加载中图标的严重并发 Bug。

* **智能体关联知识库【集团-机构-部门-个人】4 层极简结构与批量勾选** (`index.ts` / `data.tsx`)
  * **4 层极简树状分组**：将【关联知识库】下拉菜单彻底重构为 **集团级、分支机构级、部门级、个人私有级** 4 层干净透明的扁平分组结构。
  * **一键分类批量勾选与留空全权限提示**：支持勾选“全集团共享知识库”、“分支机构级知识库”、“部门级知识库”、“个人私有知识库”标题复选框，一键批量全选该分类下的所有底层知识库；同步更新【关联知识库】输入框占位符（`请选择关联的知识库（留空默认自动检索当前员工全量权限知识库）`）并增加 `helpMessage` 提示说明，让管理员清晰明了“留空即全量开放权限库”的规则。
* **大模型缺失与智能体默认模型自动兜底** (`ChatServiceFacade.java`)
  * 当前端在未选定模型或模型参数为空 (`""`) 时发送对话，后端自动查找系统中启用的首个通用对话大模型（如智谱 `glm-4.7-flash`）自动打底接管，彻底解决 `模型不存在: ""` 引起的 SSE 握手中断问题。
* **磁盘 Skills 技能目录路径多环境探测与全局容错** (`SkillsPathResolver.java` / `ChatServiceFacade.java`)
  * 优化了 `SkillsPathResolver.java` 的探测优先级，优先精准匹配 IntelliJ IDEA 及子模块运行环境下的 `src/main/resources/skills`，彻底消除了路径重叠报错（`ruoyi-admin/ruoyi-admin/...`）。
  * 在 `buildShellSkills` 及 `AgentServiceImpl` 引入全局异常拦截与防空防护，未配置技能或磁盘无技能文件时优雅忽略，保障核心流式对话永不中断。

## [Unreleased] - 2026-07-23

### 修复

* **后端 RAG 检索链路打通（PostgreSQL 本地向量库）**
  * 补齐了 `PgVectorStoreStrategy.java` 原框架遗留的空桩代码（`return Collections.emptyList()`），实现了基于 MyBatis-Plus `LambdaQueryWrapper` 的本地 PG 关键词模糊召回逻辑，彻底解决检索始终返回 0 条的根本问题。
  * 将 `KnowledgeFragmentMapper.java` 中关键词检索 SQL 从 MySQL 专属的 `MATCH...AGAINST` 语法迁移为 PostgreSQL 兼容的 `'%' || #{query} || '%'` ILIKE 匹配，修复 JDBC 预编译时 `无法确定参数数据类型` 异常。
  * 修正 `KnowledgeRetrievalServiceImpl.java` 缓存逻辑：禁止将空检索结果写入 5 分钟 JVM 内存缓存，彻底消除首次检索失败后缓存锁死导致的持续返回 0 条问题。
  * `KnowledgeFragmentServiceImpl.java` 检索结果 `sourceName`（来源文档名）回填：检索完成后自动关联 `knowledge_attach` 表，将文件原始名称填入每条检索结果的 `sourceName` 字段，解决前端"来源文档"列空白问题。

* **前端传参精准度修复**
  * `RetrievalTest.vue` 中对 `knowledgeId` 增加 `String()` 强制字符串转换，防止 JavaScript 浮点数精度丢失将 19 位雪花 ID 末尾抹零（如 `...161` → `...200`），导致后端查不到知识库记录。

* **模型配置管理页面增强**
  * `model-modal.vue` / `model/index.vue`：大模型配置弹窗新增厂商（Provider）分类选择、API Host 前缀自动填充、`Date` 类型序列化兼容修复（`yyyy-MM-dd HH:mm:ss` → ISO 8601）。
  * `KnowledgeConfig.vue`：知识库高级配置面板（向量模型、重排模型、混合检索、相似度阈值等）从弹窗迁移至独立页签，新增参数说明 Tooltip，修复向量模型选择不生效问题。

### 新增

* **厂商品牌图标体系**：在 `apps/web-antd/public/providers/` 目录下新增主流 AI 厂商 SVG 图标（智谱、DeepSeek、阿里云、OpenAI 等），供模型配置页面展示品牌 Logo。
* **知识库文件列表增强** (`attach/index.vue` / `attach/data.ts`)：新增文件审批状态徽章、版本号、时效状态筛选列，解析状态实时轮询刷新，文件批量删除与重解析操作。

## [Unreleased] - 2026-07-21

### 纠正与重构

* **项目结构与包名纠正**
  * 根据若依 AI (RuoYi AI) 官方文档的项目介绍与结构，将包路径由 `com.ruoyi.ai` 修正为 **`org.ruoyi`** 顶层包名。
  * 将后端 Java 代码的存放模块由原本暂存的 `ruoyi-ai` 迁移至若依官方标准的业务模块 **`ruoyi-modules/ruoyi-chat`** 下对应的包路径。
  * 删除了不合符规范的临时 `ruoyi-ai` 目录，确保工作区整洁。

### 新增

* **数据库设计 (PostgreSQL + pgvector)**
  * 在 [ruoyi-ai-base/docs/script/sql/sys_knowledge.sql](file:///d:/czl/企业知识库/ruoyi-ai-base/docs/script/sql/sys_knowledge.sql) 数据库脚本中定义了主表、文档表与切片表。
  * 在切片表中引入 1024 维的 `embedding` 向量字段，并基于 pgvector 插件建立 HNSW 余弦距离索引。
  * 通过外键级联约束 (`ON DELETE CASCADE`)，保障文档被物理删除时，向量自动同步清空。

* **后端大模型适配模块 (Model Factory)**
  * 新增 [LlmAdapter.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/factory/LlmAdapter.java) 核心接口，规范流式与同步对话。
  * 新增 [DeepSeekAdapter.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/factory/impl/DeepSeekAdapter.java) 与 [QwenAdapter.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/factory/impl/QwenAdapter.java) 模型驱动，基于 WebClient SSE 实现大模型流式解析。
  * 新增 [ModelFactory.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/factory/ModelFactory.java) 模型工厂路由与并发缓存容器。

* **后端 RAG 服务与 HTTP 控制器**
  * 新增 [KnowledgeSearchServiceImpl.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/knowledge/impl/KnowledgeSearchServiceImpl.java)，提供包含多租户忽略、混合权限向量过滤、Rerank、版本冲突重排及 Redis 缓存的核心 RAG 检索流水线。
  * 新增 [KnowledgeSearchController.java](file:///d:/czl/企业知识库/ruoyi-ai-base/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/controller/knowledge/KnowledgeSearchController.java) 控制器，暴露 `/knowledge/chat/stream` SSE 接口。自动获取若依 `LoginHelper` 登录上下文中的当前租户 ID 和部门 ID。
  * 引入首包 **`[SOURCES]:JSON`** 数据流分段传输协议，支持在 SSE 数据流首包传输引用来源的完整元数据。

* **前端 Vue 3 对话组件与路由注册**
  * 在前端 [ruoyi-web-base/src/pages/chat/KnowledgeChat.vue](file:///d:/czl/企业知识库/ruoyi-web-base/src/pages/chat/KnowledgeChat.vue) 中实现了界面。
  * 在前端 `sendMessage` 中完成对后端真实 API 的对接：通过 fetch 实现流式 SSE 接收、通过 TextDecoder 解析 Buffer，并兼容了解析 `[SOURCES]:` 报头渲染引用来源卡片，在网络异常或本地无后端时提供自动 Mock 本地降级体验。
  * 修改 [ruoyi-web-base/src/routers/modules/staticRouter.ts](file:///d:/czl/企业知识库/ruoyi-web-base/src/routers/modules/staticRouter.ts)，在侧边栏路由中注册了“企业知识库”菜单项，路径为 `/knowledge-chat`。

## [Unreleased] - 2026-07-20

### 项目立项与基础搭建

* **项目基线初始化**
  * 基于若依 AI (RuoYi AI) 框架，拉取并初始化企业知识库系统代码仓库 `ruoyi-ai-base`，建立多模块 Maven 工程结构（`ruoyi-framework`、`ruoyi-modules/ruoyi-chat`、`ruoyi-modules/ruoyi-system` 等）。
  * 配置本地 PostgreSQL + pgvector 开发环境，初始化基础系统库表（`sys_user`、`sys_dept`、`sys_tenant`、`sys_menu` 等）。
  * 配置 MinIO 对象存储基础连接，搭建本地 Redis 缓存服务。

* **前端工程初始化**
  * 初始化管理后台 `ruoyi-admin`（基于 Vite + Ant Design Vue），配置开发服务器代理与环境变量。
  * 初始化问答客户端 `ruoyi-web-base`（基于 Vite + Element Plus），配置基础路由与 Pinia 状态管理。

* **基础系统功能验证**
  * 验证若依多租户登录、RBAC 权限控制、菜单动态加载及 JWT Token 鉴权链路正常运行。
  * 验证 MinIO 文件上传、OSS 接口与前端图片回显基础链路通畅。
