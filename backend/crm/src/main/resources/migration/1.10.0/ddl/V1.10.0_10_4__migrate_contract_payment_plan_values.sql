-- 每个非空旧值都必须取得唯一的目标字段 ID，才能进入待回填表。
DROP TEMPORARY TABLE IF EXISTS payment_plan_migration_value;
CREATE TEMPORARY TABLE payment_plan_migration_value (
    resource_id VARCHAR(32) NOT NULL,
    field_id VARCHAR(32) NOT NULL CHECK (field_id <> ''),
    field_value VARCHAR(255) NOT NULL,
    PRIMARY KEY (resource_id, field_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

START TRANSACTION;

INSERT INTO payment_plan_migration_value
SELECT legacy.resource_id, (SELECT f.id FROM sys_module_form form JOIN sys_module_field f ON f.form_id = form.id
                           WHERE form.organization_id = legacy.organization_id AND form.form_key = 'contractPaymentPlan'
                             AND f.internal_key = CONVERT(legacy.internal_key USING utf8mb4) COLLATE utf8mb4_general_ci),
       legacy.field_value
FROM (
    SELECT id AS resource_id, organization_id, 'contractPaymentPlanPlanAmount' AS internal_key, CAST(plan_amount AS CHAR) AS field_value
    FROM contract_payment_plan WHERE plan_amount IS NOT NULL
    UNION ALL
    SELECT id, organization_id, 'contractPaymentPlanPlanEndTime', CAST(plan_end_time AS CHAR)
    FROM contract_payment_plan WHERE plan_end_time IS NOT NULL
) legacy;

-- 支持带子表格行和不带 row_id 的历史表结构，已写入的顶层自定义值优先。
SET @plan_existing_values = CONCAT(
    'DELETE pending FROM payment_plan_migration_value pending JOIN contract_payment_plan_field existing ',
    'ON existing.resource_id = pending.resource_id AND existing.field_id = pending.field_id',
    IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
               AND table_name = 'contract_payment_plan_field' AND column_name = 'row_id'), ' AND existing.row_id IS NULL', '')
);
PREPARE plan_existing_values FROM @plan_existing_values;
EXECUTE plan_existing_values;
DEALLOCATE PREPARE plan_existing_values;

INSERT INTO contract_payment_plan_field (id, resource_id, field_id, field_value)
SELECT UUID_SHORT(), resource_id, field_id, field_value FROM payment_plan_migration_value;

COMMIT;
DROP TEMPORARY TABLE payment_plan_migration_value;

ALTER TABLE contract_payment_plan
    DROP COLUMN plan_amount,
    DROP COLUMN plan_end_time;
