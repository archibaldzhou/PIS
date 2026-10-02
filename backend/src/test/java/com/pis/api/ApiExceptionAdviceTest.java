package com.pis.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Only test routes are declared here; this does not add any clinical production endpoint. */
class ApiExceptionAdviceTest {
    private static final String SECRET = "synthetic-private-marker";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ContractController())
            .setControllerAdvice(new ApiExceptionAdvice())
            .setValidator(validator).addFilters(new TraceIdFilter()).build();
    }

    @AfterEach
    void closeValidator() { validator.close(); }

    @Test
    void acceptsAValidTypedSyntheticDto() throws Exception {
        var result = mvc.perform(post("/test-contract/body").contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"synthetic\",\"count\":1}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void bodyValidationOnlyExposesFieldAndStaticCode() throws Exception {
        var result = mvc.perform(post("/test-contract/body").queryParam("private", SECRET)
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"\",\"count\":0}")).andReturn();
        var problem = assertProblem(result, 400, "VALIDATION_FAILED");
        assertThat(problem.path("violations").size()).isEqualTo(2);
        assertViolation(problem.path("violations").get(0), "count", "Min");
        assertViolation(problem.path("violations").get(1), "label", "NotBlank");
    }

    @Test
    void missingDtoFieldsProduceSafeViolations() throws Exception {
        var result = mvc.perform(post("/test-contract/body").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andReturn();
        var problem = assertProblem(result, 400, "VALIDATION_FAILED");
        assertThat(problem.path("violations").size()).isEqualTo(2);
        assertViolation(problem.path("violations").get(0), "count", "NotNull");
        assertViolation(problem.path("violations").get(1), "label", "NotBlank");
    }

    @Test
    void malformedOrWrongTypedJsonNeverReturnsInputOrParserDiagnostics() throws Exception {
        for (String content : new String[] {"{\"label\":\"" + SECRET,
            "{\"label\":\"synthetic\",\"count\":\"" + SECRET + "\"}"}) {
            var result = mvc.perform(post("/test-contract/body").contentType(MediaType.APPLICATION_JSON)
                .content(content)).andReturn();
            assertProblem(result, 400, "INVALID_JSON");
        }
    }

    @Test
    void missingAndWrongTypedQueryParametersUseTheSameContract() throws Exception {
        assertProblem(mvc.perform(get("/test-contract/query")).andReturn(), 400, "MISSING_PARAMETER");
        assertProblem(mvc.perform(get("/test-contract/query").param("count", SECRET)).andReturn(),
            400, "TYPE_MISMATCH");
    }

    @Test
    void methodParameterValidationUsesDeclaredParameterName() throws Exception {
        var problem = assertProblem(mvc.perform(get("/test-contract/query").param("count", "0")).andReturn(),
            400, "VALIDATION_FAILED");
        assertViolation(problem.path("violations").get(0), "count", "Min");
    }

    @Test
    void returnValueValidationIsA500WithNoReturnedValueOrViolations() throws Exception {
        var problem = assertProblem(mvc.perform(get("/test-contract/invalid-return")).andReturn(),
            500, "INTERNAL_ERROR");
        assertThat(problem.has("violations")).isFalse();
    }

    @Test
    void domainAndUnexpectedFailuresDoNotLeakExceptionDetails() throws Exception {
        assertProblem(mvc.perform(get("/test-contract/domain")).andReturn(), 409, "VERSION_CONFLICT");
        assertProblem(mvc.perform(get("/test-contract/unknown")).andReturn(), 500, "INTERNAL_ERROR");
    }

    @Test
    void unsupportedMethodRetainsAllowHeaderAndSafeProblemContract() throws Exception {
        var result = mvc.perform(post("/test-contract/query")).andReturn();
        assertProblem(result, 405, "METHOD_NOT_ALLOWED");
        assertThat(result.getResponse().getHeader("Allow")).contains("GET");
    }

    @Test
    void securityExceptionsAreRethrownForTheRealSecurityChainInsteadOfBecoming500() {
        var advice = new ApiExceptionAdvice();
        var denied = new AccessDeniedException(SECRET);
        var authentication = new BadCredentialsException(SECRET);
        assertThatThrownBy(() -> advice.security(denied)).isSameAs(denied);
        assertThatThrownBy(() -> advice.security(authentication)).isSameAs(authentication);
        assertThatThrownBy(() -> advice.unknown(new RuntimeException(denied), new MockHttpServletRequest()))
            .isSameAs(denied);
        assertThatThrownBy(() -> advice.unknown(new RuntimeException(authentication), new MockHttpServletRequest()))
            .isSameAs(authentication);
        assertThatThrownBy(() -> mvc.perform(get("/test-contract/denied")))
            .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mvc.perform(get("/test-contract/authentication")))
            .hasRootCauseInstanceOf(BadCredentialsException.class);
    }

    @Test
    void bindingDoesNotEchoMapKeysRejectedValuesOrConstraintMessages() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "synthetic");
        binding.addError(new FieldError("synthetic", "entries[" + SECRET + "].label", SECRET,
            false, new String[] {SECRET}, new Object[] {SECRET}, SECRET));
        var request = new MockHttpServletRequest();
        new TraceIdFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var response = new ApiExceptionAdvice().binding(new BindException(binding), request);
            var problem = (org.springframework.http.ProblemDetail) response.getBody();
            @SuppressWarnings("unchecked")
            var violations = (java.util.List<ApiExceptionAdvice.Violation>) problem.getProperties().get("violations");
            assertThat(violations).containsExactly(new ApiExceptionAdvice.Violation("entries[].label", "Invalid"));
        });
    }

    private static JsonNode assertProblem(MvcResult result, int status, String code) {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        var problem = JSON.readTree(body);
        assertThat(problem.path("status").intValue()).isEqualTo(status);
        assertThat(problem.path("code").stringValue()).isEqualTo(code);
        assertThat(problem.path("type").stringValue()).startsWith("urn:pis:problem:");
        assertThat(problem.path("title").stringValue()).isNotBlank();
        assertThat(problem.path("detail").stringValue()).isNotBlank();
        String trace = response.getHeader(TraceIdFilter.HEADER);
        assertThat(java.util.UUID.fromString(trace).version()).isEqualTo(4);
        assertThat(problem.path("traceId").stringValue()).isEqualTo(trace);
        assertThat(problem.path("instance").stringValue()).isEqualTo("urn:uuid:" + trace);
        assertThat(body).doesNotContain(SECRET, "rejectedValue", "stackTrace", "java.lang", "properties",
            "SQL", "/test-contract", "password", "constraintDescriptor", "arguments");
        assertThatThrownBy(TraceIdFilter::currentTraceId).isInstanceOf(IllegalStateException.class);
        return problem;
    }

    private static void assertViolation(JsonNode violation, String field, String code) {
        assertThat(violation.propertyNames()).containsExactlyInAnyOrder("field", "code");
        assertThat(violation.path("field").stringValue()).isEqualTo(field);
        assertThat(violation.path("code").stringValue()).isEqualTo(code);
    }

    public record SyntheticRequest(@NotBlank String label, @NotNull @Min(1) Integer count) { }

    @RestController
    static final class ContractController {
        @PostMapping("/test-contract/body")
        Map<String, Boolean> body(@Valid @RequestBody SyntheticRequest body) { return Map.of("ok", true); }

        @GetMapping("/test-contract/query")
        Map<String, Integer> query(@RequestParam("count") @Min(1) int count) { return Map.of("count", count); }

        @GetMapping("/test-contract/invalid-return")
        @NotBlank
        String invalidReturn() { return ""; }

        @GetMapping("/test-contract/domain")
        void domain() { throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The resource version has changed."); }

        @GetMapping("/test-contract/unknown")
        void unknown() { throw new IllegalStateException("SQL password " + SECRET); }

        @GetMapping("/test-contract/denied")
        void denied() { throw new AccessDeniedException(SECRET); }

        @GetMapping("/test-contract/authentication")
        void authentication() { throw new BadCredentialsException(SECRET); }
    }
}
