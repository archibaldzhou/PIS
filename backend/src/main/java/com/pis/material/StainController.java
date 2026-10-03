package com.pis.material;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/materials/requests/{request}/staining")
public class StainController {
 private final StainService service;
 public StainController(StainService service){this.service=service;}
 @GetMapping public StainContracts.Detail detail(@PathVariable UUID request,@RequestParam(required=false) UUID batch,@RequestParam(defaultValue="1") int page){return service.detail(request,batch,page);}
 @PostMapping("/{action}") public ResponseEntity<IdempotentCommands.Result> command(@PathVariable UUID request,@PathVariable StainContracts.Action action,@RequestParam(required=false) UUID batch,@Valid @RequestBody StainContracts.Command body,@RequestHeader("Idempotency-Key") String key){var r=service.command(request,batch,action,body,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
}
