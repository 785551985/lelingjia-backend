-- 将平台管理中的模型 (chat_model) 和厂商 (chat_provider) 完美同步给租户 019466
BEGIN;

-- 1. 同步全量模型厂商 (chat_provider) 至租户 019466
INSERT INTO chat_provider (
    id, provider_name, provider_code, provider_icon, provider_desc, api_host, 
    status, sort_order, create_dept, create_time, create_by, update_by, 
    update_time, remark, version, del_flag, update_ip, tenant_id
)
SELECT 
    (p.id + 900000000000000000), 
    p.provider_name, 
    p.provider_code, 
    p.provider_icon, 
    p.provider_desc, 
    p.api_host, 
    '0', -- 0: 启用状态
    p.sort_order, 
    p.create_dept, 
    NOW(), 
    '1', 
    '1', 
    NOW(), 
    '同步自平台预设厂商', 
    p.version, 
    '0', 
    p.update_ip, 
    '019466' -- 指定给租户 019466
FROM chat_provider p
WHERE p.del_flag = '0'
  AND NOT EXISTS (
      SELECT 1 FROM chat_provider existing 
      WHERE existing.provider_code = p.provider_code 
        AND existing.tenant_id::varchar = '019466' 
        AND existing.del_flag = '0'
  );

-- 2. 同步全量模型明细 (chat_model) 至租户 019466
INSERT INTO chat_model (
    id, category, model_name, provider_code, model_describe, model_dimension, 
    model_show, api_host, api_key, create_dept, create_by, create_time, 
    update_by, update_time, remark, tenant_id
)
SELECT 
    (m.id + 900000000000000000), 
    m.category, 
    m.model_name, 
    m.provider_code, 
    m.model_describe, 
    m.model_dimension, 
    'Y', -- Y: 前台/下拉可见
    m.api_host, 
    m.api_key, 
    m.create_dept, 
    1, 
    NOW(), 
    1, 
    NOW(), 
    '同步自平台预设模型', 
    '019466' -- 指定给租户 019466
FROM chat_model m
WHERE NOT EXISTS (
      SELECT 1 FROM chat_model existing 
      WHERE existing.model_name = m.model_name 
        AND existing.provider_code = m.provider_code 
        AND existing.tenant_id::varchar = '019466'
  );

-- 3. 将现有的 019466 租户模型状态重置为可见可用
UPDATE chat_model SET model_show = 'Y' WHERE tenant_id::varchar = '019466';
UPDATE chat_provider SET status = '0' WHERE tenant_id::varchar = '019466';

COMMIT;
