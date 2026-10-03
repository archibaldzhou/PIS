package com.pis.ai;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
import static com.pis.ai.AiContracts.*;
@RestController
@RequestMapping("/api/requests")
public class AiRegistryController {
 private final AiRegistryService service;
 public AiRegistryController(AiRegistryService service){this.service=service;}
 @GetMapping("/ai/scopes/{scope}/models") public Catalog catalog(@PathVariable UUID scope,@RequestParam(defaultValue="1")int page){return service.catalog(scope,page);}
 @PostMapping("/ai/scopes/{scope}/models") public IdempotentCommands.Result register(@PathVariable UUID scope,@Valid @RequestBody Register input,@RequestHeader("Idempotency-Key")String key){return service.register(scope,input,key);}
 @GetMapping("/ai/scopes/{scope}/models/{version}/history") public List<Map<String,Object>> history(@PathVariable UUID scope,@PathVariable UUID version){return service.history(scope,version);}
 @PostMapping("/ai/scopes/{scope}/models/{version}/state") public IdempotentCommands.Result change(@PathVariable UUID scope,@PathVariable UUID version,@Valid @RequestBody StateChange input,@RequestHeader("Idempotency-Key")String key){return service.change(scope,version,input,key);}
 @GetMapping("/{request}/scans/{scan}/ai") public ScanView scan(@PathVariable UUID request,@PathVariable UUID scan,@RequestParam long publicationVersion){return service.scan(request,scan,publicationVersion);}
 @PostMapping("/{request}/scans/{scan}/ai/profile") public IdempotentCommands.Result profile(@PathVariable UUID request,@PathVariable UUID scan,@Valid @RequestBody ProfileInput input,@RequestHeader("Idempotency-Key")String key){return service.profile(request,scan,input,key);}
 @PostMapping("/{request}/scans/{scan}/ai/assess") public IdempotentCommands.Result assess(@PathVariable UUID request,@PathVariable UUID scan,@Valid @RequestBody Assess input,@RequestHeader("Idempotency-Key")String key){return service.assess(request,scan,input,key);}
 @GetMapping("/{request}/scans/{scan}/ai/assess/{id}") public Decision assessment(@PathVariable UUID request,@PathVariable UUID scan,@PathVariable UUID id){return service.assessment(request,scan,id);}
}
