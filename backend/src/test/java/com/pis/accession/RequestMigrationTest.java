package com.pis.accession;
import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestMigrationTest {
    @Test void upgradesV4WithoutSeedingScopesOrRewritingHistory() throws Exception {
        try(var database=new PostgresTestDatabase()) {
            var old=database.configuration("classpath:db/migration").target("4").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(4);
            var checksum=old.info().current().getChecksum();
            var latest=database.configuration("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            try(var c=database.connection();var s=c.createStatement()) {
                try(var rows=s.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='4'")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(checksum); }
                try(var rows=s.executeQuery("SELECT (SELECT count(*) FROM workflow_scope)+(SELECT count(*) FROM workflow_grant)+(SELECT count(*) FROM request_workflow)")) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
            }
        }
    }
}
