package com.pis.frozen;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests")
public class FrozenController {
 private final FrozenService service;
 public FrozenController(FrozenService service){this.service=service;}
 @GetMapping("/{request}/frozen-cases") public List<FrozenContracts.CaseItem> cases(@PathVariable UUID request){return service.cases(request);}
 @GetMapping("/frozen/cases/{id}") public FrozenContracts.Detail detail(@PathVariable UUID id,@RequestParam(defaultValue="1") int page){return service.detail(id,page);}
 @PostMapping("/frozen/cases/{id}/{action}") public ResponseEntity<IdempotentCommands.Result> command(@PathVariable UUID id,@PathVariable FrozenContracts.Action action,@Valid @RequestBody FrozenContracts.Command input,@RequestHeader("Idempotency-Key") String key){var result=service.command(id,action,input,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);}
}
