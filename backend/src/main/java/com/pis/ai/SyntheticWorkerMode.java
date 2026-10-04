package com.pis.ai;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import com.pis.api.ApiException;
import org.springframework.http.HttpStatus;
@Component
public final class SyntheticWorkerMode {
 private final boolean enabled;
 public SyntheticWorkerMode(Environment env,@Value("${pis.ai.synthetic-worker-enabled:false}")boolean enabled){if(enabled&&(!env.acceptsProfiles(Profiles.of("dev","test"))||env.acceptsProfiles(Profiles.of("prod"))))throw new IllegalStateException("Synthetic contract worker requires isolated dev/test");this.enabled=enabled;}
 public void require(){if(!enabled)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_WORKER_DISABLED","Synthetic contract worker is disabled; clinical execution remains forbidden");}
}
