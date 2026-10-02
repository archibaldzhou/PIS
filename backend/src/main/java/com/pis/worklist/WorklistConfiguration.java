package com.pis.worklist;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false)
public class WorklistConfiguration {
 @Bean("worklistClock") Clock worklistClock() { return Clock.systemUTC(); }
}
