package com.pis.accession;

import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.accession.RequestContracts.*;

@RestController
@RequestMapping("/api/requests")
public class RequestController {
    private final RequestService service;
    public RequestController(RequestService service) { this.service=service; }
    @GetMapping("/scopes") public List<WorkflowAccess.Scope> scopes() { return service.scopes(); }
    @GetMapping("/encounters") public List<Encounter> encounters(@RequestParam UUID scopeId,@RequestParam @Size(min=1,max=255) String number) { return service.encounters(scopeId,number); }
    @GetMapping public Page list(@RequestParam(required=false) UUID scopeId,@RequestParam(defaultValue="") @Size(max=255) String keyword,
        @RequestParam(defaultValue="") String state,@RequestParam(required=false) LocalDate date,@RequestParam(defaultValue="1") @Min(1) @Max(10000) int page) { return service.list(scopeId,keyword,state,date,page); }
    @GetMapping("/{id}") public Detail detail(@PathVariable UUID id) { return service.detail(id); }
    @PostMapping public ResponseEntity<IdempotentCommands.Result> create(@RequestBody @Valid Create body,@RequestHeader("Idempotency-Key") String key) { return response(service.create(body,key)); }
    @PostMapping("/manual") public ResponseEntity<IdempotentCommands.Result> manual(@RequestBody @Valid ManualCreate body,@RequestHeader("Idempotency-Key") String key) { return response(service.createManual(body,key)); }
    @PutMapping("/{id}") public ResponseEntity<IdempotentCommands.Result> edit(@PathVariable UUID id,@RequestBody @Valid Edit body,@RequestHeader("Idempotency-Key") String key) { return response(service.edit(id,body,key)); }
    @PostMapping("/{id}/submit") public ResponseEntity<IdempotentCommands.Result> submit(@PathVariable UUID id,@RequestBody @Valid Submit body,@RequestHeader("Idempotency-Key") String key) { return response(service.submit(id,body,key)); }
    private static ResponseEntity<IdempotentCommands.Result> response(IdempotentCommands.Result result) {
        return ResponseEntity.status(result.receipt().status()).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);
    }
}
