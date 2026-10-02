package com.pis.security;

import com.pis.PisApplication;
import com.pis.database.PostgresTestDatabase;
import com.pis.security.testfixture.SyntheticAccessTestConfiguration;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SyntheticAccessTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityHttpTest {
    private static final PostgresTestDatabase DATABASE = new PostgresTestDatabase();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Synthetic-http-test-42!";
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) { DATABASE.register(registry); }
    @AfterAll static void cleanup() throws Exception { DATABASE.close(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test void anonymousRequestsGetJson401AndLoginRequiresCsrf() throws Exception {
        var browser = new Browser();
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/hello").statusCode()).isEqualTo(401);
        assertThat(browser.get("/test/access/cases/" + UUID.randomUUID()).statusCode()).isEqualTo(401);
        var missing = browser.post("/api/auth/login", "username=missing&password=missing", null);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(missing.body()).path("code").stringValue()).isEqualTo("CSRF_INVALID");
        assertThat(browser.get("/api/patients").statusCode()).isEqualTo(401);
    }

    @Test void loginRotatesSessionAndRestoresIdentityOverRealHttp() throws Exception {
        var user = account(true);
        var browser = new Browser();
        var tokenResponse = browser.get("/api/auth/csrf");
        String token = JSON.readTree(tokenResponse.body()).path("token").stringValue();
        String before = browser.cookie();
        assertThat(tokenResponse.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(tokenResponse.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(browser.login(user, token).statusCode()).isEqualTo(204);
        assertThat(browser.cookie()).isNotEqualTo(before);
        var me = browser.get("/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(me.body()).path("id").stringValue()).isEqualTo(user.id().toString());
        assertThat(me.body()).doesNotContain("password", "authVersion", "password_hash");
        assertThat(browser.get("/api/hello").statusCode()).isEqualTo(200);
        assertThat(new Browser(before).get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test void unknownWrongPasswordAndDisabledAccountsHaveIdenticalFailureResponses() throws Exception {
        var enabled = account(true);
        var disabled = account(false);
        var browser = new Browser();
        String csrf = browser.csrf();
        var wrong = browser.post("/api/auth/login", form(enabled.username(), "wrong"), csrf);
        var unknown = browser.post("/api/auth/login", form("does-not-exist", PASSWORD), csrf);
        var off = browser.login(disabled, csrf);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(off.statusCode()).isEqualTo(401);
        var stableBodies = new java.util.ArrayList<tools.jackson.databind.node.ObjectNode>();
        var traces = new java.util.HashSet<String>();
        for (var response : java.util.List.of(wrong, unknown, off)) {
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
            var problem = (tools.jackson.databind.node.ObjectNode) JSON.readTree(response.body());
            String trace = problem.path("traceId").stringValue();
            assertThat(UUID.fromString(trace).version()).isEqualTo(4);
            assertThat(problem.path("instance").stringValue()).isEqualTo("urn:uuid:" + trace);
            assertThat(response.headers().firstValue("X-Trace-Id")).contains(trace);
            assertThat(problem.path("code").stringValue()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(problem.path("status").intValue()).isEqualTo(401);
            assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "traceId", "message");
            traces.add(trace);
            problem.remove("traceId");
            problem.remove("instance");
            stableBodies.add(problem);
        }
        assertThat(traces).hasSize(3);
        assertThat(stableBodies.get(0)).isEqualTo(stableBodies.get(1)).isEqualTo(stableBodies.get(2));
    }

    @Test void logoutRequiresFreshCsrfAndInvalidatesTheOldCookie() throws Exception {
        var user = account(true);
        var browser = new Browser();
        String oldToken = browser.csrf();
        assertThat(browser.login(user, oldToken).statusCode()).isEqualTo(204);
        String loggedInCookie = browser.cookie();
        assertThat(browser.post("/api/auth/logout", "", null).statusCode()).isEqualTo(403);
        assertThat(browser.post("/api/auth/logout", "", oldToken).statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/auth/logout").statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
        assertThat(browser.post("/api/auth/logout", "", browser.csrf()).statusCode()).isEqualTo(204);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(new Browser(loggedInCookie).get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(browser.login(user, browser.csrf()).statusCode()).isEqualTo(204);
    }

    @Test void disablingAnAccountRevokesBothIndependentSessionsOnTheirNextRequest() throws Exception {
        var user = account(true);
        var first = login(user);
        var second = login(user);
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", user.id());
        assertThat(first.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(second.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test void disableThenReenableStillRevokesUnobservedOldSession() throws Exception {
        var user = account(true);
        var browser = login(user);
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", user.id());
        jdbc.update("UPDATE app_user SET enabled = true WHERE id = ?", user.id());
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(login(user).get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void passwordChangeRevokesOldSession() throws Exception {
        var user = account(true);
        var browser = login(user);
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", encoder.encode("Different-synthetic-42!"), user.id());
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test void logoutDoesNotInvalidateAnotherSession() throws Exception {
        var user = account(true);
        var first = login(user);
        var second = login(user);
        assertThat(first.post("/api/auth/logout", "", first.csrf()).statusCode()).isEqualTo(204);
        assertThat(second.get("/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void oversizedUtf8PasswordIsRejectedWithoutTruncationOrServerError() throws Exception {
        var user = account(true);
        var browser = new Browser();
        assertThat(browser.post("/api/auth/login", form(user.username(), "密".repeat(25)), browser.csrf()).statusCode()).isEqualTo(401);
    }

    @Test void syntheticHttpProbeEnforcesObjectScopeAndSeparatesReadFromEdit() throws Exception {
        var user = account(true);
        var allowed = caseGraph();
        var forbidden = caseGraph();
        jdbc.update("""
            INSERT INTO user_role_scope (user_id, role_code, hospital_id, campus_id, department_id,
              scope_kind, case_filter) VALUES (?, 'CASE_READER_TEMPLATE', ?, ?, ?, 'DEPARTMENT', 'ALL_IN_SCOPE')
            """, user.id(), allowed.hospital(), allowed.campus(), allowed.department());
        var browser = login(user);
        assertThat(browser.get("/test/access/cases/" + allowed.id()).statusCode()).isEqualTo(204);
        assertThat(browser.get("/test/access/cases/" + forbidden.id()).statusCode()).isEqualTo(403);
        assertThat(browser.post("/test/access/cases/" + allowed.id(), "", browser.csrf()).statusCode()).isEqualTo(403);
        assertThat(browser.get("/test/access/cases/" + UUID.randomUUID()).statusCode()).isEqualTo(403);
        var noCsrf = browser.post("/test/access/cases/" + allowed.id(), "", null);
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(noCsrf.body()).path("code").stringValue()).isEqualTo("CSRF_INVALID");
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", user.id());
        var revoked = browser.get("/test/access/cases/" + allowed.id());
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(revoked.body()).path("code").stringValue()).isEqualTo("SESSION_EXPIRED");
    }

    private Account account(boolean enabled) {
        var id = UUID.randomUUID();
        var username = "test." + id;
        jdbc.update("INSERT INTO app_user(id, username, display_name, password_hash, enabled, synthetic_only) VALUES (?, ?, 'Synthetic HTTP account', ?, ?, true)",
            id, username, encoder.encode(PASSWORD), enabled);
        return new Account(id, username);
    }
    private Graph caseGraph() {
        var hospital = UUID.randomUUID(); var campus = UUID.randomUUID(); var department = UUID.randomUUID();
        var source = UUID.randomUUID(); var patient = UUID.randomUUID(); var request = UUID.randomUUID(); var id = UUID.randomUUID();
        jdbc.update("INSERT INTO hospital(id, code, name) VALUES (?, ?, 'Synthetic HTTP hospital')", hospital, hospital.toString());
        jdbc.update("INSERT INTO campus(id, hospital_id, code, name) VALUES (?, ?, 'C', 'Synthetic campus')", campus, hospital);
        jdbc.update("INSERT INTO department(id, hospital_id, code, name) VALUES (?, ?, 'D', 'Synthetic department')", department, hospital);
        jdbc.update("INSERT INTO department_campus(hospital_id, campus_id, department_id) VALUES (?, ?, ?)", hospital, campus, department);
        jdbc.update("INSERT INTO source_system(id, hospital_id, code, name) VALUES (?, ?, 'S', 'Synthetic source')", source, hospital);
        jdbc.update("INSERT INTO patient(id, hospital_id) VALUES (?, ?)", patient, hospital);
        jdbc.update("INSERT INTO pathology_request(id, hospital_id, patient_id, source_system_id, request_number) VALUES (?, ?, ?, ?, 'SYNTHETIC')", request, hospital, patient, source);
        jdbc.update("INSERT INTO pathology_case(id, hospital_id, request_id) VALUES (?, ?, ?)", id, hospital, request);
        jdbc.update("INSERT INTO case_access_scope(hospital_id, case_id, campus_id, owning_department_id) VALUES (?, ?, ?, ?)", hospital, id, campus, department);
        return new Graph(id, hospital, campus, department);
    }
    private Browser login(Account user) throws Exception {
        var browser = new Browser();
        assertThat(browser.login(user, browser.csrf()).statusCode()).isEqualTo(204);
        return browser;
    }
    private record Account(UUID id, String username) { }
    private record Graph(UUID id, UUID hospital, UUID campus, UUID department) { }
    private static String form(String username, String password) {
        return "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
    }
    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        private final String manualCookie;
        Browser() { manualCookie = null; }
        Browser(String cookie) { manualCookie = cookie; }
        String cookie() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("PIS_SESSION"))
                .findFirst().map(cookie -> cookie.getName() + "=" + cookie.getValue()).orElseThrow();
        }
        String csrf() throws Exception { return JSON.readTree(get("/api/auth/csrf").body()).path("token").stringValue(); }
        HttpResponse<String> login(Account account, String csrf) throws Exception { return post("/api/auth/login", form(account.username(), PASSWORD), csrf); }
        HttpResponse<String> get(String path) throws Exception { return send(path, null, null); }
        HttpResponse<String> post(String path, String body, String csrf) throws Exception { return send(path, body, csrf); }
        private HttpResponse<String> send(String path, String body, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
            if (manualCookie != null) { request.header("Cookie", manualCookie); }
            if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
            if (body == null) { request.GET(); }
            else { request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)); }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
