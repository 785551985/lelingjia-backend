-- 修正 knowledge_fragment 表 tenant_id 字段类型与多租户框架匹配
BEGIN;

-- 1. 将 tenant_id 从 bigint 改为 varchar(20)，与租户框架字符串类型匹配
ALTER TABLE knowledge_fragment ALTER COLUMN tenant_id TYPE varchar(20) USING tenant_id::varchar;
ALTER TABLE knowledge_fragment ALTER COLUMN tenant_id SET DEFAULT '000000';

-- 2. 将现有的默认值 '0' 记录更新为 000000（租户隔离友好格式）
UPDATE knowledge_fragment SET tenant_id = '000000' WHERE tenant_id = '0';

-- 3. 验证修正结果
SELECT id, doc_id, knowledge_id, tenant_id FROM knowledge_fragment ORDER BY id DESC LIMIT 20;

COMMIT;
