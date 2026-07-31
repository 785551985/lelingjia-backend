-- 自动修正知识库范本缺失的数据权限、租户ID与切片
BEGIN;

-- 1. 修正已有切片记录继承所属附件的创建人、部门权限与租户ID (类型安全显示转换)
UPDATE knowledge_fragment f
SET 
    create_dept = COALESCE(a.create_dept::varchar, '103'),
    create_by   = COALESCE(a.create_by::varchar, '1'),
    tenant_id   = COALESCE(a.tenant_id::varchar, i.tenant_id::varchar, '000000')
FROM knowledge_attach a
LEFT JOIN knowledge_info i ON a.knowledge_id = i.id
WHERE f.doc_id = a.doc_id 
  AND (f.create_dept IS NULL OR f.create_by IS NULL OR f.tenant_id IS NULL OR f.tenant_id = '0' OR f.tenant_id = '000000');

-- 2. 为缺少切片的示范范本补齐文本切片数据 (类型安全显示转换)
INSERT INTO knowledge_fragment (id, knowledge_id, doc_id, fid, idx, content, create_dept, create_by, create_time, tenant_id)
SELECT 
    (a.id + 3000000000000000000), 
    a.knowledge_id, 
    a.doc_id, 
    md5(a.doc_id || '_0'), 
    0, 
    '# ' || a.name || E'\n\n内置标准示范范本文档，请根据实际业务需要编辑修改。', 
    COALESCE(a.create_dept::varchar, '103'),
    COALESCE(a.create_by::varchar, '1'),
    NOW(),
    COALESCE(a.tenant_id::varchar, i.tenant_id::varchar, '000000')
FROM knowledge_attach a
LEFT JOIN knowledge_info i ON a.knowledge_id = i.id
WHERE a.oss_id IS NULL 
  AND NOT EXISTS (
      SELECT 1 FROM knowledge_fragment f WHERE f.doc_id = a.doc_id
  );

-- 3. 重置解析失败的范本状态为解析成功/完成
UPDATE knowledge_attach 
SET status = 2, remark = '范本解析成功' 
WHERE oss_id IS NULL AND (status = 3 OR status = 0);

COMMIT;
