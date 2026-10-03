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
    @Autowired com.pis.material.MaterialService materials;
    @Autowired com.pis.quality.QualityService quality;
    @Autowired com.pis.worklist.WorklistService worklist;
    @Autowired com.pis.diagnosis.DiagnosisService diagnosis;
    @Autowired com.pis.report.ReportService reports;
    @Autowired WorkflowAccess workflowAccess;
    @Autowired jakarta.validation.Validator validator;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
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
    private void materialGrant(Fixture f) { jdbc.update("UPDATE workflow_grant SET can_material=true WHERE user_id=?",f.user); }
    private record MaterialSetup(UUID request,UUID cassette,UUID embedding,UUID sectioning) { }
    private MaterialSetup materialSetup(Fixture f) {
        var rid=technicalRequest(f); materialGrant(f); var box=technicalCassette(f,rid);
        var tasks=new java.util.ArrayList<UUID>();
        for(var kind:List.of(com.pis.processing.TechnicalContracts.Kind.EMBEDDING,com.pis.processing.TechnicalContracts.Kind.SECTIONING)) {
            tasks.add(f.as(()->{
                var task=technical.create(rid,new com.pis.processing.TechnicalContracts.Create(2L,box,kind,null,"Synthetic material prerequisite"),kind.name()).receipt().resourceId();
                technical.decide(task,td(0,box),"claim-"+kind,"CLAIM"); technical.decide(task,td(1,box),"finish-"+kind,"FINISH_SIMULATION"); return task;
            }));
        }
        return new MaterialSetup(rid,box,tasks.get(0),tasks.get(1));
    }
    private com.pis.material.MaterialContracts.BlockCreate blockInput(MaterialSetup s) { return new com.pis.material.MaterialContracts.BlockCreate(2L,s.embedding(),2L,s.cassette(),"Synthetic block registration"); }
    private com.pis.material.MaterialContracts.Repeat repeatInput(MaterialSetup s,UUID slide,long version) { return new com.pis.material.MaterialContracts.Repeat(version,s.sectioning(),2L,slide,"Synthetic recut/deeper reason"); }
    @Test void materialIdsRecutsDeeperVoidingAndLabelReprintsKeepDistinctStableLineage() {
        var f=new Fixture(); var s=materialSetup(f); printGrant(f);
        f.as(()->{
            var block=materials.block(s.request(),blockInput(s),"block").receipt().resourceId();
            assertThat(materials.block(s.request(),blockInput(s),"block").receipt().resourceId()).isEqualTo(block);
            assertCode(()->materials.block(s.request(),blockInput(s),"another-block"),"MATERIAL_BLOCK_EXISTS");
            var slide=materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic original"),"slide").receipt().resourceId();
            materials.voidMaterial(slide,new com.pis.material.MaterialContracts.VoidMaterial(0L,slide,"Synthetic damaged original"),"void-original");
            var recut=materials.repeat(slide,repeatInput(s,slide,1),"recut","RECUT").receipt().resourceId();
            var deeper=materials.repeat(recut,repeatInput(s,recut,0),"deeper","DEEPER").receipt().resourceId();
            var original=materials.detail(slide).entity(); var cut=materials.detail(recut).entity(); var deep=materials.detail(deeper).entity();
            assertThat(List.of(block,slide,recut,deeper)).doesNotHaveDuplicates(); assertThat(List.of(original.number(),cut.number(),deep.number())).doesNotHaveDuplicates(); assertThat(List.of(original.barcode(),cut.barcode(),deep.barcode())).doesNotHaveDuplicates();
            assertThat(cut.sourceSlideId()).isEqualTo(slide); assertThat(deep.sourceSlideId()).isEqualTo(recut); assertThat(deep.blockId()).isEqualTo(block); assertThat(cut.blockId()).isEqualTo(block); assertThat(original.state()).isEqualTo("VOID");
            assertThat(materials.barcode(deep.barcode()).entity().id()).isEqualTo(deeper);
            var label=labels.createMaterial(deeper,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"label").receipt().resourceId();
            var reprint=labels.change(label,new com.pis.label.LabelContracts.Change(0L,"Synthetic damaged label"),"reprint","REPRINT").receipt().resourceId();
            assertThat(labels.view(reprint).job()).satisfies(j->{ assertThat(j.materialId()).isEqualTo(deeper); assertThat(j.containerId()).isNull(); assertThat(j.barcode()).isEqualTo(deep.barcode()); assertThat(j.parentJobId()).isEqualTo(label); });
            assertThat(labels.verifyMaterial(reprint,new com.pis.label.LabelContracts.MaterialVerify(deeper,deep.barcode())).matches()).isTrue();
            assertCode(()->labels.verifyMaterial(reprint,new com.pis.label.LabelContracts.MaterialVerify(recut,deep.barcode())),"LABEL_IDENTITY_MISMATCH");
            assertThat(materials.view(s.request()).entities()).hasSize(4);
            materials.voidMaterial(block,new com.pis.material.MaterialContracts.VoidMaterial(1L,block,"Synthetic source withdrawn"),"void-block");
            assertThat(materials.view(s.request()).entities()).allSatisfy(e->assertThat(e.state()).isEqualTo("VOID"));
            assertThat(materials.detail(deeper).events()).anySatisfy(e->{ assertThat(e.action()).isEqualTo("SOURCE_VOIDED"); assertThat(e.relatedId()).isEqualTo(block); });
            assertCode(()->labels.view(reprint),"MATERIAL_INACTIVE");
            assertCode(()->labels.change(reprint,new com.pis.label.LabelContracts.Change(0L,"Late"),"late-print","REPRINT"),"MATERIAL_INACTIVE");
            assertCode(()->materials.repeat(slide,repeatInput(s,slide,2),"late-recut","RECUT"),"MATERIAL_INACTIVE");
            assertThatThrownBy(()->jdbc.update("UPDATE material_entity SET barcode=? WHERE id=?",com.pis.label.LabelBarcode.create(UUID.randomUUID()),deeper)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM material_event WHERE material_id=?",deeper)).isInstanceOf(org.springframework.dao.DataAccessException.class); return null;
        });
    }
    @Test void directCytologyHasNoInventedBlockAndCrossCaseSourcesAreRejected() {
        var f=new Fixture(); var rid=grossRequest(f); materialGrant(f); var other=new Fixture(); var foreign=materialSetup(other);
        var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        var foreignBlock=other.as(()->materials.block(foreign.request(),blockInput(foreign),"block")).receipt().resourceId();
        var foreignContainer=other.as(()->service.detail(foreign.request())).containers().getFirst().id();
        f.as(()->{
            var direct=new com.pis.material.MaterialContracts.DirectCreate(2L,cid,"Synthetic explicit direct cytology");
            var id=materials.direct(rid,direct,"direct").receipt().resourceId(); var e=materials.detail(id).entity();
            assertThat(e.route()).isEqualTo("DIRECT_CYTOLOGY"); assertThat(e.containerId()).isEqualTo(cid); assertThat(e.blockId()).isNull(); assertThat(e.cassetteId()).isNull(); assertThat(e.technicalTaskId()).isNull();
            assertCode(()->materials.repeat(id,repeatInput(foreign,id,0),"wrong-route","RECUT"),"MATERIAL_ROUTE_UNSUPPORTED");
            assertCode(()->materials.block(rid,blockInput(foreign),"wrong-task"),"MATERIAL_TASK_NOT_READY");
            assertCode(()->materials.detail(foreignBlock),"REQUEST_NOT_FOUND");
            assertCode(()->materials.direct(rid,new com.pis.material.MaterialContracts.DirectCreate(2L,foreignContainer,"Foreign container"),"foreign-container"),"MATERIAL_SOURCE_MISMATCH");
            assertCode(()->materials.direct(rid,new com.pis.material.MaterialContracts.DirectCreate(2L,UUID.randomUUID(),"Wrong container"),"wrong-container"),"MATERIAL_SOURCE_MISMATCH");
            assertThatThrownBy(()->jdbc.update("INSERT INTO material_entity(id,hospital_id,patient_id,request_id,case_id,kind,route,operation,display_number,barcode,container_id,created_by) SELECT ?,hospital_id,?,request_id,case_id,kind,route,operation,?, ?,container_id,created_by FROM material_entity WHERE id=?",UUID.randomUUID(),other.patient,"DEV-S-SYNTHETIC-WRONG",com.pis.label.LabelBarcode.create(UUID.randomUUID()),id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            jdbc.update("UPDATE workflow_grant SET can_material=false WHERE user_id=?",f.user);
            assertCode(()->materials.direct(rid,direct,"direct"),"MATERIAL_NOT_FOUND"); return null;
        });
        var code=other.as(()->materials.detail(foreignBlock)).entity().barcode();
        f.as(()->{ assertCode(()->materials.barcode(code),"REQUEST_NOT_FOUND"); return null; });
    }
    @Test void materialCreationAndVoidingRollBackIdentityAndCascadeOnAuditFailure() {
        var f=new Fixture(); var s=materialSetup(f);
        var block=f.as(()->materials.block(s.request(),blockInput(s),"block")).receipt().resourceId();
        var input=new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic atomic slide");
        jdbc.execute("CREATE FUNCTION reject_material_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_material_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_material_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->materials.slide(block,input,"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE request_id=?",Long.class,s.request())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_identity WHERE request_id=?",Long.class,s.request())).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER reject_material_audit ON audit_event"); }
        var slide=f.as(()->materials.slide(block,input,"recover")).receipt().resourceId();
        jdbc.execute("CREATE TRIGGER reject_material_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_material_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->materials.voidMaterial(block,new com.pis.material.MaterialContracts.VoidMaterial(1L,block,"Synthetic atomic void"),"void-recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(f.as(()->materials.detail(block)).entity().state()).isEqualTo("ACTIVE"); assertThat(f.as(()->materials.detail(slide)).entity().state()).isEqualTo("ACTIVE");
        } finally { jdbc.execute("DROP TRIGGER reject_material_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_material_audit()"); }
        f.as(()->{ assertThat(materials.slide(block,input,"recover").replayed()).isTrue(); materials.voidMaterial(block,new com.pis.material.MaterialContracts.VoidMaterial(1L,block,"Synthetic atomic void"),"void-recover"); return null; });
    }
    @Test void concurrentBlockNumberingAndRecutsObserveLocksAndAllocateOnlyWinningIdentities() throws Exception {
        for(String action:List.of("BLOCK","RECUT")) {
            var f=new Fixture(); var s=materialSetup(f); UUID parent;
            if(action.equals("RECUT")) {
                var block=f.as(()->materials.block(s.request(),blockInput(s),"initial")).receipt().resourceId();
                parent=f.as(()->materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic initial"),"initial-slide")).receipt().resourceId();
            } else parent=null;
            try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                blocker.setAutoCommit(false);
                try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,s.request()); statement.executeQuery().close(); }
                java.util.function.Function<String,String> run=key->f.as(()->{ try { if(action.equals("BLOCK")) materials.block(s.request(),blockInput(s),key); else materials.repeat(parent,repeatInput(s,parent,0),key,"RECUT"); return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
                var first=executor.submit(()->run.apply("one")); var second=executor.submit(()->run.apply("two"));
                try {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                    while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); } assertThat(waiting).isTrue();
                } finally { blocker.rollback(); }
                assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS",action.equals("BLOCK")?"MATERIAL_BLOCK_EXISTS":"VERSION_CONFLICT");
                var entities=f.as(()->materials.view(s.request())).entities(); assertThat(entities).hasSize(action.equals("BLOCK")?1:3); assertThat(entities).extracting(e->e.number()).doesNotHaveDuplicates(); assertThat(entities).extracting(e->e.barcode()).doesNotHaveDuplicates();
            }
        }
    }
    @Test void sourceVoidingRacingRecutNeverLeavesAnActiveDescendant() throws Exception {
        var f=new Fixture(); var s=materialSetup(f);
        var block=f.as(()->materials.block(s.request(),blockInput(s),"block")).receipt().resourceId();
        var slide=f.as(()->materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic initial"),"slide")).receipt().resourceId();
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,s.request()); statement.executeQuery().close(); }
            var cancel=executor.submit(()->f.as(()->materials.voidMaterial(block,new com.pis.material.MaterialContracts.VoidMaterial(1L,block,"Synthetic race void"),"void")));
            var derive=executor.submit(()->f.as(()->{ try { materials.repeat(slide,repeatInput(s,slide,0),"recut","RECUT"); return "CREATED_THEN_VOIDED"; } catch(ApiException e) { return e.code(); } }));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); } assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(cancel.get(5,TimeUnit.SECONDS).receipt().resourceId()).isEqualTo(block);
            String outcome=derive.get(5,TimeUnit.SECONDS); assertThat(outcome).isIn("CREATED_THEN_VOIDED","VERSION_CONFLICT");
            var entities=f.as(()->materials.view(s.request())).entities(); assertThat(entities).hasSize(outcome.equals("CREATED_THEN_VOIDED")?3:2);
            assertThat(entities).allSatisfy(e->assertThat(e.state()).isEqualTo("VOID"));
            if(outcome.equals("CREATED_THEN_VOIDED")) assertThat(entities.stream().filter(e->e.operation().equals("RECUT")).findFirst().orElseThrow().sourceSlideId()).isEqualTo(slide);
        }
    }
    @Test void materialHttpRequiresDedicatedPermissionCsrfAndRejectsUnknownPaths() throws Exception {
        var f=new Fixture(); var rid=grossRequest(f); var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        String password="Synthetic-material-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var b=new Browser(); var path="/api/materials/requests/"+rid;
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204); String csrf=b.csrf();
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404); materialGrant(f);
        var body="{\"requestVersion\":2,\"confirmedContainerId\":\""+cid+"\",\"reason\":\"Synthetic direct\"}";
        assertThat(b.send("POST",path+"/direct-slides",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/direct-slides",body.replace("\"reason\":","\"ownerId\":\"client\",\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/direct-slides",body,csrf,false).statusCode()).isEqualTo(201);
    }
    private void qcGrant(Fixture f) { jdbc.update("UPDATE workflow_grant SET can_qc=true WHERE user_id=?",f.user); }
    private com.pis.quality.QualityContracts.Assess qa(UUID id,long qc,long material,Long task,com.pis.quality.QualityContracts.Outcome outcome) { return new com.pis.quality.QualityContracts.Assess(qc,material,task,id,"SYN-MATERIAL-QC-1",outcome,"Synthetic quality evidence"); }
    private com.pis.quality.QualityContracts.Decision qd(UUID id,long version) { return new com.pis.quality.QualityContracts.Decision(version,id,"Synthetic quality review reason"); }
    @Test void qualityQuarantineReworkAndNewSlideKeepExactVersionsAndIndependentOutcomes() {
        var f=new Fixture(); var s=materialSetup(f); qcGrant(f); printGrant(f);
        f.as(()->{
            var block=materials.block(s.request(),blockInput(s),"b").receipt().resourceId();
            var slide=materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic original"),"s").receipt().resourceId();
            var pass=qa(slide,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PASS);
            quality.assess(slide,pass,"qc-pass"); assertThat(quality.assess(slide,pass,"qc-pass").replayed()).isTrue();
            var label=labels.createMaterial(slide,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"label").receipt().resourceId();
            assertCode(()->quality.assess(slide,qa(slide,-1,0,2L,com.pis.quality.QualityContracts.Outcome.FAIL),"qc-pass"),"IDEMPOTENCY_KEY_REUSED");
            quality.assess(slide,qa(slide,0,0,2L,com.pis.quality.QualityContracts.Outcome.FAIL),"qc-fail");
            assertCode(()->labels.view(label),"QC_QUARANTINED");
            assertCode(()->technical.create(s.request(),new com.pis.processing.TechnicalContracts.Create(2L,s.cassette(),com.pis.processing.TechnicalContracts.Kind.SECTIONING,null,"Bypass attempt"),"bypass"),"QC_QUARANTINED");
            assertCode(()->quality.decide(slide,qd(slide,1),"release","EXCEPTION_RELEASE"),"QC_EXCEPTION_RELEASE_DISABLED");
            quality.decide(slide,qd(slide,1),"rework","REWORK");
            var repair=quality.detail(slide).item().head().repairTaskId(); assertThat(repair).isNotNull().isNotEqualTo(s.sectioning());
            assertThat(technical.detail(repair).task().reworkOf()).isEqualTo(s.sectioning());
            technical.decide(repair,td(0,s.cassette()),"repair-claim","CLAIM"); technical.decide(repair,td(1,s.cassette()),"repair-finish","FINISH_SIMULATION");
            var next=materials.repeat(slide,new com.pis.material.MaterialContracts.Repeat(0L,repair,2L,slide,"Synthetic corrected recut"),"corrected","RECUT").receipt().resourceId();
            assertThat(materials.detail(next).entity().sourceSlideId()).isEqualTo(slide);
            assertThat(quality.detail(slide).item().effectiveState()).isEqualTo("REWORK_REQUIRED");
            assertThat(quality.detail(next).item().effectiveState()).isEqualTo("NOT_ASSESSED");
            quality.assess(next,qa(next,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PENDING),"pending");
            assertCode(()->labels.createMaterial(next,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"pending-label"),"QC_QUARANTINED");
            quality.assess(next,qa(next,0,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"next-pass");
            var nextLabel=labels.createMaterial(next,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"next-label").receipt().resourceId();
            quality.decide(next,qd(next,1),"revoke","REVOKE");
            assertCode(()->labels.view(nextLabel),"QC_QUARANTINED");
            assertThat(quality.detail(next).assessments()).hasSize(2); assertThat(quality.detail(next).events()).hasSize(3);
            assertThat(quality.detail(slide).events()).anySatisfy(e->{ assertThat(e.action()).isEqualTo("REWORK"); assertThat(e.relatedTaskId()).isEqualTo(repair); assertThat(e.actorId()).isEqualTo(f.user); assertThat(e.reason()).isEqualTo("Synthetic quality review reason"); assertThat(e.version()).isEqualTo(2); });
            assertThatThrownBy(()->jdbc.update("UPDATE quality_assessment SET outcome='PASS' WHERE material_id=?",slide)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM quality_event WHERE material_id=?",slide)).isInstanceOf(org.springframework.dao.DataAccessException.class); return null;
        });
    }
    @Test void qualityIdentityHoldIsStickyAndPermissionsVersionsScopesRemainEnforced() {
        var f=new Fixture(); var s=materialSetup(f); var foreign=new Fixture(); var fs=materialSetup(foreign); qcGrant(foreign);
        var id=f.as(()->materials.block(s.request(),blockInput(s),"b")).receipt().resourceId();
        f.as(()->{ assertCode(()->quality.detail(id),"QC_NOT_FOUND"); return null; }); qcGrant(f);
        var other=foreign.as(()->materials.block(fs.request(),blockInput(fs),"b")).receipt().resourceId();
        f.as(()->{
            assertCode(()->quality.detail(other),"REQUEST_NOT_FOUND");
            assertCode(()->quality.assess(id,qa(other,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"wrong-id"),"QC_IDENTITY_MISMATCH");
            assertCode(()->quality.assess(id,qa(id,-1,0,1L,com.pis.quality.QualityContracts.Outcome.PASS),"old-task"),"VERSION_CONFLICT");
            var input=qa(id,-1,0,2L,com.pis.quality.QualityContracts.Outcome.IDENTITY_MISMATCH); quality.assess(id,input,"identity");
            assertCode(()->quality.assess(id,qa(id,0,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"bypass"),"QC_IDENTITY_LOCKED");
            assertCode(()->quality.decide(id,qd(id,0),"revoke","REVOKE"),"QC_IDENTITY_LOCKED");
            assertCode(()->quality.decide(id,qd(id,0),"release","EXCEPTION_RELEASE"),"QC_EXCEPTION_RELEASE_DISABLED");
            assertCode(()->materials.slide(id,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,id,"Blocked ancestor"),"blocked"),"QC_QUARANTINED");
            assertThatThrownBy(()->jdbc.update("UPDATE quality_head SET state='PASS' WHERE material_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            jdbc.update("UPDATE workflow_grant SET can_qc=false WHERE user_id=?",f.user);
            assertCode(()->quality.assess(id,input,"identity"),"QC_NOT_FOUND"); return null;
        });
    }
    @Test void qualityAuditFailureRollsBackJudgementAndReworkTaskTogether() {
        var f=new Fixture(); var s=materialSetup(f); qcGrant(f);
        var block=f.as(()->materials.block(s.request(),blockInput(s),"b")).receipt().resourceId();
        var input=qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.FAIL);
        jdbc.execute("CREATE FUNCTION reject_quality_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hospital_id='"+f.hospital+"'::uuid AND NEW.operation_code LIKE 'QC_%' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_quality_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_quality_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->quality.assess(block,input,"recover")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM quality_head WHERE material_id=?",Long.class,block)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_quality_audit ON audit_event"); }
        f.as(()->quality.assess(block,input,"recover"));
        jdbc.execute("CREATE TRIGGER reject_quality_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_quality_audit()");
        try {
            f.as(()->{ assertThatThrownBy(()->quality.decide(block,qd(block,0),"repair-recover","REWORK")).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_task WHERE rework_of=?",Long.class,s.embedding())).isZero();
            assertThat(f.as(()->quality.detail(block)).item().head().state()).isEqualTo("FAIL");
        } finally { jdbc.execute("DROP TRIGGER reject_quality_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_quality_audit()"); }
        f.as(()->quality.decide(block,qd(block,0),"repair-recover","REWORK"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM technical_task WHERE rework_of=?",Long.class,s.embedding())).isEqualTo(1);
    }
    @Test void qualityRevocationRacingLabelConsumptionObservesDatabaseLocksAndBlocksFurtherUse() throws Exception {
        var f=new Fixture(); var s=materialSetup(f); qcGrant(f); printGrant(f);
        var block=f.as(()->materials.block(s.request(),blockInput(s),"b")).receipt().resourceId();
        f.as(()->quality.assess(block,qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"pass"));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false); try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,s.request()); statement.executeQuery().close(); }
            var revoke=executor.submit(()->f.as(()->quality.decide(block,qd(block,0),"revoke","REVOKE")));
            var consume=executor.submit(()->f.as(()->{ try { labels.createMaterial(block,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"label"); return "CREATED_BEFORE_REVOKE"; } catch(ApiException e) { return e.code(); } }));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); } assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(revoke.get(5,TimeUnit.SECONDS).receipt().version()).isEqualTo(1);
            String result=consume.get(5,TimeUnit.SECONDS); assertThat(result).isIn("CREATED_BEFORE_REVOKE","QC_QUARANTINED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_job WHERE material_id=?",Long.class,block)).isEqualTo(result.equals("CREATED_BEFORE_REVOKE")?1:0);
            f.as(()->{ assertCode(()->labels.createMaterial(block,new com.pis.label.LabelContracts.MaterialCreate(2L,0L),"after"),"QC_QUARANTINED"); return null; });
        }
    }
    @Test void directQualityUsesNullTaskAndHttpRequiresQcCsrfAndStrictDto() throws Exception {
        var f=new Fixture(); var rid=grossRequest(f); materialGrant(f); qcGrant(f); technicalGrant(f,f,true);
        var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        var id=f.as(()->materials.direct(rid,new com.pis.material.MaterialContracts.DirectCreate(2L,cid,"Synthetic direct QC"),"direct")).receipt().resourceId();
        f.as(()->{
            quality.assess(id,qa(id,-1,0,null,com.pis.quality.QualityContracts.Outcome.PENDING),"pending");
            assertThat(quality.detail(id).assessments().getFirst().taskId()).isNull();
            var initialQc=quality.detail(id); var initialEvent=initialQc.events().getFirst();
            assertThat(initialEvent.version()).isZero(); assertThat(initialEvent.action()).isEqualTo("ASSESS");
            assertThat(initialEvent.assessmentId()).isEqualTo(initialQc.assessments().getFirst().id());
            assertThat(initialEvent.relatedTaskId()).isNull(); assertThat(initialEvent.actorId()).isEqualTo(f.user);
            assertThat(initialEvent.reason()).isEqualTo("Synthetic quality evidence");
            assertCode(()->quality.decide(id,qd(id,0),"rework","REWORK"),"QC_REWORK_UNSUPPORTED");
            quality.assess(id,qa(id,0,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"pass");
            materials.voidMaterial(id,new com.pis.material.MaterialContracts.VoidMaterial(0L,id,"Synthetic withdrawal"),"void");
            assertThat(quality.detail(id).item().effectiveState()).isEqualTo("INVALIDATED");
            assertThat(quality.detail(id).events()).anySatisfy(e->assertThat(e.action()).isEqualTo("INVALIDATE")); return null;
        });
        String password="Synthetic-quality-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var b=new Browser(); String path="/api/quality/materials/"+id;
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204); String csrf=b.csrf();
        String body="{\"expectedVersion\":2,\"confirmedMaterialId\":\""+id+"\",\"reason\":\"Synthetic prohibited release\"}";
        assertThat(b.send("POST",path+"/exception-release",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/exception-release",body,csrf,false).statusCode()).isEqualTo(409);
        assertThat(b.send("POST",path+"/exception-release",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE workflow_grant SET can_qc=false WHERE user_id=?",f.user);
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);
    }
    @Test void normalTechnicalReworkInvalidatesItsMaterialQcWithoutOverwritingJudgement() {
        var f=new Fixture(); var s=materialSetup(f); qcGrant(f);
        f.as(()->{
            var block=materials.block(s.request(),blockInput(s),"b").receipt().resourceId();
            quality.assess(block,qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"pass");
            technical.decide(s.embedding(),td(2,s.cassette()),"ordinary-rework","REWORK");
            var q=quality.detail(block); assertThat(q.item().effectiveState()).isEqualTo("REWORK_REQUIRED");
            assertThat(q.assessments().getFirst().outcome()).isEqualTo("PASS"); assertThat(q.assessments().getFirst().taskVersion()).isEqualTo(2);
            assertThat(q.events()).anySatisfy(e->assertThat(e.action()).isEqualTo("REWORK"));
            assertCode(()->quality.assess(block,qa(block,1,0,3L,com.pis.quality.QualityContracts.Outcome.PASS),"old-source-pass"),"QC_SOURCE_INVALID");
            assertCode(()->materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Blocked stale QC"),"blocked"),"QC_QUARANTINED"); return null;
        });
    }
    @Test void sourceQcRevocationRacingSlideCreationCannotLeaveAConsumableDescendant() throws Exception {
        var f=new Fixture(); var s=materialSetup(f); qcGrant(f); printGrant(f);
        var block=f.as(()->materials.block(s.request(),blockInput(s),"b")).receipt().resourceId();
        f.as(()->quality.assess(block,qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.PASS),"pass"));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false); try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,s.request()); statement.executeQuery().close(); }
            var revoke=executor.submit(()->f.as(()->{ try { quality.decide(block,qd(block,0),"revoke","REVOKE"); return "REVOKED"; } catch(ApiException e) { return e.code(); } }));
            var consume=executor.submit(()->f.as(()->{ try { materials.slide(block,new com.pis.material.MaterialContracts.SlideCreate(0L,s.sectioning(),2L,block,"Synthetic race source"),"slide"); return "CREATED_THEN_INVALIDATED"; } catch(ApiException e) { return e.code(); } }));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); } assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            String decision=revoke.get(5,TimeUnit.SECONDS), creation=consume.get(5,TimeUnit.SECONDS);
            if(decision.equals("REVOKED")) assertThat(creation).isEqualTo("QC_QUARANTINED");
            else { assertThat(decision).isEqualTo("VERSION_CONFLICT"); assertThat(creation).isEqualTo("CREATED_THEN_INVALIDATED"); }
            var entities=f.as(()->materials.view(s.request())).entities(); assertThat(entities).hasSize(creation.equals("QC_QUARANTINED")?1:2);
            for(var entity:entities) f.as(()->{ assertCode(()->labels.createMaterial(entity.id(),new com.pis.label.LabelContracts.MaterialCreate(2L,entity.version()),"blocked-"+entity.id()),"QC_QUARANTINED"); return null; });
            assertThat(f.as(()->quality.detail(block)).item().effectiveState()).isIn("REVOKED","INVALIDATED");
        }
    }
    private com.pis.worklist.WorklistContracts.Page workPage(Fixture f,com.pis.worklist.WorklistContracts.Kind kind,int page,int size) {
        return worklist.list(f.scope,kind,com.pis.worklist.WorklistContracts.State.ALL,com.pis.worklist.WorklistContracts.Due.ALL,com.pis.worklist.WorklistContracts.Sort.OLDEST,page,size);
    }
    private com.pis.worklist.WorklistContracts.Claim wc(UUID id,long version,UUID cassette) { return new com.pis.worklist.WorklistContracts.Claim(id,version,cassette); }
    @Test void worklistCountsPaginationAndTraceUseDomainPermissionsAndScopedRealStates() {
        var f=new Fixture(); var setup=materialSetup(f); printGrant(f);
        var other=new Fixture(); var foreign=materialSetup(other);
        var block=f.as(()->materials.block(setup.request(),blockInput(setup),"block")).receipt().resourceId();
        f.as(()->{
            var page=workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,50); assertThat(page.total()).isEqualTo(4); assertThat(page.items()).hasSize(4);
            assertThat(page.items()).allSatisfy(item->{ assertThat(item.requestId()).isEqualTo(setup.request()); assertThat(item.patientId()).isEqualTo(f.patient); });
            assertCode(()->workPage(f,com.pis.worklist.WorklistContracts.Kind.QUALITY,1,20),"WORKLIST_NOT_FOUND");
            assertCode(()->worklist.trace(foreign.request(),1,20),"REQUEST_NOT_FOUND");
            var first=workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,2); var second=workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,2,2);
            assertThat(first.total()).isEqualTo(second.total()); assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
            assertThat(workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,100,2).items()).isEmpty();
            assertThat(workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,100,2).total()).isEqualTo(4);
            assertCode(()->workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,51),"WORKLIST_PAGE_INVALID"); return null;
        });
        qcGrant(f);
        f.as(()->{
            assertThat(workPage(f,com.pis.worklist.WorklistContracts.Kind.QUALITY,1,20).items().getFirst().state()).isEqualTo("NOT_ASSESSED");
            quality.assess(block,qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.FAIL),"fail");
            var items=workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,50); assertThat(items.total()).isEqualTo(5);
            assertThat(items.items()).anySatisfy(item->{ assertThat(item.kind()).isEqualTo("QUALITY"); assertThat(item.state()).isEqualTo("FAIL"); assertThat(item.blocked()).isTrue(); });
            var trace=worklist.trace(setup.request(),1,50); assertThat(trace.events()).extracting(e->e.domain()).contains("REQUEST","RECEPTION","GROSSING","TECHNICAL","MATERIAL","QUALITY");
            assertThat(trace.events()).extracting(e->e.eventId()).doesNotHaveDuplicates();
            assertThat(trace.events()).anySatisfy(e->{ assertThat(e.domain()).isEqualTo("QUALITY"); assertThat(e.entityId()).isEqualTo(block); assertThat(e.relatedType()).isEqualTo("QUALITY_ASSESSMENT"); assertThat(e.relatedId()).isNotNull(); });
            jdbc.update("UPDATE workflow_grant SET can_qc=false WHERE user_id=?",f.user);
            assertThat(workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,50).total()).isEqualTo(4);
            assertThat(worklist.trace(setup.request(),1,50).events()).noneMatch(e->e.domain().equals("QUALITY"));
            jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
            assertCode(()->workPage(f,com.pis.worklist.WorklistContracts.Kind.ALL,1,50),"WORKLIST_NOT_FOUND"); return null;
        });
    }
    @Test void bulkClaimsReportPartialFailureMaskForeignIdsAndNeverOverrideVersionOrQuarantine() {
        var f=new Fixture(); var rid=technicalRequest(f); var box=technicalCassette(f,rid);
        var other=new Fixture(); var foreign=technicalRequest(other); var fbox=technicalCassette(other,foreign);
        var foreignTask=other.as(()->technical.create(foreign,tc(fbox,null),"foreign")).receipt().resourceId(); technicalGrant(f,other,true);
        var a=f.as(()->technical.create(rid,tc(box,null),"a")).receipt().resourceId(); var b=f.as(()->technical.create(rid,tc(box,null),"b")).receipt().resourceId();
        f.as(()->technical.decide(b,td(0,box),"already","CLAIM"));
        UUID unknown=UUID.randomUUID(); var batch=new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),List.of(wc(a,0,box),wc(b,0,box),wc(foreignTask,0,fbox),wc(unknown,0,box)),"Synthetic bulk ownership");
        f.as(()->{
            var result=worklist.claim(f.scope,batch); assertThat(result.items()).extracting(e->e.outcome()).containsExactly("SUCCESS","REJECTED","REJECTED","REJECTED");
            assertThat(result.items().get(1).code()).isEqualTo("VERSION_CONFLICT");
            assertThat(result.items().get(2).code()).isEqualTo(result.items().get(3).code()).isEqualTo("WORKLIST_ITEM_UNAVAILABLE");
            assertThat(result.items().get(2).status()).isEqualTo(404); assertThat(result.items().get(2).version()).isNull();
            assertThat(worklist.claim(f.scope,batch).items().getFirst().replayed()).isTrue();
            var changed=new com.pis.worklist.WorklistContracts.Batch(batch.batchId(),List.of(wc(a,0,box)),"Changed reason");
            assertThat(worklist.claim(f.scope,changed).items().getFirst().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertCode(()->worklist.claim(f.scope,new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),List.of(wc(a,0,box),wc(a,0,box)),"Duplicate")),"WORKLIST_DUPLICATE_ITEM");
            assertThatThrownBy(()->worklist.claim(f.scope,new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),java.util.Collections.nCopies(21,wc(a,0,box)),"Too many"))).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
            assertThat(technical.detail(foreignTask).task().state()).isEqualTo("QUEUED"); return null;
        });
        var q=new Fixture(); var setup=materialSetup(q); qcGrant(q);
        var waiting=q.as(()->technical.create(setup.request(),tc(setup.cassette(),null),"waiting")).receipt().resourceId();
        var block=q.as(()->materials.block(setup.request(),blockInput(setup),"b")).receipt().resourceId();
        q.as(()->{ quality.assess(block,qa(block,-1,0,2L,com.pis.quality.QualityContracts.Outcome.IDENTITY_MISMATCH),"identity");
            var result=worklist.claim(q.scope,new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),List.of(wc(waiting,0,setup.cassette())),"No bypass"));
            assertThat(result.items().getFirst().code()).isEqualTo("QC_QUARANTINED"); assertThat(technical.detail(waiting).task().state()).isEqualTo("QUEUED"); return null;
        });
    }
    @Test void bulkItemAuditFailurePreservesEarlierSuccessAndRetriesOriginalReceipt() {
        var f=new Fixture(); var rid=technicalRequest(f); var box=technicalCassette(f,rid);
        var a=f.as(()->technical.create(rid,tc(box,null),"a")).receipt().resourceId(); var b=f.as(()->technical.create(rid,tc(box,null),"b")).receipt().resourceId();
        var batch=new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),List.of(wc(a,0,box),wc(b,0,box)),"Synthetic partial commit");
        jdbc.execute("CREATE FUNCTION reject_bulk_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.resource_id='"+b+"'::uuid AND NEW.operation_code='TECH_CLAIM_V1' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_bulk_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_bulk_audit()");
        try { f.as(()->{
            var result=worklist.claim(f.scope,batch); assertThat(result.items()).extracting(e->e.outcome()).containsExactly("SUCCESS","UNKNOWN");
            assertThat(technical.detail(a).task().state()).isEqualTo("ACTIVE"); assertThat(technical.detail(b).task().state()).isEqualTo("QUEUED"); return null;
        }); } finally { jdbc.execute("DROP TRIGGER reject_bulk_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_bulk_audit()"); }
        f.as(()->{ var result=worklist.claim(f.scope,batch); assertThat(result.items()).allSatisfy(e->assertThat(e.outcome()).isEqualTo("SUCCESS")); assertThat(result.items().getFirst().replayed()).isTrue(); assertThat(result.items().get(1).replayed()).isFalse(); return null; });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id IN (?,?) AND operation_code='TECH_CLAIM_V1'",Long.class,a,b)).isEqualTo(2);
    }
    @Test void competingBulkClaimsRecheckEachVersionAfterRealLockWait() throws Exception {
        var f=new Fixture(); var rid=technicalRequest(f); var box=technicalCassette(f,rid);
        var task=f.as(()->technical.create(rid,tc(box,null),"task")).receipt().resourceId();
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false); try(var statement=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { statement.setObject(1,rid); statement.executeQuery().close(); }
            java.util.concurrent.Callable<String> run=()->f.as(()->worklist.claim(f.scope,new com.pis.worklist.WorklistContracts.Batch(UUID.randomUUID(),List.of(wc(task,0,box)),"Synthetic competing batch")).items().getFirst().code());
            var first=executor.submit(run); var second=executor.submit(run);
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean waiting=false;
                while(System.nanoTime()<deadline) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) { waiting=true; break; } Thread.sleep(10); } assertThat(waiting).isTrue();
            } finally { blocker.rollback(); }
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("CLAIMED","VERSION_CONFLICT");
        }
    }
    @Test void worklistHttpPreservesSessionCsrfWhitelistPagingAndCurrentDomainPermissions() throws Exception {
        var f=new Fixture(); var rid=technicalRequest(f); var box=technicalCassette(f,rid);
        var task=f.as(()->technical.create(rid,tc(box,null),"http-task")).receipt().resourceId();
        String password="Synthetic-worklist-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        var b=new Browser(); String path="/api/worklists/scopes/"+f.scope;
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204); String csrf=b.csrf();
        assertThat(b.send("GET",path+"?kind=TECHNICAL&pageSize=50",null,null,false).statusCode()).isEqualTo(200);
        for(String query:List.of("?sort=UNTRUSTED","?page=0","?pageSize=51","?kind=AI"))
            assertThat(b.send("GET",path+query,null,null,false).statusCode()).isEqualTo(400);
        String body="""
            {"batchId":"%s","items":[{"taskId":"%s","expectedVersion":0,"confirmedCassetteId":"%s"}],"reason":"Synthetic HTTP batch"}
            """.formatted(UUID.randomUUID(),task,box);
        assertThat(b.send("POST",path+"/claims",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/claims",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        var claimed=b.send("POST",path+"/claims",body,csrf,false); assertThat(claimed.statusCode()).isEqualTo(200); assertThat(claimed.body()).contains("SUCCESS");
        jdbc.update("UPDATE workflow_grant SET can_process=false,can_handoff=false WHERE user_id=?",f.user);
        assertThat(b.send("GET",path+"?kind=TECHNICAL",null,null,false).statusCode()).isEqualTo(404);
        var trace=b.send("GET","/api/worklists/requests/"+rid+"/trace",null,null,false);
        assertThat(trace.statusCode()).isEqualTo(200); assertThat(trace.body()).doesNotContain("TECHNICAL");
        var denied=b.send("POST",path+"/claims",body,csrf,false); assertThat(denied.statusCode()).isEqualTo(200); assertThat(denied.body()).contains("REJECTED","WORKLIST_ITEM_UNAVAILABLE").doesNotContain("SUCCESS");
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);
    }
    @Test void worklistSqlDeadlineUsesExactBoundaryAndExcludesCompletedItems() {
        var f=new Fixture();
        var draft=f.as(()->service.create(new Create(f.scope,f.encounter,COMPLETE),"deadline")).receipt().resourceId();
        technicalRequest(f); // A received application is ended for REQUEST work.
        var created=jdbc.queryForObject("SELECT created_at FROM pathology_request WHERE id=?",java.sql.Timestamp.class,draft).toInstant();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        for(long micro:List.of(-1L,0L,1L)) {
            var asOf=created.plusSeconds(240*60).plusNanos(micro*1000);
            var fixed=new com.pis.worklist.WorklistService(jdbc,workflowAccess,service,technical,validator,java.time.Clock.fixed(asOf,java.time.ZoneOffset.UTC),240);
            f.as(()->tx.execute(status->{
                var page=fixed.list(f.scope,com.pis.worklist.WorklistContracts.Kind.REQUEST,com.pis.worklist.WorklistContracts.State.ALL,com.pis.worklist.WorklistContracts.Due.OVERDUE,com.pis.worklist.WorklistContracts.Sort.OLDEST,1,50);
                assertThat(page.total()).isEqualTo(micro>0?1:0);
                if(micro>0) assertThat(page.items().getFirst().id()).isEqualTo(draft);
                var all=fixed.list(f.scope,com.pis.worklist.WorklistContracts.Kind.REQUEST,com.pis.worklist.WorklistContracts.State.ALL,com.pis.worklist.WorklistContracts.Due.ALL,com.pis.worklist.WorklistContracts.Sort.OLDEST,1,50);
                assertThat(all.total()).isEqualTo(2);
                assertThat(all.items()).filteredOn(i->!i.active()).singleElement().satisfies(i->{ assertThat(i.overdue()).isFalse(); assertThat(i.dueAt()).isNull(); }); return null;
            }));
        }
    }
    private void diagnosisGrant(Fixture actor,Fixture scope,boolean assign,boolean diagnose) {
        jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read) VALUES(?,?,true) ON CONFLICT(user_id,scope_id) DO NOTHING",actor.user,scope.scope);
        jdbc.update("INSERT INTO diagnosis_grant(user_id,scope_id,can_assign,can_diagnose,qualification) VALUES(?,?,?,?,'SYN-DIAG-ASSIGNMENT-1') ON CONFLICT(user_id,scope_id) DO UPDATE SET can_assign=excluded.can_assign,can_diagnose=excluded.can_diagnose,version=diagnosis_grant.version+1",actor.user,scope.scope,assign,diagnose);
    }
    private record DiagnosisSetup(UUID request,UUID caseId,UUID slide) { }
    private DiagnosisSetup diagnosisSetup(Fixture f) {
        var rid=grossRequest(f); materialGrant(f); qcGrant(f); diagnosisGrant(f,f,true,true);
        var cid=f.as(()->service.detail(rid)).containers().getFirst().id();
        var slide=f.as(()->materials.direct(rid,new com.pis.material.MaterialContracts.DirectCreate(2L,cid,"Synthetic diagnosis source"),"direct")).receipt().resourceId();
        var caseId=f.as(()->materials.detail(slide)).entity().caseId();
        f.as(()->quality.assess(slide,qa(slide,-1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"ready"));
        return new DiagnosisSetup(rid,caseId,slide);
    }
    private com.pis.diagnosis.DiagnosisContracts.Decision dd(UUID id,long version,UUID target) { return new com.pis.diagnosis.DiagnosisContracts.Decision(version,id,target,"Synthetic assignment reason"); }
    @Test void diagnosisAssignClaimTransferRequireExplicitQualifiedScopeAndKeepHistory() {
        var f=new Fixture(); var s=diagnosisSetup(f); var receiver=new Fixture(); var foreign=new Fixture();
        diagnosisGrant(receiver,f,false,true);
        f.as(()->{
            assertCode(()->diagnosis.list(foreign.scope,com.pis.diagnosis.DiagnosisContracts.State.ALL,1,10),"DIAGNOSIS_NOT_FOUND");
            var page=diagnosis.list(f.scope,com.pis.diagnosis.DiagnosisContracts.State.ALL,1,10); assertThat(page.total()).isEqualTo(1); assertThat(page.items().getFirst().ready()).isTrue();
            assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,foreign.user),"bad-target",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"DIAGNOSIS_TARGET_UNAVAILABLE");
            var first=diagnosis.decide(s.caseId(),dd(s.caseId(),-1,f.user),"assign",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN); assertThat(first.receipt().version()).isZero();
            assertThat(diagnosis.decide(s.caseId(),dd(s.caseId(),-1,f.user),"assign",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN).replayed()).isTrue();
            assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,receiver.user),"assign",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"IDEMPOTENCY_KEY_REUSED");
            diagnosis.decide(s.caseId(),dd(s.caseId(),0,null),"claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);
            assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),0,receiver.user),"stale",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER),"VERSION_CONFLICT");
            diagnosis.decide(s.caseId(),dd(s.caseId(),1,receiver.user),"transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);
            assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),2,null),"wrong-owner",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM),"DIAGNOSIS_STATE_CONFLICT"); return null;
        });
        receiver.as(()->{ assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),2,receiver.user),"no-assign",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"DIAGNOSIS_NOT_FOUND"); diagnosis.decide(s.caseId(),dd(s.caseId(),2,null),"accept",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM); var detail=diagnosis.detail(s.caseId()); assertThat(detail.item().ownerId()).isEqualTo(receiver.user); assertThat(detail.events()).extracting(e->e.action()).containsExactly("CLAIM","TRANSFER","CLAIM","ASSIGN"); return null; });
        foreign.as(()->{ assertCode(()->diagnosis.detail(s.caseId()),"DIAGNOSIS_NOT_FOUND"); assertCode(()->diagnosis.detail(UUID.randomUUID()),"DIAGNOSIS_NOT_FOUND"); return null; });
        jdbc.update("UPDATE diagnosis_grant SET revoked_at=statement_timestamp(),version=version+1 WHERE user_id=?",f.user);
        f.as(()->{ assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,f.user),"assign",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"DIAGNOSIS_NOT_FOUND"); return null; });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=? AND operation_code LIKE 'DIAG_%'",Long.class,s.caseId())).isEqualTo(4);
    }
    @Test void diagnosisBlocksExpiredTargetsIdentityQuarantineAndAuditFailureRollsBackEverything() {
        var f=new Fixture(); var s=diagnosisSetup(f); var receiver=new Fixture(); diagnosisGrant(receiver,f,false,true);
        jdbc.update("UPDATE diagnosis_grant SET valid_from=statement_timestamp()-interval '2 hours',valid_until=statement_timestamp()-interval '1 hour' WHERE user_id=?",receiver.user);
        f.as(()->{ assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,receiver.user),"expired",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"DIAGNOSIS_TARGET_UNAVAILABLE"); assertThat(diagnosis.detail(s.caseId()).candidates()).noneMatch(c->c.id().equals(receiver.user)); return null; });
        jdbc.execute("CREATE FUNCTION reject_diag_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code LIKE 'DIAG_%' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_diag_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_diag_audit()");
        try { f.as(()->{ assertThatThrownBy(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,null),"atomic",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM)).isInstanceOf(org.springframework.dao.DataAccessException.class); return null; }); }
        finally { jdbc.execute("DROP TRIGGER reject_diag_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_diag_audit()"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis_assignment WHERE case_id=?",Long.class,s.caseId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis_event WHERE case_id=?",Long.class,s.caseId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code LIKE 'DIAG_%'",Long.class,f.hospital)).isZero();
        f.as(()->{
            diagnosis.decide(s.caseId(),dd(s.caseId(),-1,null),"atomic",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);
            quality.assess(s.slide(),qa(s.slide(),0,0,null,com.pis.quality.QualityContracts.Outcome.IDENTITY_MISMATCH),"identity");
            assertThat(diagnosis.detail(s.caseId()).item().ready()).isFalse();
            assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),0,f.user),"blocked",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER),"DIAGNOSIS_NOT_READY");
            assertThat(diagnosis.detail(s.caseId()).item().version()).isZero(); return null;
        });
    }
    @Test void diagnosisConcurrentAssignClaimTransferHaveOneCasWinnerAfterActualRootLockWait() throws Exception {
        for(var action:com.pis.diagnosis.DiagnosisContracts.Action.values()) {
            var f=new Fixture(); var s=diagnosisSetup(f); var receiver=new Fixture(); diagnosisGrant(receiver,f,false,true);
            if(action==com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER) f.as(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,null),"initial",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM));
            long version=action==com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER?0:-1; UUID target=action==com.pis.diagnosis.DiagnosisContracts.Action.CLAIM?null:receiver.user;
            try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                blocker.setAutoCommit(false); try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) { st.setObject(1,s.request());st.executeQuery().close(); }
                java.util.function.Function<String,String> run=key->(action==com.pis.diagnosis.DiagnosisContracts.Action.CLAIM && key.equals("two")?receiver:f).as(()->{ try { diagnosis.decide(s.caseId(),dd(s.caseId(),version,target),key,action);return "SUCCESS"; } catch(ApiException e) { return e.code(); } });
                var a=executor.submit(()->run.apply("one")); var b=executor.submit(()->run.apply("two"));
                try { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2) {waiting=true;break;} Thread.sleep(10); } assertThat(waiting).isTrue(); } finally { blocker.rollback(); }
                assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            }
            assertThat(f.as(()->diagnosis.detail(s.caseId())).item().version()).isEqualTo(version+1);
        }
    }
    @Test void diagnosisTargetRevocationDuringLockWaitRejectsAssignmentWithoutReceipt() throws Exception {
        var f=new Fixture();var s=diagnosisSetup(f);var receiver=new Fixture();diagnosisGrant(receiver,f,false,true);
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var st=blocker.prepareStatement("UPDATE diagnosis_grant SET revoked_at=statement_timestamp(),version=version+1 WHERE user_id=? AND scope_id=?")) { st.setObject(1,receiver.user);st.setObject(2,f.scope);st.executeUpdate(); }
            var pending=executor.submit(()->f.as(()->{ assertCode(()->diagnosis.decide(s.caseId(),dd(s.caseId(),-1,receiver.user),"revoked-target",com.pis.diagnosis.DiagnosisContracts.Action.ASSIGN),"DIAGNOSIS_TARGET_UNAVAILABLE");return null; }));
            try { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT user_id FROM diagnosis_grant%FOR SHARE%'",Long.class)>0) {waiting=true;break;} Thread.sleep(10); } assertThat(waiting).isTrue();blocker.commit(); } finally { blocker.rollback(); }
            pending.get(5,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis_assignment WHERE case_id=?",Long.class,s.caseId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=? AND operation_code LIKE 'DIAG_%'",Long.class,s.caseId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code LIKE 'DIAG_%'",Long.class,f.hospital)).isZero();
    }
    @Test void diagnosisHttpMaintainsCsrfFieldsAndNoReportEndpoints() throws Exception {
        var f=new Fixture(); var s=diagnosisSetup(f); String path="/api/requests/diagnosis/cases/"+s.caseId(); var b=new Browser();
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String password="Synthetic-diagnosis-http-42!"; jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();
        String body="""
            {"expectedVersion":-1,"confirmedCaseId":"%s","targetUserId":null,"reason":"Synthetic HTTP claim"}
            """.formatted(s.caseId());
        assertThat(b.send("POST",path+"/claim",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/claim",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/claim",body,csrf,false).statusCode()).isEqualTo(200);
        assertThat(b.send("POST",path+"/sign",body,csrf,false).statusCode()).isEqualTo(404);
        assertThat(b.send("GET","/api/requests/diagnosis/scopes/"+f.scope+"?pageSize=51",null,null,false).statusCode()).isEqualTo(400);
    }
    private void reportTemplates() {
        jdbc.update("INSERT INTO report_template(code,version,title,schema_code) VALUES('SYN-REPORT',1,'Synthetic text','SYN-TEXT-1'),('SYN-REPORT',2,'Synthetic structure','SYN-STRUCTURED-2') ON CONFLICT DO NOTHING");
    }
    private com.pis.report.ReportContracts.Save rs(UUID id,long version,int template,String text) {
        var fields=tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"gross\":\"\",\"microscopy\":\"Synthetic manual\",\"diagnosis\":\""+text+"\",\"notes\":\"\""+(template==2?",\"sampleCount\":2,\"manualChecked\":false":"")+"}");
        return new com.pis.report.ReportContracts.Save(version,0L,id,"SYN-REPORT",template,fields,"Synthetic manual revision");
    }
    @Test void reportDraftBindsImmutableTemplatesHistoryAndCurrentClaimedDoctor() {
        reportTemplates();var f=new Fixture();var d=diagnosisSetup(f);var other=new Fixture();diagnosisGrant(other,f,false,true);
        f.as(()->{ assertCode(()->reports.detail(d.caseId()),"DIAGNOSIS_NOT_FOUND");diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);
            assertThat(reports.detail(d.caseId()).current()).isNull();reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic A"),"draft");
            assertThat(reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic A"),"draft").replayed()).isTrue();
            assertCode(()->reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic B"),"draft"),"IDEMPOTENCY_KEY_REUSED");
            assertCode(()->reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic B"),"stale"),"VERSION_CONFLICT");
            reports.save(d.caseId(),rs(d.caseId(),0,2,"Synthetic B"),"v2");
            var history=reports.history(d.caseId(),1).revisions();assertThat(history).extracting(r->r.templateVersion()).containsExactly(2,1);assertThat(history.get(1).fields().get("diagnosis").stringValue()).isEqualTo("Synthetic A");
            return null; });
        other.as(()->{ assertCode(()->reports.detail(d.caseId()),"DIAGNOSIS_NOT_FOUND");assertCode(()->reports.save(d.caseId(),rs(d.caseId(),1,1,"Other"),"unauthorized"),"DIAGNOSIS_NOT_FOUND");return null; });
        f.as(()->{ diagnosis.decide(d.caseId(),dd(d.caseId(),0,other.user),"transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);assertCode(()->reports.history(d.caseId(),1),"DIAGNOSIS_NOT_FOUND");assertCode(()->reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic A"),"draft"),"DIAGNOSIS_NOT_FOUND");return null; });
        other.as(()->{ assertCode(()->reports.detail(d.caseId()),"DIAGNOSIS_NOT_FOUND");diagnosis.decide(d.caseId(),dd(d.caseId(),1,null),"accept",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);assertThat(reports.detail(d.caseId()).current().version()).isEqualTo(1);assertCode(()->reports.save(d.caseId(),rs(d.caseId(),1,1,"Changed"),"old-assignment"),"VERSION_CONFLICT");return null; });
    }
    @Test void reportQcAndAuditFailureCannotCommitPartialRevision() {
        reportTemplates();var f=new Fixture();var d=diagnosisSetup(f);
        f.as(()->{ diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);reports.save(d.caseId(),rs(d.caseId(),-1,1,"Original"),"draft");return null; });
        jdbc.execute("CREATE FUNCTION reject_report_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='REPORT_DRAFT_SAVE_V1' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_report_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_report_audit()");
        try { f.as(()->{ assertThatThrownBy(()->reports.save(d.caseId(),rs(d.caseId(),0,2,"Changed"),"atomic")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null; }); }
        finally { jdbc.execute("DROP TRIGGER reject_report_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_report_audit()"); }
        f.as(()->{ assertThat(reports.detail(d.caseId()).current().version()).isZero();assertThat(reports.history(d.caseId(),1).revisions()).hasSize(1);reports.save(d.caseId(),rs(d.caseId(),0,2,"Changed"),"atomic");quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic revoked"),"revoke", "REVOKE");assertThat(reports.detail(d.caseId()).context().ready()).isFalse();assertCode(()->reports.save(d.caseId(),rs(d.caseId(),1,1,"Blocked"),"qc"),"DIAGNOSIS_NOT_READY");return null; });
    }
    @Test void concurrentReportRevisionsHaveOneWinnerAfterRealRequestLockWait() throws Exception {
        reportTemplates();var f=new Fixture();var d=diagnosisSetup(f);f.as(()->diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")) {st.setObject(1,d.request());st.executeQuery().close();}
            java.util.function.Function<String,String> run=key->f.as(()->{try {reports.save(d.caseId(),rs(d.caseId(),-1,1,key),key);return "SUCCESS";}catch(ApiException e){return e.code();}});
            var a=executor.submit(()->run.apply("one"));var b=executor.submit(()->run.apply("two"));
            try {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }
        assertThat(f.as(()->reports.history(d.caseId(),1)).revisions()).hasSize(1);
    }
    @Test void reportHttpRequiresOwnerCsrfAndRejectsUnknownFieldsAndTemplateVersions() throws Exception {
        reportTemplates();var f=new Fixture();var d=diagnosisSetup(f);f.as(()->diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM));
        var b=new Browser();String path="/api/requests/reports/cases/"+d.caseId();assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        String password="Synthetic-report-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rs(d.caseId(),-1,1,"Synthetic"));
        assertThat(b.send("POST",path+"/draft",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/draft",body.replace("\"gross\":\"\"","\"gross\":1"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/draft",body.replace("\"templateVersion\":1","\"templateVersion\":999"),csrf,false).statusCode()).isEqualTo(409);
        assertThat(b.send("POST",path+"/draft",body.replace("\"reason\":","\"signed\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/draft",body,csrf,false).statusCode()).isEqualTo(200);
        assertThat(b.send("POST",path+"/draft",body,csrf,false).headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(b.send("POST",path+"/sign",body,csrf,false).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE diagnosis_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);assertThat(b.send("POST",path+"/draft",body,csrf,false).statusCode()).isEqualTo(404);
    }
    @org.springframework.beans.factory.annotation.Autowired com.pis.report.ReviewService reviews;
    private void reviewGrant(Fixture actor,Fixture scope) {
        diagnosisGrant(actor,scope,false,true);
        jdbc.update("INSERT INTO report_review_grant(user_id,scope_id,qualification,can_review,can_simulate_sign) VALUES(?,?,'SYN-REPORT-REVIEW-1',true,true)",actor.user,scope.scope);
    }
    private DiagnosisSetup reviewSetup(Fixture f,boolean separateAuthor,boolean separateSigner) {
        reportTemplates();var d=diagnosisSetup(f);reviewGrant(f,f);
        jdbc.update("INSERT INTO report_review_policy(scope_id,code,separate_author_review,separate_review_sign) VALUES(?,'SYN-REVIEW-1',?,?)",f.scope,separateAuthor,separateSigner);
        f.as(()->{diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"review-claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic manual review"),"review-draft");return null;});return d;
    }
    private com.pis.report.ReviewContracts.Decision rd(UUID id,boolean sign) {
        var d=reviews.detail(id);var r=d.draft();return new com.pis.report.ReviewContracts.Decision(d.version(),id,r.id(),r.version(),d.assignmentVersion(),r.templateCode(),r.templateVersion(),d.dependencyToken(),"Synthetic manual review reason",sign);
    }
    @Test void reviewSeparationReturnAndSimulationFreezeKeepExactSnapshots() {
        var f=new Fixture();var d=reviewSetup(f,true,true);var reviewer=new Fixture();reviewGrant(reviewer,f);var foreign=new Fixture();
        f.as(()->{assertCode(()->reviews.decide(d.caseId(),rd(d.caseId(),false),"self",com.pis.report.ReviewContracts.Action.APPROVE),"REPORT_SEPARATION_REQUIRED");return null;});
        foreign.as(()->{assertCode(()->reviews.detail(d.caseId()),"DIAGNOSIS_NOT_FOUND");return null;});
        reviewer.as(()->{var returned=rd(d.caseId(),false);reviews.decide(d.caseId(),returned,"return",com.pis.report.ReviewContracts.Action.RETURN);assertThat(reviews.decide(d.caseId(),returned,"return",com.pis.report.ReviewContracts.Action.RETURN).replayed()).isTrue();assertCode(()->reviews.decide(d.caseId(),rd(d.caseId(),false),"same-revision",com.pis.report.ReviewContracts.Action.APPROVE),"REPORT_REVISION_REQUIRED");return null;});
        f.as(()->reports.save(d.caseId(),rs(d.caseId(),0,2,"Synthetic revised manual"),"returned-new"));
        reviewer.as(()->{reviews.decide(d.caseId(),rd(d.caseId(),false),"approve",com.pis.report.ReviewContracts.Action.APPROVE);assertThat(reviews.detail(d.caseId()).ready()).isTrue();assertCode(()->reviews.decide(d.caseId(),rd(d.caseId(),true),"same-signer",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"REPORT_REVIEW_STALE");return null;});
        f.as(()->{var sign=rd(d.caseId(),true);reviews.decide(d.caseId(),sign,"simulate",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN);assertThat(reviews.decide(d.caseId(),sign,"simulate",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN).replayed()).isTrue();assertThat(reviews.detail(d.caseId()).events()).extracting(e->e.action()).containsExactly(com.pis.report.ReviewContracts.Action.SIMULATE_SIGN,com.pis.report.ReviewContracts.Action.APPROVE,com.pis.report.ReviewContracts.Action.RETURN);assertCode(()->reports.save(d.caseId(),rs(d.caseId(),1,1,"Frozen"),"frozen"),"REPORT_SIMULATED_FROZEN");return null;});
    }
    @Test void reviewInvalidatesAfterDraftQcAssignmentAndQualificationChanges() {
        var f=new Fixture();var d=reviewSetup(f,false,false);var other=new Fixture();reviewGrant(other,f);
        f.as(()->{reviews.decide(d.caseId(),rd(d.caseId(),false),"first",com.pis.report.ReviewContracts.Action.APPROVE);var stale=rd(d.caseId(),true);reports.save(d.caseId(),rs(d.caseId(),0,2,"New synthetic"),"new");assertThat(reviews.detail(d.caseId()).state()).isEqualTo("STALE");assertCode(()->reviews.decide(d.caseId(),stale,"stale",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"VERSION_CONFLICT");reviews.decide(d.caseId(),rd(d.caseId(),false),"second",com.pis.report.ReviewContracts.Action.APPROVE);return null;});
        jdbc.update("UPDATE report_review_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        other.as(()->{assertThat(reviews.detail(d.caseId()).ready()).isFalse();assertCode(()->reviews.decide(d.caseId(),rd(d.caseId(),true),"revoked",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"REPORT_REVIEW_STALE");return null;});
        jdbc.update("UPDATE report_review_grant SET revoked_at=NULL WHERE user_id=?",f.user);
        f.as(()->{assertThat(reviews.detail(d.caseId()).ready()).isFalse();reviews.decide(d.caseId(),rd(d.caseId(),false),"third",com.pis.report.ReviewContracts.Action.APPROVE);quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic QC withdrawal"),"withdraw","REVOKE");assertThat(reviews.detail(d.caseId()).ready()).isFalse();quality.assess(d.slide(),qa(d.slide(),1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"reassess");assertThat(reviews.detail(d.caseId()).ready()).isFalse();reviews.decide(d.caseId(),rd(d.caseId(),false),"fourth",com.pis.report.ReviewContracts.Action.APPROVE);diagnosis.decide(d.caseId(),dd(d.caseId(),0,other.user),"transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);assertThat(reviews.detail(d.caseId()).ready()).isFalse();return null;});
        other.as(()->{diagnosis.decide(d.caseId(),dd(d.caseId(),1,null),"accept",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);assertThat(reviews.detail(d.caseId()).ready()).isFalse();return null;});
    }
    @Test void reviewAuditFailureRollsBackHeadEventAndReceipt() {
        var f=new Fixture();var d=reviewSetup(f,false,false);var input=f.as(()->rd(d.caseId(),false));
        jdbc.execute("CREATE FUNCTION reject_review_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code LIKE 'REPORT_REVIEW_%' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_review_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_review_audit()");
        try { f.as(()->{assertThatThrownBy(()->reviews.decide(d.caseId(),input,"atomic-review",com.pis.report.ReviewContracts.Action.APPROVE)).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;}); }
        finally {jdbc.execute("DROP TRIGGER reject_review_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_review_audit()");}
        f.as(()->{assertThat(reviews.detail(d.caseId()).version()).isEqualTo(-1);assertThat(reviews.detail(d.caseId()).events()).isEmpty();reviews.decide(d.caseId(),input,"atomic-review",com.pis.report.ReviewContracts.Action.APPROVE);return null;});
    }
    @Test void concurrentReviewsAndSimulatedSignaturesHaveOneWinnerUnderRealRootLock() throws Exception {
        for(var pair:List.of(List.of(com.pis.report.ReviewContracts.Action.APPROVE,com.pis.report.ReviewContracts.Action.APPROVE),List.of(com.pis.report.ReviewContracts.Action.SIMULATE_SIGN,com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),List.of(com.pis.report.ReviewContracts.Action.APPROVE,com.pis.report.ReviewContracts.Action.SIMULATE_SIGN))) {
            var f=new Fixture();var d=reviewSetup(f,false,false);
            if(pair.contains(com.pis.report.ReviewContracts.Action.SIMULATE_SIGN)) f.as(()->reviews.decide(d.caseId(),rd(d.caseId(),false),"prepare",com.pis.report.ReviewContracts.Action.APPROVE));
            var first=f.as(()->rd(d.caseId(),pair.get(0)==com.pis.report.ReviewContracts.Action.SIMULATE_SIGN));var second=f.as(()->rd(d.caseId(),pair.get(1)==com.pis.report.ReviewContracts.Action.SIMULATE_SIGN));
            try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}
                java.util.function.Function<Integer,String> run=n->f.as(()->{try{reviews.decide(d.caseId(),n==0?first:second,"race-"+n,pair.get(n));return "SUCCESS";}catch(ApiException e){return e.code();}});
                var a=executor.submit(()->run.apply(0));var b=executor.submit(()->run.apply(1));
                try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
                assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            }
        }
    }
    @Test void reviewHttpRequiresExplicitQualificationAndExactWhitelistedSnapshot() throws Exception {
        var f=new Fixture();var d=reviewSetup(f,false,false);var b=new Browser();String path="/api/requests/reports/cases/"+d.caseId()+"/review";
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(401);
        var initial=f.as(()->rd(d.caseId(),false));String password="Synthetic-review-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var current=json.readTree(b.send("GET",path,null,null,false).body());
        var input=new com.pis.report.ReviewContracts.Decision(initial.expectedVersion(),initial.confirmedCaseId(),initial.revisionId(),initial.draftVersion(),initial.assignmentVersion(),initial.templateCode(),initial.templateVersion(),current.get("dependencyToken").stringValue(),"Synthetic HTTP review",false);String body=json.writeValueAsString(input);
        assertThat(b.send("POST",path+"/APPROVE",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/APPROVE",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/APPROVE",body.replace("Synthetic HTTP review"," "),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/APPROVE",body.replace("\"templateVersion\":1","\"templateVersion\":2"),csrf,false).statusCode()).isEqualTo(409);
        assertThat(b.send("POST",path+"/APPROVE",body,csrf,false).statusCode()).isEqualTo(200);
        assertThat(b.send("POST",path+"/APPROVE",body,csrf,false).headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(b.send("POST",path+"/APPROVE",body.replace("Synthetic HTTP review","Changed"),csrf,false).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE report_review_grant SET can_review=false,can_simulate_sign=false WHERE user_id=?",f.user);
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);assertThat(b.send("POST",path+"/APPROVE",body,csrf,false).statusCode()).isEqualTo(404);
        jdbc.update("INSERT INTO user_role_scope(user_id,role_code,hospital_id,scope_kind,case_filter) VALUES(?,'SECURITY_ADMIN_TEMPLATE',?,'HOSPITAL','ALL_IN_SCOPE')",f.user,f.hospital);
        assertThat(b.send("POST",path+"/SIMULATE_SIGN",body.replace("\"simulationAcknowledged\":false","\"simulationAcknowledged\":true"),csrf,false).statusCode()).isIn(401,404);
    }
    @Test void reviewRacesWithDraftQcAndAssignmentAlwaysLoseReadinessAfterDependencyChanges() throws Exception {
        for(String change:List.of("DRAFT","QC","ASSIGNMENT")) {
            var f=new Fixture();var d=reviewSetup(f,false,false);var other=new Fixture();reviewGrant(other,f);
            var input=f.as(()->rd(d.caseId(),false));
            try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}
                var approval=executor.submit(()->f.as(()->{try{reviews.decide(d.caseId(),input,"racing-review",com.pis.report.ReviewContracts.Action.APPROVE);return "SUCCESS";}catch(ApiException e){return e.code();}}));
                var mutation=executor.submit(()->f.as(()->{
                    if(change.equals("DRAFT")) reports.save(d.caseId(),rs(d.caseId(),0,2,"Concurrent manual text"),"racing-draft");
                    else if(change.equals("QC")) quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic concurrent QC"),"racing-qc","REVOKE");
                    else diagnosis.decide(d.caseId(),dd(d.caseId(),0,other.user),"racing-transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);
                    return "CHANGED";
                }));
                try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%pathology_request%FOR UPDATE%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
                assertThat(mutation.get(5,TimeUnit.SECONDS)).isEqualTo("CHANGED");assertThat(approval.get(5,TimeUnit.SECONDS)).isIn("SUCCESS","VERSION_CONFLICT","REPORT_REVIEW_NOT_READY");
            }
            f.as(()->{assertThat(reviews.detail(d.caseId()).ready()).isFalse();return null;});
        }
    }
    @Test void reviewQualificationRevocationDuringGrantLockWaitCannotAuthorizeCommand() throws Exception {
        var f=new Fixture();var d=reviewSetup(f,false,false);var input=f.as(()->rd(d.caseId(),false));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var st=blocker.prepareStatement("UPDATE report_review_grant SET revoked_at=statement_timestamp() WHERE user_id=? AND scope_id=?")){st.setObject(1,f.user);st.setObject(2,f.scope);assertThat(st.executeUpdate()).isEqualTo(1);}
            var result=executor.submit(()->f.as(()->{try{reviews.decide(d.caseId(),input,"revoke-wait",com.pis.report.ReviewContracts.Action.APPROVE);return "SUCCESS";}catch(ApiException e){return e.code();}}));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%report_review_grant%FOR SHARE%'",Long.class)>0){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.commit();}
            assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo("REPORT_REVIEW_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_review_event WHERE case_id=?",Long.class,d.caseId())).isZero();
    }
    @Test void reviewHistoryIsPagedAndPolicyAbsenceNeverEnablesSimulation() {
        var f=new Fixture();var d=reviewSetup(f,false,false);
        f.as(()->{for(int i=0;i<21;i++) reviews.decide(d.caseId(),rd(d.caseId(),false),"history-"+i,com.pis.report.ReviewContracts.Action.APPROVE);
            assertThat(reviews.history(d.caseId(),1).events()).hasSize(20);assertThat(reviews.history(d.caseId(),2).events()).singleElement().satisfies(e->assertThat(e.version()).isZero());assertCode(()->reviews.history(d.caseId(),0),"REPORT_PAGE_INVALID");return null;});
        var disabled=new Fixture();var other=diagnosisSetup(disabled);reviewGrant(disabled,disabled);
        disabled.as(()->{assertCode(()->reviews.detail(other.caseId()),"REPORT_REVIEW_DISABLED");return null;});
    }
    @org.springframework.beans.factory.annotation.Autowired com.pis.report.OutputService outputs;
    private DiagnosisSetup outputSetup(Fixture f) {
        var d=reviewSetup(f,false,false);jdbc.update("UPDATE workflow_grant SET can_print=true,can_reprint=true WHERE user_id=? AND scope_id=?",f.user,f.scope);
        f.as(()->{reviews.decide(d.caseId(),rd(d.caseId(),false),"output-review",com.pis.report.ReviewContracts.Action.APPROVE);reviews.decide(d.caseId(),rd(d.caseId(),true),"output-simulate",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN);return null;});return d;
    }
    private com.pis.report.OutputContracts.Create oc(UUID id) { var d=outputs.detail(id);return new com.pis.report.OutputContracts.Create(id,d.signatureId(),d.signatureVersion(),d.revisionId(),"Synthetic fixed artifact"); }
    private com.pis.report.OutputContracts.Operation oo(UUID id,UUID request) { var d=outputs.detail(id);var a=d.artifact();return new com.pis.report.OutputContracts.Operation(id,0L,a.sha256(),d.activityVersion(),request,"Synthetic output event"); }
    @Test void fixedPdfRepeatedAccessReprintAndSelfReportKeepSameBytesAndImmutableBinding() {
        var f=new Fixture();var d=outputSetup(f);
        f.as(()->{
            var command=oc(d.caseId());var first=outputs.create(d.caseId(),command,"generate");var artifact=first.receipt().resourceId();
            assertThat(outputs.create(d.caseId(),command,"generate").replayed()).isTrue();assertThat(outputs.create(d.caseId(),command,"ensure-existing").receipt().resourceId()).isEqualTo(artifact);
            var op=oo(d.caseId(),null);var preview=outputs.bytes(d.caseId(),artifact,op,"preview",com.pis.report.OutputContracts.Kind.PREVIEW);var replay=outputs.bytes(d.caseId(),artifact,op,"preview",com.pis.report.OutputContracts.Kind.PREVIEW);assertThat(replay.replayed()).isTrue();assertThat(preview.bytes()).isEqualTo(replay.bytes());assertThat(com.pis.report.SyntheticPdf.sha256(preview.bytes())).isEqualTo(preview.artifact().sha256());
            var download=outputs.bytes(d.caseId(),artifact,oo(d.caseId(),null),"download",com.pis.report.OutputContracts.Kind.DOWNLOAD);assertThat(download.bytes()).isEqualTo(preview.bytes());
            var print=outputs.record(d.caseId(),artifact,oo(d.caseId(),null),"print",com.pis.report.OutputContracts.Kind.PRINT_REQUEST).receipt().resourceId();
            var self=outputs.record(d.caseId(),artifact,oo(d.caseId(),print),"self",com.pis.report.OutputContracts.Kind.USER_REPORTED_PRINTED);assertThat(self.receipt().version()).isEqualTo(3);
            assertCode(()->outputs.record(d.caseId(),artifact,oo(d.caseId(),print),"contradiction",com.pis.report.OutputContracts.Kind.USER_REPORTED_FAILED),"REPORT_PRINT_RESULT_EXISTS");
            outputs.record(d.caseId(),artifact,oo(d.caseId(),print),"reprint",com.pis.report.OutputContracts.Kind.REPRINT_REQUEST);
            assertThat(outputs.history(d.caseId(),artifact,1).events()).hasSize(5);assertThat(outputs.detail(d.caseId()).artifact().sha256()).isEqualTo(preview.artifact().sha256());return null;
        });
        assertThatThrownBy(()->jdbc.update("UPDATE report_artifact SET pdf=convert_to('changed','UTF8') WHERE case_id=?",d.caseId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM report_artifact WHERE case_id=?",d.caseId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE report_output_event SET reason='Changed' WHERE artifact_id IN (SELECT id FROM report_artifact WHERE case_id=?)",d.caseId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void outputCurrentScopePrintRightsOwnResultAndQcAreEnforcedWithoutRegeneratingHistory() {
        var f=new Fixture();var d=outputSetup(f);var other=new Fixture();reviewGrant(other,f);var foreign=new Fixture();
        UUID artifact=f.as(()->outputs.create(d.caseId(),oc(d.caseId()),"create").receipt().resourceId());
        foreign.as(()->{assertCode(()->outputs.detail(d.caseId()),"DIAGNOSIS_NOT_FOUND");return null;});
        var original=f.as(()->outputs.record(d.caseId(),artifact,oo(d.caseId(),null),"print",com.pis.report.OutputContracts.Kind.PRINT_REQUEST).receipt().resourceId());
        other.as(()->{assertCode(()->outputs.record(d.caseId(),artifact,oo(d.caseId(),null),"no-print",com.pis.report.OutputContracts.Kind.PRINT_REQUEST),"REPORT_OUTPUT_NOT_FOUND");return null;});
        jdbc.update("UPDATE workflow_grant SET can_print=true,can_reprint=true WHERE user_id=? AND scope_id=?",other.user,f.scope);
        other.as(()->{assertCode(()->outputs.record(d.caseId(),artifact,oo(d.caseId(),original),"not-mine",com.pis.report.OutputContracts.Kind.USER_REPORTED_PRINTED),"REPORT_OUTPUT_NOT_FOUND");return null;});
        f.as(()->{quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic withdrawn"),"revoke-output","REVOKE");assertThat(outputs.detail(d.caseId()).dependenciesCurrent()).isFalse();assertCode(()->outputs.record(d.caseId(),artifact,oo(d.caseId(),null),"stale-print",com.pis.report.OutputContracts.Kind.PRINT_REQUEST),"REPORT_OUTPUT_STALE");assertThat(outputs.bytes(d.caseId(),artifact,oo(d.caseId(),null),"historical",com.pis.report.OutputContracts.Kind.DOWNLOAD).bytes()).isNotEmpty();return null;});
        jdbc.update("UPDATE report_review_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        f.as(()->{assertCode(()->outputs.history(d.caseId(),artifact,1),"REPORT_REVIEW_NOT_FOUND");return null;});
    }
    @Test void outputCreationAndAccessAuditFailuresRollbackArtifactHistoryAndReceipts() {
        var f=new Fixture();var d=outputSetup(f);var input=f.as(()->oc(d.caseId()));
        jdbc.execute("CREATE FUNCTION reject_output_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code LIKE 'REPORT_ARTIFACT_%' OR NEW.operation_code LIKE 'REPORT_OUTPUT_%' THEN RAISE EXCEPTION 'synthetic audit outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_output_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_output_audit()");
        try {f.as(()->{assertThatThrownBy(()->outputs.create(d.caseId(),input,"atomic-create")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(outputs.detail(d.caseId()).artifact()).isNull();return null;});}
        finally {jdbc.execute("DROP TRIGGER reject_output_audit ON audit_event");}
        UUID artifact=f.as(()->outputs.create(d.caseId(),input,"atomic-create").receipt().resourceId());var op=f.as(()->oo(d.caseId(),null));
        jdbc.execute("CREATE TRIGGER reject_output_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_output_audit()");
        try {f.as(()->{assertThatThrownBy(()->outputs.bytes(d.caseId(),artifact,op,"atomic-read",com.pis.report.OutputContracts.Kind.PREVIEW)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(outputs.detail(d.caseId()).activityVersion()).isEqualTo(-1);assertThat(outputs.history(d.caseId(),artifact,1).events()).isEmpty();return null;});}
        finally {jdbc.execute("DROP TRIGGER reject_output_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_output_audit()");}
        f.as(()->{assertThat(outputs.bytes(d.caseId(),artifact,op,"atomic-read",com.pis.report.OutputContracts.Kind.PREVIEW).bytes()).isNotEmpty();return null;});
    }
    @Test void concurrentArtifactCreationKeepsOneBlobAndConcurrentPrintRequestsHaveOneCasWinner() throws Exception {
        var f=new Fixture();var d=outputSetup(f);var input=f.as(()->oc(d.caseId()));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->f.as(()->outputs.create(d.caseId(),input,"create-one").receipt().resourceId()));var b=executor.submit(()->f.as(()->outputs.create(d.caseId(),input,"create-two").receipt().resourceId()));assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_artifact WHERE case_id=?",Long.class,d.caseId())).isEqualTo(1);
        var op=f.as(()->oo(d.caseId(),null));var artifact=f.as(()->outputs.detail(d.caseId()).artifact().id());
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}
            java.util.function.Function<String,String> run=key->f.as(()->{try{outputs.record(d.caseId(),artifact,op,key,com.pis.report.OutputContracts.Kind.PRINT_REQUEST);return "SUCCESS";}catch(ApiException e){return e.code();}});
            var a=executor.submit(()->run.apply("print-one"));var b=executor.submit(()->run.apply("print-two"));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }
    }
    @Test void outputHttpProtectsBinaryHeadersCsrfHashScopeAndForbidsUnauditedGetOrHardwareStatus() throws Exception {
        var f=new Fixture();var d=outputSetup(f);var artifact=f.as(()->outputs.create(d.caseId(),oc(d.caseId()),"http-output").receipt().resourceId());var input=f.as(()->oo(d.caseId(),null));var b=new Browser();String path="/api/requests/reports/cases/"+d.caseId()+"/output/"+artifact;String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(input);
        assertThat(b.send("POST",path+"/bytes/DOWNLOAD",body,null,false).statusCode()).isEqualTo(401);
        String password="Synthetic-output-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();
        assertThat(b.send("GET",path+"/bytes/DOWNLOAD",null,null,false).statusCode()).isNotEqualTo(200);
        assertThat(b.send("POST",path+"/bytes/DOWNLOAD",body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path+"/bytes/DOWNLOAD",body.replace("\"reason\":","\"path\":\"../../file\",\"reason\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path+"/bytes/DOWNLOAD",body.replace(input.sha256(),"0".repeat(64)),csrf,false).statusCode()).isEqualTo(409);
        assertThat(b.send("POST",path+"/events/PHYSICAL_PRINT_SUCCESS",body,csrf,false).statusCode()).isEqualTo(400);
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path+"/bytes/DOWNLOAD")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf).header("Idempotency-Key","binary-http").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var first=b.client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertThat(first.statusCode()).isEqualTo(200);assertThat(first.headers().firstValue("Content-Type")).contains("application/pdf");assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");assertThat(first.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"synthetic-"+artifact+"-v0.pdf\"");assertThat(com.pis.report.SyntheticPdf.sha256(first.body())).isEqualTo(input.sha256());
        var replay=b.client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(replay.body()).isEqualTo(first.body());
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);assertThat(b.client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
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
    @org.springframework.beans.factory.annotation.Autowired com.pis.report.AmendmentService amendments;
    private com.pis.report.AmendmentContracts.Create ac(UUID id,com.pis.report.AmendmentContracts.Kind kind) {
        var d=amendments.detail(id,1);return new com.pis.report.AmendmentContracts.Create(id,d.version(),d.assignmentVersion(),d.frozenSignatureId(),d.frozenRevisionId(),d.draftVersion(),kind,"Synthetic explicit amendment reason");
    }
    @Test void amendmentRequiresNewReviewAndKeepsOriginalPdfAndFrozenSnapshots() {
        var f=new Fixture();var d=outputSetup(f);
        f.as(()->{
            UUID id=d.caseId();var original=outputs.create(id,oc(id),"original-pdf").receipt().resourceId();var oldOperation=oo(id,null);var oldBytes=outputs.bytes(id,original,oldOperation,"old-read",com.pis.report.OutputContracts.Kind.DOWNLOAD).bytes();
            var before=jdbc.queryForList("SELECT * FROM report_revision WHERE case_id=?",id);var oldEvents=jdbc.queryForList("SELECT * FROM report_review_event WHERE case_id=? ORDER BY version",id);
            var command=ac(id,com.pis.report.AmendmentContracts.Kind.ADDENDUM);var first=amendments.create(id,command,"supplement");assertThat(amendments.create(id,command,"supplement").replayed()).isTrue();
            var chain=amendments.detail(id,1);assertThat(chain.pending()).isTrue();assertThat(chain.canCreate()).isFalse();assertThat(chain.draftId()).isEqualTo(first.receipt().resourceId());assertThat(chain.revisionId()).isNotEqualTo(command.baseRevisionId());assertThat(chain.draftVersion()).isEqualTo(1);
            assertCode(()->amendments.create(id,command,"other-branch"),"VERSION_CONFLICT");assertThat(reviews.detail(id).state()).isEqualTo("DRAFT");assertCode(()->reviews.decide(id,rd(id,true),"inherit-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"REPORT_REVIEW_STALE");
            assertThat(amendments.snapshot(id,command.baseSignatureId()).currentFrozen()).isTrue();
            reports.save(id,rs(id,1,1,"Synthetic amended manual text"),"edit-new");assertCode(()->reports.save(id,rs(id,1,1,"Stale"),"stale-new"),"VERSION_CONFLICT");
            reviews.decide(id,rd(id,false),"new-review",com.pis.report.ReviewContracts.Action.APPROVE);reviews.decide(id,rd(id,true),"new-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN);
            var newArtifact=outputs.create(id,oc(id),"new-pdf").receipt().resourceId();assertThat(newArtifact).isNotEqualTo(original);assertThat(outputs.bytes(id,newArtifact,oo(id,null),"new-download",com.pis.report.OutputContracts.Kind.DOWNLOAD).bytes()).isNotEqualTo(oldBytes);
            assertThat(outputs.bytes(id,original,oldOperation,"old-again",com.pis.report.OutputContracts.Kind.DOWNLOAD).bytes()).isEqualTo(oldBytes);
            var historic=outputs.historical(id,original);var historicalPrint=new com.pis.report.OutputContracts.Operation(id,0L,historic.artifact().sha256(),historic.activityVersion(),null,"Synthetic old print rejected");assertCode(()->outputs.record(id,original,historicalPrint,"old-print",com.pis.report.OutputContracts.Kind.PRINT_REQUEST),"REPORT_OUTPUT_STALE");
            assertThat(amendments.snapshot(id,command.baseSignatureId()).currentFrozen()).isFalse();assertThat(amendments.detail(id,1).nodes().getFirst().downstreamState()).isEqualTo("PENDING_NOT_SENT");
            assertThat(jdbc.queryForList("SELECT * FROM report_revision WHERE case_id=? AND version=0",id)).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM report_review_event WHERE case_id=? AND version<2 ORDER BY version",id)).isEqualTo(oldEvents);
            amendments.create(id,ac(id,com.pis.report.AmendmentContracts.Kind.CORRECTION),"correction");assertThat(amendments.detail(id,1).nodes()).extracting(com.pis.report.AmendmentContracts.Node::kind).containsExactly(com.pis.report.AmendmentContracts.Kind.CORRECTION,com.pis.report.AmendmentContracts.Kind.ADDENDUM);
            assertThatThrownBy(()->jdbc.update("UPDATE report_amendment SET reason='overwrite' WHERE case_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM report_replacement WHERE case_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;
        });
    }
    @Test void amendmentAuthorizationQcAndAuditFailureDoNotAdvanceChain() {
        var f=new Fixture();var d=outputSetup(f);var input=f.as(()->ac(d.caseId(),com.pis.report.AmendmentContracts.Kind.CORRECTION));var foreign=new Fixture();
        foreign.as(()->{assertThatThrownBy(()->amendments.detail(d.caseId(),1)).isInstanceOfAny(ApiException.class,org.springframework.security.access.AccessDeniedException.class);assertThatThrownBy(()->amendments.create(d.caseId(),input,"foreign")).isInstanceOfAny(ApiException.class,org.springframework.security.access.AccessDeniedException.class);return null;});
        jdbc.execute("CREATE FUNCTION reject_amendment_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='REPORT_AMENDMENT_CREATE_V1' THEN RAISE EXCEPTION 'synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_amendment_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_amendment_audit()");
        try {f.as(()->{assertThatThrownBy(()->amendments.create(d.caseId(),input,"atomic-amend")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(amendments.detail(d.caseId(),1).version()).isZero();assertThat(reports.detail(d.caseId()).current().version()).isZero();return null;});}finally{jdbc.execute("DROP TRIGGER reject_amendment_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_amendment_audit()");}
        f.as(()->{amendments.create(d.caseId(),input,"atomic-amend");reviews.decide(d.caseId(),rd(d.caseId(),false),"review-amend",com.pis.report.ReviewContracts.Action.APPROVE);return null;});
        jdbc.update("UPDATE report_review_grant SET can_review=false WHERE user_id=? AND scope_id=?",f.user,f.scope);
        f.as(()->{assertCode(()->reviews.decide(d.caseId(),rd(d.caseId(),true),"revoked-review",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"REPORT_REVIEW_STALE");return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_replacement WHERE case_id=?",Long.class,d.caseId())).isZero();
    }
    @Test void concurrentAmendmentCreationHasOneWinnerAndOneExplicitConflict() throws Exception {
        var f=new Fixture();var d=outputSetup(f);var input=f.as(()->ac(d.caseId(),com.pis.report.AmendmentContracts.Kind.ADDENDUM));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}
            java.util.function.Function<String,String> run=key->f.as(()->{try{amendments.create(d.caseId(),input,key);return "SUCCESS";}catch(ApiException e){return e.code();}});
            var a=executor.submit(()->run.apply("amend-one"));var b=executor.submit(()->run.apply("amend-two"));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_amendment WHERE case_id=?",Long.class,d.caseId())).isEqualTo(1);
    }

    @Test void amendedReviewBecomesStaleOnQcDraftAndAssignmentChanges() {
        for(String change:List.of("QC","DRAFT","ASSIGNMENT")) {
            var f=new Fixture();var d=outputSetup(f);var other=new Fixture();reviewGrant(other,f);
            f.as(()->{amendments.create(d.caseId(),ac(d.caseId(),com.pis.report.AmendmentContracts.Kind.CORRECTION),"new-branch");reviews.decide(d.caseId(),rd(d.caseId(),false),"amend-review",com.pis.report.ReviewContracts.Action.APPROVE);return null;});
            var sign=f.as(()->rd(d.caseId(),true));
            f.as(()->{
                if(change.equals("QC"))quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic changed QC"),"amend-qc","REVOKE");
                else if(change.equals("DRAFT"))reports.save(d.caseId(),rs(d.caseId(),1,2,"Synthetic changed template and fields"),"amend-revision");
                else diagnosis.decide(d.caseId(),dd(d.caseId(),0,other.user),"amend-transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);
                assertThat(reviews.detail(d.caseId()).ready()).isFalse();assertCode(()->reviews.decide(d.caseId(),sign,"stale-new-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN),"VERSION_CONFLICT");return null;
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM report_replacement WHERE case_id=?",Long.class,d.caseId())).isZero();
        }
    }
    @Test void amendmentHttpRequiresCsrfStrictTypeReasonAndCurrentObjectScope() throws Exception {
        var f=new Fixture();var d=outputSetup(f);var input=f.as(()->ac(d.caseId(),com.pis.report.AmendmentContracts.Kind.CORRECTION));var b=new Browser();String path="/api/requests/reports/cases/"+d.caseId()+"/amendments";
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(input);assertThat(b.send("POST",path,body,null,false).statusCode()).isEqualTo(401);String password="Synthetic-amendment-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();assertThat(b.send("POST",path,body,null,false).statusCode()).isEqualTo(403);
        assertThat(b.send("POST",path,body.replace("CORRECTION","OVERWRITE"),csrf,false).statusCode()).isEqualTo(400);assertThat(b.send("POST",path,body.replace("Synthetic explicit amendment reason",""),csrf,false).statusCode()).isEqualTo(400);
        assertThat(b.send("POST",path,body,csrf,false).statusCode()).isEqualTo(200);assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=? AND scope_id=?",f.user,f.scope);assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);assertThat(b.send("GET",path+"/snapshots/"+input.baseSignatureId(),null,null,false).statusCode()).isEqualTo(404);
    }

    @Test void amendmentNewFreezeAndReplacementRollbackTogetherWhenAuditFails() {
        var f=new Fixture();var d=outputSetup(f);
        f.as(()->{amendments.create(d.caseId(),ac(d.caseId(),com.pis.report.AmendmentContracts.Kind.CORRECTION),"atomic-branch");reviews.decide(d.caseId(),rd(d.caseId(),false),"atomic-review",com.pis.report.ReviewContracts.Action.APPROVE);return null;});var sign=f.as(()->rd(d.caseId(),true));
        jdbc.execute("CREATE FUNCTION reject_replacement_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='REPORT_REVIEW_SIMULATE_SIGN_V1' THEN RAISE EXCEPTION 'synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_replacement_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_replacement_audit()");
        try{f.as(()->{assertThatThrownBy(()->reviews.decide(d.caseId(),sign,"atomic-new-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(reviews.detail(d.caseId()).state()).isEqualTo("APPROVED");assertThat(amendments.detail(d.caseId(),1).nodes().getFirst().newSignatureId()).isNull();return null;});}finally{jdbc.execute("DROP TRIGGER reject_replacement_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_replacement_audit()");}
        f.as(()->{reviews.decide(d.caseId(),sign,"atomic-new-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN);assertThat(amendments.detail(d.caseId(),1).nodes().getFirst().downstreamState()).isEqualTo("PENDING_NOT_SENT");return null;});
    }

    @org.springframework.beans.factory.annotation.Autowired com.pis.report.DeliveryService deliveries;
    private com.pis.report.DeliveryContracts.Command dc(UUID id,UUID delivery) {
        var a=outputs.detail(id).artifact();var item=delivery==null?null:deliveries.detail(id,1).items().stream().filter(i->i.id().equals(delivery)).findFirst().orElseThrow();return new com.pis.report.DeliveryContracts.Command(id,a.id(),a.signatureId(),a.revisionId(),a.sha256(),"LOCAL_SIM",item==null?0:item.version(),item==null?null:item.attemptId(),"Synthetic local delivery");
    }
    @Test void deliveryRequiresRealLocalInboxBeforeAckAndDeduplicatesReceiver() {
        var f=new Fixture();var d=outputSetup(f);f.as(()->{
            UUID id=d.caseId();outputs.create(id,oc(id),"delivery-artifact");var c=dc(id,null);UUID op=deliveries.enqueue(id,c,"queue").receipt().resourceId();assertThat(deliveries.enqueue(id,c,"queue").replayed()).isTrue();
            deliveries.step(id,op,dc(id,op),"claim",com.pis.report.DeliveryContracts.Action.CLAIM);var attempt=dc(id,op);
            assertCode(()->deliveries.step(id,op,attempt,"premature-ack",com.pis.report.DeliveryContracts.Action.ACK),"DELIVERY_ACK_MISMATCH");assertThat(jdbc.queryForObject("SELECT count(*) FROM report_delivery_rejection WHERE case_id=? AND code='DELIVERY_ACK_MISMATCH'",Long.class,id)).isEqualTo(1);
            var wrong=new com.pis.report.DeliveryContracts.Command(id,attempt.artifactId(),attempt.signatureId(),attempt.revisionId(),"0".repeat(64),"LOCAL_SIM",attempt.expectedVersion(),attempt.attemptId(),"Synthetic wrong hash");assertCode(()->deliveries.step(id,op,wrong,"bad-ack",com.pis.report.DeliveryContracts.Action.ACK),"DELIVERY_BINDING");
            for(var badInput:List.of(new com.pis.report.DeliveryContracts.Command(id,attempt.artifactId(),UUID.randomUUID(),attempt.revisionId(),attempt.sha256(),"LOCAL_SIM",attempt.expectedVersion(),attempt.attemptId(),"Synthetic wrong signature"),new com.pis.report.DeliveryContracts.Command(id,attempt.artifactId(),attempt.signatureId(),UUID.randomUUID(),attempt.sha256(),"LOCAL_SIM",attempt.expectedVersion(),attempt.attemptId(),"Synthetic wrong revision")))assertCode(()->deliveries.step(id,op,badInput,"invalid-binding-"+UUID.randomUUID(),com.pis.report.DeliveryContracts.Action.ACK),"DELIVERY_BINDING");
            var destination=new com.pis.report.DeliveryContracts.Command(id,attempt.artifactId(),attempt.signatureId(),attempt.revisionId(),attempt.sha256(),"EXTERNAL",attempt.expectedVersion(),attempt.attemptId(),"Synthetic invalid destination");assertThatThrownBy(()->deliveries.step(id,op,destination,"invalid-endpoint",com.pis.report.DeliveryContracts.Action.ACK)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
            deliveries.step(id,op,attempt,"receive",com.pis.report.DeliveryContracts.Action.RECEIVE);assertThat(deliveries.step(id,op,attempt,"receive",com.pis.report.DeliveryContracts.Action.RECEIVE).replayed()).isTrue();deliveries.step(id,op,dc(id,op),"repeat-receive",com.pis.report.DeliveryContracts.Action.RECEIVE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM report_local_inbox WHERE delivery_id=?",Long.class,op)).isEqualTo(1);deliveries.step(id,op,dc(id,op),"ack",com.pis.report.DeliveryContracts.Action.ACK);assertThat(deliveries.detail(id,1).items().getFirst().state()).isEqualTo("ACKED");deliveries.step(id,op,dc(id,op),"reconcile",com.pis.report.DeliveryContracts.Action.RECONCILE);assertThat(deliveries.detail(id,1).items().getFirst().state()).isEqualTo("RECONCILED");assertThat(deliveries.detail(id,1).caStatus()).isEqualTo("NOT_CONFIGURED");assertThat(com.pis.integration.CaAdapter.unavailable(true).status()).isEqualTo(com.pis.integration.CaAdapter.Status.UNVERIFIED);
            assertThatThrownBy(()->jdbc.update("DELETE FROM report_local_inbox WHERE delivery_id=?",op)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;
        });
    }
    @Test void deliveryTimeoutRejectsLateAckAndPoisonTerminatesWithRollbackOnAuditFailure() {
        var f=new Fixture();var d=outputSetup(f);f.as(()->{
            UUID id=d.caseId();outputs.create(id,oc(id),"timeout-artifact");UUID op=deliveries.enqueue(id,dc(id,null),"timeout-queue").receipt().resourceId();deliveries.step(id,op,dc(id,op),"timeout-claim",com.pis.report.DeliveryContracts.Action.CLAIM);
            var c=dc(id,op);assertCode(()->deliveries.step(id,op,c,"early-timeout",com.pis.report.DeliveryContracts.Action.TIMEOUT),"DELIVERY_STALE_ATTEMPT");
            jdbc.update("UPDATE report_delivery_outbox SET version=version+1,lease_until=statement_timestamp()-interval '1 second' WHERE delivery_id=?",op);
            assertCode(()->deliveries.step(id,op,dc(id,op),"late-ack",com.pis.report.DeliveryContracts.Action.ACK),"DELIVERY_STALE_ATTEMPT");deliveries.step(id,op,dc(id,op),"timeout",com.pis.report.DeliveryContracts.Action.TIMEOUT);
            var retry=dc(id,op);var claim=new com.pis.report.DeliveryContracts.Command(id,retry.artifactId(),retry.signatureId(),retry.revisionId(),retry.sha256(),retry.destination(),retry.expectedVersion(),null,retry.reason());assertCode(()->deliveries.step(id,op,claim,"early-retry",com.pis.report.DeliveryContracts.Action.CLAIM),"DELIVERY_NOT_READY");return null;
        });
        var f2=new Fixture();var d2=outputSetup(f2);UUID op=f2.as(()->{outputs.create(d2.caseId(),oc(d2.caseId()),"poison-artifact");var result=deliveries.enqueue(d2.caseId(),dc(d2.caseId(),null),"poison-queue");deliveries.step(d2.caseId(),result.receipt().resourceId(),dc(d2.caseId(),result.receipt().resourceId()),"poison-claim",com.pis.report.DeliveryContracts.Action.CLAIM);return result.receipt().resourceId();});
        jdbc.execute("CREATE FUNCTION reject_delivery_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='DELIVERY_POISON_V1' THEN RAISE EXCEPTION 'synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_delivery_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_delivery_audit()");
        var input=f2.as(()->dc(d2.caseId(),op));try{f2.as(()->{assertThatThrownBy(()->deliveries.step(d2.caseId(),op,input,"poison",com.pis.report.DeliveryContracts.Action.POISON)).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_delivery_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_delivery_audit()");}
        f2.as(()->{deliveries.step(d2.caseId(),op,input,"poison",com.pis.report.DeliveryContracts.Action.POISON);assertThat(deliveries.detail(d2.caseId(),1).items().getFirst().state()).isEqualTo("DEAD");return null;});
        jdbc.update("UPDATE report_review_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f2.user);f2.as(()->{assertCode(()->deliveries.detail(d2.caseId(),1),"REPORT_REVIEW_NOT_FOUND");assertCode(()->deliveries.step(d2.caseId(),op,input,"poison",com.pis.report.DeliveryContracts.Action.POISON),"REPORT_REVIEW_NOT_FOUND");return null;});
    }

    @Test void concurrentDeliveryClaimsHaveExactlyOneAttemptAndOldCasCannotProgress() throws Exception {
        var f=new Fixture();var d=outputSetup(f);UUID op=f.as(()->{outputs.create(d.caseId(),oc(d.caseId()),"race-artifact");return deliveries.enqueue(d.caseId(),dc(d.caseId(),null),"race-queue").receipt().resourceId();});var input=f.as(()->dc(d.caseId(),op));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.function.Function<String,String> claim=k->f.as(()->{try{deliveries.step(d.caseId(),op,input,k,com.pis.report.DeliveryContracts.Action.CLAIM);return "SUCCESS";}catch(ApiException e){return e.code();}});
            var a=pool.submit(()->claim.apply("worker-a"));var b=pool.submit(()->claim.apply("worker-b"));assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM report_delivery_outbox WHERE delivery_id=?",Integer.class,op)).isEqualTo(1);
    }
    @Test void replacementDeliveryChecksReceiverPredecessorAndCannotSilentlyOverwrite() {
        for(boolean received:List.of(false,true)) {
            var f=new Fixture();var d=outputSetup(f);f.as(()->{
                UUID id=d.caseId();outputs.create(id,oc(id),"old-artifact");UUID old=deliveries.enqueue(id,dc(id,null),"old-queue").receipt().resourceId();deliveries.step(id,old,dc(id,old),"old-claim",com.pis.report.DeliveryContracts.Action.CLAIM);
                if(received){deliveries.step(id,old,dc(id,old),"old-receive",com.pis.report.DeliveryContracts.Action.RECEIVE);deliveries.step(id,old,dc(id,old),"old-ack",com.pis.report.DeliveryContracts.Action.ACK);}
                amendments.create(id,ac(id,com.pis.report.AmendmentContracts.Kind.CORRECTION),"delivery-correction");reviews.decide(id,rd(id,false),"correction-review",com.pis.report.ReviewContracts.Action.APPROVE);reviews.decide(id,rd(id,true),"correction-sign",com.pis.report.ReviewContracts.Action.SIMULATE_SIGN);outputs.create(id,oc(id),"new-artifact");UUID next=deliveries.enqueue(id,dc(id,null),"new-queue").receipt().resourceId();deliveries.step(id,next,dc(id,next),"new-claim",com.pis.report.DeliveryContracts.Action.CLAIM);deliveries.step(id,next,dc(id,next),"new-receive",com.pis.report.DeliveryContracts.Action.RECEIVE);
                var item=deliveries.detail(id,1).items().stream().filter(i->i.id().equals(next)).findFirst().orElseThrow();assertThat(item.state()).isEqualTo(received?"ATTEMPTING":"REJECTED");assertThat(jdbc.queryForObject("SELECT count(*) FROM report_local_inbox WHERE delivery_id=?",Long.class,next)).isEqualTo(received?1:0);
                assertThat(jdbc.queryForObject("SELECT downstream_state FROM report_replacement WHERE case_id=?",String.class,id)).isEqualTo("PENDING_NOT_SENT");return null;
            });
        }
    }

    @Test void durableInboxSurvivesLostAckAndLeaseRecoveryWithoutDuplicateReplacement() throws Exception {
        var f=new Fixture();var d=outputSetup(f);UUID id=d.caseId();UUID op=f.as(()->{outputs.create(id,oc(id),"recover-artifact");var o=deliveries.enqueue(id,dc(id,null),"recover-queue").receipt().resourceId();deliveries.step(id,o,dc(id,o),"recover-claim",com.pis.report.DeliveryContracts.Action.CLAIM);deliveries.step(id,o,dc(id,o),"recover-receive",com.pis.report.DeliveryContracts.Action.RECEIVE);return o;});
        var old=f.as(()->dc(id,op));jdbc.update("UPDATE report_delivery_outbox SET version=version+1,lease_until=statement_timestamp()-interval '1 second' WHERE delivery_id=?",op);
        f.as(()->{deliveries.step(id,op,dc(id,op),"recover-timeout",com.pis.report.DeliveryContracts.Action.TIMEOUT);return null;});Thread.sleep(5100);
        f.as(()->{var retry=dc(id,op);var claim=new com.pis.report.DeliveryContracts.Command(id,retry.artifactId(),retry.signatureId(),retry.revisionId(),retry.sha256(),retry.destination(),retry.expectedVersion(),null,retry.reason());deliveries.step(id,op,claim,"recover-next",com.pis.report.DeliveryContracts.Action.CLAIM);var fresh=dc(id,op);assertThat(fresh.attemptId()).isNotEqualTo(old.attemptId());var late=new com.pis.report.DeliveryContracts.Command(id,fresh.artifactId(),fresh.signatureId(),fresh.revisionId(),fresh.sha256(),fresh.destination(),fresh.expectedVersion(),old.attemptId(),"Synthetic stale worker");assertCode(()->deliveries.step(id,op,late,"late-old-ack",com.pis.report.DeliveryContracts.Action.ACK),"DELIVERY_STALE_ATTEMPT");deliveries.step(id,op,fresh,"recover-receive-again",com.pis.report.DeliveryContracts.Action.RECEIVE);deliveries.step(id,op,dc(id,op),"recover-ack",com.pis.report.DeliveryContracts.Action.ACK);assertThat(jdbc.queryForObject("SELECT count(*) FROM report_local_inbox WHERE delivery_id=?",Long.class,op)).isEqualTo(1);return null;});
    }

}
