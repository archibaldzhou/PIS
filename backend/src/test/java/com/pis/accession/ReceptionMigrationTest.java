package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class ReceptionMigrationTest {
    @Test void v5UpgradeIsAdditiveAndDoesNotSeedReceptionOrPermissions() throws Exception {
        try(var db=new PostgresTestDatabase()) {
            var old=db.configuration("classpath:db/migration").target("5").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(5);
            try(var connection=db.connection()) {
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                var before=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
                var latest=db.configuration("classpath:db/migration").load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(latest.validateWithResult().validationSuccessful).isTrue();
                assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') ORDER BY installed_rank")).isEqualTo(before);
                for(var table:java.util.List.of("workflow_grant","specimen_reception","reception_event","pathology_case")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
                assertThat(jdbc.queryForList("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name IN ('can_receive','can_exception')",String.class)).containsExactlyInAnyOrder("false","false");
            }
        }
    }
}
