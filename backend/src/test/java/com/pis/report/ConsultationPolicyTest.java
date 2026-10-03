package com.pis.report;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ConsultationPolicyTest {
 @Test void expiryIsExplicitFutureAndBounded(){var now=Instant.parse("2026-10-03T12:00:00Z");ConsultationPolicy.expiry(now.plus(Duration.ofDays(30)),now);for(var t:java.util.List.of(now,now.minusSeconds(1),now.plus(Duration.ofDays(30)).plusSeconds(1)))assertThatThrownBy(()->ConsultationPolicy.expiry(t,now)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->ConsultationPolicy.expiry(null,now)).isInstanceOf(IllegalArgumentException.class);}
 @Test void unknownAndDisagreementAreNeverResolvedByOpinion(){assertThat(ConsultationPolicy.opinion("UNKNOWN")).isTrue();assertThat(ConsultationPolicy.opinion("DISAGREE")).isTrue();assertThat(ConsultationPolicy.summary("UNKNOWN")).isFalse();assertThat(ConsultationPolicy.opinion("RESOLVED")).isFalse();assertThat(ConsultationPolicy.summary(null)).isFalse();}
}
