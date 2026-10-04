package com.pis.integration;
import java.util.*;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import com.pis.report.*;import com.pis.idempotency.IdempotentCommands;
/** SYN-HOSPITAL-1 alias of the existing local-only frozen report delivery protocol. Not HL7/FHIR. */
@RestController
@RequestMapping("/api/requests/reports/cases/{id}/adapters/emr/v1")
public class LocalEmrAdapterController {
 private final DeliveryService service;
 public LocalEmrAdapterController(DeliveryService service){this.service=service;}
 @GetMapping public DeliveryContracts.Detail detail(@PathVariable UUID id,@RequestParam(defaultValue="1") int page){return service.detail(id,page);}
 @GetMapping("/{delivery}/history") public List<DeliveryContracts.Event> history(@PathVariable UUID id,@PathVariable UUID delivery,@RequestParam(defaultValue="1") int page){return service.history(id,delivery,page);}
 @PostMapping public IdempotentCommands.Result queue(@PathVariable UUID id,@Valid @RequestBody DeliveryContracts.Command input,@RequestHeader("Idempotency-Key") String key){return service.enqueue(id,input,key);}
 @PostMapping("/{delivery}/{action}") public IdempotentCommands.Result step(@PathVariable UUID id,@PathVariable UUID delivery,@PathVariable DeliveryContracts.Action action,@Valid @RequestBody DeliveryContracts.Command input,@RequestHeader("Idempotency-Key") String key){return service.step(id,delivery,input,key,action);}
}
