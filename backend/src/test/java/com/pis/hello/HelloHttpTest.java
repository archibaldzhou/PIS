package com.pis.hello;

import com.pis.PisApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = PisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HelloHttpTest {
    @LocalServerPort
    int port;

    @Test
    void returnsHelloWorldOverHttp() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/hello"))
                .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
            assertThat(response.body()).contains("\"message\":\"Hello World\"", "\"application\":\"PIS\"");
        }
    }
}
