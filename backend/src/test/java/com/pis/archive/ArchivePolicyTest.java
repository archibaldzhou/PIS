package com.pis.archive;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ArchivePolicyTest {
 @Test void dueDateMustBeExplicitFutureBoundedInstant(){var now=Instant.parse("2026-10-03T12:00:00Z");assertThat(ArchivePolicy.validDue(now.plusSeconds(30*86400),now,30)).isTrue();assertThat(ArchivePolicy.validDue(OffsetDateTime.parse("2026-10-04T20:00:00+08:00").toInstant(),now,30)).isTrue();for(var t:java.util.List.of(now,now.minusSeconds(1),now.plusSeconds(30*86400+1)))assertThat(ArchivePolicy.validDue(t,now,30)).isFalse();assertThat(ArchivePolicy.validDue(null,now,30)).isFalse();}
 @Test void quarantineVoidUnassessedAndFoundNeverBecomeLendable(){for(var state:java.util.List.of("VOID","FAIL","NOT_ASSESSED","IDENTITY_MISMATCH","SOURCE_QUARANTINED"))assertThat(ArchivePolicy.lendable("RECORDED",state)).isFalse();for(var condition:java.util.List.of("LOST","DAMAGED","FOUND_PENDING"))assertThat(ArchivePolicy.lendable(condition,"PASS")).isFalse();assertThat(ArchivePolicy.lendable("RECORDED","PASS")).isTrue();assertThat(ArchivePolicy.lendable("RECORDED","IMMUTABLE_ARTIFACT")).isTrue();}
 @Test void foundPreservesNeedForVerificationAndCannotEraseDamage(){assertThat(ArchivePolicy.condition("LOST","FOUND")).isEqualTo("FOUND_PENDING");assertThat(ArchivePolicy.condition("DAMAGED","FOUND")).isNull();assertThat(ArchivePolicy.condition("LOST","LOST")).isNull();assertThat(ArchivePolicy.condition("FOUND_PENDING","DAMAGE")).isEqualTo("DAMAGED");}
}
