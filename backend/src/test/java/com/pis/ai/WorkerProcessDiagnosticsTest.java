package com.pis.ai;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class WorkerProcessDiagnosticsTest {
 @TempDir Path temp;
 @Test void failureTailIsBoundedRedactedAndRetainsStaticCause()throws Exception{
  var log=temp.resolve("child.log");Files.writeString(log,"old-line\n".repeat(2000)+"password=synthetic-private\nAuthorization: Bearer synthetic-private\njdbc:postgresql://private/db\nCaused by: com.pis.storage.StorageProvider$Failure: STORAGE_BUSY\nSYN_WORKER_STAGE_APPLICATION_START\n");
  var text=WorkerProcessDiagnostics.tail(log);assertThat(text.length()).isLessThanOrEqualTo(64*241);assertThat(text).doesNotContain("synthetic-private","jdbc:","Bearer").contains("STORAGE_BUSY","SYN_WORKER_STAGE_APPLICATION_START");
 }
}
