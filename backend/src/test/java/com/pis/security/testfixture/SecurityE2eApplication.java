package com.pis.security.testfixture;

import com.pis.PisApplication;
import com.pis.database.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;

/** Test-classpath-only browser launcher; owns one random schema in a disposable PG17 _test DB. */
public final class SecurityE2eApplication {
    private SecurityE2eApplication() { }
    public static void main(String[] args) throws java.io.IOException {
        var database = new PostgresTestDatabase();
        var storageRoot = java.nio.file.Files.createTempDirectory("pis-e2e-private-storage-");
        var arguments = new ArrayList<>(Arrays.asList(database.applicationArguments("classpath:db/migration")));
        arguments.remove("--server.port=0");
        arguments.add("--pis.storage.local-root="+storageRoot);
        arguments.add("--server.port=8080");
        arguments.add("--spring.profiles.active=test");
        arguments.add("--pis.workflow.development-enabled=true");
        arguments.add("--pis.ai.synthetic-worker-enabled=true");
        var application = new SpringApplication(PisApplication.class, E2eFixtureConfiguration.class);
        application.setRegisterShutdownHook(false);
        try {
            var context = application.run(arguments.toArray(String[]::new));
            // Playwright suppresses server stdout. Forward ONLY the bounded API diagnostic to stderr.
            var apiLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.pis.api.ApiExceptionAdvice.class);
            var safeErrors=new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
                @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                    if(event.getLevel()==ch.qos.logback.classic.Level.ERROR && event.getMessage().equals("api_error code=INTERNAL_ERROR traceId={} failureStructure={}"))
                        System.err.println("SYNTHETIC_E2E_SAFE_SERVER "+event.getFormattedMessage());
                }
            };
            safeErrors.setContext(apiLogger.getLoggerContext());safeErrors.start();apiLogger.addAppender(safeErrors);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { context.close(); }
                finally {
                    try { database.close(); }
                    catch (Exception error) { System.err.println("Could not remove the owned synthetic E2E schema"); }
                }
            }, "synthetic-e2e-cleanup"));
        } catch (RuntimeException error) {
            try { database.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}
