package com.pis.frozen;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class FrozenPolicyTest {
 @Test void validatesOffsetsDstAndFutureBoundaries(){
  var now=Instant.parse("2026-10-03T00:00:00Z");
  assertThat(FrozenPolicy.time(OffsetDateTime.parse("2026-10-03T08:00:00+08:00"),"Asia/Shanghai",now)).isEqualTo(now);
  assertThatThrownBy(()->FrozenPolicy.time(OffsetDateTime.parse("2026-10-03T08:00:01+08:00"),"Asia/Shanghai",now)).hasMessage("FROZEN_TIME_INVALID");
  assertThatThrownBy(()->FrozenPolicy.time(OffsetDateTime.parse("2026-03-08T02:30:00-05:00"),"America/New_York",now)).hasMessage("FROZEN_TIME_INVALID");
  assertThatThrownBy(()->FrozenPolicy.time(OffsetDateTime.parse("2026-03-08T10:30:00+07:00"),"Asia/Shanghai",now)).hasMessage("FROZEN_TIME_INVALID");
  assertThatThrownBy(()->FrozenPolicy.time(OffsetDateTime.parse("2026-03-08T10:30:00Z"),"Invalid/Zone",now)).hasMessage("FROZEN_TIME_INVALID");
 }
 @Test void equalTimesAllowedButReversedOrderRejected(){var t=Instant.parse("2026-01-01T00:00:00Z");FrozenPolicy.after(t,t);FrozenPolicy.after(t.plusSeconds(1),t);assertThatThrownBy(()->FrozenPolicy.after(t.minusNanos(1),t)).hasMessage("FROZEN_TIME_ORDER");assertThat(FrozenPolicy.digest("manual-v1")).hasSize(64).isNotEqualTo(FrozenPolicy.digest("manual-v2"));}
}
