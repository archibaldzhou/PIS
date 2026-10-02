package com.pis.database;

import com.pis.PisApplication;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseStartupFailureTest {
    @Test
    void unavailableDatabasePreventsApplicationStartup() throws Exception {
        // Keep a loopback port occupied without speaking PostgreSQL: deterministic connection timeout.
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var url = "jdbc:postgresql://127.0.0.1:" + socket.getLocalPort()
                + "/unavailable_test?connectTimeout=1&socketTimeout=1";
            assertThatThrownBy(() -> {
                try (var ignored = SpringApplication.run(PisApplication.class,
                    "--server.port=0", "--spring.main.banner-mode=off",
                    "--spring.datasource.url=" + url,
                    "--spring.datasource.username=synthetic-test-user",
                    "--spring.datasource.password=synthetic-test-password",
                    "--spring.datasource.hikari.connection-timeout=1000",
                    "--spring.datasource.hikari.initialization-fail-timeout=1")) {
                    // A successfully started context is a failure of this assertion.
                }
            }).isInstanceOf(Exception.class).hasStackTraceContaining("flyway");
        }
    }

    @Test
    void brokenMigrationPreventsApplicationStartup() throws Exception {
        try (var database = new PostgresTestDatabase()) {
            assertThatThrownBy(() -> {
                try (var ignored = SpringApplication.run(PisApplication.class,
                    database.applicationArguments("classpath:db/invalid-fixture"))) {
                    // A successfully started context is a failure of this assertion.
                }
            }).isInstanceOf(Exception.class).hasStackTraceContaining("division by zero");
        }
    }
}
