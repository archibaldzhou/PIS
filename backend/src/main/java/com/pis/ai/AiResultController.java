package com.pis.ai;
import java.util.UUID;import jakarta.validation.Valid;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import com.pis.idempotency.IdempotentCommands;
@RestController @RequestMapping("/api/requests/{request}/synthetic-results")
public class AiResultController {
 private final AiResultService service;public AiResultController(AiResultService service){this.service=service;}
 @PostMapping public IdempotentCommands.Result create(@PathVariable UUID request,@Valid @RequestBody AiResultService.Create input,@RequestHeader("Idempotency-Key")String key){return service.create(request,input,key);}
 @PostMapping("/{id}/resume") public AiResultService.Result resume(@PathVariable UUID request,@PathVariable UUID id){return service.finish(request,id);}
 @GetMapping("/{id}") public ResponseEntity<AiResultService.Metadata> metadata(@PathVariable UUID request,@PathVariable UUID id){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(service.metadata(request,id));}
 @GetMapping("/{id}/tiles/{tile}") public ResponseEntity<byte[]> tile(@PathVariable UUID request,@PathVariable UUID id,@PathVariable int tile,@RequestParam String epoch){var b=service.tile(request,id,tile,epoch);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("X-Content-SHA256",b.hash()).header("X-Result-Epoch",b.epoch()).body(b.bytes());}
}
