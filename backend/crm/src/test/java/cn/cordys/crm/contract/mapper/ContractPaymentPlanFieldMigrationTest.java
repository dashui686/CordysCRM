package cn.cordys.crm.contract.mapper;

import cn.cordys.crm.TestApplication;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ContractPaymentPlanFieldMigrationTest {
    private static final String MIGRATION_PATH = "migration/1.10.0/";
    private static final String PREPARE_FIELDS = "dml/V1.10.0_10_3__prepare_contract_payment_plan_fields.sql";
    private static final String MIGRATE_VALUES = "ddl/V1.10.0_10_4__migrate_contract_payment_plan_values.sql";
    private static final List<String> TABLES = List.of(
            "sys_module_form", "sys_module_form_blob", "sys_module_field", "sys_module_field_blob",
            "contract_payment_plan", "contract_payment_plan_field");

    @Resource
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backfillsLegacyValuesBeforeDroppingColumns(boolean hasRowId) throws IOException {
        try {
            for (String table : TABLES) {
                jdbc.execute("CREATE TABLE ppmig_" + table + " LIKE " + table);
            }
            jdbc.execute("""
                    ALTER TABLE ppmig_contract_payment_plan
                        ADD COLUMN plan_amount DECIMAL(20, 10),
                        ADD COLUMN plan_end_time BIGINT,
                        ADD INDEX idx_plan_end_time (plan_end_time)
                    """);
            if (!hasRowId) {
                jdbc.execute("ALTER TABLE ppmig_contract_payment_plan_field DROP COLUMN row_id");
            }
            seedLegacyPlans();
            if (hasRowId) {
                jdbc.update("""
                        INSERT INTO ppmig_contract_payment_plan_field (id, resource_id, field_id, field_value, row_id)
                        VALUES ('ppmig-child', 'ppmig-plan-b', 'ppmig-b-amount', '7', 'ppmig-row')
                        """);
            }

            var failure = assertThrows(ScriptStatementFailedException.class, () -> executeMigration(MIGRATE_VALUES));
            assertTrue(failure.getMessage().contains("INSERT INTO payment_plan_migration_value"), failure::getMessage);
            assertEquals(2, legacyColumnCount());
            for (int attempt = 0; attempt < 2; attempt++) {
                executeMigration(PREPARE_FIELDS);
                assertEquals(2, legacyColumnCount());
                assertEquals(List.of(List.of("ppmig-plan-c", "contractPaymentPlanPlanEndTime", "1700000000789")),
                        fieldValues(hasRowId));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM ppmig_sys_module_form form
                        JOIN ppmig_sys_module_form_blob b ON b.id = form.id AND JSON_VALID(b.prop)
                        WHERE form.organization_id = 'ppmig-org-c' AND form.form_key = 'contractPaymentPlan'
                        """, Integer.class));
                assertEquals(List.of("INPUT_NUMBER", "DATE_TIME"), jdbc.queryForList("""
                        SELECT f.type FROM ppmig_sys_module_field f
                        JOIN ppmig_sys_module_field_blob b ON b.id = f.id
                        WHERE f.form_id = 'ppmig-form-a'
                          AND b.prop->>'$.internalKey' = f.internal_key AND b.prop->>'$.type' = f.type
                        ORDER BY f.internal_key
                        """, String.class));
            }
            jdbc.update("""
                    INSERT INTO ppmig_contract_payment_plan (id, name, contract_id, owner, plan_status, plan_amount,
                            organization_id, create_time, update_time, create_user, update_user)
                    VALUES ('ppmig-plan-e', '准备后新增的计划', 'ppmig-contract', 'admin', 'PENDING', 88.5,
                            'ppmig-org-a', 0, 0, 'admin', 'admin')
                    """);
            executeMigration(MIGRATE_VALUES);
            assertEquals(0, legacyColumnCount());
            assertEquals(List.of(
                    List.of("ppmig-plan-a", "contractPaymentPlanPlanAmount", "0.0000000000"),
                    List.of("ppmig-plan-a", "contractPaymentPlanPlanEndTime", "1700000000123"),
                    List.of("ppmig-plan-b", "contractPaymentPlanPlanAmount", "1234.5678901234"),
                    List.of("ppmig-plan-c", "contractPaymentPlanPlanEndTime", "1700000000789"),
                    List.of("ppmig-plan-d", "contractPaymentPlanPlanAmount", "100.0000000000"),
                    List.of("ppmig-plan-e", "contractPaymentPlanPlanAmount", "88.5000000000")
            ), fieldValues(hasRowId));
            if (hasRowId) {
                assertEquals("7", jdbc.queryForObject("""
                        SELECT field_value FROM ppmig_contract_payment_plan_field
                        WHERE id = 'ppmig-child' AND row_id = 'ppmig-row'
                        """, String.class));
            }
        } finally {
            for (String table : TABLES.reversed()) {
                jdbc.execute("DROP TABLE IF EXISTS ppmig_" + table);
            }
        }
    }

    private void seedLegacyPlans() {
        jdbc.update("""
                INSERT INTO ppmig_sys_module_form (id, form_key, organization_id, create_time, update_time,
                                             create_user, update_user)
                VALUES ('ppmig-form-a', 'contractPaymentPlan', 'ppmig-org-a', 0, 0, 'admin', 'admin'),
                       ('ppmig-form-b', 'contractPaymentPlan', 'ppmig-org-b', 0, 0, 'admin', 'admin')
                """);
        jdbc.update("""
                INSERT INTO ppmig_sys_module_field (id, form_id, internal_key, name, type, pos,
                                              create_user, create_time, update_user, update_time)
                VALUES ('ppmig-b-amount', 'ppmig-form-b', 'contractPaymentPlanPlanAmount',
                        '计划回款金额', 'INPUT_NUMBER', 0, 'admin', 0, 'admin', 0),
                       ('ppmig-b-date', 'ppmig-form-b', 'contractPaymentPlanPlanEndTime',
                        '计划回款时间', 'DATE_TIME', 1, 'admin', 0, 'admin', 0)
                """);
        jdbc.update("""
                INSERT INTO ppmig_contract_payment_plan (id, name, contract_id, owner, plan_status, plan_amount,
                                                   plan_end_time, organization_id, create_time, update_time,
                                                   create_user, update_user)
                VALUES ('ppmig-plan-a', '回款计划一', 'ppmig-contract', 'admin', 'PENDING',
                        0, 1700000000123, 'ppmig-org-a', 0, 0, 'admin', 'admin'),
                       ('ppmig-plan-b', '回款计划二', 'ppmig-contract', 'admin', 'PENDING',
                        1234.5678901234, NULL, 'ppmig-org-b', 0, 0, 'admin', 'admin'),
                       ('ppmig-plan-c', '回款计划三', 'ppmig-contract', 'admin', 'PENDING',
                        NULL, 1700000000456, 'ppmig-org-b', 0, 0, 'admin', 'admin'),
                       ('ppmig-plan-d', '回款计划四', 'ppmig-contract', 'admin', 'PENDING',
                        100, NULL, 'ppmig-org-c', 0, 0, 'admin', 'admin')
                """);
        jdbc.update("""
                INSERT INTO ppmig_contract_payment_plan_field (id, resource_id, field_id, field_value)
                VALUES ('ppmig-existing', 'ppmig-plan-c', 'ppmig-b-date', '1700000000789')
                """);

    }

    private void executeMigration(String path) throws IOException {
        var resource = new ClassPathResource(MIGRATION_PATH + path);
        String tables = "\\b(" + String.join("|", TABLES) + ")\\b";
        String sql = resource.getContentAsString(StandardCharsets.UTF_8).replaceAll(tables, "ppmig_$1");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            } catch (ScriptStatementFailedException e) {
                try (var statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
                throw e;
            }
            return null;
        });
    }

    private Integer legacyColumnCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ppmig_contract_payment_plan'
                  AND column_name IN ('plan_amount', 'plan_end_time')
                """, Integer.class);
    }

    private List<List<String>> fieldValues(boolean hasRowId) {
        return jdbc.query("""
                SELECT v.resource_id, f.internal_key, v.field_value
                FROM ppmig_contract_payment_plan_field v
                JOIN ppmig_sys_module_field f ON f.id = v.field_id
                JOIN ppmig_sys_module_form form ON form.id = f.form_id
                JOIN ppmig_contract_payment_plan p ON p.id = v.resource_id AND p.organization_id = form.organization_id
                %s
                ORDER BY v.resource_id, f.internal_key
                """.formatted(hasRowId ? "WHERE v.row_id IS NULL" : ""),
                (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)));
    }
}
