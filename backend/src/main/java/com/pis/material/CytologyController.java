package com.pis.material;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/materials/requests/{request}/cytology/{container}")
public class CytologyController {
 private final CytologyService service;
 public CytologyController(CytologyService service){this.service=service;}
 @GetMapping public CytologyContracts.Detail detail(@PathVariable UUID request,@PathVariable UUID container,@RequestParam(defaultValue="1") int page){return service.detail(request,container,page);}
 @PostMapping("/{action}") public ResponseEntity<IdempotentCommands.Result> command(@PathVariable UUID request,@PathVariable UUID container,@PathVariable CytologyContracts.Action action,@Valid @RequestBody CytologyContracts.Command body,@RequestHeader("Idempotency-Key") String key){var r=service.command(request,container,action,body,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
}
