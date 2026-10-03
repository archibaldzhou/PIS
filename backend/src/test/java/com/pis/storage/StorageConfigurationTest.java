package com.pis.storage;
import org.junit.jupiter.api.Test;import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class StorageConfigurationTest {
 @Test void defaultOffNeverCreatesAStorageRoot()throws Exception{try(var p=new StorageConfiguration().storageProvider(new MockEnvironment(),false,"/not-created-synthetic-root")){assertThat(p.state()).isEqualTo("NOT_CONFIGURED");assertThat(p.rootId()).isNull();}}
 @Test void missingRootIsNotConfiguredEvenInDevelopment()throws Exception{try(var p=new StorageConfiguration().storageProvider(new MockEnvironment().withProperty("spring.profiles.active","test"),true,"")){assertThat(p.state()).isEqualTo("NOT_CONFIGURED");assertThat(p.capacity().volumeUsable()).isNull();}}
 @Test void enabledStorageRejectsNonDevelopmentAndProductionProfiles(){var env=new MockEnvironment();assertThatThrownBy(()->new StorageConfiguration().storageProvider(env,true,"/not-created-synthetic-root")).isInstanceOf(IllegalStateException.class);env.setActiveProfiles("test","prod");assertThatThrownBy(()->new StorageConfiguration().storageProvider(env,true,"/not-created-synthetic-root")).isInstanceOf(IllegalStateException.class);}
}
