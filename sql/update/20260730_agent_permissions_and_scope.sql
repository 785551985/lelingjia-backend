-- ===================================================
-- 大招企业知识库 - 智能体权限隔离与作用域扩展增量脚本
-- 日期: 2026-07-30
-- ===================================================

-- 1. 为 agent_info 智能体表添加权限隔离与可见范围字段
ALTER TABLE agent_info ADD COLUMN IF NOT EXISTS visible_scope smallint DEFAULT 0;
ALTER TABLE agent_info ADD COLUMN IF NOT EXISTS dept_ids varchar(500);
ALTER TABLE agent_info ADD COLUMN IF NOT EXISTS role_ids varchar(500);
ALTER TABLE agent_info ADD COLUMN IF NOT EXISTS is_public smallint DEFAULT 1;
ALTER TABLE agent_info ADD COLUMN IF NOT EXISTS scope_level smallint DEFAULT 1;

COMMENT ON COLUMN agent_info.visible_scope IS '可见范围：0 全员公开 1 指定部门/角色可见';
COMMENT ON COLUMN agent_info.dept_ids IS '允许访问的部门ID列表（逗号分隔）';
COMMENT ON COLUMN agent_info.role_ids IS '允许访问的角色ID列表（逗号分隔）';
COMMENT ON COLUMN agent_info.is_public IS '是否公开：1 对内公开 2 对外公开 0 仅自己可见';
COMMENT ON COLUMN agent_info.scope_level IS '作用域级别：1 集团级 2 机构级 3 部门级 4 个人级';

-- 2. 刷新初始化智能体数据，确保极简打招呼规范与无残留占位符
UPDATE agent_info 
SET system_prompt = REPLACE(system_prompt, '[企业/机构名称]', '大招科技') 
WHERE system_prompt LIKE '%[企业/机构名称]%';

UPDATE agent_info 
SET system_prompt = REPLACE(system_prompt, '【企业/机构名称】', '大招科技') 
WHERE system_prompt LIKE '%【企业/机构名称】%';

