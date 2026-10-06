package com.pis.identity;
import com.pis.database.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;
class IdentityMigrationTest {
 @Test void upgradesV37WithoutCreatingAdministratorsAndPreservesChecksums()throws Exception{
  try(var db=new PostgresTestDatabase()){
   db.configuration("classpath:db/migration").target("37").load().migrate();
   try(var c=db.connection()){
    var j=new JdbcTemplate(new SingleConnectionDataSource(c,true));var history=j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    var migration=db.configuration("classpath:db/migration").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(j.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT NULL AND version<>'38' ORDER BY installed_rank")).isEqualTo(history);
    assertThat(j.queryForObject("SELECT count(*) FROM identity_admin_grant",Long.class)).isZero();assertThat(j.queryForObject("SELECT count(*) FROM app_user",Long.class)).isZero();assertThat(migration.validateWithResult().validationSuccessful).isTrue();assertThat(migration.migrate().migrationsExecuted).isZero();
   }
  }
 }
}
