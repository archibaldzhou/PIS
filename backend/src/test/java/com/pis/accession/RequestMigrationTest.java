package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class RequestMigrationTest {
    @Test void upgradesV4WithoutSeedingScopesOrRewritingHistory() throws Exception {
        try(var database=new PostgresTestDatabase()) {
            var old=database.configuration("classpath:db/migration").target("4").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(4);
            try(var c=database.connection()) {
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));
                String history="SELECT version, checksum FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4') ORDER BY installed_rank";
                var checksums=jdbc.queryForList(history);
                assertThat(checksums).hasSize(4);
                var roles=jdbc.queryForList("SELECT * FROM security_role ORDER BY code");
                var permissions=jdbc.queryForList("SELECT * FROM security_role_permission ORDER BY role_code,permission_code");
                var hospital=UUID.randomUUID(); var source=UUID.randomUUID();
                var patient=UUID.randomUUID(); var request=UUID.randomUUID(); var container=UUID.randomUUID();
                jdbc.update("INSERT INTO hospital(id,code,name) VALUES(?,'synthetic-v4','Synthetic V4')",hospital);
                jdbc.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic-v4','Synthetic V4')",source,hospital);
                jdbc.update("INSERT INTO patient(id,hospital_id) VALUES(?,?)",patient,hospital);
                jdbc.update("INSERT INTO pathology_request(id,hospital_id,patient_id,source_system_id,request_number) VALUES(?,?,?,?,'SYN-V4')",request,hospital,patient,source);
                jdbc.update("INSERT INTO specimen_container(id,hospital_id,request_id) VALUES(?,?,?)",container,hospital,request);
                var before=jdbc.queryForMap("SELECT * FROM pathology_request WHERE id=?",request);
                var latest=database.configuration("classpath:db/migration").load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(latest.info().current().getVersion().toString()).isEqualTo("5");
                assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(latest.validateWithResult().validationSuccessful).isTrue();
                assertThat(jdbc.queryForList(history)).isEqualTo(checksums);
                assertThat(jdbc.queryForList("SELECT * FROM security_role ORDER BY code")).isEqualTo(roles);
                assertThat(jdbc.queryForList("SELECT * FROM security_role_permission ORDER BY role_code,permission_code")).isEqualTo(permissions);
                assertThat(jdbc.queryForMap("SELECT * FROM pathology_request WHERE id=?",request)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT request_id FROM specimen_container WHERE id=?",UUID.class,container)).isEqualTo(request);
                for(var table:java.util.List.of("workflow_scope","workflow_grant","request_workflow","request_container_detail")) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
                }
            }
        }
    }
}
