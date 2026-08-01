-- 1. 全表 tenant_id 列数据类型修正与补零校准
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN 
        SELECT table_name, data_type 
        FROM information_schema.columns 
        WHERE column_name = 'tenant_id' AND table_schema = 'public'
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN tenant_id TYPE VARCHAR(20) USING tenant_id::text;', r.table_name);
        EXECUTE format('UPDATE %I SET tenant_id = ''019466'' WHERE tenant_id = ''19466'';', r.table_name);
    END LOOP;
END $$;

-- 2. 线上知识库全量阈值优化对齐（防止短词查询被 0.5 误杀）
UPDATE knowledge_info SET rerank_score_threshold = 0.1, similarity_threshold = 0.15;
