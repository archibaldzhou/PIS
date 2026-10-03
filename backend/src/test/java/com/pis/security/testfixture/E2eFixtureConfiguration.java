package com.pis.security.testfixture;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration(proxyBeanMethods = false)
public class E2eFixtureConfiguration {
    @Bean ApplicationRunner syntheticBrowserAccounts(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return arguments -> {
            insert(jdbc, encoder, value("PIS_E2E_USERNAME", "synthetic.reader"),
                value("PIS_E2E_PASSWORD", "Synthetic-test-only-42!"),
                value("PIS_E2E_DISPLAY_NAME", "合成测试用户"), true);
            insert(jdbc, encoder, value("PIS_E2E_DISABLED_USERNAME", "synthetic.disabled"),
                value("PIS_E2E_DISABLED_PASSWORD", "Synthetic-test-only-42!"), "停用的合成测试用户", false);
            insert(jdbc, encoder, value("PIS_E2E_HANDOFF_USERNAME", "synthetic.technician"), value("PIS_E2E_HANDOFF_PASSWORD", "Synthetic-handoff-only-42!"), "合成交接用户", true);
            insert(jdbc, encoder, value("PIS_E2E_WORKFLOW_USERNAME", "synthetic.workflow"),
                value("PIS_E2E_WORKFLOW_PASSWORD", "Synthetic-workflow-only-42!"), "合成工作流用户", true);
            jdbc.update("INSERT INTO report_template(code,version,title,schema_code) VALUES('SYN-REPORT',1,'合成文本草稿','SYN-TEXT-1'),('SYN-REPORT',2,'合成结构草稿','SYN-STRUCTURED-2')");
            workflow(jdbc, value("PIS_E2E_WORKFLOW_USERNAME", "synthetic.workflow"));
        };
    }
    private static void workflow(JdbcTemplate jdbc, String username) {
        var hospital=java.util.UUID.randomUUID(); var campus=java.util.UUID.randomUUID();
        var department=java.util.UUID.randomUUID(); var source=java.util.UUID.randomUUID();
        var scope=java.util.UUID.randomUUID(); var patient=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-e2e','合成测试医院')",hospital);
        jdbc.update("INSERT INTO campus(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成院区')",campus,hospital);
        jdbc.update("INSERT INTO department(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成科室')",department,hospital);
        jdbc.update("INSERT INTO department_campus VALUES(?,?,?,statement_timestamp())",hospital,campus,department);
        jdbc.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成来源')",source,hospital);
        jdbc.update("INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES(?,?,?,?,?,'合成申请工作范围',true)",scope,hospital,campus,department,source);
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_write,can_receive,can_exception,can_print,can_reprint,can_gross,can_process,can_handoff,can_material,can_qc) SELECT id,?,true,true,true,true,true,true,true,true,true,true,true FROM app_user WHERE username=?",scope,username);
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_process,can_handoff) SELECT id,?,true,true,true FROM app_user WHERE username=?",scope,value("PIS_E2E_HANDOFF_USERNAME", "synthetic.technician"));
        jdbc.update("INSERT INTO diagnosis_grant(user_id,scope_id,can_assign,can_diagnose,qualification) SELECT g.user_id,g.scope_id,u.username=?,true,'SYN-DIAG-ASSIGNMENT-1' FROM workflow_grant g JOIN app_user u ON u.id=g.user_id WHERE g.scope_id=?",username,scope);
        jdbc.update("INSERT INTO report_review_policy(scope_id,code,separate_author_review,separate_review_sign) VALUES(?,'SYN-REVIEW-1',true,true)",scope);
        jdbc.update("INSERT INTO report_review_grant(user_id,scope_id,qualification,can_review,can_simulate_sign) SELECT user_id,scope_id,'SYN-REPORT-REVIEW-1',true,true FROM diagnosis_grant WHERE scope_id=?",scope);
        jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'合成申请患者')",patient,hospital);
        jdbc.update("INSERT INTO encounter(hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,'SYN-WORKFLOW-001',?)",hospital,patient,source,department);
        for(var number:java.util.List.of("SYN-RECEIVE-001","SYN-RETURN-001","SYN-RESOLVE-001","SYN-LABEL-001","SYN-GROSS-001","SYN-TECH-001","SYN-MATERIAL-001","SYN-DIRECT-001","SYN-QC-001","SYN-WORKLIST-001","SYN-DIAGNOSIS-001","SYN-REPORT-001","SYN-REVIEW-001")) {
            // Independent patient/encounter per scenario; shared list still exercises exact resource selection.
            var scenarioPatient=java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'合成申请患者')",scenarioPatient,hospital);
            jdbc.update("INSERT INTO encounter(hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,?,?)",hospital,scenarioPatient,source,number,department);
        }
    }
    private static void insert(JdbcTemplate jdbc, PasswordEncoder encoder, String username, String password,
                               String displayName, boolean enabled) {
        jdbc.update("""
            INSERT INTO app_user (username, display_name, password_hash, enabled, synthetic_only)
            VALUES (?, ?, ?, ?, true)
            """, username, displayName, encoder.encode(password), enabled);
    }
    private static String value(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
