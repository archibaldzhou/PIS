package com.pis.statistics;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class StatisticsPolicyTest {
 @Test void businessDayWindowPreservesDstAndInclusiveEndDate(){
  var spring=StatisticsPolicy.window(LocalDate.parse("2026-03-08"),LocalDate.parse("2026-03-08"),"America/Los_Angeles");
  assertThat(Duration.between(spring.start(),spring.end()).toHours()).isEqualTo(23);
  var fall=StatisticsPolicy.window(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-01"),"America/Los_Angeles");
  assertThat(Duration.between(fall.start(),fall.end()).toHours()).isEqualTo(25);
 }
 @Test void invalidOrUnboundedDatesFail(){var day=LocalDate.of(2026,1,1);assertThatThrownBy(()->StatisticsPolicy.window(day,day.minusDays(1),"UTC")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->StatisticsPolicy.window(day,day.plusDays(92),"UTC")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->StatisticsPolicy.window(day,day,"Not/AZone")).isInstanceOf(IllegalArgumentException.class);assertThat(StatisticsPolicy.window(day,day.plusDays(91),"UTC")).isNotNull();}
 @Test void frozenCutoffSeparatesOpenFromCompletedAndUnknown(){var start=Instant.parse("2026-01-01T00:00:00Z");var cutoff=start.plusSeconds(60);assertThat(StatisticsPolicy.timing(start,null,cutoff,false)).isEqualTo(new StatisticsPolicy.Timing("OPEN",60L));assertThat(StatisticsPolicy.timing(start,start,cutoff,false)).isEqualTo(new StatisticsPolicy.Timing("COMPLETED",0L));assertThat(StatisticsPolicy.timing(null,cutoff,cutoff,false).seconds()).isNull();assertThat(StatisticsPolicy.timing(start,start.minusSeconds(1),cutoff,false).status()).isEqualTo("UNKNOWN");assertThat(StatisticsPolicy.timing(start,cutoff.plusSeconds(1),cutoff,false).status()).isEqualTo("UNKNOWN");assertThat(StatisticsPolicy.timing(start,null,cutoff,true).status()).isEqualTo("EXCLUDED");}
 @Test void medianHasNoInventedZeroAndAvoidsOverflow(){assertThat(StatisticsPolicy.median(List.of())).isNull();assertThat(StatisticsPolicy.median(List.of(0L,8L))).isEqualTo(4);assertThat(StatisticsPolicy.median(List.of(9L,1L,2L))).isEqualTo(2);assertThat(StatisticsPolicy.median(List.of(Long.MAX_VALUE,Long.MAX_VALUE))).isPositive();}
}
