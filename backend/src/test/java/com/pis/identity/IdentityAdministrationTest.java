package com.pis.identity;

import com.pis.PisApplication;
import com.pis.accession.RequestService;
import com.pis.api.ApiException;
import com.pis.database.PostgresTestDatabase;
import com.pis.diagnosis.DiagnosisService;
import com.pis.diagnosis.DiagnosisContracts.State;
import com.pis.security.AccountRepository;
import com.pis.security.PisPrincipal;
import com.pis.security.testfixture.E2eFixtureConfiguration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;
import static com.pis.identity.IdentityContracts.*;

@SpringBootTest(classes=PisApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="pis.workflow.development-enabled=true")
@ActiveProfiles("test") @Import(E2eFixtureConfiguration.class)
class IdentityAdministrationTest {
 static final PostgresTestDatabase DATABASE=new PostgresTestDatabase();
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){DATABASE.register(r);}
 @AfterAll static void close() throws Exception {DATABASE.close();}
 @Autowired IdentityAdministration service; @Autowired RequestService requests; @Autowired DiagnosisService diagnosis; @Autowired JdbcTemplate jdbc;
 @Autowired com.pis.report.ReviewService reviews;
 @org.springframework.boot.test.web.server.LocalServerPort int port;
 static final String ADMIN="synthetic.workflow.accession", PASSWORD="Synthetic-admin-test-42!";
 UUID hospital,scope,admin;
 <T>T as(String user,Supplier<T> work){var previous=SecurityContextHolder.getContext().getAuthentication();var principal=new PisPrincipal(new AccountRepository(jdbc).findByUsername(user).orElseThrow());SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
  try{if(previous!=null)return work.get();var result=new java.util.concurrent.atomic.AtomicReference<T>();new com.pis.api.TraceIdFilter().doFilter(new org.springframework.mock.web.MockHttpServletRequest(),new org.springframework.mock.web.MockHttpServletResponse(),(r,s)->result.set(work.get()));return result.get();}catch(java.io.IOException|jakarta.servlet.ServletException e){throw new AssertionError(e);}finally{SecurityContextHolder.getContext().setAuthentication(previous);}}
 <T>T as(Supplier<T> work){return as(ADMIN,work);}
 @BeforeEach void bootstrap(){as(()->{var s=requests.scopes().getFirst();scope=s.id();hospital=s.hospitalId();return null;});admin=jdbc.queryForObject("SELECT id FROM app_user WHERE username=?",UUID.class,ADMIN);jdbc.update("INSERT INTO identity_admin_grant(user_id,hospital_id,valid_until) VALUES(?,?,statement_timestamp()+interval '1 year') ON CONFLICT(user_id,hospital_id) DO UPDATE SET revoked_at=NULL,valid_until=excluded.valid_until",admin,hospital);}
 SaveUser input(String role){var preset=RoleCatalog.roles().stream().filter(r->r.code().equals(role)).findFirst().orElseThrow();String name="synthetic.admin."+UUID.randomUUID();return new SaveUser(0,name,"合成人员",UUID.randomUUID().toString(),true,PASSWORD,scope,List.of(new Assignment(scope,List.of(role),preset.permissions(),preset.permissions().stream().anyMatch(RoleCatalog::professional),Instant.now().plusSeconds(86400))),"合成岗位配置测试");}
 SaveUser edit(User u,List<Assignment> assignments,boolean enabled){return new SaveUser(u.version(),u.username(),u.displayName(),u.employeeNumber(),enabled,null,u.defaultScopeId(),assignments,"合成修改权限测试");}
 @Test void presetsCreateActualGrantsDefaultScopeAndIdempotentAudit(){for(String role:List.of("ADMIN","RECEPTION","TECHNICIAN","REVIEWER","ARCHIVIST","QUALITY")){var body=input(role);var key=UUID.randomUUID().toString();var result=as(()->service.saveUser(hospital,null,body,key));assertThat(as(()->service.saveUser(hospital,null,body,key)).receipt()).isEqualTo(result.receipt());assertThat(as(()->service.user(hospital,result.receipt().resourceId())).assignments()).hasSize(1);var c=as(body.username(),service::context);assertThat(c.defaultScopeId()).isEqualTo(scope);assertThat(c.menus()).contains("requests");assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_change_event WHERE target_id=?",Long.class,result.receipt().resourceId())).isEqualTo(1L);assertThat(as(body.username(),()->requests.list(null,"","",null,1)).items()).allMatch(r->r.scopeId().equals(scope));}}
 @Test void adminAndArchivistCannotDiagnoseOrReviewButCanOpenOutputQueue(){for(String role:List.of("ADMIN","ARCHIVIST")){var body=input(role);as(()->service.saveUser(hospital,null,body,UUID.randomUUID().toString()));as(body.username(),()->{if(role.equals("ARCHIVIST"))assertThatThrownBy(()->diagnosis.list(scope,State.ALL,1,20)).isInstanceOf(ApiException.class);else assertThat(jdbc.queryForObject("SELECT can_diagnose FROM diagnosis_grant g JOIN app_user u ON u.id=g.user_id WHERE u.username=? AND g.scope_id=?",Boolean.class,body.username(),scope)).isFalse();assertThat(diagnosis.outputList(scope,State.ALL,1,20)).isNotNull();return null;});assertThat(jdbc.queryForObject("SELECT count(*) FROM report_review_grant g JOIN app_user u ON u.id=g.user_id WHERE u.username=? AND g.revoked_at IS NULL",Long.class,body.username())).isZero();}}
 @Test void rejectsOrdinaryUsersForeignScopesAndUnverifiedProfessionalQualification(){var body=input("RECEPTION");as("synthetic.workflow.reception",()->{assertThatThrownBy(()->service.saveUser(hospital,null,body,"denied")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);return null;});var foreign=as("synthetic.workflow.reception",()->requests.scopes().getFirst().id());var other=new SaveUser(0,body.username(),body.displayName(),body.employeeNumber(),true,PASSWORD,foreign,List.of(new Assignment(foreign,List.of("RECEPTION"),List.of("READ"),false,Instant.now().plusSeconds(86400))),body.reason());as(()->{assertThatThrownBy(()->service.saveUser(hospital,null,other,"foreign")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);var unqualified=new SaveUser(0,body.username(),body.displayName(),body.employeeNumber(),true,PASSWORD,scope,List.of(new Assignment(scope,List.of("REVIEWER"),List.of("READ","DIAGNOSE","SIGN"),false,Instant.now().plusSeconds(86400))),body.reason());assertThatThrownBy(()->service.saveUser(hospital,null,unqualified,"qualification")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("QUALIFICATION_REQUIRED"));return null;});}
 @Test void updatesRevokePermissionsAndStaleVersionsCannotOverwrite(){var body=input("TECHNICIAN");var id=as(()->service.saveUser(hospital,null,body,"create-"+UUID.randomUUID())).receipt().resourceId();var u=as(()->service.user(hospital,id));var read=List.of(new Assignment(scope,List.of("AUDITOR"),List.of("READ","AUDIT"),false,Instant.now().plusSeconds(86400)));var update=edit(u,read,true);as(()->service.saveUser(hospital,id,update,"update-"+UUID.randomUUID()));assertThat(as(body.username(),service::context).menus()).doesNotContain("technical");assertThat(jdbc.queryForObject("SELECT can_process FROM workflow_grant WHERE user_id=? AND scope_id=?",Boolean.class,id,scope)).isFalse();as(()->{assertThatThrownBy(()->service.saveUser(hospital,id,update,"stale-"+UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ADMIN_VERSION_CONFLICT"));return null;});}
 @Test void auditFailureRollsBackUserAndAllGrants(){var body=input("RECEPTION");jdbc.execute("CREATE FUNCTION reject_identity_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='IDENTITY_USER_SAVE_V1' THEN RAISE EXCEPTION 'Synthetic failure';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_identity_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_identity_audit()");try{as(()->{assertThatThrownBy(()->service.saveUser(hospital,null,body,"rollback")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=?",Long.class,body.username())).isZero();}finally{jdbc.execute("DROP TRIGGER reject_identity_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_identity_audit()");}as(()->service.saveUser(hospital,null,body,"rollback"));}
 @Test void replayReauthorizesAndChangedPayloadCannotReuseKey(){var body=input("RECEPTION");String key=UUID.randomUUID().toString();as(()->service.saveUser(hospital,null,body,key));as(()->{assertThatThrownBy(()->service.saveUser(hospital,null,input("RECEPTION"),key)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));return null;});jdbc.update("UPDATE identity_admin_grant SET revoked_at=statement_timestamp() WHERE user_id=?",admin);as(()->{assertThatThrownBy(()->service.saveUser(hospital,null,body,key)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);return null;});}
 @Test void administratorDirectSignatureAndCrossHospitalIdsAreRejected(){
  var body=input("ADMIN");var id=as(()->service.saveUser(hospital,null,body,UUID.randomUUID().toString())).receipt().resourceId();
  var draft=new com.pis.accession.RequestContracts.Draft("合成",null,List.of(new com.pis.accession.RequestContracts.ContainerInput("合成",com.pis.accession.RequestContracts.Laterality.NONE,1,"",null)));
  var request=as(()->requests.createManual(new com.pis.accession.RequestContracts.ManualCreate(scope,"合成测试",UUID.randomUUID().toString(),draft),UUID.randomUUID().toString())).receipt().resourceId();
  UUID caseId=UUID.randomUUID();jdbc.update("INSERT INTO pathology_case(id,hospital_id,request_id,number_namespace,case_number) VALUES(?,?,?,'SYN-ADMIN',?)",caseId,hospital,request,caseId.toString());
  var decision=new com.pis.report.ReviewContracts.Decision(-1L,caseId,UUID.randomUUID(),0L,0L,"SYN-REPORT",1,"a".repeat(64),"合成越权测试",true);
  as(body.username(),()->{assertThatThrownBy(()->reviews.decide(caseId,decision,UUID.randomUUID().toString(),com.pis.report.ReviewContracts.Action.SIMULATE_SIGN)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DIAGNOSIS_NOT_FOUND"));return null;});
  UUID other=as("synthetic.workflow.reception",()->requests.scopes().getFirst().hospitalId());
  as(()->{assertThatThrownBy(()->service.user(other,id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);return null;});
 }
 @Test void concurrentSameKeyCreatesOneAccountAndAudit()throws Exception{var body=input("RECEPTION");String key=UUID.randomUUID().toString();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var gate=new java.util.concurrent.CyclicBarrier(2);java.util.concurrent.Callable<UUID> work=()->{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);return as(()->service.saveUser(hospital,null,body,key)).receipt().resourceId();};var a=pool.submit(work);var b=pool.submit(work);UUID id=a.get(15,java.util.concurrent.TimeUnit.SECONDS);assertThat(b.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_change_event WHERE target_id=?",Long.class,id)).isEqualTo(1L);}}
 @Test void realHttpRequiresCsrfForcesPasswordChangeAndResetInvalidatesSession() throws Exception {
  var body=input("RECEPTION");var id=as(()->service.saveUser(hospital,null,body,UUID.randomUUID().toString())).receipt().resourceId();
  var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
  assertThat(login(client,body.username(),PASSWORD)).isEqualTo(204);
  assertThat(http(client,"GET","/api/requests/work-context",null,null).statusCode()).isEqualTo(403);
  var json=new tools.jackson.databind.json.JsonMapper();String passwordBody=json.writeValueAsString(Map.of("oldPassword",PASSWORD,"newPassword","Synthetic-new-password-42!"));
  assertThat(http(client,"POST","/api/auth/password",passwordBody,null).statusCode()).isEqualTo(403);
  assertThat(http(client,"POST","/api/auth/password",passwordBody,csrf(client)).statusCode()).isEqualTo(204);
  assertThat(http(client,"GET","/api/auth/me",null,null).statusCode()).isEqualTo(401);
  assertThat(login(client,body.username(),"Synthetic-new-password-42!")).isEqualTo(204);
  assertThat(http(client,"GET","/api/requests/work-context",null,null).statusCode()).isEqualTo(200);
  assertThat(http(client,"GET","/api/requests/admin/"+hospital+"/users",null,null).statusCode()).isEqualTo(403);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_denial_event WHERE actor_id=? AND hospital_id=? AND error_code='ACCESS_DENIED'",Long.class,id,hospital)).isEqualTo(1L);
  var u=as(()->service.user(hospital,id));as(()->service.reset(hospital,id,new ResetPassword(u.version(),"Synthetic-reset-password-42!","合成密码重置"),UUID.randomUUID().toString()));
  assertThat(http(client,"GET","/api/auth/me",null,null).statusCode()).isEqualTo(401);
 }
 @Test void organizationsScopesMultiScopeQueriesAndRevokedDefaultsStayBounded(){
  var first=as(()->service.catalog(hospital)).scopes().stream().filter(s->s.id().equals(scope)).findFirst().orElseThrow();
  var source=as(()->service.organization(hospital,new CreateOrganization(OrganizationKind.SOURCE,"SYN-"+UUID.randomUUID(),"合成第二来源","合成范围测试"),UUID.randomUUID().toString())).receipt().resourceId();
  var second=as(()->service.saveScope(hospital,null,new SaveScope(0,first.campusId(),first.departmentId(),source,"合成第二范围",true,"合成范围测试"),UUID.randomUUID().toString())).receipt().resourceId();
  var body=input("RECEPTION");var assignments=new ArrayList<>(body.assignments());assignments.add(new Assignment(second,List.of("RECEPTION"),List.of("READ","WRITE"),false,Instant.now().plusSeconds(86400)));
  var dual=new SaveUser(0,body.username(),body.displayName(),body.employeeNumber(),true,PASSWORD,second,assignments,body.reason());var id=as(()->service.saveUser(hospital,null,dual,UUID.randomUUID().toString())).receipt().resourceId();
  as(body.username(),()->{assertThat(service.context().defaultScopeId()).isEqualTo(second);assertThat(service.context().scopes()).hasSize(2);for(UUID s:List.of(scope,second)){var draft=new com.pis.accession.RequestContracts.Draft("合成",null,List.of(new com.pis.accession.RequestContracts.ContainerInput("合成",com.pis.accession.RequestContracts.Laterality.NONE,1,"",null)));requests.createManual(new com.pis.accession.RequestContracts.ManualCreate(s,"合成患者",UUID.randomUUID().toString(),draft),UUID.randomUUID().toString());}assertThat(requests.list(null,"","",null,1).items()).extracting(com.pis.accession.RequestContracts.Detail::scopeId).contains(scope,second);return null;});
  as(()->service.saveScope(hospital,second,new SaveScope(0,first.campusId(),first.departmentId(),source,"合成第二范围",false,"合成范围停用"),UUID.randomUUID().toString()));
  as(body.username(),()->{assertThat(service.context().defaultScopeId()).isNull();assertThat(service.context().menus()).isEmpty();assertThat(requests.list(null,"","",null,1).items()).noneMatch(r->r.scopeId().equals(second));return null;});
  UUID foreign=as("synthetic.workflow.reception",()->requests.scopes().getFirst().id());assertThatThrownBy(()->jdbc.update("UPDATE identity_account SET default_scope_id=? WHERE user_id=?",foreign,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,String method,String path,String body,String csrf)throws Exception{var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path));if(csrf!=null)builder.header("X-CSRF-TOKEN",csrf);if(body!=null)builder.header("Content-Type",path.endsWith("login")?"application/x-www-form-urlencoded":"application/json");return client.send(builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());}
 String csrf(java.net.http.HttpClient c)throws Exception{return new tools.jackson.databind.json.JsonMapper().readTree(http(c,"GET","/api/auth/csrf",null,null).body()).get("token").asString();}
 int login(java.net.http.HttpClient c,String username,String password)throws Exception{return http(c,"POST","/api/auth/login","username="+java.net.URLEncoder.encode(username,java.nio.charset.StandardCharsets.UTF_8)+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8),csrf(c)).statusCode();}
}
