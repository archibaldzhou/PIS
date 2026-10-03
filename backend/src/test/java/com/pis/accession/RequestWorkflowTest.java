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
    static final java.nio.file.Path STORAGE_ROOT=storageRoot();
    private static java.nio.file.Path storageRoot(){try{return java.nio.file.Files.createTempDirectory("pis-t28-integration-");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { DB.register(registry); registry.add("pis.storage.local-root",()->STORAGE_ROOT.toString()); }
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
        assertAnonymousPostSecurity(b,path+"/bytes/DOWNLOAD",body);
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
    private void assertAnonymousPostSecurity(Browser browser,String path,String body) throws Exception {
        // CSRF is checked before authentication; each boundary has one exact contract.
        assertHttpError(browser.send("POST",path,body,null,false),403,"CSRF_INVALID");
        assertHttpError(browser.send("POST",path,body,"invalid-synthetic-csrf",false),403,"CSRF_INVALID");
        assertHttpError(browser.send("POST",path,body,browser.csrf(),false),401,"UNAUTHENTICATED");
    }
    private void assertHttpError(java.net.http.HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body()).path("code").stringValue()).isEqualTo(code);
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
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(input);assertAnonymousPostSecurity(b,path,body);String password="Synthetic-amendment-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf();assertThat(b.send("POST",path,body,null,false).statusCode()).isEqualTo(403);
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

    @Autowired com.pis.frozen.FrozenService frozen;
    private void frozenGrant(Fixture actor,Fixture owner){
        diagnosisGrant(actor,owner,false,true);
        jdbc.update("INSERT INTO frozen_grant(scope_id,user_id,qualification,can_record,can_review,can_qc) VALUES(?,?,'SYN-FROZEN-1',true,true,true)",owner.scope,actor.user);
    }
    private com.pis.frozen.FrozenContracts.Command fc(UUID id,UUID related,UUID target,String text){
        var d=frozen.detail(id,1);var h=d.head();return new com.pis.frozen.FrozenContracts.Command(id,h==null?-1L:h.version(),java.time.OffsetDateTime.parse("2026-01-01T12:00:00Z"),"UTC","Synthetic manual reason",d.sources().getFirst().id(),"Synthetic frozen site",h==null?null:h.revisionId(),related,target,text,null,null,d.reviewToken());
    }
    private void frozenStep(UUID id,String action,UUID related,UUID target,String text){frozen.command(id,com.pis.frozen.FrozenContracts.Action.valueOf(action),fc(id,related,target,text),UUID.randomUUID().toString());}
    private UUID frozenSetup(Fixture owner,Fixture reviewer){
        var request=grossRequest(owner);frozenGrant(owner,owner);if(reviewer!=null)frozenGrant(reviewer,owner);
        UUID id=owner.as(()->frozen.cases(request)).getFirst().id();
        owner.as(()->{for(String action:List.of("RECEIVE","PREPARE","QC_PASS","DRAFT"))frozenStep(id,action,null,null,"Synthetic human text");return null;});return id;
    }
    @Test void frozenIsIndependentOfRoutineRoutingAndCommunicationProofIsSeparate(){
        var owner=new Fixture();var reviewer=new Fixture();UUID id=frozenSetup(owner,reviewer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis_assignment WHERE case_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_entity WHERE case_id=?",Long.class,id)).isZero();
        owner.as(()->{assertCode(()->frozenStep(id,"REVIEW",null,null,""),"FROZEN_SEPARATION_REQUIRED");assertCode(()->frozenStep(id,"COMMUNICATE",null,reviewer.user,""),"FROZEN_REVIEW_INVALIDATED");return null;});
        reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        UUID communication=owner.as(()->{assertThat(frozen.detail(id,1).reviewValid()).isTrue();frozenStep(id,"COMMUNICATE",null,reviewer.user,"Synthetic local simulation only");return frozen.detail(id,1).events().getFirst().id();});
        owner.as(()->{assertCode(()->frozenStep(id,"READBACK",communication,null,"Synthetic evidence"),"FROZEN_COMMUNICATION_MISMATCH");return null;});
        reviewer.as(()->{
            assertCode(()->frozenStep(id,"CONFIRM",communication,null,"Synthetic confirmation"),"FROZEN_READBACK_REQUIRED");
            frozenStep(id,"READBACK",communication,null,"Synthetic independently recorded readback");
            assertThat(frozen.detail(id,1).events()).noneMatch(e->e.action()==com.pis.frozen.FrozenContracts.Action.CONFIRM);
            var command=fc(id,communication,null,"Synthetic independent confirmation");
            frozen.command(id,com.pis.frozen.FrozenContracts.Action.CONFIRM,command,"confirm-once");
            assertThat(frozen.command(id,com.pis.frozen.FrozenContracts.Action.CONFIRM,command,"confirm-once").replayed()).isTrue();
            assertCode(()->frozenStep(id,"CONFIRM",communication,null,"Synthetic duplicate"),"FROZEN_ALREADY_RECORDED");return null;
        });
        assertThatThrownBy(()->jdbc.update("UPDATE frozen_event SET content='changed' WHERE case_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void frozenRevocationQcDraftAndHandoffInvalidateExactReviews(){
        var owner=new Fixture();var reviewer=new Fixture();UUID id=frozenSetup(owner,reviewer);
        reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        jdbc.update("UPDATE frozen_grant SET can_review=false WHERE scope_id=? AND user_id=?",owner.scope,reviewer.user);
        jdbc.update("UPDATE frozen_grant SET can_review=true WHERE scope_id=? AND user_id=?",owner.scope,reviewer.user);
        owner.as(()->{assertThat(frozen.detail(id,1).reviewValid()).isFalse();return null;});
        reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        owner.as(()->{frozenStep(id,"QC_FAIL",null,null,"");assertThat(frozen.detail(id,1).reviewValid()).isFalse();assertCode(()->frozenStep(id,"DRAFT",null,null,"Synthetic"),"FROZEN_NOT_READY");frozenStep(id,"QC_PASS",null,null,"");return null;});
        reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        owner.as(()->{frozenStep(id,"DRAFT",null,null,"Synthetic revised human text");assertThat(frozen.detail(id,1).reviewValid()).isFalse();return null;});
        reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        owner.as(()->{frozenStep(id,"TRANSFER",null,reviewer.user,"");assertThat(frozen.detail(id,1).reviewValid()).isFalse();assertCode(()->frozenStep(id,"DRAFT",null,null,"Synthetic old holder"),"FROZEN_NOT_FOUND");return null;});
        reviewer.as(()->{assertCode(()->frozenStep(id,"DRAFT",null,null,"Synthetic before claim"),"FROZEN_NOT_FOUND");frozenStep(id,"CLAIM",null,null,"");frozenStep(id,"DRAFT",null,null,"Synthetic new holder");return null;});
        owner.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        jdbc.update("UPDATE diagnosis_grant SET revoked_at=statement_timestamp() WHERE scope_id=? AND user_id=?",owner.scope,reviewer.user);
        owner.as(()->{assertThat(frozen.detail(id,1).reviewValid()).isFalse();return null;});
    }
    @Test void frozenTimeCorrectionIsAppendOnlyAndCannotReverseDependentTimes(){
        var owner=new Fixture();var reviewer=new Fixture();UUID id=frozenSetup(owner,reviewer);reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        owner.as(()->{
            var before=frozen.detail(id,1);var input=fc(id,before.head().receivedId(),null,"");
            var earlier=new com.pis.frozen.FrozenContracts.Command(id,input.expectedVersion(),java.time.OffsetDateTime.parse("2026-01-01T11:00:00Z"),"UTC","Synthetic late correction",null,null,input.resultId(),input.relatedId(),null,"",null,null,null);
            frozen.command(id,com.pis.frozen.FrozenContracts.Action.CORRECT_TIME,earlier,"earlier-time");
            var after=frozen.detail(id,1);assertThat(after.elapsedSeconds()).isEqualTo(3600L);assertThat(after.reviewValid()).isFalse();assertThat(after.events()).anyMatch(e->e.id().equals(before.head().receivedId()));
            var invalid=new com.pis.frozen.FrozenContracts.Command(id,after.head().version(),java.time.OffsetDateTime.parse("2026-01-01T13:00:00Z"),"UTC","Synthetic impossible order",null,null,null,after.head().receivedId(),null,"",null,null,null);
            assertCode(()->frozen.command(id,com.pis.frozen.FrozenContracts.Action.CORRECT_TIME,invalid,"later-time"),"FROZEN_TIME_ORDER");return null;
        });
    }
    @Test void frozenAuditFailureRollsBackEventHeadAndIdempotency(){
        var owner=new Fixture();var reviewer=new Fixture();UUID id=frozenSetup(owner,reviewer);var before=owner.as(()->frozen.detail(id,1));var input=owner.as(()->fc(id,null,null,"Synthetic next draft"));
        jdbc.execute("CREATE FUNCTION reject_frozen_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='FROZEN_DRAFT_V1' THEN RAISE EXCEPTION 'synthetic audit outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_frozen_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_frozen_audit()");
        try{owner.as(()->{assertThatThrownBy(()->frozen.command(id,com.pis.frozen.FrozenContracts.Action.DRAFT,input,"atomic-frozen")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_frozen_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_frozen_audit()");}
        assertThat(owner.as(()->frozen.detail(id,1)).head()).isEqualTo(before.head());assertThat(owner.as(()->frozen.detail(id,1)).events()).isEqualTo(before.events());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code='FROZEN_DRAFT_V1'",Long.class,owner.hospital)).isEqualTo(1); // only initial draft
        owner.as(()->{assertThat(frozen.command(id,com.pis.frozen.FrozenContracts.Action.DRAFT,input,"atomic-frozen").replayed()).isFalse();return null;});
    }
    @Test void frozenDraftAndReviewRacesHaveOneCasWinnerAfterObservedRootLockWait() throws Exception {
        for(boolean reviewRace:List.of(false,true)){
        var owner=new Fixture();var reviewer=new Fixture();UUID id=frozenSetup(owner,reviewer);UUID request=jdbc.queryForObject("SELECT request_id FROM pathology_case WHERE id=?",UUID.class,id);var input=owner.as(()->fc(id,null,null,"Synthetic concurrent draft"));var reviewInput=reviewer.as(()->fc(id,null,null,"Synthetic concurrent draft"));
        try(var blocker=DB.connection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,request);st.executeQuery().close();}
            java.util.function.Function<String,String> run=k->(reviewRace&&k.endsWith("b")?reviewer:owner).as(()->{try{frozen.command(id,reviewRace&&k.endsWith("b")?com.pis.frozen.FrozenContracts.Action.REVIEW:com.pis.frozen.FrozenContracts.Action.DRAFT,reviewRace&&k.endsWith("b")?reviewInput:input,k);return "SUCCESS";}catch(ApiException e){return e.code();}});
            var a=executor.submit(()->run.apply("frozen-race-a"));var b=executor.submit(()->run.apply("frozen-race-b"));
            try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }
        }
    }
    @Test void frozenForeignScopesAdminAndRevokedReplaysCannotAccess(){
        var owner=new Fixture();UUID id=frozenSetup(owner,null);var other=new Fixture();frozenGrant(other,other);
        jdbc.update("INSERT INTO user_role_scope(user_id,role_code,hospital_id,scope_kind,case_filter) VALUES(?,'SECURITY_ADMIN_TEMPLATE',?,'HOSPITAL','ALL_IN_SCOPE')",other.user,owner.hospital);
        diagnosisGrant(other,owner,false,true);
        other.as(()->{assertCode(()->frozen.detail(id,1),"FROZEN_NOT_FOUND");return null;});
        var input=owner.as(()->fc(id,null,null,"Synthetic replay"));owner.as(()->frozen.command(id,com.pis.frozen.FrozenContracts.Action.DRAFT,input,"revoke-replay"));
        jdbc.update("UPDATE frozen_grant SET revoked_at=statement_timestamp() WHERE scope_id=? AND user_id=?",owner.scope,owner.user);
        owner.as(()->{assertCode(()->frozen.command(id,com.pis.frozen.FrozenContracts.Action.DRAFT,input,"revoke-replay"),"FROZEN_NOT_FOUND");return null;});
    }
    @Test void frozenRoutineDiscrepancyLinkKeepsBothVersionsAndOriginalResult(){
        var owner=new Fixture();var d=outputSetup(owner);var reviewer=new Fixture();frozenGrant(owner,owner);frozenGrant(reviewer,owner);
        UUID id=d.caseId();owner.as(()->{for(String a:List.of("RECEIVE","PREPARE","QC_PASS","DRAFT"))frozenStep(id,a,null,null,"Synthetic frozen original");return null;});reviewer.as(()->{frozenStep(id,"REVIEW",null,null,"");return null;});
        owner.as(()->{
            var original=frozen.detail(id,1).head().revisionId();var signature=amendments.detail(id,1).frozenSignatureId();var input=fc(id,null,null,"Synthetic difference explanation");
            var link=new com.pis.frozen.FrozenContracts.Command(id,input.expectedVersion(),java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),input.zoneId(),input.reason(),null,null,input.resultId(),null,null,input.content(),signature,"DISCREPANCY",null);
            frozen.command(id,com.pis.frozen.FrozenContracts.Action.LINK_ROUTINE,link,"routine-link");var event=frozen.detail(id,1).events().getFirst();assertThat(event.resultId()).isEqualTo(original);assertThat(event.routineSignatureId()).isEqualTo(signature);assertThat(event.comparison()).isEqualTo("DISCREPANCY");assertThat(event.routineRevisionId()).isEqualTo(amendments.snapshot(id,signature).revision().id());
            frozenStep(id,"DRAFT",null,null,"Synthetic later frozen draft");assertThat(frozen.detail(id,1).events()).anyMatch(e->e.id().equals(event.id())&&e.resultId().equals(original));return null;
        });
    }
    @Test void frozenSourceQcIsolationBlocksWorkflowWithoutForcingRoutineMaterialCreation(){
        var owner=new Fixture();var source=diagnosisSetup(owner);frozenGrant(owner,owner);UUID id=source.caseId();
        owner.as(()->{for(String a:List.of("RECEIVE","PREPARE","QC_PASS","DRAFT"))frozenStep(id,a,null,null,"Synthetic");quality.assess(source.slide(),qa(source.slide(),0,0,null,com.pis.quality.QualityContracts.Outcome.IDENTITY_MISMATCH),"source-identity");assertThat(frozen.detail(id,1).gateReady()).isFalse();assertCode(()->frozenStep(id,"DRAFT",null,null,"Synthetic blocked"),"QC_QUARANTINED");assertCode(()->frozenStep(id,"QC_PASS",null,null,"Synthetic blocked"),"QC_QUARANTINED");return null;});
    }
    @Test void frozenHttpRequiresCsrfAndRejectsUnknownFieldsAndForeignObjects() throws Exception {
        var owner=new Fixture();UUID id=frozenSetup(owner,null);String password="Synthetic-frozen-http-only-42!";
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),owner.user);var b=new Browser();String login="username="+owner.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);assertThat(b.send("POST","/api/auth/login",login,b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf(),path="/api/requests/frozen/cases/"+id;
        assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(200);assertThat(b.send("GET","/api/requests/frozen/cases/"+UUID.randomUUID(),null,null,false).statusCode()).isEqualTo(404);
        String body="{\"confirmedCaseId\":\""+id+"\",\"expectedVersion\":3,\"occurredAt\":\"2026-01-01T12:00:00Z\",\"zoneId\":\"UTC\",\"reason\":\"Synthetic HTTP\",\"content\":\"Synthetic manual text\"}";
        assertThat(b.send("POST",path+"/DRAFT",body,null,false).statusCode()).isEqualTo(403);assertThat(b.send("POST",path+"/DRAFT",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);assertThat(b.send("POST",path+"/DRAFT",body,csrf,false).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",owner.user);assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);assertThat(b.send("POST",path+"/DRAFT",body,csrf,false).statusCode()).isEqualTo(404);
    }
    @Test void frozenReviewCannotSilentlyBindUnseenChangedSourceQc(){
        var owner=new Fixture();var source=diagnosisSetup(owner);var reviewer=new Fixture();frozenGrant(owner,owner);frozenGrant(reviewer,owner);UUID id=source.caseId();
        owner.as(()->{for(String a:List.of("RECEIVE","PREPARE","QC_PASS","DRAFT"))frozenStep(id,a,null,null,"Synthetic");return null;});
        var stale=reviewer.as(()->fc(id,null,null,""));
        owner.as(()->quality.assess(source.slide(),qa(source.slide(),0,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"updated-source-qc"));
        reviewer.as(()->{assertCode(()->frozen.command(id,com.pis.frozen.FrozenContracts.Action.REVIEW,stale,"stale-review-dependency"),"FROZEN_REVIEW_INVALIDATED");frozenStep(id,"REVIEW",null,null,"");return null;});
    }
    @Autowired com.pis.material.CytologyService cytology;
    private UUID cytoSetup(Fixture f){materialGrant(f);qcGrant(f);UUID r=grossRequest(f);jdbc.update("INSERT INTO cytology_grant(user_id,scope_id,qualification,can_prepare,can_qc) VALUES(?,?,'SYN-CYTOLOGY-1',true,true)",f.user,f.scope);return r;}
    private UUID cytoContainer(UUID r){return jdbc.queryForObject("SELECT id FROM specimen_container WHERE request_id=?",UUID.class,r);}
    private com.pis.material.CytologyContracts.Command cc(UUID r,String path,UUID prep,int used,int discarded,int returned,int slides,UUID repeat){UUID c=cytoContainer(r);var d=cytology.detail(r,c,1);var p=d.preparations().stream().filter(v->v.id().equals(prep)).findFirst().orElse(null);return new com.pis.material.CytologyContracts.Command(c,d.specimen()==null?-1:d.specimen().version(),"Synthetic explicit reason","Synthetic manually recorded method and medium",path==null?null:com.pis.material.CytologyContracts.Path.valueOf(path),path==null?0:1,repeat,prep,p==null?-1:p.version(),used,discarded,returned,slides);}
    private void cytoStep(UUID r,String action,com.pis.material.CytologyContracts.Command c,String key){cytology.command(r,cytoContainer(r),com.pis.material.CytologyContracts.Action.valueOf(action),c,key);}
    @Test void cytologyAllPathsKeepIndependentIdsExactConsumptionAndCannotBypassLedger(){
        for(String path:List.of("DIRECT_SMEAR","LIQUID_BASED","CELL_BLOCK")){var f=new Fixture();UUID r=cytoSetup(f);f.as(()->{
            cytoStep(r,"REGISTER",cc(r,null,null,0,0,0,0,null),"register");assertCode(()->cytoStep(r,"PREPARE",cc(r,path,null,0,0,0,0,null),"premature"),"CYTOLOGY_NOT_READY");cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"pass");
            var input=cc(r,path,null,0,0,0,0,null);cytoStep(r,"PREPARE",input,"prepare");assertThat(cytology.command(r,cytoContainer(r),com.pis.material.CytologyContracts.Action.PREPARE,input,"prepare").replayed()).isTrue();assertCode(()->cytoStep(r,"PREPARE",input,"stale"),"VERSION_CONFLICT");
            var prepared=cytology.detail(r,cytoContainer(r),1);UUID p=prepared.preparations().getFirst().id();assertThat(prepared.specimen().remaining()).isZero();
            assertCode(()->materials.direct(r,new com.pis.material.MaterialContracts.DirectCreate(2L,cytoContainer(r),"Synthetic bypass"),"bypass"),"CYTOLOGY_LEDGER_REQUIRED");
            assertCode(()->cytoStep(r,"COMPLETE",cc(r,null,p,1,1,0,1,null),"bad-sum"),"CYTOLOGY_RECONCILIATION");var done=cc(r,null,p,1,0,0,1,null);cytoStep(r,"COMPLETE",done,"complete");assertThat(cytology.command(r,cytoContainer(r),com.pis.material.CytologyContracts.Action.COMPLETE,done,"complete").replayed()).isTrue();
            var d=cytology.detail(r,cytoContainer(r),1);assertThat(d.materials()).hasSize(path.equals("CELL_BLOCK")?2:1);var slide=d.materials().stream().filter(m->m.kind().equals("SLIDE")).findFirst().orElseThrow();assertThat(slide.cytologyPreparationId()).isEqualTo(p);assertThat(slide.patientId()).isEqualTo(f.patient);assertThat(slide.containerId()).isEqualTo(cytoContainer(r));if(path.equals("CELL_BLOCK"))assertThat(slide.blockId()).isNotNull();else assertThat(slide.blockId()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM label_identity WHERE material_id=?",Long.class,slide.id())).isEqualTo(1);assertCode(()->cytoStep(r,"COMPLETE",cc(r,null,p,1,0,0,1,null),"duplicate"),"CYTOLOGY_STATE");assertCode(()->cytoStep(r,"PREPARE",cc(r,path,null,0,0,0,0,p),"empty"),"CYTOLOGY_QUANTITY");
            assertThatThrownBy(()->jdbc.update("UPDATE cytology_event SET reason='changed' WHERE specimen_id=?",d.specimen().id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;
        });}
    }
    @Test void cytologyFailureReturnsAccountedStockButNewPreparationHasIndependentIdentityAndSourceGeneration(){
        var f=new Fixture();UUID r=cytoSetup(f);f.as(()->{cytoStep(r,"REGISTER",cc(r,null,null,0,0,0,0,null),"r");cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"q");cytoStep(r,"PREPARE",cc(r,"DIRECT_SMEAR",null,0,0,0,0,null),"p");UUID first=cytology.detail(r,cytoContainer(r),1).preparations().getFirst().id();
            cytoStep(r,"QC_FAIL",cc(r,null,null,0,0,0,0,null),"fail-qc");assertCode(()->cytoStep(r,"COMPLETE",cc(r,null,first,1,0,0,1,null),"blocked"),"CYTOLOGY_SOURCE_STALE");cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"pass-again");assertCode(()->cytoStep(r,"COMPLETE",cc(r,null,first,1,0,0,1,null),"still-blocked"),"CYTOLOGY_SOURCE_STALE");
            cytoStep(r,"FAIL",cc(r,null,first,0,0,1,0,null),"account-failure");assertThat(cytology.detail(r,cytoContainer(r),1).materials()).isEmpty();cytoStep(r,"PREPARE",cc(r,"LIQUID_BASED",null,0,0,0,0,first),"repeat");var p=cytology.detail(r,cytoContainer(r),1).preparations().getFirst();assertThat(p.id()).isNotEqualTo(first);assertThat(p.repeatOf()).isEqualTo(first);cytoStep(r,"COMPLETE",cc(r,null,p.id(),1,0,0,1,null),"done");var m=cytology.detail(r,cytoContainer(r),1).materials().getFirst();quality.assess(m.id(),qa(m.id(),-1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"material-qc");
            assertThat(jdbc.queryForObject("SELECT state FROM workflow_quality_projection WHERE id=?",String.class,m.id())).isEqualTo("PASS");cytoStep(r,"IDENTITY_MISMATCH",cc(r,null,null,0,0,0,0,null),"isolate");assertThat(jdbc.queryForObject("SELECT state FROM workflow_quality_projection WHERE id=?",String.class,m.id())).isEqualTo("SOURCE_QUARANTINED");assertCode(()->quality.assess(m.id(),qa(m.id(),0,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"qc-bypass"),"QC_SOURCE_INVALID");assertCode(()->cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"release"),"QC_QUARANTINED");return null;});
    }
    @Test void cytologyForeignScopeAndRevokedQualificationDenyReadAndReplay(){
        var f=new Fixture();UUID r=cytoSetup(f);var other=new Fixture();cytoSetup(other);var c=f.as(()->cc(r,null,null,0,0,0,0,null));f.as(()->{cytoStep(r,"REGISTER",c,"r");return null;});other.as(()->{assertThatThrownBy(()->cytology.detail(r,cytoContainer(r),1)).isInstanceOf(ApiException.class);return null;});
        jdbc.update("UPDATE cytology_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);f.as(()->{assertCode(()->cytology.detail(r,cytoContainer(r),1),"CYTOLOGY_NOT_FOUND");assertCode(()->cytoStep(r,"REGISTER",c,"r"),"CYTOLOGY_NOT_FOUND");return null;});
    }
    @Test void cytologyAuditFailureRollsBackMaterialLabelsStockAndRetryKey(){
        var f=new Fixture();UUID r=cytoSetup(f);var c=f.as(()->{cytoStep(r,"REGISTER",cc(r,null,null,0,0,0,0,null),"r");cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"q");cytoStep(r,"PREPARE",cc(r,"CELL_BLOCK",null,0,0,0,0,null),"p");return cc(r,null,cytology.detail(r,cytoContainer(r),1).preparations().getFirst().id(),1,0,0,1,null);});var before=f.as(()->cytology.detail(r,cytoContainer(r),1));
        jdbc.execute("CREATE FUNCTION reject_cyto_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='CYTOLOGY_COMPLETE_V1' THEN RAISE EXCEPTION 'synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_cyto_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_cyto_audit()");
        try{f.as(()->{assertThatThrownBy(()->cytoStep(r,"COMPLETE",c,"atomic")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_cyto_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_cyto_audit()");}
        assertThat(f.as(()->cytology.detail(r,cytoContainer(r),1))).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code='CYTOLOGY_COMPLETE_V1'",Long.class,f.hospital)).isZero();f.as(()->{cytoStep(r,"COMPLETE",c,"atomic");return null;});
    }
    @Test void cytologyConcurrentReservationsAndCompletionsHaveOneCasWinner() throws Exception {
        for(boolean completing:List.of(false,true)){var f=new Fixture();UUID r=cytoSetup(f);var c=f.as(()->{cytoStep(r,"REGISTER",cc(r,null,null,0,0,0,0,null),"r");cytoStep(r,"QC_PASS",cc(r,null,null,0,0,0,0,null),"q");if(completing){cytoStep(r,"PREPARE",cc(r,"DIRECT_SMEAR",null,0,0,0,0,null),"p");return cc(r,null,cytology.detail(r,cytoContainer(r),1).preparations().getFirst().id(),1,0,0,1,null);}return cc(r,"DIRECT_SMEAR",null,0,0,0,0,null);});
            try(var blocker=DB.connection();var pool=Executors.newVirtualThreadPerTaskExecutor()){blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,r);st.executeQuery().close();}
                java.util.function.Function<String,String> run=k->f.as(()->{try{cytoStep(r,completing?"COMPLETE":"PREPARE",c,k);return "SUCCESS";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply("a"));var b=pool.submit(()->run.apply("b"));
                try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean wait=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){wait=true;break;}Thread.sleep(10);}assertThat(wait).isTrue();}finally{blocker.rollback();}assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
            }
        }
    }
    @Test void cytologyHttpChecksCsrfWhitelistObjectScopeAndKeyPayload() throws Exception {
        var f=new Fixture();UUID r=cytoSetup(f),cid=cytoContainer(r);String password="Synthetic-cyto-http-only-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);var b=new Browser();assertThat(b.send("POST","/api/auth/login","username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8),b.csrf(),true).statusCode()).isEqualTo(204);String csrf=b.csrf(),path="/api/materials/requests/"+r+"/cytology/"+cid;
        var command=new com.pis.material.CytologyContracts.Command(cid,-1L,"Synthetic HTTP","Synthetic manually entered source",null,0,null,null,-1,0,0,0,0);
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(command);
        // Missing primitive quantities are invalid under the shared strict JSON contract.
        assertHttpError(b.send("POST",path+"/REGISTER",body.replace("\"transferred\":0,",""),csrf,false),400,"INVALID_JSON");
        assertThat(b.send("POST",path+"/REGISTER",body,null,false).statusCode()).isEqualTo(403);assertThat(b.send("POST",path+"/REGISTER",body.replace("\"reason\":","\"adminOverride\":true,\"reason\":"),csrf,false).statusCode()).isEqualTo(400);var registered=b.send("POST",path+"/REGISTER",body,csrf,false);assertThat(registered.statusCode()).as("Synthetic REGISTER response: %s",registered.body()).isEqualTo(200);assertThat(b.send("POST",path+"/REGISTER",body,csrf,false).headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(b.send("POST",path+"/REGISTER",body.replace("Synthetic HTTP","Synthetic changed"),csrf,false).statusCode()).isEqualTo(409);
        assertThat(b.send("GET","/api/materials/requests/"+r+"/cytology/"+UUID.randomUUID(),null,null,false).statusCode()).isEqualTo(404);jdbc.update("UPDATE cytology_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);assertThat(b.send("GET",path,null,null,false).statusCode()).isEqualTo(404);assertThat(b.send("POST",path+"/REGISTER",body,csrf,false).statusCode()).isEqualTo(404);
    }
    @Test void cytologyLedgerCannotShareUnaccountedGrossOrFrozenSource(){
        var f=new Fixture();UUID r=cytoSetup(f);grossGrant(f);frozenGrant(f,f);f.as(()->{cytoStep(r,"REGISTER",cc(r,null,null,0,0,0,0,null),"r");var g=gross.create(r,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"g").receipt().resourceId();assertCode(()->gross.addCassette(g,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cytoContainer(r)),"Synthetic",1),"box"),"CYTOLOGY_LEDGER_REQUIRED");UUID caseId=cytology.detail(r,cytoContainer(r),1).caseId();assertCode(()->frozenStep(caseId,"RECEIVE",null,null,"Synthetic"),"CYTOLOGY_LEDGER_REQUIRED");return null;});
        var second=new Fixture();UUID r2=cytoSetup(second);grossGrant(second);second.as(()->{var g=gross.create(r2,new com.pis.grossing.GrossContracts.Create(2L,"Synthetic"),"g").receipt().resourceId();gross.addCassette(g,new com.pis.grossing.GrossContracts.AddCassette(0L,List.of(cytoContainer(r2)),"Synthetic",1),"box");assertCode(()->cytoStep(r2,"REGISTER",cc(r2,null,null,0,0,0,0,null),"r"),"CYTOLOGY_LEGACY_SOURCE");return null;});
    }
    @Autowired com.pis.material.StainService stains;
    private void stainGrant(Fixture f){jdbc.update("INSERT INTO stain_grant(user_id,scope_id,qualification,can_request,can_execute,can_qc) VALUES(?,?,'SYN-STAIN-1',true,true,true)",f.user,f.scope);}
    private com.pis.material.StainContracts.Command sc(UUID r,UUID b,List<com.pis.material.StainContracts.Source> sources,UUID order,com.pis.material.StainContracts.TechnicalQc qc,String content){var d=stains.detail(r,b,1);var s=d.selected();return new com.pis.material.StainContracts.Command(r,s==null?-1:s.version(),"Synthetic reason",sources,com.pis.material.StainContracts.Kind.IHC,"SYN-P",1,"SYN-S",1,"Synthetic metadata only","SYN-LOT",java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(10),"Synthetic control reference",null,order,s==null?null:s.frozenVersion(),s==null?null:s.controlEventId(),qc,content);}
    private com.pis.material.StainContracts.Command sc(UUID r,UUID b){return sc(r,b,List.of(),null,null,"Synthetic manual evidence");}
    private com.pis.idempotency.IdempotentCommands.Result stainStep(UUID r,UUID b,String action,com.pis.material.StainContracts.Command c,String key){return stains.command(r,b,com.pis.material.StainContracts.Action.valueOf(action),c,key);}
    private UUID stainBatch(DiagnosisSetup d){return stainStep(d.request(),null,"CREATE",sc(d.request(),null,List.of(new com.pis.material.StainContracts.Source(d.slide(),0L)),null,null,""),"stain-create").receipt().resourceId();}
    @Test void stainingFreezesExactOneForOneLineageAndControlRevocationInvalidatesAcceptedResult(){
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);printGrant(f);f.as(()->{UUID r=d.request(),b=stainBatch(d);assertThat(stains.detail(r,b,1).orders().getFirst().outputId()).isNull();var freeze=sc(r,b);stainStep(r,b,"FREEZE",freeze,"freeze");assertThat(stainStep(r,b,"FREEZE",freeze,"freeze").replayed()).isTrue();var o=stains.detail(r,b,1).orders().getFirst();var output=materials.detail(o.outputId()).entity();assertThat(output.sourceSlideId()).isEqualTo(d.slide());assertThat(output.stainOrderId()).isEqualTo(o.id());assertThat(materials.detail(d.slide()).entity().state()).isEqualTo("VOID");assertThat(output.id()).isNotEqualTo(d.slide());
            assertCode(()->labels.material(output.id()),"QC_QUARANTINED");assertCode(()->quality.assess(output.id(),qa(output.id(),-1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"premature-qc"),"QC_SOURCE_INVALID");assertCode(()->stainStep(r,b,"ADD",sc(r,b),"add-frozen"),"STAIN_FROZEN");assertCode(()->stainStep(r,b,"RESULT",sc(r,b,List.of(),o.id(),com.pis.material.StainContracts.TechnicalQc.TECH_PASS,"Synthetic result"),"missing-control"),"STAIN_CONTROL_REQUIRED");
            assertCode(()->stainStep(r,b,"CONTROL_PASS",sc(r,b,List.of(),null,null,""),"blank-control"),"STAIN_TEXT_REQUIRED");stainStep(r,b,"CONTROL_PASS",sc(r,b),"control");var result=sc(r,b,List.of(),o.id(),com.pis.material.StainContracts.TechnicalQc.TECH_PASS,"Synthetic manual observation, no diagnosis");stainStep(r,b,"RESULT",result,"result");assertThat(stainStep(r,b,"RESULT",result,"result").replayed()).isTrue();assertThat(stains.detail(r,b,1).orders().getFirst().effectiveState()).isEqualTo("PASS");quality.assess(output.id(),qa(output.id(),-1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"qc");assertThat(jdbc.queryForObject("SELECT state FROM workflow_quality_projection WHERE id=?",String.class,output.id())).isEqualTo("PASS");var history=jdbc.queryForMap("SELECT * FROM stain_event WHERE order_id=?",o.id());
            stainStep(r,b,"REVOKE",sc(r,b),"revoke");assertThat(stains.detail(r,b,1).orders().getFirst().effectiveState()).isEqualTo("SOURCE_QUARANTINED");assertCode(()->labels.material(output.id()),"QC_QUARANTINED");assertThat(jdbc.queryForMap("SELECT * FROM stain_event WHERE order_id=?",o.id())).isEqualTo(history);assertCode(()->stainStep(r,b,"CONTROL_PASS",sc(r,b),"repass"),"STAIN_STATE");assertThatThrownBy(()->jdbc.update("UPDATE stain_event SET content='replacement' WHERE order_id=?",o.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;});
    }
    @Test void stainingFailureAndChangedSourceNeverRestoreOldResultOrConsumeSourceTwice(){
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);f.as(()->{UUID r=d.request(),b=stainBatch(d);stainStep(r,b,"FREEZE",sc(r,b),"freeze");stainStep(r,b,"CONTROL_FAIL",sc(r,b),"fail");assertCode(()->stainStep(r,b,"CONTROL_PASS",sc(r,b),"pass"),"STAIN_STATE");assertCode(()->stainStep(r,null,"CREATE",sc(r,null,List.of(new com.pis.material.StainContracts.Source(d.slide(),1L)),null,null,""),"consume-again"),"STAIN_SOURCE");return null;});
        var f2=new Fixture();var d2=diagnosisSetup(f2);stainGrant(f2);f2.as(()->{UUID r=d2.request(),b=stainBatch(d2);quality.decide(d2.slide(),new com.pis.quality.QualityContracts.Decision(0L,d2.slide(),"Synthetic revoke"),"source-revoke","REVOKE");quality.assess(d2.slide(),qa(d2.slide(),1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"source-repass");assertCode(()->stainStep(r,b,"FREEZE",sc(r,b),"freeze-stale"),"STAIN_SOURCE_STALE");assertThat(materials.detail(d2.slide()).entity().state()).isEqualTo("ACTIVE");return null;});
    }
    @Test void stainingSchemeVersionMetadataIsImmutableAndCrossScopeSourceIsRejected(){
        var f=new Fixture();var d=diagnosisSetup(f);var other=new Fixture();var foreign=diagnosisSetup(other);stainGrant(f);stainGrant(other);f.as(()->{var input=sc(d.request(),null,List.of(new com.pis.material.StainContracts.Source(foreign.slide(),0L)),null,null,"");assertCode(()->stainStep(d.request(),null,"CREATE",input,"foreign"),"STAIN_SOURCE");UUID b=stainBatch(d);assertThatThrownBy(()->jdbc.update("UPDATE stain_scheme SET metadata='Changed' WHERE scope_id=?",f.scope)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);jdbc.update("UPDATE stain_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);assertCode(()->stains.detail(d.request(),b,1),"STAIN_NOT_FOUND");return null;});other.as(()->{assertThatThrownBy(()->stains.detail(d.request(),null,1)).isInstanceOf(ApiException.class);return null;});
    }
    @Test void stainingAuditFailureRestoresOldSlideAndRollsBackNewIdentityLabelMembersAndCommand(){
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);UUID b=f.as(()->stainBatch(d));var input=f.as(()->sc(d.request(),b));var before=f.as(()->materials.detail(d.slide()));
        jdbc.execute("CREATE FUNCTION reject_stain_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STAIN_FREEZE_V1' THEN RAISE EXCEPTION 'Synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_stain_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_stain_audit()");try{f.as(()->{assertThatThrownBy(()->stainStep(d.request(),b,"FREEZE",input,"atomic")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_stain_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_stain_audit()");}
        assertThat(f.as(()->materials.detail(d.slide()))).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM stain_member WHERE batch_id=?",Long.class,b)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM label_identity WHERE request_id=? AND material_id<>?",Long.class,d.request(),d.slide())).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id=? AND operation_code='STAIN_FREEZE_V1'",Long.class,f.hospital)).isZero();f.as(()->{stainStep(d.request(),b,"FREEZE",input,"atomic");return null;});
    }
    @Test void stainingConcurrentResultAndRevokeHaveOneCasWinnerAndRevokeEventuallyInvalidatesResult() throws Exception {
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);UUID b=f.as(()->{UUID id=stainBatch(d);stainStep(d.request(),id,"FREEZE",sc(d.request(),id),"freeze");stainStep(d.request(),id,"CONTROL_PASS",sc(d.request(),id),"control");return id;});var result=f.as(()->sc(d.request(),b,List.of(),stains.detail(d.request(),b,1).orders().getFirst().id(),com.pis.material.StainContracts.TechnicalQc.TECH_PASS,"Synthetic result"));var revoke=f.as(()->sc(d.request(),b));
        try(var blocker=DB.connection();var pool=Executors.newVirtualThreadPerTaskExecutor()){blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}java.util.function.Function<Boolean,String> run=isResult->f.as(()->{try{stainStep(d.request(),b,isResult?"RESULT":"REVOKE",isResult?result:revoke,isResult?"race-result":"race-revoke");return "SUCCESS";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply(true));var c=pool.submit(()->run.apply(false));try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean wait=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){wait=true;break;}Thread.sleep(10);}assertThat(wait).isTrue();}finally{blocker.rollback();}assertThat(List.of(a.get(5,TimeUnit.SECONDS),c.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");}
        f.as(()->{if(stains.detail(d.request(),b,1).selected().state().equals("PASS"))stainStep(d.request(),b,"REVOKE",sc(d.request(),b),"final-revoke");assertThat(stains.detail(d.request(),b,1).orders().getFirst().effectiveState()).isEqualTo("SOURCE_QUARANTINED");return null;});
    }
    @Test void stainingDuplicateSourcesAndRevokedReplayFailWithoutAdditionalIdentities(){
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);var input=f.as(()->sc(d.request(),null,List.of(new com.pis.material.StainContracts.Source(d.slide(),0L)),null,null,""));
        f.as(()->{assertCode(()->stainStep(d.request(),null,"CREATE",sc(d.request(),null,List.of(new com.pis.material.StainContracts.Source(d.slide(),0L),new com.pis.material.StainContracts.Source(d.slide(),0L)),null,null,""),"duplicate-source"),"STAIN_QUANTITY");var created=stainStep(d.request(),null,"CREATE",input,"create-replay");assertThat(stainStep(d.request(),null,"CREATE",input,"create-replay").receipt()).isEqualTo(created.receipt());return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stain_batch WHERE request_id=?",Long.class,d.request())).isEqualTo(1);
        jdbc.update("UPDATE stain_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        f.as(()->{assertCode(()->stainStep(d.request(),null,"CREATE",input,"create-replay"),"STAIN_NOT_FOUND");return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stain_member WHERE source_id=?",Long.class,d.slide())).isZero();
    }
    @Test void stainingHttpPreservesCsrfUnknownFieldAndScopeBoundaries() throws Exception {
        var f=new Fixture();var d=diagnosisSetup(f);stainGrant(f);UUID batch=f.as(()->stainBatch(d));String password="Synthetic-stain-http-42!";
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);var browser=new Browser();String login="username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);
        String csrf=browser.csrf(),path="/api/materials/requests/"+d.request()+"/staining",body="{\"confirmedRequestId\":\""+d.request()+"\",\"expectedVersion\":0,\"reason\":\"Synthetic HTTP\",\"sources\":[],\"content\":\"\"}";
        assertThat(browser.send("GET",path+"?batch="+batch,null,null,false).statusCode()).isEqualTo(200);
        assertThat(browser.send("GET",path+"?batch="+UUID.randomUUID(),null,null,false).statusCode()).isEqualTo(404);
        assertThat(browser.send("POST",path+"/FREEZE?batch="+batch,body,null,false).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST",path+"/FREEZE?batch="+batch,body.replace("\"sources\":","\"adminOverride\":true,\"sources\":"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST",path+"/FREEZE?batch="+batch,body,csrf,false).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        assertThat(browser.send("GET",path,null,null,false).statusCode()).isEqualTo(404);
        assertThat(browser.send("POST",path+"/FREEZE?batch="+batch,body,csrf,false).statusCode()).isEqualTo(404);
    }

    @Autowired com.pis.report.ConsultationService consultations;
    private DiagnosisSetup consultationSetup(Fixture owner,Fixture reviewer){reportTemplates();var d=diagnosisSetup(owner);diagnosisGrant(reviewer,owner,false,true);owner.as(()->{diagnosis.decide(d.caseId(),dd(d.caseId(),-1,null),"consult-claim",com.pis.diagnosis.DiagnosisContracts.Action.CLAIM);reports.save(d.caseId(),rs(d.caseId(),-1,1,"Synthetic original"),"consult-draft");return null;});return d;}
    private com.pis.report.ConsultationContracts.Command co(UUID c,UUID id,List<UUID> invitees,String disposition,String content){var d=consultations.detail(c,id,1);return new com.pis.report.ConsultationContracts.Command(c,d.selected()==null?-1:d.selected().version(),"Synthetic explicit reason",d.basis().id(),d.basis().assignmentVersion(),"CONSULT","Synthetic reread purpose",java.time.Instant.now().plusSeconds(3600),invitees,null,disposition,content,d.members().stream().map(com.pis.report.ConsultationContracts.Member::opinionId).filter(java.util.Objects::nonNull).toList(),d.selected()==null?null:d.selected().summaryId());}
    private com.pis.idempotency.IdempotentCommands.Result consultStep(UUID c,UUID id,String action,com.pis.report.ConsultationContracts.Command in,String key){return consultations.command(c,id,com.pis.report.ConsultationContracts.Action.valueOf(action),in,key);}
    private UUID consultationCreate(Fixture owner,Fixture reviewer,DiagnosisSetup d){return owner.as(()->consultStep(d.caseId(),null,"CREATE",co(d.caseId(),null,List.of(reviewer.user),null,""),"consult-create").receipt().resourceId());}
    private void consultationReady(Fixture owner,Fixture reviewer,DiagnosisSetup d,UUID id){reviewer.as(()->{consultStep(d.caseId(),id,"ACCEPT",co(d.caseId(),id,List.of(),null,""),"accept");consultStep(d.caseId(),id,"OPINION",co(d.caseId(),id,List.of(),"DISAGREE","Synthetic differing observation"),"opinion");return null;});owner.as(()->{consultStep(d.caseId(),id,"SUMMARY",co(d.caseId(),id,List.of(),"RESOLVED","Synthetic manual difference explanation"),"summary");assertThat(consultations.detail(d.caseId(),id,1).ready()).isFalse();return null;});reviewer.as(()->{consultStep(d.caseId(),id,"CONFIRM",co(d.caseId(),id,List.of(),null,""),"confirm");return null;});}
    @Test void consultationRequiresExplicitConfirmationAndAdoptsOnlyNewNotesRevision(){var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);consultationReady(owner,reviewer,d,id);
        owner.as(()->{var old=reports.detail(d.caseId()).current();assertThat(consultations.detail(d.caseId(),id,1).ready()).isTrue();var input=co(d.caseId(),id,List.of(),null,"Synthetic adopted manual note");consultStep(d.caseId(),id,"ADOPT",input,"adopt");assertThat(consultStep(d.caseId(),id,"ADOPT",input,"adopt").replayed()).isTrue();var now=reports.detail(d.caseId()).current();assertThat(now.id()).isNotEqualTo(old.id());assertThat(now.version()).isEqualTo(old.version()+1);assertThat(now.fields().get("diagnosis")).isEqualTo(old.fields().get("diagnosis"));assertThat(now.fields().get("notes").stringValue()).contains("Synthetic adopted manual note");assertThat(reports.history(d.caseId(),1).revisions()).contains(old);assertThat(consultations.detail(d.caseId(),id,1).selected().state()).isEqualTo("ADOPTED");assertThat(jdbc.queryForObject("SELECT count(*) FROM report_review_event WHERE case_id=?",Long.class,d.caseId())).isZero();return null;});
        reviewer.as(()->{assertCode(()->consultations.detail(d.caseId(),id,1),"CONSULT_NOT_FOUND");return null;});
    }
    @Test void consultationNewOpinionInvalidatesExactSummaryAndForeignOrUninvitedUsersCannotRead(){var owner=new Fixture();var reviewer=new Fixture();var outsider=new Fixture();var d=consultationSetup(owner,reviewer);diagnosisGrant(outsider,owner,false,true);UUID id=consultationCreate(owner,reviewer,d);consultationReady(owner,reviewer,d,id);
        outsider.as(()->{assertCode(()->consultations.detail(d.caseId(),id,1),"CONSULT_NOT_FOUND");assertCode(()->consultations.detail(d.caseId(),null,1),"CONSULT_NOT_FOUND");return null;});
        reviewer.as(()->{var old=consultations.detail(d.caseId(),id,1).opinions().getFirst();consultStep(d.caseId(),id,"OPINION",co(d.caseId(),id,List.of(),"UNKNOWN","Synthetic new uncertainty"),"new-opinion");var next=consultations.detail(d.caseId(),id,1);assertThat(next.ready()).isFalse();assertThat(next.invalidReason()).isEqualTo("SUMMARY_UNRESOLVED_OR_CHANGED");assertThat(next.events()).contains(old);assertCode(()->consultStep(d.caseId(),id,"CONFIRM",co(d.caseId(),id,List.of(),null,""),"stale-confirm"),"CONSULT_SUMMARY_CHANGED");return null;});
        owner.as(()->{assertCode(()->consultStep(d.caseId(),id,"ADOPT",co(d.caseId(),id,List.of(),null,"Synthetic"),"blocked-adopt"),"CONSULT_NOT_READY");return null;});
    }
    @Test void consultationInputRevisionQcAndAssignmentChangesInvalidateWithoutDeletingOpinions(){for(String change:List.of("REPORT","QC","ASSIGNMENT")){var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);consultationReady(owner,reviewer,d,id);owner.as(()->{if(change.equals("REPORT"))reports.save(d.caseId(),rs(d.caseId(),0,1,"Synthetic updated"),"new-draft");else if(change.equals("QC"))quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic revoke"),"revoke","REVOKE");else diagnosis.decide(d.caseId(),dd(d.caseId(),0,reviewer.user),"transfer",com.pis.diagnosis.DiagnosisContracts.Action.TRANSFER);return null;});reviewer.as(()->{var view=consultations.detail(d.caseId(),id,1);assertThat(view.ready()).isFalse();assertThat(view.invalidReason()).isEqualTo(change.equals("REPORT")?"REPORT_CHANGED":change.equals("QC")?"QC_OR_QUALIFICATION_CHANGED":"ASSIGNMENT_CHANGED");assertThat(view.opinions()).hasSize(1);assertCode(()->consultStep(d.caseId(),id,"OPINION",co(d.caseId(),id,List.of(),"AGREE","Synthetic late"),"late"),"CONSULT_INPUT_CHANGED");return null;});}}
    @Test void consultationRevocationBlocksParticipantReadsWritesAndIdempotentReplay(){var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);var accepted=reviewer.as(()->co(d.caseId(),id,List.of(),null,""));reviewer.as(()->consultStep(d.caseId(),id,"ACCEPT",accepted,"accept"));owner.as(()->{var c=co(d.caseId(),id,List.of(),null,"");var revoke=new com.pis.report.ConsultationContracts.Command(c.confirmedCaseId(),c.expectedVersion(),c.reason(),c.revisionId(),c.assignmentVersion(),null,null,null,List.of(),reviewer.user,null,"",List.of(),null);consultStep(d.caseId(),id,"REVOKE",revoke,"revoke");return null;});reviewer.as(()->{assertCode(()->consultations.detail(d.caseId(),id,1),"CONSULT_NOT_FOUND");assertCode(()->consultStep(d.caseId(),id,"ACCEPT",accepted,"accept"),"CONSULT_NOT_FOUND");return null;});}
    @Test void consultationAuditFailureRollsBackAdoptionAndRetryCreatesExactlyOneRevision(){var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);consultationReady(owner,reviewer,d,id);var input=owner.as(()->co(d.caseId(),id,List.of(),null,"Synthetic note"));
        jdbc.execute("CREATE FUNCTION reject_consult_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='CONSULT_ADOPT_V1' THEN RAISE EXCEPTION 'Synthetic outage';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_consult_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_consult_audit()");try{owner.as(()->{assertThatThrownBy(()->consultStep(d.caseId(),id,"ADOPT",input,"atomic-adopt")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_consult_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_consult_audit()");}
        owner.as(()->{assertThat(reports.detail(d.caseId()).current().version()).isZero();assertThat(consultations.detail(d.caseId(),id,1).selected().state()).isEqualTo("OPEN");consultStep(d.caseId(),id,"ADOPT",input,"atomic-adopt");assertThat(reports.history(d.caseId(),1).revisions()).hasSize(2);return null;});
    }
    @Test void consultationParallelPersonalOpinionsHaveExplicitCasConflict() throws Exception {var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);var second=new Fixture();diagnosisGrant(second,owner,false,true);UUID id=owner.as(()->consultStep(d.caseId(),null,"CREATE",co(d.caseId(),null,List.of(reviewer.user,second.user),null,""),"multi-create").receipt().resourceId());reviewer.as(()->consultStep(d.caseId(),id,"ACCEPT",co(d.caseId(),id,List.of(),null,""),"accept"));second.as(()->consultStep(d.caseId(),id,"ACCEPT",co(d.caseId(),id,List.of(),null,""),"accept-second"));var input=reviewer.as(()->co(d.caseId(),id,List.of(),"UNKNOWN","Synthetic parallel"));
        try(var blocker=DB.connection();var pool=Executors.newVirtualThreadPerTaskExecutor()){blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}java.util.function.Function<String,String> run=key->(key.endsWith("a")?reviewer:second).as(()->{try{consultStep(d.caseId(),id,"OPINION",input,key);return "SUCCESS";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply("parallel-a"));var b=pool.submit(()->run.apply("parallel-b"));try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");}
    }

    @Test void consultationExpiryAndRoleRevocationDenyRestrictedReadsAndReplay() throws Exception {
        var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);
        var input=owner.as(()->co(d.caseId(),null,List.of(reviewer.user),null,""));var shortLived=new com.pis.report.ConsultationContracts.Command(input.confirmedCaseId(),input.expectedVersion(),input.reason(),input.revisionId(),input.assignmentVersion(),input.kind(),input.purpose(),java.time.Instant.now().plusSeconds(2),input.invitees(),null,null,"",List.of(),null);
        UUID id=owner.as(()->consultStep(d.caseId(),null,"CREATE",shortLived,"short").receipt().resourceId());var accept=reviewer.as(()->co(d.caseId(),id,List.of(),null,""));reviewer.as(()->consultStep(d.caseId(),id,"ACCEPT",accept,"short-accept"));Thread.sleep(2100);
        reviewer.as(()->{assertCode(()->consultations.detail(d.caseId(),id,1),"CONSULT_NOT_FOUND");assertCode(()->consultStep(d.caseId(),id,"ACCEPT",accept,"short-accept"),"CONSULT_NOT_FOUND");return null;});owner.as(()->{assertThat(consultations.detail(d.caseId(),id,1).invalidReason()).isEqualTo("EXPIRED");return null;});
        UUID fresh=owner.as(()->consultStep(d.caseId(),null,"CREATE",co(d.caseId(),null,List.of(reviewer.user),null,""),"fresh").receipt().resourceId());jdbc.update("UPDATE diagnosis_grant SET revoked_at=statement_timestamp(),version=version+1 WHERE scope_id=? AND user_id=?",owner.scope,reviewer.user);
        reviewer.as(()->{assertThatThrownBy(()->consultations.detail(d.caseId(),fresh,1)).isInstanceOf(ApiException.class);return null;});owner.as(()->{assertThat(consultations.detail(d.caseId(),fresh,1).invalidReason()).isEqualTo("PARTICIPANT_QUALIFICATION_CHANGED");return null;});
    }
    @Test void consultationRefusalReturnAndWithdrawalRemainAppendOnly(){for(String action:List.of("REJECT","RETURN","WITHDRAW")){var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);if(action.equals("RETURN"))reviewer.as(()->consultStep(d.caseId(),id,"ACCEPT",co(d.caseId(),id,List.of(),null,""),"accept"));var actor=action.equals("WITHDRAW")?owner:reviewer;actor.as(()->consultStep(d.caseId(),id,action,co(d.caseId(),id,List.of(),null,""),"end"));owner.as(()->{var v=consultations.detail(d.caseId(),id,1);assertThat(v.ready()).isFalse();assertThat(v.events()).anyMatch(e->e.action().equals(action)&&!e.reason().isBlank());return null;});reviewer.as(()->{assertCode(()->consultations.detail(d.caseId(),id,1),"CONSULT_NOT_FOUND");return null;});assertThatThrownBy(()->jdbc.update("DELETE FROM consultation_event WHERE consultation_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}}

    @Test void consultationHttpRejectsMissingCsrfUnknownFieldsAndForeignCase() throws Exception {var owner=new Fixture();var reviewer=new Fixture();var d=consultationSetup(owner,reviewer);UUID id=consultationCreate(owner,reviewer,d);var foreign=new Fixture();var other=diagnosisSetup(foreign);String password="Synthetic-consult-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),owner.user);var browser=new Browser();String login="username="+owner.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);assertThat(browser.send("POST","/api/auth/login",login,browser.csrf(),true).statusCode()).isEqualTo(204);String csrf=browser.csrf(),path="/api/requests/consultations/cases/"+d.caseId(),body="{\"confirmedCaseId\":\""+d.caseId()+"\",\"expectedVersion\":0,\"reason\":\"Synthetic\",\"invitees\":[],\"opinionIds\":[],\"content\":\"\"}";
        assertThat(browser.send("GET",path+"?consultation="+id,null,null,false).statusCode()).isEqualTo(200);assertThat(browser.send("GET","/api/requests/consultations/cases/"+other.caseId()+"?consultation="+id,null,null,false).statusCode()).isEqualTo(404);
        assertThat(browser.send("POST",path+"/WITHDRAW?consultation="+id,body,null,false).statusCode()).isEqualTo(403);assertThat(browser.send("POST",path+"/WITHDRAW?consultation="+id,body.replace("\"invitees\":","\"adminOverride\":true,\"invitees\":"),csrf,false).statusCode()).isEqualTo(400);assertThat(browser.send("POST",path+"/WITHDRAW?consultation="+id,body,csrf,false).statusCode()).isEqualTo(200);
    }

    @Autowired com.pis.archive.ArchiveService archives;
    private void archiveGrant(Fixture actor,Fixture owner){jdbc.update("INSERT INTO workflow_grant(user_id,scope_id,can_read) VALUES(?,?,true) ON CONFLICT(user_id,scope_id) DO UPDATE SET can_read=true",actor.user,owner.scope);jdbc.update("INSERT INTO archive_grant(user_id,scope_id,qualification,can_request,can_approve,can_manage,valid_until) VALUES(?,?,'SYN-ARCHIVE-1',true,true,true,statement_timestamp()+interval '1 day') ON CONFLICT(user_id,scope_id) DO NOTHING",actor.user,owner.scope);}
    private com.pis.archive.ArchiveContracts.Command ar(UUID rid,java.util.Map<String,Object> patch){var v=archives.view(rid,null,1);var m=new java.util.HashMap<String,Object>();m.put("confirmedRequestId",rid);m.put("expectedVersion",v.version());m.put("reason","Synthetic archive reason");m.put("items",List.of());m.putAll(patch);return tools.jackson.databind.json.JsonMapper.builder().build().convertValue(m,com.pis.archive.ArchiveContracts.Command.class);}
    private com.pis.idempotency.IdempotentCommands.Result archiveStep(UUID rid,String action,java.util.Map<String,Object> fields,String key){return archives.command(rid,com.pis.archive.ArchiveContracts.Action.valueOf(action),ar(rid,fields),key);}
    private UUID archiveRegister(Fixture f,DiagnosisSetup d){archiveGrant(f,f);return f.as(()->{var src=archives.view(d.request(),null,1).sources().stream().filter(x->x.id().equals(d.slide())).findFirst().orElseThrow();archiveStep(d.request(),"REGISTER",java.util.Map.of("sourceId",src.id(),"sourceVersion",src.version(),"barcode",src.barcode(),"policyLabel","SYN-ONLY","legalHold",true),"archive-register");return archives.view(d.request(),null,1).items().getFirst().id();});}
    private java.util.Map<String,Object> archiveItem(UUID rid,UUID item){var i=archives.view(rid,null,1).items().stream().filter(x->x.id().equals(item)).findFirst().orElseThrow();return new java.util.HashMap<>(java.util.Map.of("itemId",item,"itemVersion",i.version(),"barcode",i.barcode()));}
    @Test void archiveReservationPartialReturnAndExactReplayRemainManual(){var f=new Fixture();var approver=new Fixture();var d=diagnosisSetup(f);UUID first=archiveRegister(f,d);archiveGrant(approver,f);
        UUID second=f.as(()->{var cid=service.detail(d.request()).containers().getFirst().id();var mid=materials.direct(d.request(),new com.pis.material.MaterialContracts.DirectCreate(2L,cid,"Synthetic second"),"second-slide").receipt().resourceId();quality.assess(mid,qa(mid,-1,0,null,com.pis.quality.QualityContracts.Outcome.PASS),"second-qc");var src=archives.view(d.request(),null,1).sources().stream().filter(x->x.id().equals(mid)).findFirst().orElseThrow();archiveStep(d.request(),"REGISTER",java.util.Map.of("sourceId",mid,"sourceVersion",0,"barcode",src.barcode(),"policyLabel","SYN-ONLY","legalHold",false),"second-register");return archives.view(d.request(),null,1).items().stream().filter(x->x.sourceId().equals(mid)).findFirst().orElseThrow().id();});
        UUID loan=f.as(()->{var v=archives.view(d.request(),null,1);archiveStep(d.request(),"LOAN",java.util.Map.of("borrowerId",f.user,"purpose","Synthetic internal loan","dueAt",java.time.Instant.now().plusSeconds(3600),"items",v.items().stream().map(x->new com.pis.archive.ArchiveContracts.Selection(x.id(),x.version())).toList()),"loan");var id=archives.view(d.request(),null,1).loans().getFirst().id();assertCode(()->archiveStep(d.request(),"APPROVE",java.util.Map.of("loanId",id),"self-approve"),"ARCHIVE_SEPARATION");return id;});
        approver.as(()->archiveStep(d.request(),"APPROVE",java.util.Map.of("loanId",loan),"approve"));f.as(()->{for(var id:List.of(first,second)){var m=archiveItem(d.request(),id);m.put("loanId",loan);archiveStep(d.request(),"CHECKOUT",m,"out-"+id);}var m=archiveItem(d.request(),first);m.put("loanId",loan);var input=ar(d.request(),m);archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.RETURN,input,"return-first");assertThat(archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.RETURN,input,"return-first").replayed()).isTrue();var v=archives.view(d.request(),null,1);assertThat(v.loanItems()).extracting(com.pis.archive.ArchiveContracts.LoanItem::state).containsExactlyInAnyOrder("OUT","RETURNED");assertThat(v.loans().getFirst().state()).isEqualTo("APPROVED");assertThat(v.events()).allMatch(e->e.physicalConfirmation().equals("UNVERIFIED"));return null;});
    }
    @Test void archiveWrongBarcodeForeignItemAndRevokedReplayAreRejected(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);var foreign=new Fixture();var other=diagnosisSetup(foreign);UUID foreignItem=archiveRegister(foreign,other);
        var input=f.as(()->{var m=archiveItem(d.request(),item);m.put("barcode","WRONG");var wrong=java.util.Map.copyOf(m);assertCode(()->archiveStep(d.request(),"LOST",wrong,"wrong"),"ARCHIVE_BARCODE_MISMATCH");m=archiveItem(d.request(),item);m.put("itemId",foreignItem);var bad=ar(d.request(),m);assertCode(()->archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,bad,"foreign"),"ARCHIVE_NOT_FOUND");var good=ar(d.request(),archiveItem(d.request(),item));archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,good,"lost");return good;});
        jdbc.update("UPDATE archive_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);f.as(()->{assertCode(()->archives.view(d.request(),null,1),"ARCHIVE_NOT_FOUND");assertCode(()->archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,input,"lost"),"ARCHIVE_NOT_FOUND");return null;});
    }
    @Test void archiveInventoryDifferenceAndStaleSnapshotKeepOriginalHistory(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);f.as(()->{archiveStep(d.request(),"INVENTORY",java.util.Map.of(),"inventory");UUID inv=archives.view(d.request(),null,1).inventories().getFirst().id();var m=archiveItem(d.request(),item);m.put("inventoryId",inv);m.put("observation","DIFFERENCE");archiveStep(d.request(),"CHECK",m,"difference");var event=archives.view(d.request(),inv,1).events().getFirst().id();m=archiveItem(d.request(),item);m.put("referenceId",event);archiveStep(d.request(),"CORRECT",m,"correction");var stale=archiveItem(d.request(),item);stale.put("inventoryId",inv);stale.put("observation","MATCH");assertCode(()->archiveStep(d.request(),"CHECK",stale,"stale"),"ARCHIVE_SNAPSHOT_STALE");assertThat(archives.view(d.request(),inv,1).snapshot().getFirst().itemVersion()).isZero();assertThatThrownBy(()->jdbc.update("DELETE FROM archive_event WHERE request_id=?",d.request())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);return null;});}
    @Test void archiveQcRevocationAndLossNeverEraseTraceabilityOrAllowLoan(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);f.as(()->{quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(0L,d.slide(),"Synthetic revoke"),"revoke","REVOKE");var v=archives.view(d.request(),null,1);assertThat(v.items()).hasSize(1);assertCode(()->archiveStep(d.request(),"LOAN",java.util.Map.of("borrowerId",f.user,"purpose","Synthetic","dueAt",java.time.Instant.now().plusSeconds(3600),"items",List.of(new com.pis.archive.ArchiveContracts.Selection(item,0L))),"qc-blocked"),"ARCHIVE_SOURCE_UNSUITABLE");archiveStep(d.request(),"LOST",archiveItem(d.request(),item),"lost");archiveStep(d.request(),"FOUND",archiveItem(d.request(),item),"found");assertThat(archives.view(d.request(),null,1).items().getFirst().condition()).isEqualTo("FOUND_PENDING");return null;});}
    @Test void archiveAuditFailureRollsBackItemRootEventAndOriginalKeyCanRetry(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);var input=f.as(()->ar(d.request(),archiveItem(d.request(),item)));jdbc.execute("CREATE FUNCTION reject_archive_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='ARCHIVE_LOST_V1' THEN RAISE EXCEPTION 'Synthetic audit unavailable';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_archive_test BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_archive_test()");try{f.as(()->{assertThatThrownBy(()->archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,input,"rollback")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});}finally{jdbc.execute("DROP TRIGGER reject_archive_test ON audit_event");jdbc.execute("DROP FUNCTION reject_archive_test()");}f.as(()->{var v=archives.view(d.request(),null,1);assertThat(v.version()).isZero();assertThat(v.items().getFirst().condition()).isEqualTo("RECORDED");archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,input,"rollback");assertThat(archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOST,input,"rollback").replayed()).isTrue();return null;});}
    @Test void archiveConcurrentReservationHasOneWinnerAndOneVersionConflict() throws Exception {var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);var input=f.as(()->ar(d.request(),java.util.Map.of("borrowerId",f.user,"purpose","Synthetic race","dueAt",java.time.Instant.now().plusSeconds(3600),"items",List.of(new com.pis.archive.ArchiveContracts.Selection(item,0L)))));
        try(var blocker=DB.connection();var pool=Executors.newVirtualThreadPerTaskExecutor()){blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}java.util.function.Function<String,String> run=key->f.as(()->{try{archives.command(d.request(),com.pis.archive.ArchiveContracts.Action.LOAN,input,key);return "SUCCESS";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply("reserve-a"));var b=pool.submit(()->run.apply("reserve-b"));try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");}
    }
    @Test void archiveGrantChangeAfterFreezeRequiresFreshReviewBeforeOutputCreation() {
        var f=new Fixture();var d=outputSetup(f);
        f.as(()->{assertThat(outputs.detail(d.caseId()).dependenciesCurrent()).isTrue();return null;});
        // Even a same-value grant update advances the authorization generation.
        archiveGrant(f,f);
        f.as(()->{
            assertThat(outputs.detail(d.caseId()).dependenciesCurrent()).isFalse();
            assertCode(()->outputs.create(d.caseId(),oc(d.caseId()),"stale-archive-output"),"REPORT_OUTPUT_STALE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM report_artifact WHERE case_id=?",Long.class,d.caseId())).isZero();
            return null;
        });
    }
    @Test void archiveReportBindingPreservesFrozenBytesAndRejectsDifferentSourceVersion(){var f=new Fixture();archiveGrant(f,f);var d=outputSetup(f);f.as(()->{assertThat(outputs.detail(d.caseId()).dependenciesCurrent()).isTrue();UUID artifact=outputs.create(d.caseId(),oc(d.caseId()),"archive-output").receipt().resourceId();var bytes=jdbc.queryForObject("SELECT pdf FROM report_artifact WHERE id=?",byte[].class,artifact);var src=archives.view(d.request(),null,1).sources().stream().filter(x->x.id().equals(artifact)).findFirst().orElseThrow();assertCode(()->archiveStep(d.request(),"REGISTER",java.util.Map.of("sourceId",artifact,"sourceVersion",1,"barcode",src.barcode(),"policyLabel","SYN-ONLY","legalHold",true),"wrong-version"),"VERSION_CONFLICT");archiveStep(d.request(),"REGISTER",java.util.Map.of("sourceId",artifact,"sourceVersion",0,"barcode",src.barcode(),"policyLabel","SYN-ONLY","legalHold",true),"exact-output");var i=archives.view(d.request(),null,1).items().getFirst();assertThat(i.hash()).isEqualTo(src.hash());assertThat(i.revisionId()).isEqualTo(src.revisionId());assertThat(jdbc.queryForObject("SELECT pdf FROM report_artifact WHERE id=?",byte[].class,artifact)).isEqualTo(bytes);return null;});}

    @Test void archiveBatchDoesNotMarkConflictingOrUnattemptedItemsSuccessful(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);f.as(()->{var a=ar(d.request(),archiveItem(d.request(),item));var result=archives.batch(d.request(),new com.pis.archive.ArchiveContracts.Batch(List.of(new com.pis.archive.ArchiveContracts.BatchEntry("batch-a",com.pis.archive.ArchiveContracts.Action.LOST,a),new com.pis.archive.ArchiveContracts.BatchEntry("batch-b",com.pis.archive.ArchiveContracts.Action.DAMAGE,a))));assertThat(result).extracting(com.pis.archive.ArchiveContracts.BatchResult::status).containsExactly("SUCCESS","FAILED");assertThat(result.get(1).code()).isEqualTo("VERSION_CONFLICT");var replay=archives.batch(d.request(),new com.pis.archive.ArchiveContracts.Batch(List.of(new com.pis.archive.ArchiveContracts.BatchEntry("batch-a",com.pis.archive.ArchiveContracts.Action.LOST,a))));assertThat(replay.getFirst().result().replayed()).isTrue();return null;});}
    @Test void archiveExpiredQualificationAndCrossScopeDoNotReadArchivedObjects(){var f=new Fixture();var d=diagnosisSetup(f);archiveRegister(f,d);var outsider=new Fixture();archiveGrant(outsider,outsider);outsider.as(()->{assertCode(()->archives.view(d.request(),null,1),"REQUEST_NOT_FOUND");return null;});jdbc.update("UPDATE archive_grant SET valid_until=statement_timestamp()-interval '1 second' WHERE user_id=?",f.user);f.as(()->{assertCode(()->archives.view(d.request(),null,1),"ARCHIVE_NOT_FOUND");return null;});}
    @Test void archiveBatchAuditOutageRollsBackFirstAndDoesNotAttemptFollowingEntry(){var f=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);var a=f.as(()->ar(d.request(),archiveItem(d.request(),item)));jdbc.execute("CREATE FUNCTION reject_archive_batch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='ARCHIVE_LOST_V1' THEN RAISE EXCEPTION 'Synthetic audit unavailable';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_archive_batch BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_archive_batch()");try{f.as(()->{var r=archives.batch(d.request(),new com.pis.archive.ArchiveContracts.Batch(List.of(new com.pis.archive.ArchiveContracts.BatchEntry("outage-a",com.pis.archive.ArchiveContracts.Action.LOST,a),new com.pis.archive.ArchiveContracts.BatchEntry("outage-b",com.pis.archive.ArchiveContracts.Action.DAMAGE,a))));assertThat(r).extracting(com.pis.archive.ArchiveContracts.BatchResult::status).containsExactly("UNKNOWN","NOT_ATTEMPTED");return null;});}finally{jdbc.execute("DROP TRIGGER reject_archive_batch ON audit_event");jdbc.execute("DROP FUNCTION reject_archive_batch()");}f.as(()->{assertThat(archives.view(d.request(),null,1).items().getFirst().condition()).isEqualTo("RECORDED");return null;});}

    @Test void archiveDoubleCheckoutAndReturnRaceCannotDuplicateCustodyTransition() throws Exception {var f=new Fixture();var approver=new Fixture();var d=diagnosisSetup(f);UUID item=archiveRegister(f,d);archiveGrant(approver,f);UUID loan=f.as(()->{archiveStep(d.request(),"LOAN",java.util.Map.of("borrowerId",f.user,"purpose","Synthetic race","dueAt",java.time.Instant.now().plusSeconds(3600),"items",List.of(new com.pis.archive.ArchiveContracts.Selection(item,0L))),"race-loan");return archives.view(d.request(),null,1).loans().getFirst().id();});approver.as(()->archiveStep(d.request(),"APPROVE",java.util.Map.of("loanId",loan),"race-approve"));
        for(var action:List.of(com.pis.archive.ArchiveContracts.Action.CHECKOUT,com.pis.archive.ArchiveContracts.Action.RETURN)){var input=f.as(()->{var m=archiveItem(d.request(),item);m.put("loanId",loan);return ar(d.request(),m);});try(var blocker=DB.connection();var pool=Executors.newVirtualThreadPerTaskExecutor()){blocker.setAutoCommit(false);try(var st=blocker.prepareStatement("SELECT id FROM pathology_request WHERE id=? FOR UPDATE")){st.setObject(1,d.request());st.executeQuery().close();}java.util.function.Function<String,String> run=key->f.as(()->{try{archives.command(d.request(),action,input,key);return "SUCCESS";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply(action+"-a"));var b=pool.submit(()->run.apply(action+"-b"));try{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean waiting=false;while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM pathology_request WHERE id=%'",Long.class)>=2){waiting=true;break;}Thread.sleep(10);}assertThat(waiting).isTrue();}finally{blocker.rollback();}assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");}}
        f.as(()->{assertThat(archives.view(d.request(),null,1).loans().getFirst().state()).isEqualTo("CLOSED");return null;});
    }

    @Autowired com.pis.statistics.StatisticsService statistics;
    private void statisticsGrant(Fixture f,boolean all,boolean drill) {
        jdbc.update("INSERT INTO statistics_grant(user_id,scope_id,qualification,all_requests,can_drill,can_report,valid_until) VALUES(?,?,'SYN-STATS-1',?,?,true,statement_timestamp()+interval '1 day')",f.user,f.scope,all,drill);
    }
    private com.pis.statistics.StatisticsContracts.Query statisticsQuery(){var now=java.time.LocalDate.now(java.time.ZoneOffset.UTC);return new com.pis.statistics.StatisticsContracts.Query(now.minusDays(1),now,"UTC");}
    private com.pis.statistics.StatisticsContracts.View statisticsView(Fixture f,UUID id,com.pis.statistics.StatisticsContracts.Metric metric){return statistics.view(f.scope,id,metric,com.pis.statistics.StatisticsContracts.Sort.ENTITY,1);}
    @Test void statisticsScopesBeforeAggregatingAndRevocationBlocksSavedTotalsAndReplay(){
        var f=new Fixture();var a=grossRequest(f);statisticsGrant(f,false,true);
        f.as(()->{var empty=statistics.create(f.scope,statisticsQuery(),"empty").receipt().resourceId();assertThat(statisticsView(f,empty,null).summaries()).allMatch(x->x.cohort()==0);return null;});
        jdbc.update("INSERT INTO statistics_resource_grant(user_id,scope_id,request_id,valid_until) VALUES(?,?,?,statement_timestamp()+interval '1 day')",f.user,f.scope,a);
        UUID id=f.as(()->statistics.create(f.scope,statisticsQuery(),"allowed").receipt().resourceId());
        f.as(()->{var v=statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.RECEPTION);assertThat(v.total()).isEqualTo(1);assertThat(v.facts()).allMatch(x->x.requestId().equals(a)&&x.status().equals("COMPLETED"));assertThat(statistics.create(f.scope,statisticsQuery(),"allowed").replayed()).isTrue();return null;});
        var foreign=new Fixture();statisticsGrant(foreign,true,true);foreign.as(()->{assertCode(()->statistics.view(f.scope,id,null,com.pis.statistics.StatisticsContracts.Sort.ENTITY,1),"STATISTICS_NOT_FOUND");return null;});
        jdbc.update("UPDATE statistics_resource_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);
        f.as(()->{assertCode(()->statisticsView(f,id,null),"STATISTICS_NOT_FOUND");assertCode(()->statistics.create(f.scope,statisticsQuery(),"allowed"),"STATISTICS_NOT_FOUND");return null;});
    }
    @Test void statisticsFixedSnapshotDeduplicatesRetriesAndProtectsHistory(){
        var f=new Fixture();var request=grossRequest(f);statisticsGrant(f,true,true);
        f.as(()->{var id=statistics.create(f.scope,statisticsQuery(),"snapshot").receipt().resourceId();var before=statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.RECEPTION);assertThat(before.total()).isEqualTo(1);
            assertThat(statistics.create(f.scope,statisticsQuery(),"snapshot").receipt().resourceId()).isEqualTo(id);
            assertThatThrownBy(()->jdbc.update("UPDATE statistics_fact SET status='UNKNOWN' WHERE snapshot_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM statistics_snapshot WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(before.facts().getFirst().requestId()).isEqualTo(request);return null;});
    }
    @Test void statisticsQcSnapshotRetainsOldEvidenceWhileNewSnapshotReflectsRevocation(){
        var f=new Fixture();var d=diagnosisSetup(f);statisticsGrant(f,true,true);
        f.as(()->{var id=statistics.create(f.scope,statisticsQuery(),"qc-before").receipt().resourceId();var before=statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.QC);assertThat(before.facts()).hasSize(1);assertThat(before.facts().getFirst().status()).isEqualTo("PASS");
            var h=quality.detail(d.slide());quality.decide(d.slide(),new com.pis.quality.QualityContracts.Decision(h.item().head().version(),d.slide(),"Synthetic revoke"),"stats-revoke","REVOKE");
            assertThat(statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.QC).facts()).isEqualTo(before.facts());
            var next=statistics.create(f.scope,statisticsQuery(),"qc-after").receipt().resourceId();assertThat(statisticsView(f,next,com.pis.statistics.StatisticsContracts.Metric.QC).facts().getFirst().status()).isEqualTo("UNKNOWN");return null;});
    }
    @Test void statisticsAuditFailureRollsBackSnapshotAndPreservesRetryKey(){
        var f=new Fixture();grossRequest(f);statisticsGrant(f,true,false);
        jdbc.execute("CREATE FUNCTION reject_statistics_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STATISTICS_SNAPSHOT_V1' THEN RAISE EXCEPTION 'Synthetic outage'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER reject_statistics_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_statistics_audit()");
        try{f.as(()->{assertThatThrownBy(()->statistics.create(f.scope,statisticsQuery(),"atomic-statistics")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM statistics_snapshot WHERE actor_id=?",Long.class,f.user)).isZero();return null;});}finally{jdbc.execute("DROP TRIGGER reject_statistics_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_statistics_audit()");}
        f.as(()->{var id=statistics.create(f.scope,statisticsQuery(),"atomic-statistics").receipt().resourceId();assertThat(statisticsView(f,id,null).canDrill()).isFalse();assertCode(()->statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.RECEPTION),"STATISTICS_NOT_FOUND");return null;});
    }

    @Test void statisticsConcurrentSameKeyFreezesOneSnapshot() throws Exception {
        var f=new Fixture();grossRequest(f);statisticsGrant(f,true,true);var query=statisticsQuery();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<com.pis.idempotency.IdempotentCommands.Result> task=()->{ready.countDown();if(!go.await(3,TimeUnit.SECONDS))throw new AssertionError("Synthetic barrier timeout");return f.as(()->statistics.create(f.scope,query,"parallel-stats"));};
            var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(3,TimeUnit.SECONDS)).isTrue();go.countDown();var first=a.get(8,TimeUnit.SECONDS);var second=b.get(8,TimeUnit.SECONDS);
            assertThat(first.receipt()).isEqualTo(second.receipt());assertThat(first.replayed()).isNotEqualTo(second.replayed());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM statistics_snapshot WHERE actor_id=?",Long.class,f.user)).isEqualTo(1);
        }
    }
    @Test void statisticsHttpRequiresCsrfStrictFilterAndCurrentScope() throws Exception {
        var f=new Fixture();grossRequest(f);statisticsGrant(f,true,true);var browser=new Browser();
        String password="Synthetic-statistics-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        assertThat(browser.send("POST","/api/auth/login","username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8),browser.csrf(),true).statusCode()).isEqualTo(204);
        String path="/api/requests/statistics/scopes/"+f.scope;var query=statisticsQuery();String body="{\"from\":\""+query.from()+"\",\"to\":\""+query.to()+"\",\"zone\":\"UTC\"}";String csrf=browser.csrf();
        assertHttpError(browser.send("POST",path,body,null,false),403,"CSRF_INVALID");
        assertThat(browser.send("POST",path,body.replace("UTC","Invalid/Zone"),csrf,false).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST",path,body.replace("\"zone\":","\"allRequests\":true,\"zone\":"),csrf,false).statusCode()).isEqualTo(400);
        var created=browser.send("POST",path,body,csrf,false);assertThat(created.statusCode()).as("Synthetic statistics response: %s",created.body()).isEqualTo(200);
        String id=tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.body()).path("receipt").path("resourceId").stringValue();
        assertThat(browser.send("GET",path+"/"+id+"?page=251",null,null,false).statusCode()).isEqualTo(400);
        assertThat(browser.send("GET",path+"/"+id+"?sort=UNBOUNDED",null,null,false).statusCode()).isEqualTo(400);
        assertThat(browser.send("GET",path+"/"+id,null,null,false).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE statistics_grant SET valid_until=statement_timestamp()-interval '1 second' WHERE user_id=?",f.user);
        assertThat(browser.send("GET",path+"/"+id,null,null,false).statusCode()).isEqualTo(404);
        assertThat(browser.send("POST",path,body,csrf,false).statusCode()).isEqualTo(404);
    }

    @Test void statisticsConcurrentSourceRevocationCannotSplitSavedSummaryAndDrill() throws Exception {
        var f=new Fixture();var d=diagnosisSetup(f);statisticsGrant(f,true,true);
        var id=f.as(()->statistics.create(f.scope,statisticsQuery(),"read-race").receipt().resourceId());
        var before=f.as(()->statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.QC));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var go=new java.util.concurrent.CountDownLatch(1);
            var read=pool.submit(()->{if(!go.await(3,TimeUnit.SECONDS))throw new AssertionError("Synthetic read barrier");return f.as(()->statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.QC));});
            var write=pool.submit(()->{if(!go.await(3,TimeUnit.SECONDS))throw new AssertionError("Synthetic write barrier");return f.as(()->quality.decide(d.slide(),qd(d.slide(),0),"stats-race-revoke","REVOKE"));});
            go.countDown();var during=read.get(8,TimeUnit.SECONDS);write.get(8,TimeUnit.SECONDS);
            assertThat(during.factsHash()).isEqualTo(before.factsHash());assertThat(during.summaries()).isEqualTo(before.summaries());assertThat(during.facts()).isEqualTo(before.facts());
        }
        f.as(()->{var next=statistics.create(f.scope,statisticsQuery(),"race-after").receipt().resourceId();assertThat(statisticsView(f,next,com.pis.statistics.StatisticsContracts.Metric.QC).facts().getFirst().status()).isEqualTo("UNKNOWN");return null;});
    }

    @Test void statisticsReturnedRequestIsExcludedRatherThanOpenOrZeroTat(){
        var f=new Fixture();var request=submitted(f);receptionGrant(f);statisticsGrant(f,true,true);
        f.as(()->{reception.exception(request,new com.pis.specimen.ReceptionContracts.ExceptionInput(1L,com.pis.specimen.ReceptionContracts.Category.INFORMATION,"Synthetic missing information"),"stats-exception");reception.sendBack(request,new com.pis.specimen.ReceptionContracts.Decision(2L,"Synthetic return"),"stats-return");var id=statistics.create(f.scope,statisticsQuery(),"returned-statistics").receipt().resourceId();var view=statisticsView(f,id,com.pis.statistics.StatisticsContracts.Metric.RECEPTION);assertThat(view.facts().getFirst().status()).isEqualTo("EXCLUDED");assertThat(view.facts().getFirst().durationSeconds()).isNull();assertThat(view.facts().getFirst().endEvent()).isNotNull();return null;});
    }

    @Autowired com.pis.storage.StorageService storage;
    @Autowired com.pis.storage.StorageProvider storageProvider;
    private void storageGrant(Fixture f,UUID request){jdbc.update("INSERT INTO storage_grant(user_id,scope_id,qualification,can_write,can_capacity,valid_until) VALUES(?,?,'SYN-STORAGE-1',true,true,statement_timestamp()+interval '1 day')",f.user,f.scope);jdbc.update("INSERT INTO storage_case_grant(user_id,scope_id,case_id,valid_until) SELECT ?,?,id,statement_timestamp()+interval '1 day' FROM pathology_case WHERE request_id=?",f.user,f.scope,request);}
    private byte[] storageData(){var data=new byte[65536];for(int i=0;i<data.length;i++)data[i]=(byte)(i%251);System.arraycopy(com.pis.storage.StoragePolicy.MARKER,0,data,0,com.pis.storage.StoragePolicy.MARKER.length);return data;}
    private com.pis.storage.StorageContracts.Reserve storageReserve(Fixture f,UUID request,UUID asset,long head){var bytes=storageData();return new com.pis.storage.StorageContracts.Reserve(asset,head,jdbc.queryForObject("SELECT id FROM pathology_case WHERE request_id=?",UUID.class,request),(long)bytes.length,com.pis.report.SyntheticPdf.sha256(bytes),"SYNTHETIC_ORIGINAL","application/octet-stream");}
    @Test void storagePreservesOriginalVersionsAndReplaysFinalizeWithoutDuplicateOutbox(){
        var f=new Fixture();var request=grossRequest(f);storageGrant(f,request);f.as(()->{var input=storageReserve(f,request,null,-1);var reserved=storage.reserve(request,input,"storage-reserve");var id=reserved.receipt().resourceId();assertThat(storage.reserve(request,input,"storage-reserve").replayed()).isTrue();assertThat(storage.list(request,1).versions()).hasSize(1);
            assertCode(()->storage.bytes(request,id,null,"DOWNLOAD"),"STORAGE_STATE");var staged=storage.upload(request,id,0,storageData().length,new java.io.ByteArrayInputStream(storageData()));assertThat(staged.state()).isEqualTo("STAGED");var command=new com.pis.storage.StorageContracts.Command(input.confirmedCaseId(),staged.version());var ready=storage.finish(request,id,command,"storage-finish");assertThat(ready.state()).isEqualTo("READY");assertThat(storage.finish(request,id,command,"storage-finish")).isEqualTo(ready);
            assertThat(storage.bytes(request,id,"bytes=0-127","PREVIEW").bytes()).isEqualTo(java.util.Arrays.copyOf(storageData(),128));assertThat(storage.bytes(request,id,null,"DOWNLOAD").bytes()).isEqualTo(storageData());
            var second=storage.reserve(request,storageReserve(f,request,ready.assetId(),0),"new-version").receipt().resourceId();assertThat(second).isNotEqualTo(id);assertThat(storage.detail(request,second).ordinal()).isEqualTo(1);assertThat(storage.bytes(request,id,null,"DOWNLOAD").bytes()).isEqualTo(storageData());
            assertThatThrownBy(()->jdbc.update("UPDATE storage_version SET sha256=repeat('a',64),version=version+1 WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_finalize WHERE version_id=? AND state='DONE'",Long.class,id)).isEqualTo(1);return null;});
    }
    @Test void storageFailedStageIsDryRunOnlyAndDoesNotExposeBytes(){var f=new Fixture();var request=grossRequest(f);storageGrant(f,request);f.as(()->{var input=storageReserve(f,request,null,-1);var id=storage.reserve(request,input,"bad-stage").receipt().resourceId();assertCode(()->storage.upload(request,id,0,storageData().length,new java.io.ByteArrayInputStream(new byte[10])),"STORAGE_INTEGRITY");assertThat(storage.detail(request,id).state()).isEqualTo("FAILED");assertCode(()->storage.bytes(request,id,null,"DOWNLOAD"),"STORAGE_STATE");var dry=storage.cleanup(request);assertThat(dry.dryRun()).isTrue();assertThat(dry.action()).isEqualTo("NO_FILES_DELETED");assertThat(dry.failedStagingCandidates()).contains(id);assertThat(java.nio.file.Files.exists(STORAGE_ROOT.resolve(id+".part"))).isTrue();return null;});}
    @Test void storageFileCompletionSurvivesDatabaseAuditRollbackAndReconcilesExactBytes(){var f=new Fixture();var request=grossRequest(f);storageGrant(f,request);var input=f.as(()->storageReserve(f,request,null,-1));var id=f.as(()->storage.reserve(request,input,"file-before-db").receipt().resourceId());var staged=f.as(()->storage.upload(request,id,0,storageData().length,new java.io.ByteArrayInputStream(storageData())));var command=new com.pis.storage.StorageContracts.Command(input.confirmedCaseId(),staged.version());
        jdbc.execute("CREATE FUNCTION reject_storage_ready() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STORAGE_READY_V1' THEN RAISE EXCEPTION 'Synthetic DB outage';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_storage_ready BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_storage_ready()");
        try{f.as(()->{assertThatThrownBy(()->storage.finish(request,id,command,"finalize-outage")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(storage.detail(request,id).state()).isEqualTo("FINALIZING");assertCode(()->storage.bytes(request,id,null,"DOWNLOAD"),"STORAGE_STATE");return null;});}finally{jdbc.execute("DROP TRIGGER reject_storage_ready ON audit_event");jdbc.execute("DROP FUNCTION reject_storage_ready()");}
        f.as(()->{assertThat(storage.finish(request,id,command,"finalize-outage").state()).isEqualTo("READY");assertThat(storage.bytes(request,id,null,"DOWNLOAD").bytes()).isEqualTo(storageData());return null;});
    }
    @Test void storageStageRecoveryAndRevokedScopeRemainFailClosed()throws Exception{var f=new Fixture();var request=grossRequest(f);storageGrant(f,request);var id=f.as(()->storage.reserve(request,storageReserve(f,request,null,-1),"recover-stage").receipt().resourceId());jdbc.update("UPDATE storage_version SET state='UPLOADING',version=version+1 WHERE id=?",id);storageProvider.stage(id,new java.io.ByteArrayInputStream(storageData()),storageData().length,com.pis.report.SyntheticPdf.sha256(storageData()));f.as(()->{assertThat(storage.reconcile(request,id).state()).isEqualTo("STAGED");return null;});var stranger=new Fixture();var other=grossRequest(stranger);storageGrant(stranger,other);stranger.as(()->{assertCode(()->storage.detail(request,id),"STORAGE_NOT_FOUND");return null;});jdbc.update("UPDATE storage_case_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);f.as(()->{assertCode(()->storage.detail(request,id),"STORAGE_NOT_FOUND");assertCode(()->storage.reserve(request,storageReserve(f,request,null,-1),"revoked"),"STORAGE_NOT_FOUND");return null;});}
    @Test void storageQuotaRaceReservesOnlyOneBoundedOriginal()throws Exception{var f=new Fixture();var request=grossRequest(f);storageGrant(f,request);jdbc.update("INSERT INTO storage_quota(hospital_id,reserved_bytes) VALUES(?,402653184)",f.hospital);var input=f.as(()->{var ordinary=storageReserve(f,request,null,-1);return new com.pis.storage.StorageContracts.Reserve(null,-1L,ordinary.confirmedCaseId(),67108864L,ordinary.sha256(),ordinary.purpose(),ordinary.mediaType());});try(var pool=Executors.newVirtualThreadPerTaskExecutor()){java.util.function.Function<String,String> run=key->f.as(()->{try{storage.reserve(request,input,key);return "OK";}catch(ApiException e){return e.code();}});var a=pool.submit(()->run.apply("quota-a"));var b=pool.submit(()->run.apply("quota-b"));assertThat(List.of(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","STORAGE_QUOTA");}assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM storage_quota WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(536870912);assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_version WHERE request_id=?",Long.class,request)).isEqualTo(1);}

    @Test void storageHttpKeepsAuthenticationCsrfRangeAndStrictMetadata()throws Exception {
        var f=new Fixture();var rid=grossRequest(f);storageGrant(f,rid);var input=f.as(()->storageReserve(f,rid,null,-1));var id=f.as(()->{var v=storage.reserve(rid,input,"http-storage").receipt().resourceId();var stage=storage.upload(rid,v,0,storageData().length,new java.io.ByteArrayInputStream(storageData()));storage.finish(rid,v,new com.pis.storage.StorageContracts.Command(input.confirmedCaseId(),stage.version()),"http-finish");return v;});
        var anonymous=new Browser();String base="/api/requests/"+rid+"/storage";
        assertHttpError(anonymous.send("GET",base,null,null,false),401,"UNAUTHENTICATED");
        var b=new Browser();String password="Synthetic-storage-http-42!";jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(password),f.user);
        assertThat(b.send("POST","/api/auth/login","username="+f.principal.getUsername()+"&password="+java.net.URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8),b.csrf(),true).statusCode()).isEqualTo(204);
        assertHttpError(b.send("POST",base+"/cleanup-dry-run","{}",null,false),403,"CSRF_INVALID");
        var uri=java.net.URI.create("http://127.0.0.1:"+port+base+"/"+id+"/bytes");
        var get=java.net.http.HttpRequest.newBuilder(uri).header("Range","bytes=0-127").header("X-Storage-Purpose","PREVIEW").GET().build();
        var response=b.client.send(get,java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(206);assertThat(response.body()).isEqualTo(java.util.Arrays.copyOf(storageData(),128));assertThat(response.headers().firstValue("Content-Range")).contains("bytes 0-127/65536");assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");assertThat(response.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"synthetic-"+id+".bin\"");
        var invalid=java.net.http.HttpRequest.newBuilder(uri).header("Range","bytes=-1").header("X-Storage-Purpose","DOWNLOAD").GET().build();assertThat(b.client.send(invalid,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(416);
        assertThat(b.send("GET",base+"/"+id+"/bytes",null,null,false).statusCode()).isEqualTo(400);
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(input);assertThat(b.send("POST",base,body.replace("SYNTHETIC_ORIGINAL","REAL_PATIENT"),b.csrf(),false).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE storage_case_grant SET revoked_at=statement_timestamp() WHERE user_id=?",f.user);assertThat(b.client.send(get,java.net.http.HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(404);
    }
    @Test void storageReservationAuditRollbackPreservesQuotaAndOriginalKey(){var f=new Fixture();var rid=grossRequest(f);storageGrant(f,rid);var input=f.as(()->storageReserve(f,rid,null,-1));
        jdbc.execute("CREATE FUNCTION reject_storage_reserve() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STORAGE_RESERVE_V1' THEN RAISE EXCEPTION 'Synthetic audit outage';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_storage_reserve BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_storage_reserve()");
        try{f.as(()->{assertThatThrownBy(()->storage.reserve(rid,input,"atomic-reserve")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_version WHERE request_id=?",Long.class,rid)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_quota WHERE hospital_id=?",Long.class,f.hospital)).isZero();}finally{jdbc.execute("DROP TRIGGER reject_storage_reserve ON audit_event");jdbc.execute("DROP FUNCTION reject_storage_reserve()");}
        f.as(()->{assertThat(storage.reserve(rid,input,"atomic-reserve").replayed()).isFalse();return null;});
    }
    @Test void storageReadBudgetAndIdentityRevocationBlockExactVersion(){var f=new Fixture();var d=diagnosisSetup(f);storageGrant(f,d.request());f.as(()->{var input=storageReserve(f,d.request(),null,-1);var id=storage.reserve(d.request(),input,"rate-create").receipt().resourceId();var stage=storage.upload(d.request(),id,0,storageData().length,new java.io.ByteArrayInputStream(storageData()));storage.finish(d.request(),id,new com.pis.storage.StorageContracts.Command(input.confirmedCaseId(),stage.version()),"rate-finish");
        jdbc.update("INSERT INTO storage_read_budget(user_id,minute,requests,bytes) SELECT ?,date_trunc('minute',statement_timestamp())+n*interval '1 minute',60,0 FROM generate_series(0,1) n",f.user);assertCode(()->storage.bytes(d.request(),id,"bytes=0-127","PREVIEW"),"STORAGE_RATE");
        jdbc.update("UPDATE quality_head SET state='IDENTITY_MISMATCH',version=version+1 WHERE material_id=?",d.slide());assertCode(()->storage.detail(d.request(),id),"STORAGE_ISOLATED");return null;});
    }

    @Test void storageConcurrentUploadUsesCasAndNewVersionHeadCannotFork()throws Exception {
        var f=new Fixture();var rid=grossRequest(f);storageGrant(f,rid);var id=f.as(()->storage.reserve(rid,storageReserve(f,rid,null,-1),"upload-race").receipt().resourceId());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            java.util.concurrent.Callable<String> upload=()->f.as(()->{try{return storage.upload(rid,id,0,storageData().length,new java.io.ByteArrayInputStream(storageData())).state();}catch(ApiException e){return e.code();}});
            var a=pool.submit(upload);var b=pool.submit(upload);assertThat(List.of(a.get(8,TimeUnit.SECONDS),b.get(8,TimeUnit.SECONDS))).containsExactlyInAnyOrder("STAGED","VERSION_CONFLICT");
            var current=f.as(()->storage.detail(rid,id));var input=f.as(()->storageReserve(f,rid,current.assetId(),0));
            java.util.function.Function<String,String> create=key->f.as(()->{try{storage.reserve(rid,input,key);return "OK";}catch(ApiException e){return e.code();}});
            var c=pool.submit(()->create.apply("head-a"));var d=pool.submit(()->create.apply("head-b"));assertThat(List.of(c.get(8,TimeUnit.SECONDS),d.get(8,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_version WHERE request_id=?",Long.class,rid)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM storage_quota WHERE hospital_id=?",Long.class,f.hospital)).isEqualTo(262144);
    }
    @Test void storageStagedAuditFailureRecoversBytesWithoutRepeatingUpload(){var f=new Fixture();var rid=grossRequest(f);storageGrant(f,rid);var id=f.as(()->storage.reserve(rid,storageReserve(f,rid,null,-1),"stage-db-failure").receipt().resourceId());
        jdbc.execute("CREATE FUNCTION reject_storage_stage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STORAGE_STAGED_V1' THEN RAISE EXCEPTION 'Synthetic stage audit outage';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_storage_stage BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_storage_stage()");
        try{f.as(()->{assertThatThrownBy(()->storage.upload(rid,id,0,storageData().length,new java.io.ByteArrayInputStream(storageData()))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(storage.detail(rid,id).state()).isEqualTo("UPLOADING");assertCode(()->storage.bytes(rid,id,null,"DOWNLOAD"),"STORAGE_STATE");return null;});}finally{jdbc.execute("DROP TRIGGER reject_storage_stage ON audit_event");jdbc.execute("DROP FUNCTION reject_storage_stage()");}
        f.as(()->{assertThat(storage.reconcile(rid,id).state()).isEqualTo("STAGED");assertCode(()->storage.upload(rid,id,0,storageData().length,new java.io.ByteArrayInputStream(storageData())),"VERSION_CONFLICT");return null;});
    }

    @Test void storageDownloadAuditFailureWithholdsBytesAndKeepsAttemptBudget(){var f=new Fixture();var rid=grossRequest(f);storageGrant(f,rid);var id=f.as(()->{var input=storageReserve(f,rid,null,-1);var object=storage.reserve(rid,input,"download-audit").receipt().resourceId();var staged=storage.upload(rid,object,0,storageData().length,new java.io.ByteArrayInputStream(storageData()));storage.finish(rid,object,new com.pis.storage.StorageContracts.Command(input.confirmedCaseId(),staged.version()),"download-finish");return object;});
        jdbc.execute("CREATE FUNCTION reject_storage_download() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='STORAGE_DOWNLOAD_V1' THEN RAISE EXCEPTION 'Synthetic read audit outage';END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER reject_storage_download BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_storage_download()");
        try{f.as(()->{assertThatThrownBy(()->storage.bytes(rid,id,null,"DOWNLOAD")).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;});assertThat(jdbc.queryForObject("SELECT sum(requests) FROM storage_read_budget WHERE user_id=?",Long.class,f.user)).isEqualTo(1);}finally{jdbc.execute("DROP TRIGGER reject_storage_download ON audit_event");jdbc.execute("DROP FUNCTION reject_storage_download()");}
        f.as(()->{assertThat(storage.bytes(rid,id,null,"DOWNLOAD").bytes()).isEqualTo(storageData());return null;});
    }

}
