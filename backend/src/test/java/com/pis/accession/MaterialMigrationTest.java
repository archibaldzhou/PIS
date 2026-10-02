package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import com.pis.label.LabelBarcode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class MaterialMigrationTest {
    @Test void v9UpgradePreservesExistingContainerIdentityJobsAndImmutableHistory() throws Exception {
        try(var db=new PostgresTestDatabase()) {
            assertThat(db.configuration("classpath:db/migration").target("9").load().migrate().migrationsExecuted).isEqualTo(9);
            try(var connection=db.connection()) {
                var j=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                var h=UUID.randomUUID(); var p=UUID.randomUUID(); var source=UUID.randomUUID(); var r=UUID.randomUUID(); var c=UUID.randomUUID(); var u=UUID.randomUUID(); var job=UUID.randomUUID();
                j.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-upgrade','Synthetic')",h);
                j.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'Synthetic')",p,h);
                j.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",source,h);
                j.update("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES(?,?,?,?,'SYN-UPGRADE')",r,h,p,source);
                j.update("INSERT INTO specimen_container(id,hospital_id,request_id) VALUES(?,?,?)",c,h,r);
                j.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES(?,'synthetic-upgrade','Synthetic','unused',true)",u);
                String code=LabelBarcode.create(UUID.randomUUID());
                j.update("INSERT INTO label_identity(container_id,hospital_id,request_id,barcode) VALUES(?,?,?,?)",c,h,r,code);
                j.update("INSERT INTO label_job(id,hospital_id,request_id,container_id,barcode,template_version,state,request_version,container_version,patient_id,patient_label,encounter_number,request_number,case_number,site,laterality,reason,created_by) VALUES(?,?,?,?,?,'SYN-CONTAINER-1','PREVIEW_READY',0,0,?,'Synthetic','SYN-E','SYN-R','SYN-C','Synthetic','UNKNOWN','Synthetic upgrade',?)",job,h,r,c,code,p,u);
                j.update("INSERT INTO label_job_event(job_id,job_version,action,reason,actor_id) VALUES(?,0,'CREATE','Synthetic upgrade',?)",job,u);
                String history="SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer<=9 ORDER BY installed_rank";
                var oldChecksums=j.queryForList(history); var oldIdentity=j.queryForList("SELECT container_id,hospital_id,request_id,barcode FROM label_identity");
                var oldJob=j.queryForList("SELECT id,container_id,barcode,template_version,state,version,parent_job_id FROM label_job"); var events=j.queryForList("SELECT * FROM label_job_event");
                var v10=db.configuration("classpath:db/migration").target("10").load(); assertThat(v10.migrate().migrationsExecuted).isEqualTo(1);
                assertThatThrownBy(()->j.update("UPDATE label_job SET version=version+1 WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                var v10Checksums=j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
                var latest=db.configuration("classpath:db/migration").load(); assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.validateWithResult().validationSuccessful).isTrue(); assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(j.queryForList(history)).isEqualTo(oldChecksums);
                assertThat(j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer<=10 ORDER BY installed_rank")).isEqualTo(v10Checksums);
                assertThat(j.queryForList("SELECT container_id,hospital_id,request_id,barcode FROM label_identity")).isEqualTo(oldIdentity);
                assertThat(j.queryForList("SELECT id,container_id,barcode,template_version,state,version,parent_job_id FROM label_job")).isEqualTo(oldJob);
                assertThat(j.queryForList("SELECT * FROM label_job_event")).isEqualTo(events);
                assertThat(j.queryForObject("SELECT target_id FROM label_identity",UUID.class)).isEqualTo(c);
                assertThat(j.queryForObject("SELECT target_id FROM label_job",UUID.class)).isEqualTo(c);
                assertThat(j.queryForObject("SELECT count(*) FROM material_entity",Long.class)).isZero();
                assertThat(j.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name='can_material'",String.class)).isEqualTo("false");
                assertThat(j.update("UPDATE label_job SET version=version+1 WHERE id=? AND version=0",job)).isEqualTo(1);
                assertThat(j.update("UPDATE label_job SET state='FAILED',version=version+1 WHERE id=? AND version=1",job)).isEqualTo(1);
                assertThat(j.update("UPDATE label_job SET state='PREVIEW_READY',attempts=attempts+1,version=version+1 WHERE id=? AND version=2",job)).isEqualTo(1);
                assertThat(j.queryForObject("SELECT target_id FROM label_job WHERE id=?",UUID.class,job)).isEqualTo(c);
                var currentJob=j.queryForList("SELECT * FROM label_job");
                for(String column:java.util.List.of("reason","patient_label","site","template_version")) {
                    assertThatThrownBy(()->j.update("UPDATE label_job SET "+column+"='Synthetic changed',version=version+1 WHERE id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThat(j.queryForList("SELECT * FROM label_job")).isEqualTo(currentJob);
                }
                assertThatThrownBy(()->j.update("UPDATE label_identity SET barcode=?",LabelBarcode.create(UUID.randomUUID()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->j.update("DELETE FROM label_job_event WHERE job_id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        }
    }

    @Test void v10MaterialsUpgradeAllowsStateAndVersionButRejectsIdentityChanges() throws Exception {
        try(var db=new PostgresTestDatabase()) {
            db.configuration("classpath:db/migration").target("10").load().migrate();
            try(var connection=db.connection()) {
                var j=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                UUID h=UUID.randomUUID(),p=UUID.randomUUID(),source=UUID.randomUUID(),r=UUID.randomUUID(),c=UUID.randomUUID(),u=UUID.randomUUID(),caseId=UUID.randomUUID(),m=UUID.randomUUID();
                j.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-material-upgrade','Synthetic')",h);
                j.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'Synthetic')",p,h);
                j.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",source,h);
                j.update("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES(?,?,?,?,'SYN-MATERIAL-UPGRADE')",r,h,p,source);
                j.update("INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES(?,?,?,'SYN','SYN-CASE')",caseId,h,r);
                j.update("INSERT INTO specimen_container(id,hospital_id,request_id,case_id) VALUES(?,?,?,?)",c,h,r,caseId);
                j.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES(?,'synthetic-material-upgrade','Synthetic','unused',true)",u);
                j.update("INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,created_by) VALUES(?,?,?,?,?,'SLIDE','DIRECT_CYTOLOGY','ORIGINAL','DEV-S-SYNTHETIC',?,?,?)",m,h,p,r,caseId,LabelBarcode.create(m),c,u);
                j.update("INSERT INTO material_event(material_id,material_version,action,reason,actor_id) VALUES(?,0,'CREATE','Synthetic upgrade',?)",m,u);
                var before=j.queryForList("SELECT * FROM material_entity"); var events=j.queryForList("SELECT * FROM material_event");
                var checksums=j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
                assertThatThrownBy(()->j.update("UPDATE material_entity SET version=version+1 WHERE id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->j.update("UPDATE material_entity SET state='VOID',version=version+1 WHERE id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                var latest=db.configuration("classpath:db/migration").load(); assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.validateWithResult().validationSuccessful).isTrue();
                assertThat(j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version::integer<=10 ORDER BY installed_rank")).isEqualTo(checksums);
                assertThat(j.queryForList("SELECT * FROM material_entity")).isEqualTo(before); assertThat(j.queryForList("SELECT * FROM material_event")).isEqualTo(events);
                assertThat(j.update("UPDATE material_entity SET version=version+1 WHERE id=? AND version=0",m)).isEqualTo(1);
                assertThat(j.update("UPDATE material_entity SET state='VOID',version=version+1 WHERE id=? AND version=1",m)).isEqualTo(1);
                assertThat(j.queryForObject("SELECT state FROM material_entity WHERE id=?",String.class,m)).isEqualTo("VOID");
                var after=j.queryForList("SELECT * FROM material_entity");
                for(String assignment:java.util.List.of("display_number='DEV-S-CHANGED'","created_at=created_at+interval '1 second'","barcode='"+LabelBarcode.create(UUID.randomUUID())+"'","container_id='"+UUID.randomUUID()+"'::uuid","block_kind='SLIDE'","slide_kind='BLOCK'")) {
                    assertThatThrownBy(()->j.update("UPDATE material_entity SET "+assignment+",version=version+1 WHERE id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThat(j.queryForList("SELECT * FROM material_entity")).isEqualTo(after);
                }
                assertThatThrownBy(()->j.update("UPDATE material_event SET reason='Changed' WHERE material_id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->j.update("DELETE FROM material_event WHERE material_id=?",m)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(j.queryForList("SELECT * FROM material_event")).isEqualTo(events);
            }
        }
    }
}
