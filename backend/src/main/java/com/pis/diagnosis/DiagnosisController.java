package com.pis.diagnosis;
import com.pis.api.ApiException;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.diagnosis.DiagnosisContracts.*;
@RestController
@RequestMapping("/api/requests/diagnosis")
public class DiagnosisController {
 private final DiagnosisService service;
 public DiagnosisController(DiagnosisService service) { this.service=service; }
 @GetMapping("/scopes/{id}") public Page list(@PathVariable UUID id,@RequestParam(defaultValue="ALL") State state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(id,state,page,pageSize); }
 @GetMapping("/cases/{id}") public Detail detail(@PathVariable UUID id) { return service.detail(id); }
 @PostMapping("/cases/{id}/{action}") public ResponseEntity<IdempotentCommands.Result> decide(@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) {
  Action code=switch(action) { case "assign"->Action.ASSIGN; case "claim"->Action.CLAIM; case "transfer"->Action.TRANSFER; default->throw new ApiException(HttpStatus.NOT_FOUND,"DIAGNOSIS_NOT_FOUND","Unknown action"); };
  var r=service.decide(id,input,key,code); return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);
 }
}
