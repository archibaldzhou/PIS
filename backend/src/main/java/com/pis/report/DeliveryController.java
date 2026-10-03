package com.pis.report;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/reports/cases/{id}/deliveries")
public class DeliveryController {
 private final DeliveryService service;
 public DeliveryController(DeliveryService service){this.service=service;}
 @GetMapping public DeliveryContracts.Detail detail(@PathVariable UUID id,@RequestParam(defaultValue="1") int page){return service.detail(id,page);}
 @GetMapping("/{delivery}/history") public java.util.List<DeliveryContracts.Event> history(@PathVariable UUID id,@PathVariable UUID delivery,@RequestParam(defaultValue="1") int page){return service.history(id,delivery,page);}
 @PostMapping public IdempotentCommands.Result queue(@PathVariable UUID id,@Valid @RequestBody DeliveryContracts.Command c,@RequestHeader("Idempotency-Key") String key){return service.enqueue(id,c,key);}
 @PostMapping("/{delivery}/{action}") public IdempotentCommands.Result step(@PathVariable UUID id,@PathVariable UUID delivery,@PathVariable DeliveryContracts.Action action,@Valid @RequestBody DeliveryContracts.Command c,@RequestHeader("Idempotency-Key") String key){return service.step(id,delivery,c,key,action);}
}
