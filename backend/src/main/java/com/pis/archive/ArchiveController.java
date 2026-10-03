package com.pis.archive;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{requestId}/archive")
public class ArchiveController {
 private final ArchiveService service; public ArchiveController(ArchiveService service){this.service=service;}
 @GetMapping public ArchiveContracts.View view(@PathVariable UUID requestId,@RequestParam(required=false) UUID inventory,@RequestParam(defaultValue="1") int page){return service.view(requestId,inventory,page);}
 @PostMapping("/{action}") public ResponseEntity<IdempotentCommands.Result> command(@PathVariable UUID requestId,@PathVariable ArchiveContracts.Action action,@Valid @RequestBody ArchiveContracts.Command body,@RequestHeader("Idempotency-Key") String key){var r=service.command(requestId,action,body,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
 @PostMapping("/batch") public List<ArchiveContracts.BatchResult> batch(@PathVariable UUID requestId,@Valid @RequestBody ArchiveContracts.Batch body){return service.batch(requestId,body);}
}
