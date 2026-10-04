package com.pis.ai;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
import static com.pis.ai.AiTaskContracts.*;
@RestController
@RequestMapping("/api/requests/{request}")
public class AiTaskController {
 private final AiTaskService service;
 public AiTaskController(AiTaskService service){this.service=service;}
 @PostMapping("/scans/{scan}/synthetic-tasks") public IdempotentCommands.Result submit(@PathVariable UUID request,@PathVariable UUID scan,@Valid @RequestBody Submit input,@RequestHeader("Idempotency-Key")String key){return service.submit(request,scan,input,key);}
 @GetMapping("/synthetic-tasks") public Listing list(@PathVariable UUID request,@RequestParam UUID scanId,@RequestParam(defaultValue="1")int page){return service.list(request,scanId,page);}
 @GetMapping("/synthetic-tasks/{id}") public View detail(@PathVariable UUID request,@PathVariable UUID id){return service.detail(request,id);}
 @PostMapping("/synthetic-tasks/{id}/actions/{action}") public IdempotentCommands.Result command(@PathVariable UUID request,@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody Command input,@RequestHeader("Idempotency-Key")String key){return service.command(request,id,action,input,key);}
 @PostMapping("/synthetic-tasks/{id}/run") public IdempotentCommands.Result run(@PathVariable UUID request,@PathVariable UUID id,@Valid @RequestBody Command input){return service.run(request,id,input);}
 @PostMapping("/synthetic-tasks/{id}/callback") public IdempotentCommands.Result callback(@PathVariable UUID request,@PathVariable UUID id,@Valid @RequestBody Callback input){return service.callback(request,id,input);}
 @PostMapping("/synthetic-tasks/{id}/artifact") public ResponseEntity<byte[]> artifact(@PathVariable UUID request,@PathVariable UUID id){var bytes=service.artifact(request,id);return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=synthetic-contract.txt").contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes.bytes());}
}
