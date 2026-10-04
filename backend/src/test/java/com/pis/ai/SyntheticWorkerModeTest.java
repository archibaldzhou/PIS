package com.pis.ai;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class SyntheticWorkerModeTest {
 @Test void disabledByDefaultAndNeverPermittedOutsideIsolatedProfiles(){var env=new MockEnvironment();assertThatThrownBy(()->new SyntheticWorkerMode(env,false).require()).isInstanceOf(com.pis.api.ApiException.class).hasMessageContaining("disabled");assertThatThrownBy(()->new SyntheticWorkerMode(env,true)).isInstanceOf(IllegalStateException.class);env.setActiveProfiles("test");assertThatCode(()->new SyntheticWorkerMode(env,true).require()).doesNotThrowAnyException();env.setActiveProfiles("test","prod");assertThatThrownBy(()->new SyntheticWorkerMode(env,true)).isInstanceOf(IllegalStateException.class);}
}
