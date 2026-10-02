package com.pis.security.testfixture;

import com.pis.PisApplication;
import com.pis.database.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;

/** Test-classpath-only browser launcher; owns one random schema in a disposable PG17 _test DB. */
public final class SecurityE2eApplication {
    private SecurityE2eApplication() { }
    public static void main(String[] args) {
        var database = new PostgresTestDatabase();
        var arguments = new ArrayList<>(Arrays.asList(database.applicationArguments("classpath:db/migration")));
        arguments.remove("--server.port=0");
        arguments.add("--server.port=8080");
        arguments.add("--spring.profiles.active=test");
        arguments.add("--pis.workflow.development-enabled=true");
        var application = new SpringApplication(PisApplication.class, E2eFixtureConfiguration.class);
        application.setRegisterShutdownHook(false);
        try {
            var context = application.run(arguments.toArray(String[]::new));
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
