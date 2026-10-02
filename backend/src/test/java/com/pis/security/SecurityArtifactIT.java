package com.pis.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs after package under Failsafe, inspecting the actual production artifact. */
class SecurityArtifactIT {
    @Test void productionJarContainsNeitherFixturesNorDefaultCredentials() throws Exception {
        try (var jar = new JarFile(Path.of("target/pis-backend-0.0.1-SNAPSHOT.jar").toFile())) {
            var names = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(names).noneMatch(name -> name.contains("testfixture") || name.endsWith("application-test.properties")
                || name.contains("invalid-fixture") || name.contains("upgrade-fixture")
                || name.contains("spring-boot-test") || name.contains("junit")
                || name.startsWith("BOOT-INF/classes/com/pis/api/http/"));
            for (var entry : jar.stream().filter(item -> !item.isDirectory() && item.getName().startsWith("BOOT-INF/classes/")).toList()) {
                String content = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1);
                assertThat(content).doesNotContain("synthetic.reader", "synthetic.disabled", "Synthetic-test-only-42!", "Synthetic-http-test-42!",
                    "synthetic.technician", "Synthetic-handoff-only-42!", "Synthetic-technical-http-42!", "Synthetic-material-http-42!", "Synthetic-api-http-42!", "/test/api-contract", "http_probe_resource", "command_test_resource");
            }
        }
    }
}
