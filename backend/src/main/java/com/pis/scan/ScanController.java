package com.pis.scan;
import java.util.*;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import com.pis.idempotency.IdempotentCommands;
@RestController @RequestMapping("/api/requests/{requestId}/scans")
public class ScanController {
 private final ScanService service;public ScanController(ScanService service){this.service=service;}
 @GetMapping public ScanContracts.View view(@PathVariable UUID requestId,@RequestParam(defaultValue="1")int page){return service.view(requestId,page);}
 @GetMapping("/{id}") public ScanContracts.Job detail(@PathVariable UUID requestId,@PathVariable UUID id){return service.detail(requestId,id);}
 @GetMapping("/{id}/events") public List<ScanContracts.Event> events(@PathVariable UUID requestId,@PathVariable UUID id){return service.events(requestId,id);}
 @PostMapping public List<ScanContracts.Item> batch(@PathVariable UUID requestId,@Valid @RequestBody ScanContracts.Batch input,@RequestHeader("Idempotency-Key")String key){return service.batch(requestId,input,key);}
 @PostMapping("/{id}/{action}") public IdempotentCommands.Result command(@PathVariable UUID requestId,@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody ScanContracts.Command input,@RequestHeader("Idempotency-Key")String key){return action.equals("PROCESS")?service.process(requestId,id,input,key):service.command(requestId,id,action,input,key);}
}
