-- 平台统一模版集中管理中心 菜单与权限 SQL
BEGIN;

-- 1. 插入【平台模版中心】主目录菜单 (ID: 2090)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (2090, '平台模版中心', 0, 9, 'template', NULL, 1, 0, 'M', '0', '0', '', 'ant-design:appstore-outlined', 103, 1, NOW(), '平台统一模版集中管理目录')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name;

-- 2. 插入【知识库范本管理】子菜单 (ID: 2091)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (2091, '知识库范本管理', 2090, 1, 'knowledge', 'system/template/knowledge', 1, 0, 'C', '0', '0', 'system:knowledgeTemplate:list', 'ant-design:file-text-outlined', 103, 1, NOW(), '知识库预设范本集中管理菜单')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, path = EXCLUDED.path, component = EXCLUDED.component;

-- 3. 插入【智能体模板管理】子菜单 (ID: 2092)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (2092, '智能体模板管理', 2090, 2, 'agent', 'system/template/agent', 1, 0, 'C', '0', '0', 'system:agentTemplate:list', 'ant-design:robot-outlined', 103, 1, NOW(), '金牌智能体预设模板集中管理菜单')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, path = EXCLUDED.path, component = EXCLUDED.component;

-- 4. 自动授权给超级管理员角色 (Role ID: 1)
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2090), (1, 2091), (1, 2092)
ON CONFLICT DO NOTHING;

COMMIT;
