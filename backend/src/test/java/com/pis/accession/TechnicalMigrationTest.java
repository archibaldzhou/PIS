package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class TechnicalMigrationTest {
    @Test void v8UpgradePreservesHistoryAndAddsNoTechnicalDataOrGrants() throws Exception {
        try(var db=new PostgresTestDatabase()) {
            assertThat(db.configuration("classpath:db/migration").target("8").load().migrate().migrationsExecuted).isEqualTo(8);
            try(var c=db.connection()) {
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));
                String sql="SELECT version,checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') ORDER BY installed_rank";
                var before=jdbc.queryForList(sql); assertThat(before).hasSize(8);
                var latest=db.configuration("classpath:db/migration").target("9").load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(latest.validateWithResult().validationSuccessful).isTrue(); assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(jdbc.queryForList(sql)).isEqualTo(before);
                for(String table:java.util.List.of("technical_task","technical_event","workflow_grant")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='workflow_grant' AND column_name='can_process'",String.class)).isEqualTo("false");
            }
        }
    }
}
