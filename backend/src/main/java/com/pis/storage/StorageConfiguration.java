package com.pis.storage;
import java.io.IOException;import java.nio.file.Path;import org.springframework.context.annotation.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.core.env.*;
@Configuration(proxyBeanMethods=false)
public class StorageConfiguration {
 @Bean(destroyMethod="close") StorageProvider storageProvider(Environment environment,@Value("${pis.workflow.development-enabled:false}") boolean enabled,@Value("${pis.storage.local-root:}") String root)throws IOException{
  if(!enabled||root.isBlank())return new S3StorageContract();
  if(!environment.acceptsProfiles(Profiles.of("dev","test"))||environment.acceptsProfiles(Profiles.of("prod")))throw new IllegalStateException("Synthetic storage requires isolated dev/test");
  return new LocalStorageProvider(Path.of(root));
 }
}
