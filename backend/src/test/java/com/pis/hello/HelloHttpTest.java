package com.pis.hello;

import com.pis.PisApplication;
import com.pis.database.PostgresTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HelloHttpTest {
    private static final PostgresTestDatabase DATABASE = new PostgresTestDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        DATABASE.register(registry);
    }

    @AfterAll
    static void removeOwnedTestSchema() throws Exception {
        DATABASE.close();
    }

    @LocalServerPort
    int port;

    @Test
    void returnsHelloWorldOverHttp() throws Exception {
        var response = get("/api/hello");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        var json = JsonMapper.builder().build().readTree(response.body());
        assertThat(json.path("message").stringValue()).isEqualTo("Hello World");
        assertThat(json.path("application").stringValue()).isEqualTo("PIS");
    }

    @Test
    void exposesOnlyHealthWithoutDatabaseDetails() throws Exception {
        var readiness = get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        var json = JsonMapper.builder().build().readTree(readiness.body());
        assertThat(json.path("status").stringValue()).isEqualTo("UP");
        assertThat(json.has("components")).isFalse();
        assertThat(json.has("details")).isFalse();
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/flyway").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
