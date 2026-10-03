package com.pis.material;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class StainPolicyTest {
 @Test void expiryHasExplicitInclusiveUtcDateBoundary(){var day=LocalDate.of(2026,10,3);StainPolicy.expiry(day,day);StainPolicy.expiry(day.plusDays(1),day);assertThatThrownBy(()->StainPolicy.expiry(day.minusDays(1),day)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->StainPolicy.expiry(null,day)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->StainPolicy.expiry(LocalDate.of(10000,1,1),day)).isInstanceOf(IllegalArgumentException.class);}
 @Test void quantitiesAndCodesCannotInventProtocolOrExtraSlides(){StainPolicy.quantity(20,20,0);StainPolicy.quantity(1,1,19);for(int[] q:new int[][]{{0,0,0},{2,1,0},{1,1,20},{21,21,0}})assertThatThrownBy(()->StainPolicy.quantity(q[0],q[1],q[2])).isInstanceOf(IllegalArgumentException.class);assertThat(StainPolicy.code("SYN-IHC-1")).isTrue();assertThat(StainPolicy.code("Vendor recommendation")).isFalse();assertThat(StainPolicy.code(null)).isFalse();}
}
