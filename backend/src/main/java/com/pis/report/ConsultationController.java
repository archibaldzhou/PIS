package com.pis.report;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/consultations/cases/{caseId}")
public class ConsultationController {
 private final ConsultationService service;
 public ConsultationController(ConsultationService service){this.service=service;}
 @GetMapping public ConsultationContracts.View detail(@PathVariable UUID caseId,@RequestParam(required=false) UUID consultation,@RequestParam(defaultValue="1") int page){return service.detail(caseId,consultation,page);}
 @PostMapping("/{action}") public ResponseEntity<IdempotentCommands.Result> command(@PathVariable UUID caseId,@PathVariable ConsultationContracts.Action action,@RequestParam(required=false) UUID consultation,@Valid @RequestBody ConsultationContracts.Command body,@RequestHeader("Idempotency-Key") String key){var r=service.command(caseId,consultation,action,body,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
}
