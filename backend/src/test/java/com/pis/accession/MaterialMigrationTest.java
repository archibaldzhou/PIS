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
                String history="SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank";
                var oldChecksums=j.queryForList(history); var oldIdentity=j.queryForList("SELECT container_id,hospital_id,request_id,barcode FROM label_identity");
                var oldJob=j.queryForList("SELECT id,container_id,barcode,template_version,state,version,parent_job_id FROM label_job"); var events=j.queryForList("SELECT * FROM label_job_event");
                var latest=db.configuration("classpath:db/migration").load(); assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.validateWithResult().validationSuccessful).isTrue(); assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(j.queryForList(history+" LIMIT 9")).isEqualTo(oldChecksums);
                assertThat(j.queryForList("SELECT container_id,hospital_id,request_id,barcode FROM label_identity")).isEqualTo(oldIdentity);
                assertThat(j.queryForList("SELECT id,container_id,barcode,template_version,state,version,parent_job_id FROM label_job")).isEqualTo(oldJob);
                assertThat(j.queryForList("SELECT * FROM label_job_event")).isEqualTo(events);
                assertThat(j.queryForObject("SELECT target_id FROM label_identity",UUID.class)).isEqualTo(c);
                assertThat(j.queryForObject("SELECT target_id FROM label_job",UUID.class)).isEqualTo(c);
                assertThat(j.queryForObject("SELECT count(*) FROM material_entity",Long.class)).isZero();
                assertThat(j.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name='can_material'",String.class)).isEqualTo("false");
                assertThatThrownBy(()->j.update("UPDATE label_identity SET barcode=?",LabelBarcode.create(UUID.randomUUID()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->j.update("DELETE FROM label_job_event WHERE job_id=?",job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
        }
    }
}
