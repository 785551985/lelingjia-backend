-- 修正 knowledge_info 中租户 019466 的向量模型名称对齐
BEGIN;

UPDATE knowledge_info
SET embedding_model = 'embedding-3'
WHERE embedding_model = 'text-embedding-v3' OR embedding_model IS NULL OR embedding_model = '';

COMMIT;
