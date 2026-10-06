package com.pis.security.testfixture;

import com.pis.identity.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local test tool, never packaged in the application or called by a migration. */
public final class SyntheticAdminBootstrap {
 private SyntheticAdminBootstrap() { }
 public static void main(String[] args)throws Exception{
  if(args.length!=2||!args[0].matches("pis_test_[a-f0-9]{32}")||!args[1].startsWith("synthetic."))throw new IllegalArgumentException("Expected owned test schema and explicit synthetic username");
  String url=System.getenv("PIS_TEST_DB_URL");if(url==null||!url.startsWith("jdbc:postgresql://127.0.0.1:")||!url.contains("/pis_test?"))throw new IllegalArgumentException("Local disposable test database only");
  try(var connection=DriverManager.getConnection(url,System.getenv("PIS_TEST_DB_USERNAME"),System.getenv("PIS_TEST_DB_PASSWORD"))){
   if(!connection.getCatalog().equals("pis_test"))throw new IllegalArgumentException("Wrong database");connection.setSchema(args[0]);
   var ds=new SingleConnectionDataSource(connection,true);var jdbc=new JdbcTemplate(ds);var provisioner=new GrantProvisioner(jdbc);var transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));transaction.setTimeout(10);
   transaction.executeWithoutResult(status->{
    var users=jdbc.query("SELECT id FROM app_user WHERE username=? AND enabled AND synthetic_only",(r,i)->r.getObject(1,UUID.class),args[1]);if(users.size()!=1)throw new IllegalArgumentException("Enabled synthetic account required");UUID user=users.getFirst();
    var scopes=jdbc.queryForList("SELECT s.id,s.hospital_id FROM workflow_scope s JOIN workflow_grant g ON g.scope_id=s.id WHERE g.user_id=? AND s.enabled AND g.can_read AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())",user);if(scopes.size()!=1)throw new IllegalArgumentException("Bootstrap requires exactly one existing scope");UUID scope=(UUID)scopes.getFirst().get("id"),hospital=(UUID)scopes.getFirst().get("hospital_id");
    jdbc.queryForList("SELECT id FROM hospital WHERE id=? FOR UPDATE",hospital);jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",user);
    if(jdbc.queryForObject("SELECT count(*) FROM identity_admin_grant WHERE hospital_id=? AND revoked_at IS NULL AND valid_until>statement_timestamp()",Long.class,hospital)>0)throw new IllegalStateException("Administrator exists; use the management UI");
    var existing=provisioner.permissions(user).getOrDefault(scope,Set.of());var permissions=new LinkedHashSet<>(existing);RoleCatalog.roles().stream().filter(r->r.code().equals("ADMIN")).findFirst().orElseThrow().permissions().forEach(permissions::add);
    jdbc.update("INSERT INTO identity_account(user_id,hospital_id,employee_number) VALUES(?,?,'SYN-BOOTSTRAP')",user,hospital);
    var until=Instant.now().plusSeconds(365L*86400);provisioner.replace(user,hospital,List.of(new IdentityContracts.Assignment(scope,List.of("ADMIN"),List.copyOf(permissions),existing.stream().anyMatch(RoleCatalog::professional),until)));
    jdbc.update("UPDATE identity_account SET default_scope_id=? WHERE user_id=?",scope,user);
    jdbc.update("INSERT INTO identity_admin_grant(user_id,hospital_id,valid_until) VALUES(?,?,?)",user,hospital,until.atOffset(java.time.ZoneOffset.UTC));
    jdbc.update("INSERT INTO identity_change_event(id,hospital_id,actor_id,target_id,action,reason,result_version) VALUES(?,?,?,?,'BOOTSTRAP','本地合成开发显式初始化管理员；不新增专业签署资格',0)",UUID.randomUUID(),hospital,user,user);
   });
   System.out.println("Synthetic administrator configured. No password was created or changed.");
  }
 }
}
