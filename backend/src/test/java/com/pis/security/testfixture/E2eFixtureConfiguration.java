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
            jdbc.update("INSERT INTO report_template(code,version,title,schema_code) VALUES('SYN-REPORT',1,'合成文本草稿','SYN-TEXT-1'),('SYN-REPORT',2,'合成结构草稿','SYN-STRUCTURED-2')");
            // Each browser file has its own identities AND scope; production never loads this fixture.
            for (String scenario : WORKFLOW_SCENARIOS) {
                String owner=value("PIS_E2E_WORKFLOW_USERNAME", "synthetic.workflow")+"."+scenario;
                String receiver=value("PIS_E2E_HANDOFF_USERNAME", "synthetic.technician")+"."+scenario;
                insert(jdbc,encoder,owner,value("PIS_E2E_WORKFLOW_PASSWORD", "Synthetic-workflow-only-42!"),"合成工作流用户",true);
                insert(jdbc,encoder,receiver,value("PIS_E2E_HANDOFF_PASSWORD", "Synthetic-handoff-only-42!"),"合成交接用户",true);
                workflow(jdbc,owner,receiver,scenario);
            }
        };
    }
    static final java.util.List<String> WORKFLOW_SCENARIOS=java.util.List.of("accession","amendment","archive","consultation","cytology","delivery","diagnosis","digitalqc","frozen","grossing","labels","materials","output","quality","reception","report","review","scan","staining","statistics","storage","technical","viewer","worklist");
    private static void workflow(JdbcTemplate jdbc, String username, String receiver, String scenario) {
        var hospital=java.util.UUID.randomUUID(); var campus=java.util.UUID.randomUUID();
        var department=java.util.UUID.randomUUID(); var source=java.util.UUID.randomUUID();
        var scope=java.util.UUID.randomUUID(); var patient=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO hospital(id,code,name) VALUES(?,?,'合成测试医院')",hospital,"synthetic-e2e-"+scenario);
        jdbc.update("INSERT INTO campus(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成院区')",campus,hospital);
        jdbc.update("INSERT INTO department(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成科室')",department,hospital);
        jdbc.update("INSERT INTO department_campus VALUES(?,?,?,statement_timestamp())",hospital,campus,department);
        jdbc.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic-e2e','合成来源')",source,hospital);
        jdbc.update("INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES(?,?,?,?,?,'合成申请工作范围',true)",scope,hospital,campus,department,source);
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_write,can_receive,can_exception,can_print,can_reprint,can_gross,can_process,can_handoff,can_material,can_qc) SELECT id,?,true,true,true,true,true,true,true,true,true,true,true FROM app_user WHERE username=?",scope,username);
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_process,can_handoff) SELECT id,?,true,true,true FROM app_user WHERE username=?",scope,receiver);
        jdbc.update("INSERT INTO diagnosis_grant(user_id,scope_id,can_assign,can_diagnose,qualification) SELECT g.user_id,g.scope_id,u.username=?,true,'SYN-DIAG-ASSIGNMENT-1' FROM workflow_grant g JOIN app_user u ON u.id=g.user_id WHERE g.scope_id=?",username,scope);
        jdbc.update("INSERT INTO report_review_policy(scope_id,code,separate_author_review,separate_review_sign) VALUES(?,'SYN-REVIEW-1',true,true)",scope);
        jdbc.update("INSERT INTO report_review_grant(user_id,scope_id,qualification,can_review,can_simulate_sign) SELECT user_id,scope_id,'SYN-REPORT-REVIEW-1',true,true FROM diagnosis_grant WHERE scope_id=?",scope);
        jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'合成申请患者')",patient,hospital);
        jdbc.update("INSERT INTO encounter(hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,'SYN-WORKFLOW-001',?)",hospital,patient,source,department);
        jdbc.update("INSERT INTO frozen_grant(user_id,scope_id,qualification,can_record,can_review,can_qc) SELECT user_id,scope_id,'SYN-FROZEN-1',true,true,true FROM diagnosis_grant WHERE scope_id=?",scope);
        jdbc.update("INSERT INTO cytology_grant(user_id,scope_id,qualification,can_prepare,can_qc) SELECT user_id,scope_id,'SYN-CYTOLOGY-1',true,true FROM workflow_grant WHERE scope_id=? AND can_material",scope);
        jdbc.update("INSERT INTO stain_grant(user_id,scope_id,qualification,can_request,can_execute,can_qc) SELECT user_id,scope_id,'SYN-STAIN-1',true,true,true FROM workflow_grant WHERE scope_id=? AND can_material",scope);
        jdbc.update("INSERT INTO archive_grant(user_id,scope_id,qualification,can_request,can_approve,can_manage,valid_until) SELECT user_id,scope_id,'SYN-ARCHIVE-1',true,true,true,statement_timestamp()+interval '2 days' FROM workflow_grant WHERE scope_id=?",scope);
        if (scenario.equals("storage") || (scenario.equals("scan") || (scenario.equals("digitalqc") || scenario.equals("viewer")))) jdbc.update("INSERT INTO storage_grant(user_id,scope_id,qualification,all_cases,can_write,can_capacity,valid_until) SELECT id,?,'SYN-STORAGE-1',true,true,true,statement_timestamp()+interval '1 day' FROM app_user WHERE username=?",scope,username);
        if ((scenario.equals("scan") || (scenario.equals("digitalqc") || scenario.equals("viewer")))) jdbc.update("INSERT INTO scan_grant(user_id,scope_id,qualification,valid_until) SELECT id,?,'SYN-SCAN-1',statement_timestamp()+interval '1 day' FROM app_user WHERE username=?",scope,username);
        if ((scenario.equals("digitalqc") || scenario.equals("viewer"))) jdbc.update("INSERT INTO digital_qc_grant(user_id,scope_id,qualification,valid_until) SELECT id,?,'SYN-DIGITAL-QC-1',statement_timestamp()+interval '1 day' FROM app_user WHERE username=?",scope,username);
        if (scenario.equals("statistics")) jdbc.update("INSERT INTO statistics_grant(user_id,scope_id,qualification,all_requests,can_drill,can_report,valid_until) SELECT id,?,'SYN-STATS-1',true,true,true,statement_timestamp()+interval '1 day' FROM app_user WHERE username=?",scope,username);
        for(var number:java.util.List.of("SYN-RECEIVE-001","SYN-RETURN-001","SYN-RESOLVE-001","SYN-LABEL-001","SYN-GROSS-001","SYN-TECH-001","SYN-MATERIAL-001","SYN-DIRECT-001","SYN-QC-001","SYN-WORKLIST-001","SYN-DIAGNOSIS-001","SYN-REPORT-001","SYN-REVIEW-001","SYN-OUTPUT-001","SYN-AMEND-001","SYN-DELIVERY-001","SYN-FROZEN-001","SYN-CYTOLOGY-001","SYN-STAIN-001","SYN-CONSULT-001","SYN-ARCHIVE-001","SYN-STATS-001","SYN-STORAGE-001","SYN-SCAN-001")) {
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
