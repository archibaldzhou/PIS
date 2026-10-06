package com.pis.accession;

import com.pis.PisApplication;
import com.pis.api.ApiException;
import com.pis.database.PostgresTestDatabase;
import com.pis.security.AccountRepository;
import com.pis.security.PisPrincipal;
import com.pis.security.testfixture.E2eFixtureConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static com.pis.accession.RequestContracts.*;

@SpringBootTest(classes=PisApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="pis.workflow.development-enabled=true")
@ActiveProfiles("test")
@Import(E2eFixtureConfiguration.class)
class ManualRequestTest {
    static final PostgresTestDatabase DATABASE=new PostgresTestDatabase();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { DATABASE.register(r); }
    @AfterAll static void close() throws Exception { DATABASE.close(); }
    @Autowired RequestService service;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    <T> T as(String user, Supplier<T> work) {
        var previous=SecurityContextHolder.getContext().getAuthentication();
        var principal=new PisPrincipal(new AccountRepository(jdbc).findByUsername(user).orElseThrow());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
        try {
            if (previous!=null) return work.get();
            var result=new java.util.concurrent.atomic.AtomicReference<T>();
            new com.pis.api.TraceIdFilter().doFilter(new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(),(r,s)->result.set(work.get()));
            return result.get();
        } catch(java.io.IOException|jakarta.servlet.ServletException error) { throw new AssertionError(error); }
        finally { SecurityContextHolder.getContext().setAuthentication(previous); }
    }
    <T> T as(Supplier<T> work) { return as("synthetic.workflow.accession",work); }
    UUID scope() { return as(()->service.scopes().getFirst().id()); }
    ManualCreate command(String number) { return new ManualCreate(scope(),"合成手工患者",number,
        new Draft("合成开发资料",null,List.of(new ContainerInput("合成部位",Laterality.NONE,1,"",null)))); }
    long patients() { return jdbc.queryForObject("SELECT count(*) FROM patient",Long.class); }
    @Test void createsLinkedIdentityDraftAndOneAuditAndReplaysWithoutDuplicates() {
        var input=command("SYN-MANUAL-"+UUID.randomUUID()); var key=UUID.randomUUID().toString(); long before=patients();
        as(()->{
            var first=service.createManual(input,key); var detail=service.detail(first.receipt().resourceId());
            assertThat(detail.patientLabel()).isEqualTo(input.patientName());
            assertThat(detail.encounterNumber()).isEqualTo(input.encounterNumber());
            assertThat(detail.state()).isEqualTo("DRAFT");
            assertThat(service.createManual(input,key).receipt()).isEqualTo(first.receipt());
            assertThat(patients()).isEqualTo(before+1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=? AND operation_code='REQUEST_MANUAL_CREATE_V1'",Long.class,detail.id())).isEqualTo(1);
            assertThatThrownBy(()->service.createManual(new ManualCreate(input.scopeId(),"另一合成姓名",input.encounterNumber(),input.draft()),key)).isInstanceOf(ApiException.class);
            return null;
        });
    }
    @Test void duplicateNumberRollsBackNewPatientAndDoesNotMergeNames() {
        var first=command("SYN-DUP-"+UUID.randomUUID());
        as(()->{ service.createManual(first,UUID.randomUUID().toString()); long before=patients();
            assertThatThrownBy(()->service.createManual(first,UUID.randomUUID().toString())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ENCOUNTER_NUMBER_EXISTS"));
            assertThat(patients()).isEqualTo(before);
            service.createManual(command("SYN-OTHER-"+UUID.randomUUID()),UUID.randomUUID().toString());
            assertThat(patients()).isEqualTo(before+1); return null;
        });
    }
    @Test void readOnlyCrossScopeAndRevokedReplayCannotCreateIdentity() {
        var input=command("SYN-AUTH-"+UUID.randomUUID()); long before=patients();
        as("synthetic.reader",()->{ assertThatThrownBy(()->service.createManual(input,"readonly")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class); return null; });
        as("synthetic.workflow.reception",()->{ assertThatThrownBy(()->service.createManual(input,"foreign")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class); return null; });
        assertThat(patients()).isEqualTo(before);
        var key=UUID.randomUUID().toString(); as(()->service.createManual(input,key));
        jdbc.update("UPDATE workflow_grant SET revoked_at=statement_timestamp() WHERE scope_id=?",input.scopeId());
        try { as(()->{assertThatThrownBy(()->service.createManual(input,key)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);return null;}); }
        finally { jdbc.update("UPDATE workflow_grant SET revoked_at=NULL WHERE scope_id=?",input.scopeId()); }
    }
    @Test void auditFailureRollsBackAllIdentityAndAllowsSameKeyRetry() {
        var input=command("SYN-ROLLBACK-"+UUID.randomUUID()); var key=UUID.randomUUID().toString(); long before=patients();
        jdbc.execute("CREATE FUNCTION reject_manual_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_code='REQUEST_MANUAL_CREATE_V1' THEN RAISE EXCEPTION 'Synthetic failure';END IF;RETURN NEW;END $$");
        jdbc.execute("CREATE TRIGGER reject_manual_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_manual_audit()");
        try { as(()->{assertThatThrownBy(()->service.createManual(input,key)).isInstanceOf(org.springframework.dao.DataAccessException.class);return null;}); assertThat(patients()).isEqualTo(before); }
        finally { jdbc.execute("DROP TRIGGER reject_manual_audit ON audit_event");jdbc.execute("DROP FUNCTION reject_manual_audit()"); }
        as(()->service.createManual(input,key)); assertThat(patients()).isEqualTo(before+1);
    }
    @Test void concurrentSameNumberHasOneWinnerAndNoOrphanPatient() throws Exception {
        var input=command("SYN-RACE-"+UUID.randomUUID()); long before=patients();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<String> work=()->{gate.await(5,TimeUnit.SECONDS);return as(()->{try{service.createManual(input,UUID.randomUUID().toString());return "CREATED";}catch(ApiException e){return e.code();}});};
            var a=pool.submit(work);var b=pool.submit(work);
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("CREATED","ENCOUNTER_NUMBER_EXISTS");
            assertThat(patients()).isEqualTo(before+1);
        }
    }
    @Test void invalidIdentityCannotLeaveRows() {
        var good=command("SYN-VALIDATION-"+UUID.randomUUID()); long before=patients();
        as(()->{assertThatThrownBy(()->service.createManual(new ManualCreate(good.scopeId(),"  ",good.encounterNumber(),good.draft()),"invalid")).isInstanceOf(jakarta.validation.ConstraintViolationException.class);return null;});
        assertThat(patients()).isEqualTo(before);
    }
    @Test void httpRequiresLoginCsrfAndWritePermissionAndReturnsPersistedIdentity() throws Exception {
        var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var input=command("SYN-HTTP-MANUAL-"+UUID.randomUUID());
        String body=json.writeValueAsString(input),key=UUID.randomUUID().toString();
        java.util.function.Function<java.net.http.HttpRequest,java.net.http.HttpResponse<String>> send=request->{try{return client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());}catch(Exception e){throw new AssertionError(e);}};
        var uri=java.net.URI.create("http://127.0.0.1:"+port+"/api/requests/manual");
        var bare=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json").header("Idempotency-Key",key).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        assertThat(send.apply(bare.build()).statusCode()).isIn(401,403);
        var csrfUri=java.net.URI.create("http://127.0.0.1:"+port+"/api/auth/csrf");
        String token=json.readTree(send.apply(java.net.http.HttpRequest.newBuilder(csrfUri).GET().build()).body()).get("token").asString();
        var loginUri=java.net.URI.create("http://127.0.0.1:"+port+"/api/auth/login");
        var login=java.net.http.HttpRequest.newBuilder(loginUri).header("X-CSRF-TOKEN",token).header("Content-Type","application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("username=synthetic.workflow.accession&password=Synthetic-workflow-only-42%21")).build();
        assertThat(send.apply(login).statusCode()).isEqualTo(204);
        assertThat(send.apply(bare.build()).statusCode()).isEqualTo(403);
        token=json.readTree(send.apply(java.net.http.HttpRequest.newBuilder(csrfUri).GET().build()).body()).get("token").asString();
        var created=send.apply(bare.header("X-CSRF-TOKEN",token).build());
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).get("receipt").get("resourceId").asString();
        var detail=send.apply(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/requests/"+id)).GET().build());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).get("encounterNumber").asString()).isEqualTo(input.encounterNumber());
        assertThat(send.apply(bare.build()).statusCode()).isEqualTo(201);
        // A separately authenticated read-only account cannot call the new endpoint directly.
        var deniedCookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        var denied=java.net.http.HttpClient.newBuilder().cookieHandler(deniedCookies).build();
        var deniedCsrf=denied.send(java.net.http.HttpRequest.newBuilder(csrfUri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        String deniedToken=json.readTree(deniedCsrf.body()).get("token").asString();
        assertThat(denied.send(java.net.http.HttpRequest.newBuilder(loginUri).header("X-CSRF-TOKEN",deniedToken).header("Content-Type","application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString("username=synthetic.reader&password=Synthetic-test-only-42%21")).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
        deniedToken=json.readTree(denied.send(java.net.http.HttpRequest.newBuilder(csrfUri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).body()).get("token").asString();
        long before=patients();
        assertThat(denied.send(java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).header("X-CSRF-TOKEN",deniedToken).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(patients()).isEqualTo(before);
    }
}
