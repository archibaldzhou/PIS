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
    @Autowired com.pis.specimen.ReceptionService reception;
    @Autowired com.pis.label.LabelService labels;
    @Autowired com.pis.grossing.GrossService gross;
    @Autowired com.pis.processing.TechnicalService technical;
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
    private UUID submitted(Fixture f) {
        return f.as(()->{ var id=service.create(new Create(f.scope,f.encounter,COMPLETE),"create").receipt().resourceId(); service.submit(id,new Submit(0L),"submit"); return id; });
    }
    private void receptionGrant(Fixture f) { jdbc.update("UPDATE workflow_grant SET can_receive=true,can_exception=true WHERE user_id=?",f.user); }
    private com.pis.specimen.ReceptionContracts.Check check(Fixture f,UUID id,long version) {
        java.util.function.Supplier<com.pis.specimen.ReceptionContracts.Check> read=()->new com.pis.specimen.ReceptionContracts.Check(version,f.patient,"synthetic-001",service.detail(id).containers().stream().map(Container::id).toList());
        return SecurityContextHolder.getContext().getAuthentication()==null?f.as(read):read.get();
    }
    @Test void receivesExactlyOnceWithCaseScopeContainersAndAppendOnlyHistory() {
        var f=new Fixture(); var id=submitted(f); receptionGrant(f); var command=check(f,id,1);
        f.as(()->{
            var result=reception.receive(id,command,"receive");
            assertThat(result.receipt().version()).isEqualTo(2);
            assertThat(reception.receive(id,command,"receive").replayed()).isTrue();
            var view=reception.view(id);
            assertThat(view.request().state()).isEqualTo("RECEIVED");
            assertThat(view.caseNumber()).startsWith("DEV-P-");
            assertThat(view.events()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_case WHERE request_id=?",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM specimen_container WHERE request_id=? AND case_id IS NOT NULL AND received_at IS NOT NULL AND version=1",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM case_access_scope WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(1);
            assertCode(()->reception.receive(id,check(f,id,2),"again"),"RECEPTION_STATE_CONFLICT");
            assertThatThrownBy(()->jdbc.update("UPDATE reception_event SET reason='changed' WHERE request_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM reception_event WHERE request_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
    }
    @Test void identityAndDuplicateContainerMismatchBlockAndCannotResolve() {
        for(boolean identity:List.of(true,false)) {
            var f=new Fixture(); var id=submitted(f); receptionGrant(f); var valid=check(f,id,1);
            var wrong=new com.pis.specimen.ReceptionContracts.Check(1L,identity?UUID.randomUUID():f.patient,"synthetic-001",identity?valid.containerIds():List.of(valid.containerIds().getFirst(),valid.containerIds().getFirst()));
            f.as(()->{
                reception.receive(id,wrong,"mismatch");
                var view=reception.view(id); assertThat(view.request().state()).isEqualTo("EXCEPTION");
                assertThat(view.events().getFirst().category()).isEqualTo(identity?"IDENTITY":"QUANTITY");
                assertThat(view.caseNumber()).isNull();
                assertCode(()->reception.resolve(id,new com.pis.specimen.ReceptionContracts.Decision(2L,"Synthetic correction"),"resolve"),"IDENTITY_OR_QUANTITY_REVIEW_REQUIRED");
                reception.sendBack(id,new com.pis.specimen.ReceptionContracts.Decision(2L,"Synthetic return reason"),"return");
                assertThat(reception.view(id).request().state()).isEqualTo("RETURNED");
                assertThat(reception.view(id).events()).hasSize(2);
                return null;
            });
        }
    }
    @Test void informationCanBeResolvedAndRecheckedWithoutErasingHistory() {
        var f=new Fixture(); var id=submitted(f); receptionGrant(f);
        f.as(()->{
            reception.exception(id,new com.pis.specimen.ReceptionContracts.ExceptionInput(1L,com.pis.specimen.ReceptionContracts.Category.INFORMATION,"Synthetic missing information"),"exception");
            reception.resolve(id,new com.pis.specimen.ReceptionContracts.Decision(2L,"Synthetic supporting information"),"resolve");
            reception.receive(id,check(f,id,3),"receive");
            assertThat(reception.view(id).request().state()).isEqualTo("RECEIVED");
            assertThat(reception.view(id).events()).extracting(e->e.action()).containsExactly("RECEIVE","RESOLVE","EXCEPTION");
            return null;
        });
    }
    @Test void informationResolutionCannotBypassDuplicateSubmissionRule() {
        var f=new Fixture(); var id=submitted(f); receptionGrant(f);
        f.as(()->{
            reception.exception(id,new com.pis.specimen.ReceptionContracts.ExceptionInput(1L,com.pis.specimen.ReceptionContracts.Category.INFORMATION,"Synthetic missing information"),"exception");
            var other=service.create(new Create(f.scope,f.encounter,COMPLETE),"other-create").receipt().resourceId();
            service.submit(other,new Submit(0L),"other-submit");
            assertCode(()->reception.resolve(id,new com.pis.specimen.ReceptionContracts.Decision(2L,"Synthetic corrected information"),"resolve"),"DUPLICATE_REVIEW_REQUIRED");
            assertThat(reception.view(id).request().state()).isEqualTo("EXCEPTION");
            assertThat(reception.view(id).events()).hasSize(1);
            return null;
        });
    }
    @Test void writeDoesNotGrantReceptionAndRevocationBlocksReplay() {
        var f=new Fixture(); var other=new Fixture(); var id=submitted(f); var command=check(f,id,1);
        f.as(()->{ assertThatThrownBy(()->reception.receive(id,command,"denied")).isInstanceOf(AccessDeniedException.class); return null; });
        other.as(()->{ assertCode(()->reception.view(id),"REQUEST_NOT_FOUND"); return null; });
        receptionGrant(f);
        f.as(()->{
            reception.receive(id,command,"receive");
            jdbc.update("UPDATE workflow_grant SET can_receive=false WHERE user_id=?",f.user);
            assertThatThrownBy(()->reception.receive(id,command,"receive")).isInstanceOf(AccessDeniedException.class);
            assertThat(reception.view(id).events()).hasSize(1);
            return null;
        });
    }
    @Test void receptionAuditFailureRollsBackCaseContainersStateEventAndReceipt() {
        var f=new Fixture(); var id=submitted(f); receptionGrant(f); var command=check(f,id,1);
        jdbc.execute("CREATE FUNCTION reject_reception_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_reception_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_reception_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->reception.receive(id,command,"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(f.as(()->reception.view(id)).request().state()).isEqualTo("SUBMITTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_case WHERE request_id=?",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM specimen_container WHERE request_id=? AND case_id IS NOT NULL",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reception_event WHERE request_id=?",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code='RECEPTION_RECEIVE_V1'",Long.class,f.hospital)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_reception_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_reception_audit()"); }
        assertThat(f.as(()->reception.receive(id,command,"recover")).replayed()).isFalse();
    }
    @Test void competingReceptionsWaitAndOnlyOneCaseIsCreated() throws Exception {
        var f=new Fixture(); var id=submitted(f); receptionGrant(f); var command=check(f,id,1);
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var s=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { s.setObject(1,id); s.executeQuery().close(); }
            java.util.function.Function<String,String> run=key->f.as(()->{ try { reception.receive(id,command,key); return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
            var first=executor.submit(()->run.apply("one")); var second=executor.submit(()->run.apply("two"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_case WHERE request_id=?",Long.class,id)).isEqualTo(1);
        }
    }
    @Test void realReceptionHttpEnforcesCsrfSeparatePermissionAndReplayRevocation() throws Exception {
        var f=new Fixture(); var id=submitted(f); var command=check(f,id,1);
        String password="Synthetic-reception-http-42!";
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var browser=new Browser();
        assertThat(browser.send("GET","/api/receptions/"+id,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf();
        String body="{\"expectedVersion\":1,\"patientId\":\""+f.patient+"\",\"encounterNumber\":\"synthetic-001\",\"containerIds\":[\""+command.containerIds().getFirst()+"\"]}";
        String path="/api/receptions/"+id+"/receive";
        assertThat(browser.send("POST",path,body,csrf,false).statusCode()).isEqualTo(403);
        receptionGrant(f);
        var missingCsrf=browser.send("POST",path,body,null,false);
        assertThat(missingCsrf.statusCode()).isEqualTo(403); assertThat(missingCsrf.body()).contains("CSRF_INVALID");
        assertThat(browser.send("POST",path,body,csrf,false).statusCode()).isEqualTo(200);
        assertThat(browser.send("POST",path,body,csrf,false).headers().firstValue("Idempotency-Replayed")).contains("true");
        jdbc.update("UPDATE workflow_grant SET can_receive=false WHERE user_id=?",f.user);
        assertThat(browser.send("POST",path,body,csrf,false).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pathology_case WHERE request_id=?",Long.class,id)).isEqualTo(1);
    }
    private UUID receivedContainer(Fixture f) {
        var id=submitted(f); receptionGrant(f); var input=check(f,id,1);
        f.as(()->reception.receive(id,input,"receive")); return input.containerIds().getFirst();
    }
    private void printGrant(Fixture f) { jdbc.update("UPDATE workflow_grant SET can_print=true,can_reprint=true WHERE user_id=?",f.user); }
    @Test void labelReprintKeepsEntityBarcodeTemplateAndHistoryWithoutCreatingSpecimens() {
        var f=new Fixture(); var cid=receivedContainer(f); printGrant(f);
        f.as(()->{
            var input=new com.pis.label.LabelContracts.Create(2L,1L);
            var root=labels.create(cid,input,"label").receipt().resourceId();
            assertThat(labels.create(cid,input,"label").replayed()).isTrue();
            var original=labels.view(root).job();
            assertThat(com.pis.label.LabelBarcode.valid(original.barcode())).isTrue();
            assertCode(()->labels.create(cid,input,"second-root"),"LABEL_USE_REPRINT");
            var command=new com.pis.label.LabelContracts.Change(0L,"Synthetic damaged label");
            var child=labels.change(root,command,"reprint","REPRINT").receipt().resourceId();
            assertThat(labels.change(root,command,"reprint","REPRINT").replayed()).isTrue();
            var copy=labels.view(child).job();
            assertThat(copy.parentJobId()).isEqualTo(root);
            assertThat(copy.containerId()).isEqualTo(cid);
            assertThat(copy.barcode()).isEqualTo(original.barcode());
            assertThat(copy.templateVersion()).isEqualTo(original.templateVersion());
            assertThat(copy.patientId()).isEqualTo(original.patientId());
            assertThat(copy.caseNumber()).isEqualTo(original.caseNumber());
            assertThat(labels.view(root).job().version()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM specimen_container WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_identity WHERE container_id=?",Long.class,cid)).isEqualTo(1);
            assertThatThrownBy(()->jdbc.update("UPDATE label_job SET barcode='changed' WHERE id=?",child)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM label_job_event WHERE job_id=?",child)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
    }
    @Test void labelFailedRetryCancelAreVersionedAndNeverClaimPhysicalSuccess() {
        var f=new Fixture(); var cid=receivedContainer(f); printGrant(f);
        f.as(()->{
            var id=labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"create").receipt().resourceId();
            assertCode(()->labels.change(id,new com.pis.label.LabelContracts.Change(1L,"Synthetic"),"stale","FAIL"),"VERSION_CONFLICT");
            assertThatThrownBy(()->labels.change(id,new com.pis.label.LabelContracts.Change(0L," "),"empty","REPRINT")).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
            labels.change(id,new com.pis.label.LabelContracts.Change(0L,"Synthetic disconnected adapter"),"fail","FAIL");
            assertThat(labels.view(id).job().state()).isEqualTo("FAILED");
            labels.change(id,new com.pis.label.LabelContracts.Change(1L,"Synthetic retry"),"retry","RETRY");
            assertThat(labels.view(id).job().state()).isEqualTo("PREVIEW_READY");
            assertThat(labels.view(id).job().attempts()).isEqualTo(2);
            labels.change(id,new com.pis.label.LabelContracts.Change(2L,"Synthetic cancel"),"cancel","CANCEL");
            assertCode(()->labels.change(id,new com.pis.label.LabelContracts.Change(3L,"Synthetic late retry"),"late","RETRY"),"LABEL_STATE_CONFLICT");
            assertThat(labels.view(id).events()).extracting(e->e.action()).containsExactly("CANCEL","RETRY","FAIL","CREATE");
            return null;
        });
    }
    @Test void labelsRejectCrossScopeUnreceivedMismatchedBarcodeAndRevokedReprints() {
        var f=new Fixture(); var other=new Fixture(); var cid=receivedContainer(f);
        f.as(()->{ assertCode(()->labels.container(cid),"LABEL_NOT_FOUND"); return null; });
        printGrant(f);
        var root=f.as(()->labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"create")).receipt().resourceId();
        printGrant(other);
        other.as(()->{ assertCode(()->labels.view(root),"REQUEST_NOT_FOUND"); return null; });
        f.as(()->{
            var barcode=labels.view(root).job().barcode();
            assertThat(labels.verify(root,new com.pis.label.LabelContracts.Verify(cid,barcode)).matches()).isTrue();
            assertCode(()->labels.verify(root,new com.pis.label.LabelContracts.Verify(UUID.randomUUID(),barcode)),"LABEL_IDENTITY_MISMATCH");
            assertCode(()->labels.verify(root,new com.pis.label.LabelContracts.Verify(cid,barcode.substring(0,33)+"!")),"LABEL_IDENTITY_MISMATCH");
            var command=new com.pis.label.LabelContracts.Change(0L,"Synthetic reprint");
            labels.change(root,command,"copy","REPRINT");
            assertCode(()->labels.change(root,new com.pis.label.LabelContracts.Change(0L,"Different reason"),"copy","REPRINT"),"IDEMPOTENCY_KEY_REUSED");
            jdbc.update("UPDATE workflow_grant SET can_reprint=false WHERE user_id=?",f.user);
            assertCode(()->labels.change(root,command,"copy","REPRINT"),"LABEL_NOT_FOUND");
            return null;
        });
        var draft=new Fixture(); printGrant(draft);
        draft.as(()->{
            var id=service.create(new Create(draft.scope,draft.encounter,COMPLETE),"draft").receipt().resourceId();
            var container=service.detail(id).containers().getFirst().id();
            assertCode(()->labels.create(container,new com.pis.label.LabelContracts.Create(0L,0L),"label"),"LABEL_REQUIRES_RECEIVED"); return null;
        });
    }
    @Test void labelAuditFailureRollsBackIdentityJobAndReceipt() {
        var f=new Fixture(); var cid=receivedContainer(f); printGrant(f);
        jdbc.execute("CREATE FUNCTION reject_label_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_label_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_label_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_identity WHERE container_id=?",Long.class,cid)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE container_id=?",Long.class,cid)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code='LABEL_CREATE_V1'",Long.class,f.hospital)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_label_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_label_audit()"); }
        assertThat(f.as(()->labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"recover")).replayed()).isFalse();
    }
    @Test void concurrentSameKeyLabelCreationReturnsOneIdentityAndJob() throws Exception {
        var f=new Fixture(); var cid=receivedContainer(f); printGrant(f);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<com.pis.idempotency.IdempotentCommands.Result> run=()->{ if(!start.await(2,TimeUnit.SECONDS)) throw new AssertionError("barrier"); return f.as(()->labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"same")); };
            var a=executor.submit(run); var b=executor.submit(run); start.countDown();
            var first=a.get(5,TimeUnit.SECONDS); var second=b.get(5,TimeUnit.SECONDS);
            assertThat(first.receipt()).isEqualTo(second.receipt());
            assertThat(List.of(first.replayed(),second.replayed())).containsExactlyInAnyOrder(false,true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE container_id=?",Long.class,cid)).isEqualTo(1);
        }
    }
    @Test void labelHttpRequiresCsrfPrintPermissionAndNonblankReprintReason() throws Exception {
        var f=new Fixture(); var cid=receivedContainer(f); String password="Synthetic-label-http-42!";
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var browser=new Browser();
        assertThat(browser.send("GET","/api/labels/containers/"+cid,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf(); String path="/api/labels/containers/"+cid+"/jobs";
        String input="{\"requestVersion\":2,\"containerVersion\":1}";
        assertThat(browser.send("POST",path,input,csrf,false).statusCode()).isEqualTo(404);
        printGrant(f);
        assertThat(browser.send("POST",path,input,null,false).statusCode()).isEqualTo(403);
        var response=browser.send("POST",path,input,csrf,false); assertThat(response.statusCode()).isEqualTo(201);
        String id=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("receipt").path("resourceId").stringValue();
        assertThat(browser.send("POST",path,input,csrf,false).headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(browser.send("POST","/api/labels/jobs/"+id+"/reprint","{\"expectedVersion\":0,\"reason\":\" \"}",csrf,false).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE workflow_grant SET can_reprint=false,can_print=false WHERE user_id=?",f.user);
        assertThat(browser.send("GET","/api/labels/jobs/"+id,null,null,false).statusCode()).isEqualTo(404);
    }
    @Test void competingReprintsCannotReuseOneParentVersion() throws Exception {
        var f=new Fixture(); var cid=receivedContainer(f); printGrant(f);
        var root=f.as(()->labels.create(cid,new com.pis.label.LabelContracts.Create(2L,1L),"root")).receipt().resourceId();
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM specimen_container WHERE id=? FOR UPDATE")) { statement.setObject(1,cid); statement.executeQuery().close(); }
            java.util.function.Function<String,String> run=key->f.as(()->{ try { labels.change(root,new com.pis.label.LabelContracts.Change(0L,"Synthetic replacement"),key,"REPRINT"); return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
            var first=executor.submit(()->run.apply("one")); var second=executor.submit(()->run.apply("two"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%SELECT id FROM specimen_container WHERE id=%' OR query LIKE '%SELECT id FROM pathology_request WHERE id=%')",Long.class)>=2) { waiting=true; break; } Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE parent_job_id=?",Long.class,root)).isEqualTo(1);
        }
    }
    private void grossGrant(Fixture f) { jdbc.update("UPDATE workflow_grant SET can_gross=true WHERE user_id=?",f.user); }
    private UUID grossRequest(Fixture f) { var cid=receivedContainer(f); return jdbc.queryForObject("SELECT request_id FROM specimen_container WHERE id=?",UUID.class,cid); }
    private String syntheticPhoto() { var store=new com.pis.grossing.SyntheticPhotoStore(); var sample=store.sample(); return java.util.Base64.getEncoder().encodeToString(store.read(sample.key(),sample.sha256())); }
    @Test void grossingDescriptionPhotoAndCassetteKeepCaseLineageAndRevisionHistory() {
        var f=new Fixture(); var rid=grossRequest(f); grossGrant(f);
        f.as(()->{
            var created=new com.pis.grossing.GrossContracts.Create(2L,"Synthetic original description");
            var id=gross.create(rid,created,"create").receipt().resourceId();
            assertThat(gross.create(rid,created,"create").replayed()).isTrue();
            gross.describe(id,new com.pis.grossing.GrossContracts.Description(0L,"Synthetic revised description","Synthetic edit reason"),"edit",false);
            var cid=service.detail(rid).containers().getFirst().id();
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(1L,List.of(cid),"Synthetic site",2),"cassette");
            var photo=new com.pis.grossing.GrossContracts.AddPhoto(2L,cid,syntheticPhoto(),"Synthetic image caption");
            gross.addPhoto(id,photo,"photo");
            assertThat(gross.addPhoto(id,photo,"photo").replayed()).isTrue();
            var before=gross.view(rid).record();
            assertThat(before.cassettes()).hasSize(1); assertThat(before.cassettes().getFirst().containerIds()).containsExactly(cid);
            assertThat(before.photos()).hasSize(1); assertThat(gross.photo(before.photos().getFirst().id())).hasSize(656);
            gross.decide(id,null,new com.pis.grossing.GrossContracts.Decision(3L,"Synthetic record completed"),"complete","COMPLETE");
            gross.describe(id,new com.pis.grossing.GrossContracts.Description(4L,"Synthetic corrected description","Synthetic correction reason"),"correction",true);
            var after=gross.view(rid).record(); assertThat(after.state()).isEqualTo("COMPLETED");
            assertThat(after.revisions()).extracting(v->v.description()).containsExactly("Synthetic corrected description","Synthetic revised description","Synthetic original description");
            assertThat(after.cassettes().getFirst().id()).isEqualTo(before.cassettes().getFirst().id());
            assertThat(after.cassettes().getFirst().state()).isEqualTo("PLANNED");
            assertCode(()->gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(5L,List.of(cid),"Late",1),"late"),"GROSS_STATE_CONFLICT");
            assertThatThrownBy(()->jdbc.update("UPDATE gross_revision SET description='changed' WHERE record_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM gross_cassette_source WHERE record_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
    }
    @Test void grossingSupportsOneSourceSplitAndSameCaseMultipleSourcesWithoutChangingIdentity() {
        var f=new Fixture(); receptionGrant(f); grossGrant(f);
        f.as(()->{
            var two=new Draft("Synthetic multisource",COMPLETE.sampledAt(),List.of(COMPLETE.containers().getFirst(),
                new ContainerInput("Synthetic second site",Laterality.UNKNOWN,1,"Synthetic fixative",Instant.parse("2026-01-01T08:10:00Z"))));
            var rid=service.create(new Create(f.scope,f.encounter,two),"create-request").receipt().resourceId();
            service.submit(rid,new Submit(0L),"submit");
            reception.receive(rid,check(f,rid,1L),"receive");
            var containers=service.detail(rid).containers().stream().map(c->c.id()).toList();
            var id=gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"create").receipt().resourceId();
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,containers,"Synthetic combined",2),"combined");
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(1L,List.of(containers.getFirst()),"Synthetic split",1),"split");
            var boxes=gross.view(rid).record().cassettes(); assertThat(boxes).hasSize(2);
            assertThat(boxes.stream().filter(b->b.site().equals("Synthetic combined")).findFirst().orElseThrow().containerIds()).containsExactlyInAnyOrderElementsOf(containers);
            assertThat(boxes.stream().filter(b->b.site().equals("Synthetic split")).findFirst().orElseThrow().containerIds()).containsExactly(containers.getFirst());
            assertThat(boxes.getFirst().id()).isNotEqualTo(boxes.getLast().id());
            assertThatThrownBy(()->jdbc.update("UPDATE gross_cassette SET site='changed' WHERE id=?",boxes.getFirst().id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
    }
    @Test void grossingRejectsUnreceivedCrossCaseSourcesAndNonSyntheticPhotos() {
        var f=new Fixture(); var other=new Fixture(); var rid=grossRequest(f); var foreign=grossRequest(other); grossGrant(f); grossGrant(other);
        var foreignCid=other.as(()->service.detail(foreign)).containers().getFirst().id();
        other.as(()->{ assertCode(()->gross.view(rid),"REQUEST_NOT_FOUND"); return null; });
        f.as(()->{
            var id=gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"create").receipt().resourceId();
            var cid=service.detail(rid).containers().getFirst().id();
            assertCode(()->gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(foreignCid),"wrong",1),"wrong"),"GROSS_SOURCE_MISMATCH");
            assertCode(()->gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cid,cid),"duplicate",1),"duplicate"),"GROSS_SOURCE_MISMATCH");
            assertCode(()->gross.addPhoto(id,new com.pis.grossing.GrossContracts.AddPhoto(0L,cid,"PHN2Zy8+","Wrong image"),"image"),"GROSS_PHOTO_REJECTED");
            assertCode(()->gross.addPhoto(id,new com.pis.grossing.GrossContracts.AddPhoto(0L,foreignCid,syntheticPhoto(),"Wrong source"),"foreign-image"),"GROSS_SOURCE_MISMATCH");
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cid),"Synthetic",1),"valid");
            var box=gross.view(rid).record().cassettes().getFirst().id();
            assertThatThrownBy(()->jdbc.update("INSERT INTO gross_cassette_source(cassette_id,container_id,record_id,hospital_id,request_id,case_id) SELECT id,?,record_id,hospital_id,request_id,case_id FROM gross_cassette WHERE id=?",foreignCid,box)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
        var draft=new Fixture(); grossGrant(draft);
        draft.as(()->{ var id=service.create(new Create(draft.scope,draft.encounter,COMPLETE),"draft").receipt().resourceId(); assertCode(()->gross.create(id,new com.pis.grossing.GrossContracts.Create(0L,""),"gross"),"GROSS_REQUIRES_RECEIVED"); return null; });
    }
    @Test void grossingCancellationAndPhotoWithdrawalKeepHistoryAndBlockNewDownloads() {
        var f=new Fixture(); var rid=grossRequest(f); grossGrant(f);
        f.as(()->{
            var id=gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"create").receipt().resourceId();
            var cid=service.detail(rid).containers().getFirst().id();
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cid),"Synthetic",1),"box");
            var box=gross.view(rid).record().cassettes().getFirst().id();
            gross.decide(id,box,new com.pis.grossing.GrossContracts.Decision(1L,"Synthetic cancelled box"),"cancel-box","CANCEL_CASSETTE");
            assertCode(()->gross.decide(id,null,new com.pis.grossing.GrossContracts.Decision(2L,"Incomplete"),"complete","COMPLETE"),"GROSS_INCOMPLETE");
            gross.addPhoto(id,new com.pis.grossing.GrossContracts.AddPhoto(2L,cid,syntheticPhoto(),"Synthetic"),"photo");
            var photo=gross.view(rid).record().photos().getFirst().id();
            gross.decide(id,photo,new com.pis.grossing.GrossContracts.Decision(3L,"Synthetic image withdrawn"),"withdraw","WITHDRAW_PHOTO");
            assertCode(()->gross.photo(photo),"GROSS_PHOTO_WITHDRAWN");
            gross.decide(id,null,new com.pis.grossing.GrossContracts.Decision(4L,"Synthetic cancellation"),"cancel","CANCEL");
            var view=gross.view(rid).record(); assertThat(view.state()).isEqualTo("CANCELLED");
            assertThat(view.cassettes().getFirst().containerIds()).containsExactly(cid);
            assertThat(view.photos()).hasSize(1); assertThat(view.events()).hasSize(6);
            assertCode(()->gross.describe(id,new com.pis.grossing.GrossContracts.Description(5L,"Late","late"),"late",false),"GROSS_STATE_CONFLICT");
            return null;
        });
    }
    @Test void grossingPhotoAuditFailureRollsBackMetadataAndCanReplaySameIntentAfterRecovery() {
        var f=new Fixture(); var rid=grossRequest(f); grossGrant(f);
        var id=f.as(()->gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"create")).receipt().resourceId();
        var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        var input=new com.pis.grossing.GrossContracts.AddPhoto(0L,cid,syntheticPhoto(),"Synthetic");
        jdbc.execute("CREATE FUNCTION reject_gross_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_gross_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_gross_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->gross.addPhoto(id,input,"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gross_photo WHERE record_id=?",Long.class,id)).isZero();
            assertThat(f.as(()->gross.view(rid)).record().version()).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_gross_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_gross_audit()"); }
        f.as(()->{
            assertThat(gross.addPhoto(id,input,"recover").replayed()).isFalse();
            assertThat(gross.addPhoto(id,input,"recover").replayed()).isTrue();
            assertCode(()->gross.addPhoto(id,new com.pis.grossing.GrossContracts.AddPhoto(0L,cid,syntheticPhoto(),"Changed caption"),"recover"),"IDEMPOTENCY_KEY_REUSED");
            jdbc.update("UPDATE workflow_grant SET can_gross=false WHERE user_id=?",f.user);
            assertCode(()->gross.addPhoto(id,input,"recover"),"GROSS_NOT_FOUND"); return null;
        });
    }
    @Test void competingGrossDescriptionsWaitAndOnlyOneRevisionWins() throws Exception {
        var f=new Fixture(); var rid=grossRequest(f); grossGrant(f);
        var id=f.as(()->gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"create")).receipt().resourceId();
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM gross_record WHERE id=? FOR UPDATE")) { statement.setObject(1,id); statement.executeQuery().close(); }
            java.util.function.Function<String,String> run=key->f.as(()->{ try { gross.describe(id,new com.pis.grossing.GrossContracts.Description(0L,"Synthetic "+key,"Concurrent revision"),key,false); return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
            var first=executor.submit(()->run.apply("one")); var second=executor.submit(()->run.apply("two"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%SELECT id FROM gross_record WHERE id=%' OR query LIKE '%SELECT id FROM pathology_request WHERE id=%')",Long.class)>=2) { waiting=true; break; } Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            assertThat(f.as(()->gross.view(rid)).record().revisions()).hasSize(2);
        }
    }
    @Test void grossPhotoHttpChecksAuthorizationCsrfBodyLimitAndPrivateDownloadHeaders() throws Exception {
        var f=new Fixture(); var rid=grossRequest(f); var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        String password="Synthetic-gross-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var browser=new Browser();
        assertThat(browser.send("GET","/api/grossing/requests/"+rid,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf(); String path="/api/grossing/requests/"+rid;
        assertThat(browser.send("GET",path,null,null,false).statusCode()).isEqualTo(404); grossGrant(f);
        String create="{\"requestVersion\":2,\"description\":\"Synthetic HTTP\"}";
        assertThat(browser.send("POST",path,create,null,false).statusCode()).isEqualTo(403);
        var created=browser.send("POST",path,create,csrf,false); assertThat(created.statusCode()).isEqualTo(201);
        String id=tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.body()).path("receipt").path("resourceId").stringValue();
        String upload="/api/grossing/records/"+id+"/photos";
        assertThat(browser.send("POST",upload,"x".repeat(40000),csrf,false).statusCode()).isEqualTo(413);
        var chunked=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+upload)).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).header("Idempotency-Key","chunked-limit")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(new byte[40000]))).build();
        assertThat(browser.client.send(chunked,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        String photo="{\"expectedVersion\":0,\"containerId\":\""+cid+"\",\"caption\":\"Synthetic\",\"base64\":\""+syntheticPhoto()+"\"}";
        assertThat(browser.send("POST",upload,photo,csrf,false).statusCode()).isEqualTo(200);
        var photoId=jdbc.queryForObject("SELECT id FROM gross_photo WHERE record_id=?",UUID.class,UUID.fromString(id));
        var downloaded=browser.send("GET","/api/grossing/photos/"+photoId+"/content",null,null,false);
        assertThat(downloaded.statusCode()).isEqualTo(200); assertThat(downloaded.headers().firstValue("cache-control")).contains("no-store");
        assertThat(downloaded.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(downloaded.headers().firstValue("content-type")).contains("image/png");
        assertThat(browser.send("GET","/grossing-assets/synthetic-v1.png",null,null,false).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE workflow_grant SET can_gross=false WHERE user_id=?",f.user);
        assertThat(browser.send("GET","/api/grossing/photos/"+photoId+"/content",null,null,false).statusCode()).isEqualTo(404);
    }
    private void technicalGrant(Fixture actor,Fixture scope,boolean handoff) {
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read,can_process,can_handoff) VALUES(?,?,true,true,?) ON CONFLICT(user_id,scope_id) DO UPDATE SET can_process=true,can_handoff=excluded.can_handoff",actor.user,scope.scope,handoff);
    }
    private UUID technicalRequest(Fixture f) {
        var rid=grossRequest(f); grossGrant(f); technicalGrant(f,f,true);
        f.as(()->{
            var id=gross.create(rid,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic technical source"),"gross").receipt().resourceId();
            var cid=service.detail(rid).containers().getFirst().id();
            gross.addCassette(id,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cid),"Synthetic",1),"box");
            gross.decide(id,null,new com.pis.grossing.GrossContracts.Decision(1L,"Synthetic release"),"release","COMPLETE"); return null;
        });
        return rid;
    }
    private UUID technicalCassette(Fixture f,UUID rid) { return f.as(()->technical.view(rid)).source().cassettes().getFirst().id(); }
    private com.pis.processing.TechnicalContracts.Decision td(long version,UUID cassette) { return new com.pis.processing.TechnicalContracts.Decision(version,cassette,"Synthetic checked action"); }
    private com.pis.processing.TechnicalContracts.Create tc(UUID cassette,UUID predecessor) { return new com.pis.processing.TechnicalContracts.Create(2L,cassette,com.pis.processing.TechnicalContracts.Kind.PROCESSING,predecessor,"Synthetic route only"); }
    @Test void technicalHandoffRequiresDistinctAuthorizedReceiverAndPreservesReworkLineage() {
        var f=new Fixture(); var receiver=new Fixture(); var rid=technicalRequest(f); var cassette=technicalCassette(f,rid); technicalGrant(receiver,f,true);
        var id=f.as(()->technical.create(rid,tc(cassette,null),"create")).receipt().resourceId();
        f.as(()->{
            assertThat(technical.create(rid,tc(cassette,null),"create").replayed()).isTrue();
            technical.decide(id,td(0,cassette),"claim","CLAIM"); technical.decide(id,td(1,cassette),"offer","OFFER");
            assertCode(()->technical.decide(id,td(2,cassette),"self","ACCEPT"),"TECH_SELF_HANDOFF");
            assertCode(()->technical.decide(id,td(2,cassette),"premature","FINISH_SIMULATION"),"TECH_STATE_CONFLICT"); return null;
        });
        receiver.as(()->{
            assertCode(()->technical.decide(id,td(2,UUID.randomUUID()),"wrong","ACCEPT"),"TECH_SOURCE_MISMATCH");
            technical.decide(id,td(2,cassette),"accept","ACCEPT"); return null;
        });
        f.as(()->{ assertCode(()->technical.decide(id,td(3,cassette),"former-owner","FINISH_SIMULATION"),"TECH_OWNER_REQUIRED"); return null; });
        receiver.as(()->{
            technical.decide(id,td(3,cassette),"finish","FINISH_SIMULATION");
            assertThat(technical.detail(id).task().state()).isEqualTo("SIMULATED_DONE");
            assertThat(technical.detail(id).events().stream().filter(e->e.action().equals("ACCEPT")).findFirst().orElseThrow()).satisfies(e->{ assertThat(e.previousOwnerId()).isEqualTo(f.user); assertThat(e.nextOwnerId()).isEqualTo(receiver.user); assertThat(e.actorId()).isEqualTo(receiver.user); });
            var child=technical.decide(id,td(4,cassette),"rework","REWORK").receipt().resourceId();
            assertThat(technical.decide(id,td(4,cassette),"rework","REWORK").receipt().resourceId()).isEqualTo(child);
            var t=technical.detail(child).task(); assertThat(t.reworkOf()).isEqualTo(id); assertThat(t.cassetteId()).isEqualTo(cassette); assertThat(t.state()).isEqualTo("QUEUED"); assertThat(t.ownerId()).isNull();
            assertCode(()->technical.decide(id,td(5,cassette),"duplicate-rework","REWORK"),"TECH_REWORK_EXISTS");
            technical.decide(child,td(0,cassette),"abort","ABORT");
            assertThat(technical.detail(child).task().state()).isEqualTo("ABORTED");
            var next=technical.decide(child,td(1,cassette),"rework-again","REWORK").receipt().resourceId();
            assertThat(technical.detail(next).task().reworkOf()).isEqualTo(child);
            assertThat(technical.detail(id).task().state()).isEqualTo("SIMULATED_DONE");
            assertThatThrownBy(()->jdbc.update("DELETE FROM technical_event WHERE task_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE technical_task SET cassette_id=? WHERE id=?",UUID.randomUUID(),child)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        });
    }
    @Test void technicalSourcesPredecessorsAndPermissionsAreRecheckedWithoutForcingOneRoute() {
        var f=new Fixture(); var other=new Fixture(); var rid=technicalRequest(f); var foreign=technicalRequest(other);
        var cassette=technicalCassette(f,rid); var foreignBox=technicalCassette(other,foreign);
        var id=f.as(()->technical.create(rid,tc(cassette,null),"direct")).receipt().resourceId();
        var foreignTask=other.as(()->technical.create(foreign,tc(foreignBox,null),"foreign")).receipt().resourceId();
        f.as(()->{
            assertCode(()->technical.create(rid,tc(foreignBox,null),"wrong-source"),"TECH_SOURCE_MISMATCH");
            assertCode(()->technical.create(rid,tc(cassette,id),"not-ready"),"TECH_PREDECESSOR_NOT_READY");
            assertCode(()->technical.create(rid,tc(cassette,foreignTask),"wrong-parent"),"TECH_PREDECESSOR_NOT_READY");
            assertThatThrownBy(()->jdbc.update("INSERT INTO technical_task(id,hospital_id,request_id,case_id,record_id,cassette_id,kind,state,predecessor_id,created_by) SELECT ?,hospital_id,request_id,case_id,record_id,cassette_id,kind,'QUEUED',?,created_by FROM technical_task WHERE id=?",UUID.randomUUID(),foreignTask,id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            technical.decide(id,td(0,cassette),"claim","CLAIM");
            jdbc.update("UPDATE workflow_grant SET can_handoff=false WHERE user_id=?",f.user);
            assertCode(()->technical.decide(id,td(1,cassette),"no-handoff","OFFER"),"TECH_NOT_FOUND");
            jdbc.update("UPDATE workflow_grant SET can_handoff=true WHERE user_id=?",f.user);
            technical.decide(id,td(1,cassette),"offer-again","OFFER");
            technical.decide(id,td(2,cassette),"withdraw","WITHDRAW");
            technical.decide(id,td(3,cassette),"finish","FINISH_SIMULATION");
            assertThat(technical.create(rid,tc(cassette,id),"with-parent").receipt().status()).isEqualTo(201);
            var direct=new com.pis.processing.TechnicalContracts.Create(2L,cassette,com.pis.processing.TechnicalContracts.Kind.SECTIONING,null,"Synthetic alternate route without invented slide");
            assertThat(technical.create(rid,direct,"alternate-route").receipt().status()).isEqualTo(201);
            jdbc.update("UPDATE workflow_grant SET can_handoff=false,can_process=false WHERE user_id=?",f.user);
            assertCode(()->technical.create(rid,direct,"alternate-route"),"TECH_NOT_FOUND"); return null;
        });
        other.as(()->{ assertCode(()->technical.detail(id),"REQUEST_NOT_FOUND"); return null; });
        var unready=new Fixture(); var unreadyId=grossRequest(unready); technicalGrant(unready,unready,true);
        unready.as(()->{ assertCode(()->technical.view(unreadyId),"TECH_SOURCE_NOT_READY"); return null; });
    }
    @Test void technicalHandoffAuditFailureRollsBackOwnershipAndReceipt() {
        var f=new Fixture(); var receiver=new Fixture(); var rid=technicalRequest(f); var cassette=technicalCassette(f,rid); technicalGrant(receiver,f,true);
        var id=f.as(()->technical.create(rid,tc(cassette,null),"create")).receipt().resourceId();
        f.as(()->{ technical.decide(id,td(0,cassette),"claim","CLAIM"); technical.decide(id,td(1,cassette),"offer","OFFER"); return null; });
        jdbc.execute("CREATE FUNCTION reject_technical_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_technical_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_technical_audit()");
        try { receiver.as(()->{ assertThatThrownBy(()->technical.decide(id,td(2,cassette),"recover","ACCEPT")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; }); }
        finally { jdbc.execute("DROP TRIGGER reject_technical_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_technical_audit()"); }
        assertThat(f.as(()->technical.detail(id)).task()).satisfies(t->{ assertThat(t.version()).isEqualTo(2); assertThat(t.ownerId()).isEqualTo(f.user); assertThat(t.state()).isEqualTo("HANDOFF_PENDING"); });
        receiver.as(()->{
            assertThat(technical.decide(id,td(2,cassette),"recover","ACCEPT").replayed()).isFalse();
            assertThat(technical.decide(id,td(2,cassette),"recover","ACCEPT").replayed()).isTrue();
            assertCode(()->technical.decide(id,new com.pis.processing.TechnicalContracts.Decision(2L,cassette,"Changed"),"recover","ACCEPT"),"IDEMPOTENCY_KEY_REUSED");
            jdbc.update("UPDATE workflow_grant SET can_handoff=false WHERE user_id=? AND scope_id=?",receiver.user,f.scope);
            assertCode(()->technical.decide(id,td(2,cassette),"recover","ACCEPT"),"TECH_NOT_FOUND"); return null;
        });
    }
    @Test void competingTechnicalClaimsAndHandoffAcceptancesHaveOneWinnerAfterObservedLockWait() throws Exception {
        for(String action:List.of("CLAIM","ACCEPT")) {
            var owner=new Fixture(); var a=new Fixture(); var b=new Fixture(); var rid=technicalRequest(owner); var cassette=technicalCassette(owner,rid); technicalGrant(a,owner,true); technicalGrant(b,owner,true);
            var id=owner.as(()->technical.create(rid,tc(cassette,null),"create")).receipt().resourceId();
            long version=action.equals("CLAIM")?0:2;
            if(action.equals("ACCEPT")) owner.as(()->{ technical.decide(id,td(0,cassette),"claim","CLAIM"); technical.decide(id,td(1,cassette),"offer","OFFER"); return null; });
            try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                blocker.setAutoCommit(false);
                try(var statement=blocker.prepareStatement("SELECT id FROM technical_task WHERE id=? FOR UPDATE")) { statement.setObject(1,id); statement.executeQuery().close(); }
                java.util.function.Function<Fixture,String> run=who->who.as(()->{ try { technical.decide(id,td(version,cassette),"race",action); return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
                var first=executor.submit(()->run.apply(a)); var second=executor.submit(()->run.apply(b));
                try {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                    while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE '%SELECT id FROM technical_task WHERE id=%' OR query LIKE '%SELECT id FROM pathology_request WHERE id=%')",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); }
                    assertThat(waiting).isTrue();
                } finally { blocker.rollback(); }
                assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
                assertThat(owner.as(()->technical.detail(id)).task()).satisfies(t->{ assertThat(t.version()).isEqualTo(version+1); assertThat(t.ownerId()).isIn(a.user,b.user); });
            }
        }
    }
    @Test void technicalHttpRequiresAuthenticationDedicatedGrantCsrfAndExactIdentity() throws Exception {
        var f=new Fixture(); var rid=technicalRequest(f); var cassette=technicalCassette(f,rid);
        String password="Synthetic-technical-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var browser=new Browser(); String path="/api/technical/requests/"+rid;
        assertThat(browser.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf(); String create="{\"requestVersion\":2,\"cassetteId\":\""+cassette+"\",\"kind\":\"EMBEDDING\",\"predecessorId\":null,\"reason\":\"Synthetic direct task\"}";
        assertThat(browser.send("POST",path,create,null,false).statusCode()).isEqualTo(403);
        var response=browser.send("POST",path,create,csrf,false); assertThat(response.statusCode()).isEqualTo(201);
        var id=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("receipt").path("resourceId").stringValue();
        var decision="{\"expectedVersion\":0,\"confirmedCassetteId\":\""+UUID.randomUUID()+"\",\"reason\":\"Synthetic wrong identity\"}";
        assertThat(browser.send("POST","/api/technical/tasks/"+id+"/claim",decision,csrf,false).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE workflow_grant SET can_handoff=false,can_process=false WHERE user_id=?",f.user);
        assertThat(browser.send("GET","/api/technical/tasks/"+id,null,null,false).statusCode()).isEqualTo(404);
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
