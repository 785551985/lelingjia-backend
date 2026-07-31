-- 平台统一模版集中管理中心 菜单与文本切片全量按钮权限授权 SQL
BEGIN;

-- 1. 插入【平台模版中心】主目录菜单 (ID: 2090)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (2090, '平台模版中心', 0, 9, 'template', NULL, 1, 0, 'M', '0', '0', '', 'ant-design:appstore-outlined', 103, 1, NOW(), '平台统一模版集中管理目录')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name;

-- 2. 插入【知识库模板管理】子菜单 (ID: 2091)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (2091, '知识库模板管理', 2090, 1, 'knowledge', 'system/template/knowledge/index', 1, 0, 'C', '0', '0', 'system:knowledgeTemplate:list', 'ant-design:file-text-outlined', 103, 1, NOW(), '知识库预设模板集中管理菜单')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, path = EXCLUDED.path, component = EXCLUDED.component;

-- 3. 插入【知识库模板管理】与【知识库附件初始化】按钮细粒度权限节点 (IDs: 20911 - 20917)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark) VALUES
(20911, '知识库模板查询', 2091, 1, '', NULL, 1, 0, 'F', '0', '0', 'system:knowledgeTemplate:list', '#', 103, 1, NOW(), '查询知识库预设模板'),
(20912, '知识库模板新增', 2091, 2, '', NULL, 1, 0, 'F', '0', '0', 'system:knowledgeTemplate:add', '#', 103, 1, NOW(), '新增知识库预设模板'),
(20913, '知识库模板修改', 2091, 3, '', NULL, 1, 0, 'F', '0', '0', 'system:knowledgeTemplate:edit', '#', 103, 1, NOW(), '修改知识库预设模板'),
(20914, '知识库模板删除', 2091, 4, '', NULL, 1, 0, 'F', '0', '0', 'system:knowledgeTemplate:remove', '#', 103, 1, NOW(), '删除知识库预设模板'),
(20915, '知识库附件初始化', 2091, 5, '', NULL, 1, 0, 'F', '0', '0', 'system:attach:add', '#', 103, 1, NOW(), '知识库附件初始化范本'),
(20916, '知识库附件修改', 2091, 6, '', NULL, 1, 0, 'F', '0', '0', 'system:attach:edit', '#', 103, 1, NOW(), '知识库附件编辑'),
(20917, '知识库附件删除', 2091, 7, '', NULL, 1, 0, 'F', '0', '0', 'system:attach:remove', '#', 103, 1, NOW(), '知识库附件删除')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, perms = EXCLUDED.perms;

-- 4. 插入【文本切片管理】全量按钮细粒度权限节点 (IDs: 20931 - 20936)
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark) VALUES
(20931, '文本切片查询', 2090, 1, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:list', '#', 103, 1, NOW(), '查询文本切片列表'),
(20932, '文本切片详情', 2090, 2, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:query', '#', 103, 1, NOW(), '查看文本切片详情'),
(20933, '文本切片新增', 2090, 3, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:add', '#', 103, 1, NOW(), '新增文本切片'),
(20934, '文本切片修改', 2090, 4, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:edit', '#', 103, 1, NOW(), '修改文本切片'),
(20935, '文本切片删除', 2090, 5, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:remove', '#', 103, 1, NOW(), '删除文本切片'),
(20936, '文本切片导出', 2090, 6, '', NULL, 1, 0, 'F', '0', '0', 'system:fragment:export', '#', 103, 1, NOW(), '导出文本切片')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, perms = EXCLUDED.perms;

-- 5. 【治本核心】：将上述所有模板中心菜单、细粒度按钮权限及切片/附件权限，全量自动授权给系统中的所有角色（包含集团管理员、租户管理员）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id 
FROM sys_role r
CROSS JOIN (
    SELECT 2090 AS menu_id UNION ALL
    SELECT 2091 UNION ALL SELECT 20911 UNION ALL SELECT 20912 UNION ALL SELECT 20913 UNION ALL SELECT 20914 UNION ALL SELECT 20915 UNION ALL SELECT 20916 UNION ALL SELECT 20917 UNION ALL
    SELECT 20931 UNION ALL SELECT 20932 UNION ALL SELECT 20933 UNION ALL SELECT 20934 UNION ALL SELECT 20935 UNION ALL SELECT 20936
) m
ON CONFLICT DO NOTHING;

COMMIT;
