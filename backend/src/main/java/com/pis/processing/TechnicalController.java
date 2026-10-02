package com.pis.processing;
import com.pis.api.ApiException;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.processing.TechnicalContracts.*;
@RestController
@RequestMapping("/api/technical")
public class TechnicalController {
    private final TechnicalService service;
    public TechnicalController(TechnicalService service) { this.service=service; }
    @GetMapping("/requests/{id}") public View view(@PathVariable UUID id) { return service.view(id); }
    @GetMapping("/tasks/{id}") public Detail detail(@PathVariable UUID id) { return service.detail(id); }
    @PostMapping("/requests/{id}") public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID id,@RequestBody @Valid Create body,@RequestHeader("Idempotency-Key") String key) { return result(service.create(id,body,key)); }
    @PostMapping("/tasks/{id}/{action}") public ResponseEntity<IdempotentCommands.Result> decide(@PathVariable UUID id,@PathVariable String action,@RequestBody @Valid Decision body,@RequestHeader("Idempotency-Key") String key) {
        String code=switch(action) { case "claim"->"CLAIM"; case "offer"->"OFFER"; case "accept"->"ACCEPT"; case "withdraw"->"WITHDRAW"; case "finish-simulation"->"FINISH_SIMULATION"; case "abort"->"ABORT"; case "rework"->"REWORK"; default->throw new ApiException(HttpStatus.NOT_FOUND,"TECH_NOT_FOUND","Unknown task action"); };
        return result(service.decide(id,body,key,code));
    }
    private static ResponseEntity<IdempotentCommands.Result> result(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
