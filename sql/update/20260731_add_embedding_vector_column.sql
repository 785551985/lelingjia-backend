-- 为 knowledge_fragment 表添加 embedding_vector 向量持久化字段
ALTER TABLE knowledge_fragment 
ADD COLUMN IF NOT EXISTS embedding_vector real[];

-- 建立复合索引
CREATE INDEX IF NOT EXISTS idx_fragment_kid_id ON knowledge_fragment(knowledge_id, id);
