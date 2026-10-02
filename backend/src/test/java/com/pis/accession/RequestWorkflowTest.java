package com.pis.accession;

import com.pis.PisApplication;
import com.pis.api.ApiException;
import com.pis.api.TraceIdFilter;
import com.pis.database.PostgresTestDatabase;
import com.pis.security.AccountRepository;
import com.pis.security.PisPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static com.pis.accession.RequestContracts.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=PisApplication.class,properties="pis.workflow.development-enabled=true",
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RequestWorkflowTest {
    static final PostgresTestDatabase DB=new PostgresTestDatabase();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { DB.register(registry); }
    @AfterAll static void close() throws Exception { DB.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestService service;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    static final Draft COMPLETE=new Draft("Synthetic history",Instant.parse("2026-01-01T08:00:00Z"),
        List.of(new ContainerInput("Synthetic site",Laterality.UNKNOWN,1,"Synthetic fixative",Instant.parse("2026-01-01T08:10:00Z"))));

    @Test void registersEditsSubmitsAndReplaysWithoutRepeatingHistoryOrContainers() {
        var f=new Fixture();
        f.as(()->{
            var command=new Create(f.scope,f.encounter,COMPLETE);
            var first=service.create(command,"create"); var id=first.receipt().resourceId();
            assertThat(service.create(command,"create").receipt()).isEqualTo(first.receipt());
            assertThat(service.detail(id).state()).isEqualTo("DRAFT");
            var containers=service.detail(id).containers();
            assertThat(service.edit(id,new Edit(0L,COMPLETE),"edit").receipt().version()).isEqualTo(1);
            assertThat(service.detail(id).containers()).isEqualTo(containers);
            assertThat(service.submit(id,new Submit(1L),"submit").receipt().version()).isEqualTo(2);
            assertThat(service.submit(id,new Submit(1L),"submit").replayed()).isTrue();
            assertThat(service.detail(id).state()).isEqualTo("SUBMITTED");
            assertCode(()->service.edit(id,new Edit(2L,COMPLETE),"late-edit"),"REQUEST_NOT_DRAFT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=?",Long.class,id)).isEqualTo(3);
            assertThat(service.list(f.scope,"","SUBMITTED",null,1).total()).isEqualTo(1);
            return null;
        });
    }
    @Test void checksCrossScopeIdentityInputAndCurrentGrantsIncludingReplay() {
        var f=new Fixture(); var other=new Fixture();
        f.as(()->{
            assertThatThrownBy(()->service.create(new Create(other.scope,other.encounter,COMPLETE),"cross-scope")).isInstanceOf(AccessDeniedException.class);
            assertCode(()->service.create(new Create(f.scope,other.encounter,COMPLETE),"wrong-encounter"),"ENCOUNTER_MISMATCH");
            var command=new Create(f.scope,f.encounter,COMPLETE); var result=service.create(command,"intent");
            assertCode(()->service.create(new Create(f.scope,f.encounter,new Draft("different",COMPLETE.sampledAt(),COMPLETE.containers())),"intent"),"IDEMPOTENCY_KEY_REUSED");
            jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
            assertThatThrownBy(()->service.create(command,"intent")).isInstanceOf(AccessDeniedException.class);
            assertCode(()->service.detail(result.receipt().resourceId()),"REQUEST_NOT_FOUND");
            assertThat(service.scopes()).isEmpty();
            return null;
        });
    }
    @Test void incompleteAndDuplicateSubmissionsAreRejectedWithoutChangingVersion() {
        var f=new Fixture();
        f.as(()->{
            var incomplete=new Draft("",null,COMPLETE.containers());
            var id=service.create(new Create(f.scope,f.encounter,incomplete),"draft").receipt().resourceId();
            assertCode(()->service.submit(id,new Submit(0L),"incomplete"),"REQUEST_INCOMPLETE");
            assertThat(service.detail(id).version()).isZero();
            service.edit(id,new Edit(0L,COMPLETE),"fix"); service.submit(id,new Submit(1L),"submit");
            var second=service.create(new Create(f.scope,f.encounter,COMPLETE),"second").receipt().resourceId();
            assertCode(()->service.submit(second,new Submit(0L),"duplicate"),"DUPLICATE_REVIEW_REQUIRED");
            assertThat(service.detail(second).state()).isEqualTo("DRAFT");
            assertCode(()->service.edit(second,new Edit(1L,COMPLETE),"stale"),"VERSION_CONFLICT");
            return null;
        });
    }
    @Test void auditFailureRollsBackTheWholeRegistrationAndSameKeyCanRecover() {
        var f=new Fixture();
        // Scope-local trigger avoids affecting independent synthetic tests.
        jdbc.execute("CREATE FUNCTION reject_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_test_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_request WHERE hospital_id=?",Long.class,f.hospital)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=?",Long.class,f.hospital)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_test_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_test_audit()"); }
        assertThat(f.as(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"recover")).replayed()).isFalse();
    }
    @Test void competingEditsActuallyWaitForTheRowAndOnlyOneVersionWins() throws Exception {
        var f=new Fixture(); var id=f.as(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"create")).receipt().resourceId();
        try(var blocker=DB.connection(); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,id); statement.executeQuery().close(); }
            var first=executor.submit(()->editResult(f,id,"one")); var second=executor.submit(()->editResult(f,id,"two"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    long count=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class);
                    if(count>=2) { waiting=true; break; } Thread.sleep(10);
                }
                assertThat(waiting).as("two real PostgreSQL row-lock waits").isTrue();
            } finally { blocker.rollback(); }
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            assertThat(f.as(()->service.detail(id)).version()).isEqualTo(1);
        }
    }
    @Test void simultaneousSameKeyCreatesOneRequestAndOneAuditEvent() throws Exception {
        var f=new Fixture();
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<com.pis.idempotency.IdempotentCommands.Result> create=()->{
                ready.countDown();
                if(!start.await(2,TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
                return f.as(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"same-intent"));
            };
            var first=executor.submit(create); var second=executor.submit(create);
            try { assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var a=first.get(5,TimeUnit.SECONDS); var b=second.get(5,TimeUnit.SECONDS);
            assertThat(a.receipt()).isEqualTo(b.receipt());
            assertThat(List.of(a.replayed(),b.replayed())).containsExactlyInAnyOrder(false,true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_request WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=?",Long.class,a.receipt().resourceId())).isEqualTo(1);
        }
    }
    @Test void revocationCommittedWhileWaitingForGrantPreventsRegistration() throws Exception {
        var f=new Fixture();
        try(var blocker=DB.connection(); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=? AND scope_id=?")) {
                statement.setObject(1,f.user); statement.setObject(2,f.scope); statement.executeUpdate();
            }
            var pending=executor.submit(()->f.as(()->{
                assertThatThrownBy(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"revoked-wait"))
                    .isInstanceOf(AccessDeniedException.class);
                return null;
            }));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    long count=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT user_id FROM workflow_grant%FOR SHARE%'",Long.class);
                    if(count>0) { waiting=true; break; } Thread.sleep(10);
                }
                assertThat(waiting).as("authorization waits for the uncommitted grant change").isTrue();
                blocker.commit();
            } finally { blocker.rollback(); }
            pending.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_request WHERE hospital_id=?",Long.class,f.hospital)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=?",Long.class,f.hospital)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE hospital_id=?",Long.class,f.hospital)).isZero();
        }
    }
    @Test void realHttpRequiresSessionCsrfAndCurrentWriteGrant() throws Exception {
        var f=new Fixture(); var other=new Fixture();
        var hidden=other.as(()->service.create(new Create(other.scope,other.encounter,COMPLETE),"hidden")).receipt().resourceId();
        var browser=new Browser();
        assertThat(browser.send("GET","/api/requests/scopes",null,null,false).statusCode()).isEqualTo(401);
        String password="Synthetic-request-http-42!";
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf();
        String body="""
            {"scopeId":"%s","encounterId":"%s","draft":{"clinicalHistory":"Synthetic history",
             "sampledAt":"2026-01-01T08:00:00Z","containers":[{"site":"Synthetic site","laterality":"UNKNOWN",
             "materialQuantity":1,"fixative":"Synthetic fixative","fixedAt":"2026-01-01T08:10:00Z"}]}}
            """.formatted(f.scope,f.encounter);
        var noCsrf=browser.send("POST","/api/requests",body,null,false);
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(noCsrf.body()).contains("CSRF_INVALID");
        assertThat(browser.send("GET","/api/requests/"+hidden,null,null,false).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE workflow_grant SET can_write=false WHERE user_id=?",f.user);
        assertThat(browser.send("GET","/api/requests?scopeId="+f.scope,null,null,false).statusCode()).isEqualTo(200);
        assertThat(browser.send("POST","/api/requests",body,csrf,false).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_request WHERE hospital_id=?",Long.class,f.hospital)).isZero();
        jdbc.update("UPDATE workflow_grant SET can_write=true WHERE user_id=?",f.user);
        assertThat(browser.send("POST","/api/requests",body.replace("\"materialQuantity\":1","\"materialQuantity\":0"),csrf,false).statusCode()).isEqualTo(400);
        var created=browser.send("POST","/api/requests",body,csrf,false);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();
        var replay=browser.send("POST","/api/requests",body,csrf,false);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        assertThat(browser.send("POST","/api/requests",body,csrf,false).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_request WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(1);
    }
    private final class Browser {
        final java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL))
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
        String csrf() throws Exception {
            var response=send("GET","/api/auth/csrf",null,null,false);
            assertThat(response.statusCode()).isEqualTo(200);
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("token").stringValue();
        }
        java.net.http.HttpResponse<String> send(String method,String path,String body,String csrf,boolean form) throws Exception {
            var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(10));
            if(csrf!=null) builder.header("X-CSRF-TOKEN",csrf);
            if(body!=null) builder.header("Content-Type",form?"application/x-www-form-urlencoded":"application/json")
                .header("Idempotency-Key","synthetic-http-intent");
            builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }
    private String editResult(Fixture f,UUID id,String key) { return f.as(()->{ try { service.edit(id,new Edit(0L,COMPLETE),key); return "SUCCESS"; } catch(ApiException e) { return e.code(); } }); }
    private static void assertCode(Runnable action,String code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code)); }
    final class Fixture {
        final UUID hospital=UUID.randomUUID(),campus=UUID.randomUUID(),department=UUID.randomUUID(),source=UUID.randomUUID(),scope=UUID.randomUUID(),user=UUID.randomUUID(),patient=UUID.randomUUID(),encounter=UUID.randomUUID();
        final PisPrincipal principal;
        Fixture() {
            jdbc.update("INSERT INTO hospital(id,code,name) VALUES(?,?,'Synthetic')",hospital,hospital.toString());
            jdbc.update("INSERT INTO campus(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",campus,hospital);
            jdbc.update("INSERT INTO department(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",department,hospital);
            jdbc.update("INSERT INTO department_campus VALUES(?,?,?,statement_timestamp())",hospital,campus,department);
            jdbc.update("INSERT INTO source_system(id,hospital_id,code,name) VALUES(?,?,'synthetic','Synthetic')",source,hospital);
            jdbc.update("INSERT INTO workflow_scope(id,hospital_id,campus_id,department_id,source_system_id,name,enabled) VALUES(?,?,?,?,?,'Synthetic',true)",scope,hospital,campus,department,source);
            String username="synthetic-"+user;
            jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash,enabled) VALUES(?,?,'Synthetic','unused-synthetic',true)",user,username);
            jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_write) VALUES(?,?,true,true)",user,scope);
            jdbc.update("INSERT INTO patient(id,hospital_id,display_name) VALUES(?,?,'Synthetic patient')",patient,hospital);
            jdbc.update("INSERT INTO encounter(id,hospital_id,patient_id,source_system_id,encounter_number,department_id) VALUES(?,?,?,?,'synthetic-001',?)",encounter,hospital,patient,source,department);
            principal=new PisPrincipal(new AccountRepository(jdbc).findByUsername(username).orElseThrow());
        }
        <T> T as(Supplier<T> work) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
            var result=new AtomicReference<T>();
            try {
                new TraceIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),(r,s)->result.set(work.get()));
                return result.get();
            } catch(java.io.IOException|jakarta.servlet.ServletException error) { throw new AssertionError(error); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }
}
