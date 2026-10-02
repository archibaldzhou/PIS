package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class LabelMigrationTest {
    @Test void v6UpgradePreservesChecksumsAndDoesNotSeedLabelIdentitiesOrPrintGrants() throws Exception {
        try(var db=new PostgresTestDatabase()) {
            assertThat(db.configuration("classpath:db/migration").target("6").load().migrate().migrationsExecuted).isEqualTo(6);
            try(var connection=db.connection()) {
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                String history="SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6') ORDER BY installed_rank";
                var before=jdbc.queryForList(history); assertThat(before).hasSize(6);
                var latest=db.configuration("classpath:db/migration").target("7").load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(latest.validateWithResult().validationSuccessful).isTrue();
                assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList(history)).isEqualTo(before);
                for(String table:java.util.List.of("label_identity","label_job","label_job_event","workflow_grant")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
                assertThat(jdbc.queryForList("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name IN ('can_print','can_reprint')",String.class)).containsExactlyInAnyOrder("false","false");
            }
        }
    }
}
