-- 仅处理有旧值的组织。组织缺少整张表单时创建完整表单，已有表单只补回填所需的字段。
DROP TEMPORARY TABLE IF EXISTS payment_plan_migration_form, payment_plan_migration_field;
CREATE TEMPORARY TABLE payment_plan_migration_form (
    organization_id VARCHAR(32) PRIMARY KEY,
    form_id VARCHAR(32) NOT NULL,
    new_form BOOLEAN NOT NULL,
    need_amount BOOLEAN NOT NULL,
    need_time BOOLEAN NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

START TRANSACTION;

INSERT INTO payment_plan_migration_form
SELECT legacy.organization_id, COALESCE(form.id, UUID_SHORT()), form.id IS NULL,
       legacy.need_amount, legacy.need_time
FROM (
    SELECT organization_id, MAX(plan_amount IS NOT NULL) AS need_amount,
           MAX(plan_end_time IS NOT NULL) AS need_time
    FROM contract_payment_plan
    WHERE plan_amount IS NOT NULL OR plan_end_time IS NOT NULL
    GROUP BY organization_id
) legacy
LEFT JOIN sys_module_form form ON form.organization_id = legacy.organization_id
    AND form.form_key = 'contractPaymentPlan';

SET @plan_migration_time = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;
INSERT INTO sys_module_form (id, form_key, organization_id, create_time, update_time, create_user, update_user)
SELECT form_id, 'contractPaymentPlan', organization_id, @plan_migration_time, @plan_migration_time, 'admin', 'admin'
FROM payment_plan_migration_form WHERE new_form;

INSERT INTO sys_module_form_blob (id, prop)
SELECT form_id, '{"layout":1,"labelPos":"top","inputWidth":"custom","optBtnPos":"flex-row","viewSize":"small","optBtnContent":[{"text":"保存","enable":true},{"text":"保存并继续添加","enable":false},{"text":"取消","enable":true}]}'
FROM payment_plan_migration_form WHERE new_form;

-- 保留已有字段的 ID 和属性。只有新建字段或缺少属性的字段使用 1.10 的模板。
CREATE TEMPORARY TABLE payment_plan_migration_field AS
SELECT form.organization_id, form.form_id, template.internal_key,
       COALESCE((SELECT f.id FROM sys_module_field f
                 WHERE f.form_id = form.form_id AND f.internal_key = template.internal_key), UUID_SHORT()) AS field_id,
       template.name, template.type,
       COALESCE((SELECT MAX(f.pos) FROM sys_module_field f WHERE f.form_id = form.form_id), 0) + template.ord AS pos,
       template.props
FROM payment_plan_migration_form form
CROSS JOIN JSON_TABLE('[
    {"internalKey":"contractPaymentPlanName","name":"回款计划名称","type":"INPUT","rules":[{"key":"required"}]},
    {"internalKey":"contractPaymentPlanContract","name":"合同","type":"DATA_SOURCE","dataSourceType":"CONTRACT","rules":[{"key":"required"}]},
    {"internalKey":"contractPaymentPlanOwner","name":"负责人","type":"MEMBER","hasCurrentUser":true,"rules":[{"key":"required"}]},
    {"internalKey":"contractPaymentPlanPlanAmount","name":"计划回款金额","type":"INPUT_NUMBER","numberFormat":"number","showThousandsSeparator":true},
    {"internalKey":"contractPaymentPlanPlanEndTime","name":"计划回款时间","type":"DATE_TIME","dateType":"date"}
]', '$[*]' COLUMNS (
    ord FOR ORDINALITY, internal_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci PATH '$.internalKey',
    name VARCHAR(64) PATH '$.name', type VARCHAR(32) PATH '$.type', props JSON PATH '$'
)) template
WHERE form.new_form
   OR (form.need_amount AND template.internal_key = 'contractPaymentPlanPlanAmount')
   OR (form.need_time AND template.internal_key = 'contractPaymentPlanPlanEndTime');

INSERT INTO sys_module_field (id, form_id, internal_key, name, type, mobile, pos, create_user, create_time, update_user, update_time)
SELECT field_id, form_id, internal_key, name, type, 1, pos, 'admin', @plan_migration_time, 'admin', @plan_migration_time
FROM payment_plan_migration_field f
WHERE NOT EXISTS (SELECT 1 FROM sys_module_field existing WHERE existing.id = f.field_id);

INSERT INTO sys_module_field_blob (id, prop)
SELECT f.field_id, JSON_MERGE_PATCH(f.props, '{"showLabel":true,"readable":true,"editable":true,"fieldWidth":1}',
                                    JSON_OBJECT('id', saved.id, 'name', saved.name, 'type', saved.type,
                                                'mobile', CAST(IF(saved.mobile, 'true', 'false') AS JSON)))
FROM payment_plan_migration_field f
JOIN sys_module_field saved ON saved.id = f.field_id
WHERE NOT EXISTS (SELECT 1 FROM sys_module_field_blob existing WHERE existing.id = f.field_id);

COMMIT;
DROP TEMPORARY TABLE payment_plan_migration_field, payment_plan_migration_form;
