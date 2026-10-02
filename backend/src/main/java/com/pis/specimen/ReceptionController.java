package com.pis.specimen;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.specimen.ReceptionContracts.*;

@RestController
@RequestMapping("/api/receptions/{id}")
public class ReceptionController {
    private final ReceptionService service;
    public ReceptionController(ReceptionService service) { this.service=service; }
    @GetMapping public View view(@PathVariable UUID id) { return service.view(id); }
    @PostMapping("/receive") public ResponseEntity<IdempotentCommands.Result> receive(@PathVariable UUID id,@RequestBody @Valid Check input,@RequestHeader("Idempotency-Key") String key) { return result(service.receive(id,input,key)); }
    @PostMapping("/exception") public ResponseEntity<IdempotentCommands.Result> exception(@PathVariable UUID id,@RequestBody @Valid ExceptionInput input,@RequestHeader("Idempotency-Key") String key) { return result(service.exception(id,input,key)); }
    @PostMapping("/resolve") public ResponseEntity<IdempotentCommands.Result> resolve(@PathVariable UUID id,@RequestBody @Valid Decision input,@RequestHeader("Idempotency-Key") String key) { return result(service.resolve(id,input,key)); }
    @PostMapping("/return") public ResponseEntity<IdempotentCommands.Result> sendBack(@PathVariable UUID id,@RequestBody @Valid Decision input,@RequestHeader("Idempotency-Key") String key) { return result(service.sendBack(id,input,key)); }
    private static ResponseEntity<IdempotentCommands.Result> result(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
