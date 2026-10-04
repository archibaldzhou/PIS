package com.pis.integration;
import java.util.*;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{request}/adapters")
public class HospitalAdapterController {
 private final HospitalAdapterService service;
 public HospitalAdapterController(HospitalAdapterService service){this.service=service;}
 @GetMapping public HospitalAdapterContracts.View view(@PathVariable UUID request,@RequestParam(defaultValue="1") int page){return service.view(request,page);}
 @GetMapping("/{id}/history") public List<HospitalAdapterContracts.Event> history(@PathVariable UUID request,@PathVariable UUID id){return service.history(request,id);}
 @PostMapping public IdempotentCommands.Result submit(@PathVariable UUID request,@Valid @RequestBody HospitalAdapterContracts.Submit input,@RequestHeader("Idempotency-Key") String key){return service.submit(request,input,key);}
 @PostMapping("/{id}/{action}") public IdempotentCommands.Result step(@PathVariable UUID request,@PathVariable UUID id,@PathVariable HospitalAdapterContracts.Action action,@Valid @RequestBody HospitalAdapterContracts.Command input,@RequestHeader("Idempotency-Key") String key){return service.step(request,id,action,input,key);}
}
