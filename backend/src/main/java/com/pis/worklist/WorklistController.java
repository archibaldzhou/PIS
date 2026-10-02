package com.pis.worklist;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.pis.worklist.WorklistContracts.*;
@RestController
@RequestMapping("/api/worklists")
public class WorklistController {
 private final WorklistService service;
 public WorklistController(WorklistService service) { this.service=service; }
 @GetMapping("/scopes/{id}") public Page list(@PathVariable UUID id,@RequestParam(defaultValue="ALL") Kind kind,@RequestParam(defaultValue="ALL") State state,@RequestParam(defaultValue="ALL") Due due,@RequestParam(defaultValue="OLDEST") Sort sort,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(id,kind,state,due,sort,page,pageSize); }
 @GetMapping("/requests/{id}/trace") public Trace trace(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.trace(id,page,pageSize); }
 @PostMapping("/scopes/{id}/claims") public BatchResult claims(@PathVariable UUID id,@Valid @RequestBody Batch input) { return service.claim(id,input); }
}
