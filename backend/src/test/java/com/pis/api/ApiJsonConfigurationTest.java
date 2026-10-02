package com.pis.api;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ApiJsonConfigurationTest {
    private static final String VALID = "{\"label\":\"synthetic\",\"count\":2,\"enabled\":true,\"mode\":\"FIRST\"}";
    private static final JsonMapper JSON = mapper();

    @Test
    void acceptsExactlyTypedInputWithoutChangingTheValues() {
        assertThat(JSON.readValue(VALID, SyntheticCommand.class))
            .isEqualTo(new SyntheticCommand("synthetic", 2, true, Mode.FIRST));
    }

    @Test
    void bootAutoConfiguredMapperReceivesTheStrictInputCustomizer() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(ApiJsonConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(JsonMapper.class);
                var mapper = context.getBean(JsonMapper.class);
                assertThat(mapper.readValue(VALID, SyntheticCommand.class).count()).isEqualTo(2);
                invalidCommands().forEach(body -> assertThatThrownBy(() -> mapper.readValue(body, SyntheticCommand.class))
                    .as("Boot-configured mapper must reject %s", body).isInstanceOf(JacksonException.class));
            });
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsAmbiguousOrCoercedInput(String body) {
        assertThatThrownBy(() -> JSON.readValue(body, SyntheticCommand.class))
            .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void mvcReturnsSafeInvalidJsonForStrictInputFailures(String body) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StrictController())
            .setControllerAdvice(new ApiExceptionAdvice())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON.rebuild()))
            .addFilters(new TraceIdFilter()).build();
        var response = mvc.perform(post("/test-strict/command").contentType(MediaType.APPLICATION_JSON)
            .content(body).queryParam("private", "synthetic-sensitive-query")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var problem = JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.path("code").stringValue()).isEqualTo("INVALID_JSON");
        assertThat(problem.path("traceId").stringValue()).isEqualTo(response.getHeader(TraceIdFilter.HEADER));
        assertThat(problem.path("instance").stringValue()).isEqualTo("urn:uuid:" + response.getHeader(TraceIdFilter.HEADER));
        assertThat(response.getContentAsString()).doesNotContain("synthetic-sensitive", "rejectedValue",
            "properties", "UnrecognizedPropertyException", "MismatchedInputException", "test-strict");
    }

    static Stream<String> invalidCommands() {
        return Stream.of(
            VALID.replace("\"label\":", "\"synthetic-sensitive-extra\":\"private\",\"label\":"),
            VALID.replace("\"count\":2", "\"count\":2,\"count\":3"),
            VALID + " {}",
            VALID + " true",
            VALID.replace("\"count\":2", "\"count\":\"2\""),
            VALID.replace("\"enabled\":true", "\"enabled\":\"true\""),
            VALID.replace("\"enabled\":true", "\"enabled\":1"),
            VALID.replace("\"label\":\"synthetic\"", "\"label\":42"),
            VALID.replace("\"label\":\"synthetic\"", "\"label\":4.2"),
            VALID.replace("\"label\":\"synthetic\"", "\"label\":true"),
            VALID.replace("\"count\":2", "\"count\":2.5"),
            VALID.replace("\"count\":2", "\"count\":2.0"),
            VALID.replace("\"mode\":\"FIRST\"", "\"mode\":0"),
            VALID.replace("\"mode\":\"FIRST\"", "\"mode\":\"0\""),
            VALID.replace("\"count\":2", "\"count\":null"),
            VALID.replace("\"enabled\":true", "\"enabled\":null")
        );
    }

    private static JsonMapper mapper() {
        var builder = JsonMapper.builder();
        new ApiJsonConfiguration().strictCommandInputs().customize(builder);
        return builder.build();
    }

    enum Mode { FIRST, SECOND }
    record SyntheticCommand(String label, int count, boolean enabled, Mode mode) { }

    @RestController
    static final class StrictController {
        @PostMapping("/test-strict/command")
        Map<String, Boolean> command(@RequestBody SyntheticCommand command) { return Map.of("ok", true); }
    }
}
