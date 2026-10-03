package com.pis.frozen;
import com.pis.database.PostgresTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class FrozenMigrationTest {
 @Test void v19UpgradePreservesHistoryAndCreatesNoArtifacts() throws Exception {
  try(var db=new PostgresTestDatabase()) {
   db.configuration("classpath:db/migration").target("19").load().migrate();
   try(var connection=db.connection()) {
    var j=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
    UUID h=UUID.randomUUID(),p=UUID.randomUUID(),s=UUID.randomUUID(),r=UUID.randomUUID(),u=UUID.randomUUID(),campus=UUID.randomUUID(),dept=UUID.randomUUID(),scope=UUID.randomUUID();
    j.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-list','Synthetic')",h);
    j.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'Synthetic hidden name')",p,h);
    j.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",s,h);
    j.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES(?,'synthetic-list','Synthetic','unused',true)",u);
    j.update("INSERT INTO campus(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",campus,h);
    j.update("INSERT INTO department(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",dept,h);
    j.update("INSERT INTO department_campus(hospital_id,campus_id,department_id) VALUES(?,?,?)",h,campus,dept);
    j.update("INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES(?,?,?,?,?,'Synthetic',true)",scope,h,campus,dept,s);
    j.update("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES(?,?,?,?,'SYN-LIST')",r,h,p,s);
    j.update("INSERT INTO request_workflow(request_id,hospital_id,scope_id,state,created_by,clinical_history) VALUES(?,?,?,'DRAFT',?,'Synthetic hidden clinical text')",r,h,scope,u);
    j.update("INSERT INTO audit_event(id,actor_user_id,actor_auth_version,hospital_id,operation_code,resource_type,resource_id,result_version,trace_id) VALUES(?,?,0,?,'REQUEST_CREATE_V1','PATHOLOGY_REQUEST',?,0,?)",UUID.randomUUID(),u,h,r,UUID.randomUUID());
    String history="SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer<=19 ORDER BY installed_rank";
    var checksums=j.queryForList(history); var audit=j.queryForList("SELECT * FROM audit_event");
    var latest=db.configuration("classpath:db/migration").target("20").load(); assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.validateWithResult().validationSuccessful).isTrue();
    assertThat(j.queryForList(history)).isEqualTo(checksums); assertThat(j.queryForList("SELECT * FROM audit_event")).isEqualTo(audit);
    assertThat(j.queryForObject("SELECT count(*) FROM workflow_work_item",Long.class)).isEqualTo(1);
    assertThat(j.queryForObject("SELECT state FROM workflow_work_item WHERE entity_id=?",String.class,r)).isEqualTo("DRAFT");
    assertThat(j.queryForObject("SELECT action FROM workflow_trace_event WHERE request_id=?",String.class,r)).isEqualTo("REQUEST_CREATE_V1");
    assertThat(j.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name IN ('workflow_work_item','workflow_trace_event')",String.class)).doesNotContain("display_name","clinical_history","reason","password_hash","actor_id");
    assertThat(j.queryForObject("SELECT count(*) FROM frozen_case",Long.class)).isZero(); assertThat(j.queryForObject("SELECT count(*) FROM frozen_event",Long.class)).isZero(); assertThat(j.queryForObject("SELECT count(*) FROM frozen_grant",Long.class)).isZero(); assertThat(latest.migrate().migrationsExecuted).isZero();
   }
  }
 }
}
