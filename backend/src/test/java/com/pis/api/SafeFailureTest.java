package com.pis.api;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SafeFailureTest {
    @Test void onlyBoundedClassesAndFramesNotMessagesOrSuppressedPayloads() {
        var cause=new java.sql.SQLException("Synthetic-secret SQL cookie token");
        var failure=new IllegalStateException("Synthetic-secret patient",cause);
        failure.addSuppressed(new RuntimeException("Synthetic-secret suppressed"));
        var frames=new StackTraceElement[20];
        java.util.Arrays.fill(frames,new StackTraceElement("com.pis.viewer.ViewerService","budget","Synthetic-secret.java",77));
        failure.setStackTrace(frames);
        String safe=SafeFailure.describe(failure);
        assertThat(safe).contains("java.lang.IllegalStateException","java.sql.SQLException","ViewerService.budget:77").doesNotContain("Synthetic-secret","cookie","patient","suppressed");
        assertThat(safe.split("ViewerService.budget",-1)).hasSize(7);
        cause.initCause(failure);assertThat(SafeFailure.describe(failure)).isEqualTo(safe);
    }
}
