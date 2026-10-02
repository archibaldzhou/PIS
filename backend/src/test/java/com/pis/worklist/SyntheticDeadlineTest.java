package com.pis.worklist;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SyntheticDeadlineTest {
 @Test void strictBoundaryAndClosedWorkNeverBecomeClinicalTatClaims() {
  var start=Instant.parse("2026-01-01T00:00:00Z"); var due=SyntheticDeadline.dueAt(start,240);
  assertThat(due).isEqualTo(Instant.parse("2026-01-01T04:00:00Z"));
  assertThat(SyntheticDeadline.overdue(start,240,due.minusNanos(1),true)).isFalse();
  assertThat(SyntheticDeadline.overdue(start,240,due,true)).isFalse();
  assertThat(SyntheticDeadline.overdue(start,240,due.plusNanos(1),true)).isTrue();
  assertThat(SyntheticDeadline.overdue(start,240,due.plusSeconds(600),false)).isFalse();
  assertThat(SyntheticDeadline.dueAt(start,1)).isEqualTo(start.plusSeconds(60));
  assertThatThrownBy(()->SyntheticDeadline.dueAt(start,0)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->SyntheticDeadline.dueAt(start,10081)).isInstanceOf(IllegalArgumentException.class);
 }
}
