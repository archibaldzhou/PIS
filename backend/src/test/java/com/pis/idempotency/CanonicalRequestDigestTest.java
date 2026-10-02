package com.pis.idempotency;

import com.pis.api.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalRequestDigestTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final CanonicalRequestDigest digests = new CanonicalRequestDigest(json);

    @Test void ordersObjectsRecursivelyAndCanonicalizesEquivalentNumbers() {
        var first = json.readTree("{\"nested\":{\"b\":2,\"a\":1.0},\"items\":[null,true,\" x \"],\"version\":0}");
        var second = json.readTree("{\"version\":0.0,\"items\":[null,true,\" x \"],\"nested\":{\"a\":1e0,\"b\":2.00}}");
        assertThat(digests.request(first)).containsExactly(digests.request(second));
        assertThat(digests.request(Map.of("number", new BigDecimal("0.0")))).containsExactly(digests.request(Map.of("number", BigDecimal.ZERO)));
    }

    @Test void preservesArrayOrderTextWhitespaceCaseNullAndAllCommandFields() {
        assertThat(digests.request(Map.of("items", List.of(1, 2)))).isNotEqualTo(digests.request(Map.of("items", List.of(2, 1))));
        assertThat(digests.request(Map.of("value", " A "))).isNotEqualTo(digests.request(Map.of("value", "A")));
        assertThat(digests.request(Map.of("value", "A"))).isNotEqualTo(digests.request(Map.of("value", "a")));
        assertThat(digests.request(json.readTree("{\"value\":null}"))).isNotEqualTo(digests.request(Map.of()));
        assertThat(digests.request(Map.of("version", 1))).isNotEqualTo(digests.request(Map.of("version", 2)));
    }

    @Test void boundsKeysAndPayloadsAndNeverPersistsTheRawKey() {
        assertThat(digests.key("opaque:client-request_1")).hasSize(32).containsExactly(digests.key("opaque:client-request_1"));
        for (String invalid : List.of("", "has space", "\n", "x".repeat(129), "患者")) {
            assertThatThrownBy(() -> digests.key(invalid)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> digests.key(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> digests.request(Map.of("value", "x".repeat(65_537)))).isInstanceOf(ApiException.class);
        Object deeplyNested = "leaf";
        for (int level = 0; level < 34; level++) deeplyNested = List.of(deeplyNested);
        Object command = deeplyNested;
        assertThatThrownBy(() -> digests.request(command)).isInstanceOf(ApiException.class);
    }
}
