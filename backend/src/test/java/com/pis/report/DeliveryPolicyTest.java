package com.pis.report;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DeliveryPolicyTest {
 @Test void retryIsBoundedAndDeadlineIsExclusiveForAck(){var t=Instant.parse("2026-10-03T00:00:00Z");var attempt=UUID.randomUUID();assertThat(DeliveryPolicy.canClaim("RETRY_WAIT",1,t,t.minusMillis(1))).isFalse();assertThat(DeliveryPolicy.canClaim("RETRY_WAIT",1,t,t)).isTrue();assertThat(DeliveryPolicy.canClaim("RETRY_WAIT",3,t,t)).isFalse();assertThat(DeliveryPolicy.active("ATTEMPTING",attempt,attempt,t,t)).isFalse();assertThat(DeliveryPolicy.active("ATTEMPTING",attempt,UUID.randomUUID(),t,t.minusSeconds(1))).isFalse();assertThat(DeliveryPolicy.active("ATTEMPTING",attempt,attempt,t,t.minusNanos(1))).isTrue();assertThat(DeliveryPolicy.backoffSeconds(1)).isEqualTo(5);assertThat(DeliveryPolicy.backoffSeconds(2)).isEqualTo(20);assertThat(DeliveryPolicy.failureState(3,false)).isEqualTo("DEAD");assertThat(DeliveryPolicy.failureState(1,true)).isEqualTo("DEAD");assertThatThrownBy(()->DeliveryPolicy.backoffSeconds(4)).isInstanceOf(IllegalArgumentException.class);}
}
