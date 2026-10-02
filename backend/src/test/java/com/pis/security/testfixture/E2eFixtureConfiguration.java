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
            workflow(jdbc, value("PIS_E2E_USERNAME", "synthetic.reader"));
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
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_write,can_receive,can_exception,can_print,can_reprint,can_gross) SELECT id,?,true,true,true,true,true,true,true FROM app_user WHERE username=?",scope,username);
        jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'合成申请患者')",patient,hospital);
        jdbc.update("INSERT INTO encounter(hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,'SYN-WORKFLOW-001',?)",hospital,patient,source,department);
        for(var number:java.util.List.of("SYN-RECEIVE-001","SYN-RETURN-001","SYN-RESOLVE-001","SYN-LABEL-001","SYN-GROSS-001"))
            jdbc.update("INSERT INTO encounter(hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,?,?)",hospital,patient,source,number,department);
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
