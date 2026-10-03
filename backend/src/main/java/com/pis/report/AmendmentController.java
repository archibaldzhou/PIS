package com.pis.report;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/reports/cases/{id}/amendments")
public class AmendmentController {
 private final AmendmentService service;
 public AmendmentController(AmendmentService service){this.service=service;}
 @GetMapping public AmendmentContracts.Detail detail(@PathVariable UUID id,@RequestParam(defaultValue="1") int page){return service.detail(id,page);}
 @GetMapping("/snapshots/{signature}") public AmendmentContracts.Snapshot snapshot(@PathVariable UUID id,@PathVariable UUID signature){return service.snapshot(id,signature);}
 @PostMapping public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID id,@Valid @RequestBody AmendmentContracts.Create input,@RequestHeader("Idempotency-Key") String key){var r=service.create(id,input,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
}
