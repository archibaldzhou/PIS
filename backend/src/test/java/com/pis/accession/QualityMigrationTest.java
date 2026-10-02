package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class QualityMigrationTest {
 @Test void v11UpgradePreservesChecksumsAndMaterialIdentityAndKeepsQualityHistoryImmutable() throws Exception {
  try(var db=new PostgresTestDatabase()) {
   assertThat(db.configuration("classpath:db/migration").target("11").load().migrate().migrationsExecuted).isEqualTo(11);
   try(var connection=db.connection()) {
    var j=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
    UUID h=UUID.randomUUID(),p=UUID.randomUUID(),s=UUID.randomUUID(),r=UUID.randomUUID(),c=UUID.randomUUID(),u=UUID.randomUUID(),k=UUID.randomUUID(),m=UUID.randomUUID(),a=UUID.randomUUID();
    j.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-qc','Synthetic')",h);
    j.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'Synthetic')",p,h);
    j.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",s,h);
    j.update("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES(?,?,?,?,'SYN-QC')",r,h,p,s);
    j.update("INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES(?,?,?,'SYN','SYN-QC')",k,h,r);
    j.update("INSERT INTO specimen_container(id,hospital_id,request_id,case_id) VALUES(?,?,?,?)",c,h,r,k);
    j.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES(?,'synthetic-qc','Synthetic','unused',true)",u);
    j.update("INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,created_by) VALUES(?,?,?,?,?,'SLIDE','DIRECT_CYTOLOGY','ORIGINAL','DEV-S-QC',?,?,?)",m,h,p,r,k,com.pis.label.LabelBarcode.create(m),c,u);
    var before=j.queryForList("SELECT * FROM material_entity");
    String history="SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer<=11 ORDER BY installed_rank";
    var checksums=j.queryForList(history); var latest=db.configuration("classpath:db/migration").target("12").load(); assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.validateWithResult().validationSuccessful).isTrue();
    assertThat(j.queryForList(history)).isEqualTo(checksums); assertThat(j.queryForList("SELECT * FROM material_entity")).isEqualTo(before);
    assertThat(j.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name='can_qc'",String.class)).isEqualTo("false");
    j.update("INSERT INTO quality_head(material_id,hospital_id,request_id,case_id,patient_id,material_version,state,version) VALUES(?,?,?,?,?,0,'PENDING',0)",m,h,r,k,p);
    j.update("INSERT INTO quality_assessment(id,material_id,material_version,standard_version,outcome,reason,actor_id) VALUES(?,?,0,'SYN-MATERIAL-QC-1','PENDING','Synthetic evidence',?)",a,m,u);
    j.update("UPDATE quality_head SET assessment_id=? WHERE material_id=?",a,m);
    j.update("INSERT INTO quality_event(material_id,version,action,assessment_id,reason,actor_id) VALUES(?,0,'ASSESS',?,'Synthetic evidence',?)",m,a,u);
    assertThatThrownBy(()->j.update("UPDATE quality_head SET patient_id=? WHERE material_id=?",UUID.randomUUID(),m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    j.update("UPDATE quality_head SET state='IDENTITY_MISMATCH',version=version+1 WHERE material_id=?",m);
    assertThatThrownBy(()->j.update("UPDATE quality_head SET state='PASS',version=version+1 WHERE material_id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(()->j.update("UPDATE quality_assessment SET outcome='PASS' WHERE id=?",a)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(()->j.update("DELETE FROM quality_event WHERE material_id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(j.queryForObject("SELECT outcome FROM quality_assessment WHERE id=?",String.class,a)).isEqualTo("PENDING");
    assertThat(latest.migrate().migrationsExecuted).isZero();
   }
  }
 }
}
