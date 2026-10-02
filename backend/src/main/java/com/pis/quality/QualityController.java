package com.pis.quality;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.quality.QualityContracts.*;
@RestController
@RequestMapping("/api/quality")
public class QualityController {
 private final QualityService service;
 public QualityController(QualityService service) { this.service=service; }
 @GetMapping("/requests/{id}") public View view(@PathVariable UUID id) { return service.view(id); }
 @GetMapping("/materials/{id}") public Detail detail(@PathVariable UUID id) { return service.detail(id); }
 @PostMapping("/materials/{id}/assess") public ResponseEntity<IdempotentCommands.Result> assess(@PathVariable UUID id,@Valid @RequestBody Assess input,@RequestHeader("Idempotency-Key") String key) { return response(service.assess(id,input,key)); }
 @PostMapping("/materials/{id}/revoke") public ResponseEntity<IdempotentCommands.Result> revoke(@PathVariable UUID id,@Valid @RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) { return response(service.decide(id,input,key,"REVOKE")); }
 @PostMapping("/materials/{id}/rework") public ResponseEntity<IdempotentCommands.Result> rework(@PathVariable UUID id,@Valid @RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) { return response(service.decide(id,input,key,"REWORK")); }
 @PostMapping("/materials/{id}/exception-release") public ResponseEntity<IdempotentCommands.Result> release(@PathVariable UUID id,@Valid @RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) { return response(service.decide(id,input,key,"EXCEPTION_RELEASE")); }
 private static ResponseEntity<IdempotentCommands.Result> response(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
