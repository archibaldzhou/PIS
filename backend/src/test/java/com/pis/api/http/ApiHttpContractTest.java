package com.pis.api.http;

import com.pis.PisApplication;
import com.pis.api.TraceIdFilter;
import com.pis.database.PostgresTestDatabase;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.pis.api.http.ApiHttpTestConfiguration.BEFORE_ERROR_TRACE;
import static com.pis.api.http.ApiHttpTestConfiguration.OPERATION;
import static com.pis.api.http.ApiHttpTestConfiguration.RESOURCE_TYPE;
import static com.pis.api.http.ApiHttpTestConfiguration.ROOT;
import static com.pis.api.http.ApiHttpTestConfiguration.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded-server HTTP, the full session/CSRF security chain, and disposable PostgreSQL 17. */
@SpringBootTest(classes = PisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiHttpTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiHttpContractTest {
    private static final PostgresTestDatabase DATABASE = new PostgresTestDatabase();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Synthetic-api-http-42!";
    private static final String FORGED_TRACE = "11111111-1111-4111-8111-111111111111";
    private static final String VALID_BODY = "{\"label\":\"synthetic\",\"count\":1,\"mode\":\"SYNTHETIC\"}";
    private static final String INITIAL_COMMAND = "{\"expectedVersion\":0,\"increment\":1}";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) { DATABASE.register(registry); }

    @AfterAll
    static void cleanup() throws Exception { DATABASE.close(); }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void createNonClinicalProbeTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS http_probe_resource (
                id uuid PRIMARY KEY, hospital_id uuid NOT NULL REFERENCES hospital(id),
                counter integer NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 0)
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS http_probe_permission (
                actor_id uuid NOT NULL REFERENCES app_user(id), hospital_id uuid NOT NULL REFERENCES hospital(id),
                resource_id uuid NOT NULL REFERENCES http_probe_resource(id), enabled boolean NOT NULL DEFAULT true,
                PRIMARY KEY (actor_id, hospital_id, resource_id))
            """);
    }

    @Test
    void anonymousAndMissingCsrfFailuresUseSafeProblemsWithServerOwnedTraces() throws Exception {
        var browser = new Browser();
        for (String path : List.of("/api/auth/me", "/api/patients", ROOT + "/guarded")) {
            assertProblem(browser.get(path), 401, "UNAUTHENTICATED");
        }
        assertProblem(browser.postForm("/api/auth/login", "username=" + SECRET + "&password=" + SECRET, null),
            403, "CSRF_INVALID");
        assertProblem(browser.post(ROOT + "/body", VALID_BODY, null, null), 403, "CSRF_INVALID");
    }

    @Test
    void methodSecurityAndWrappedDenialsRemain403AndProductionRoutesStayClosed() throws Exception {
        var browser = login(account());
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertProblem(browser.get(ROOT + "/guarded"), 403, "ACCESS_DENIED");
        assertProblem(browser.get(ROOT + "/wrapped-denied"), 403, "ACCESS_DENIED");
        assertProblem(browser.get("/api/patients"), 403, "ACCESS_DENIED");
        assertProblem(browser.get("/error"), 403, "ACCESS_DENIED");
    }

    @Test
    void invalidDtoExposesOnlyDeclaredFieldsAndStaticConstraintCodes() throws Exception {
        var browser = login(account());
        var valid = browser.post(ROOT + "/body", VALID_BODY, browser.csrf(), null);
        assertThat(valid.statusCode()).isEqualTo(200);
        var problem = assertProblem(browser.post(ROOT + "/body?private=" + SECRET,
            "{\"label\":\"\",\"count\":0,\"mode\":\"SYNTHETIC\"}", browser.csrf(), null),
            400, "VALIDATION_FAILED");
        assertThat(problem.path("violations").size()).isEqualTo(2);
        assertViolation(problem.path("violations").get(0), "count", "Min");
        assertViolation(problem.path("violations").get(1), "label", "NotBlank");
    }

    @Test
    void malformedUnknownDuplicateCoercedAndEnumInputsAreRejectedBeforeControllerUse() throws Exception {
        var browser = login(account());
        String csrf = browser.csrf();
        for (String body : List.of(
                "{\"label\":\"" + SECRET,
                "{\"label\":\"synthetic\",\"count\":1,\"mode\":\"SYNTHETIC\",\"actorUserId\":\"" + SECRET + "\"}",
                "{\"label\":\"synthetic\",\"label\":\"" + SECRET + "\",\"count\":1,\"mode\":\"SYNTHETIC\"}",
                "{\"label\":\"synthetic\",\"count\":\"1\",\"mode\":\"SYNTHETIC\"}",
                "{\"label\":\"synthetic\",\"count\":1.5,\"mode\":\"SYNTHETIC\"}",
                "{\"label\":\"synthetic\",\"count\":1,\"mode\":0}",
                "{\"label\":\"synthetic\",\"count\":1,\"mode\":\"" + SECRET + "\"}",
                VALID_BODY + " {}")) {
            assertProblem(browser.post(ROOT + "/body", body, csrf, null), 400, "INVALID_JSON");
        }
    }

    @Test
    void missingTypedAndValidatedQueryParametersUseTheProblemContract() throws Exception {
        var browser = login(account());
        assertProblem(browser.get(ROOT + "/query"), 400, "MISSING_PARAMETER");
        assertProblem(browser.get(ROOT + "/query?count=" + SECRET + "&mode=SYNTHETIC"), 400, "TYPE_MISMATCH");
        assertProblem(browser.get(ROOT + "/query?count=1&mode=" + SECRET), 400, "TYPE_MISMATCH");
        var problem = assertProblem(browser.get(ROOT + "/query?count=0&mode=SYNTHETIC"), 400, "VALIDATION_FAILED");
        assertViolation(problem.path("violations").get(0), "count", "Min");
    }

    @Test
    void returnValidationAndUnexpectedExceptionsAreSafe500Failures() throws Exception {
        var browser = login(account());
        for (String path : List.of("/invalid-return", "/throw")) {
            var problem = assertProblem(browser.get(ROOT + path + "?private=" + SECRET), 500, "INTERNAL_ERROR");
            assertThat(problem.has("violations")).isFalse();
        }
    }

    @Test
    void servletSendErrorUsesTheSameContractAndTraceAcrossErrorDispatch() throws Exception {
        var browser = login(account());
        for (int status : List.of(404, 500)) {
            var response = browser.get(ROOT + "/send-error/" + status + "?private=" + SECRET);
            assertProblem(response, status, status == 500 ? "INTERNAL_ERROR" : "NOT_FOUND");
            assertThat(response.headers().firstValue(BEFORE_ERROR_TRACE)).contains(trace(response));
        }
    }

    @Test
    void realLoginCsrfCommandAndReplayReturnOneReceiptWithOneMutationAndAudit() throws Exception {
        var user = account();
        var probe = probe(user);
        var browser = login(user);
        String csrf = browser.csrf();
        String key = UUID.randomUUID().toString();
        var first = browser.post(probePath(probe), INITIAL_COMMAND, csrf, key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");
        var receipt = JSON.readTree(first.body());
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("status", "resourceType", "resourceId", "version");
        assertThat(receipt.path("resourceId").stringValue()).isEqualTo(probe.id().toString());
        assertThat(receipt.path("resourceType").stringValue()).isEqualTo(RESOURCE_TYPE);
        assertThat(receipt.path("version").longValue()).isEqualTo(1);
        var replay = browser.post(probePath(probe), "{ \"increment\": 1, \"expectedVersion\": 0 }", csrf, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(JSON.readTree(replay.body())).isEqualTo(receipt);
        assertThat(trace(replay)).isNotEqualTo(trace(first));
        assertState(probe, 1, 1, 1, 1);
        var audit = jdbc.queryForMap("SELECT * FROM audit_event WHERE resource_id = ?", probe.id());
        assertThat(audit.get("actor_user_id")).isEqualTo(user.id());
        assertThat(((Number) audit.get("actor_auth_version")).longValue()).isEqualTo(
            jdbc.queryForObject("SELECT auth_version FROM app_user WHERE id = ?", Long.class, user.id()));
        assertThat(audit.get("hospital_id")).isEqualTo(probe.hospital());
        assertThat(audit.get("operation_code")).isEqualTo(OPERATION);
        assertThat(audit.get("resource_type")).isEqualTo(RESOURCE_TYPE);
        assertThat(audit.get("trace_id")).isEqualTo(UUID.fromString(trace(first)));
        assertThat(((Number) audit.get("previous_version")).longValue()).isZero();
        assertThat(((Number) audit.get("result_version")).longValue()).isEqualTo(1);
        var saved = jdbc.queryForMap("SELECT * FROM idempotency_command WHERE hospital_id = ?", probe.hospital());
        assertThat(saved.get("actor_user_id")).isEqualTo(user.id());
        assertThat(saved.get("state")).isEqualTo("SUCCEEDED");
        assertThat((byte[]) saved.get("key_hash")).hasSize(32);
        assertThat((byte[]) saved.get("request_digest")).hasSize(32);
        assertThat(first.body()).doesNotContain("actor", "hospital", SECRET, "password");
    }

    @Test
    void reusingAKeyWithAnotherBodyConflictsWithoutAnotherMutationOrAudit() throws Exception {
        var user = account(); var probe = probe(user); var browser = login(user);
        String csrf = browser.csrf(); String key = UUID.randomUUID().toString();
        assertThat(browser.post(probePath(probe), INITIAL_COMMAND, csrf, key).statusCode()).isEqualTo(200);
        assertProblem(browser.post(probePath(probe), "{\"expectedVersion\":0,\"increment\":2}", csrf, key),
            409, "IDEMPOTENCY_KEY_REUSED");
        assertState(probe, 1, 1, 1, 1);
    }

    @Test
    void aDifferentResourcePathIsPartOfTheCommandDigestWithinTheSameHospital() throws Exception {
        var user = account(); var firstProbe = probe(user); var browser = login(user);
        var secondProbe = new Probe(UUID.randomUUID(), firstProbe.hospital());
        jdbc.update("INSERT INTO http_probe_resource(id, hospital_id) VALUES (?, ?)",
            secondProbe.id(), secondProbe.hospital());
        jdbc.update("INSERT INTO http_probe_permission(actor_id, hospital_id, resource_id) VALUES (?, ?, ?)",
            user.id(), secondProbe.hospital(), secondProbe.id());
        String csrf = browser.csrf(); String key = UUID.randomUUID().toString();
        assertThat(browser.post(probePath(firstProbe), INITIAL_COMMAND, csrf, key).statusCode()).isEqualTo(200);
        assertProblem(browser.post(probePath(secondProbe), INITIAL_COMMAND, csrf, key),
            409, "IDEMPOTENCY_KEY_REUSED");
        assertState(firstProbe, 1, 1, 1, 1);
        assertState(secondProbe, 0, 0, 0, 1);
    }

    @Test
    void revokingResourcePermissionBlocksReplayWithoutExposingTheSavedReceipt() throws Exception {
        var user = account(); var probe = probe(user); var browser = login(user);
        String csrf = browser.csrf(); String key = UUID.randomUUID().toString();
        assertThat(browser.post(probePath(probe), INITIAL_COMMAND, csrf, key).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE http_probe_permission SET enabled = false WHERE actor_id = ? AND resource_id = ?",
            user.id(), probe.id());
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
        var response = browser.post(probePath(probe), INITIAL_COMMAND, csrf, key);
        assertProblem(response, 403, "ACCESS_DENIED");
        assertThat(response.body()).doesNotContain(probe.id().toString(), "resourceId", "resourceType");
        assertState(probe, 1, 1, 1, 1);
    }

    @Test
    void rejectedCommandsLeaveNoMutationAuditOrIdempotencyReservation() throws Exception {
        var user = account(); var probe = probe(user); var browser = login(user);
        String csrf = browser.csrf(); String key = UUID.randomUUID().toString();
        assertProblem(browser.post(probePath(probe), INITIAL_COMMAND, null, key), 403, "CSRF_INVALID");
        assertProblem(browser.post(probePath(probe), INITIAL_COMMAND, csrf, null), 400, "IDEMPOTENCY_KEY_REQUIRED");
        assertProblem(browser.post(probePath(probe), INITIAL_COMMAND, csrf, "invalid key"), 400, "IDEMPOTENCY_KEY_INVALID");
        assertProblem(browser.post(probePath(probe), "{\"expectedVersion\":1,\"increment\":1}", csrf, key),
            409, "VERSION_CONFLICT");
        assertProblem(browser.post(probePath(probe),
            "{\"expectedVersion\":0,\"increment\":1,\"actorUserId\":\"" + UUID.randomUUID() + "\"}", csrf, key),
            400, "INVALID_JSON");
        assertProblem(browser.post(probePath(probe),
            "{\"expectedVersion\":0,\"increment\":1,\"hospitalId\":\"" + UUID.randomUUID() + "\"}", csrf, key),
            400, "INVALID_JSON");
        var outsider = login(account());
        assertProblem(outsider.post(probePath(probe), INITIAL_COMMAND, outsider.csrf(), key), 403, "ACCESS_DENIED");
        assertProblem(browser.post(ROOT + "/probes/" + UUID.randomUUID(), INITIAL_COMMAND, csrf, key),
            403, "ACCESS_DENIED");
        assertState(probe, 0, 0, 0, 0);
    }

    private Account account() {
        UUID id = UUID.randomUUID(); String username = "api.http." + id;
        jdbc.update("""
            INSERT INTO app_user(id, username, display_name, password_hash, enabled, synthetic_only)
            VALUES (?, ?, 'Synthetic API HTTP account', ?, true, true)
            """, id, username, encoder.encode(PASSWORD));
        return new Account(id, username);
    }

    private Probe probe(Account user) {
        UUID hospital = UUID.randomUUID(); UUID resource = UUID.randomUUID();
        jdbc.update("INSERT INTO hospital(id, code, name) VALUES (?, ?, 'Synthetic API HTTP hospital')",
            hospital, hospital.toString());
        jdbc.update("INSERT INTO http_probe_resource(id, hospital_id) VALUES (?, ?)", resource, hospital);
        jdbc.update("INSERT INTO http_probe_permission(actor_id, hospital_id, resource_id) VALUES (?, ?, ?)",
            user.id(), hospital, resource);
        return new Probe(resource, hospital);
    }

    private void assertState(Probe probe, int counter, long version, int audits, int commands) {
        assertThat(jdbc.queryForObject("SELECT counter FROM http_probe_resource WHERE id = ?", Integer.class, probe.id()))
            .isEqualTo(counter);
        assertThat(jdbc.queryForObject("SELECT version FROM http_probe_resource WHERE id = ?", Long.class, probe.id()))
            .isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id = ?", Integer.class, probe.id()))
            .isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_command WHERE hospital_id = ?",
            Integer.class, probe.hospital())).isEqualTo(commands);
    }

    private Browser login(Account user) throws Exception {
        var browser = new Browser();
        String loginBody = "username=" + URLEncoder.encode(user.username(), StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        assertThat(browser.postForm("/api/auth/login", loginBody, browser.csrf()).statusCode()).isEqualTo(204);
        return browser;
    }

    private static String probePath(Probe probe) { return ROOT + "/probes/" + probe.id(); }
    private record Account(UUID id, String username) { }
    private record Probe(UUID id, UUID hospital) { }

    private static JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        var problem = JSON.readTree(response.body());
        assertThat(problem.path("status").intValue()).isEqualTo(status);
        assertThat(problem.path("code").stringValue()).isEqualTo(code);
        assertThat(problem.path("type").stringValue()).isEqualTo("urn:pis:problem:"
            + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        assertThat(problem.path("title").stringValue()).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(problem.path("detail").stringValue()).isNotBlank();
        String trace = trace(response);
        assertThat(problem.path("traceId").stringValue()).isEqualTo(trace);
        assertThat(problem.path("instance").stringValue()).isEqualTo("urn:uuid:" + trace);
        assertThat(response.body()).doesNotContain(SECRET, "SQL", "password", "rejectedValue", "stackTrace",
            "java.lang", "properties", ROOT, "constraintDescriptor", "arguments");
        return problem;
    }

    private static String trace(HttpResponse<String> response) {
        String value = response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow();
        assertThat(UUID.fromString(value).version()).isEqualTo(4);
        assertThat(value).isNotEqualTo(FORGED_TRACE);
        return value;
    }

    private static void assertViolation(JsonNode violation, String field, String code) {
        assertThat(violation.propertyNames()).containsExactlyInAnyOrder("field", "code");
        assertThat(violation.path("field").stringValue()).isEqualTo(field);
        assertThat(violation.path("code").stringValue()).isEqualTo(code);
    }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

        String csrf() throws Exception {
            var response = get("/api/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body()).path("token").stringValue();
        }

        HttpResponse<String> get(String path) throws Exception { return send(path, null, null, null, null); }
        HttpResponse<String> post(String path, String body, String csrf, String key) throws Exception {
            return send(path, body, csrf, key, "application/json");
        }
        HttpResponse<String> postForm(String path, String body, String csrf) throws Exception {
            return send(path, body, csrf, null, "application/x-www-form-urlencoded");
        }

        private HttpResponse<String> send(String path, String body, String csrf, String key, String contentType)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header(TraceIdFilter.HEADER, FORGED_TRACE);
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (key != null) request.header("Idempotency-Key", key);
            if (body == null) request.GET();
            else request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
