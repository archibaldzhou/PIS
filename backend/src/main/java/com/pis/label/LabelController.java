package com.pis.label;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.label.LabelContracts.*;
@RestController
@RequestMapping("/api/labels")
public class LabelController {
    private final LabelService service;
    public LabelController(LabelService service) { this.service=service; }
    @GetMapping("/containers/{id}") public LabelService.ContainerView container(@PathVariable UUID id) { return service.container(id); }
    @GetMapping("/jobs/{id}") public View view(@PathVariable UUID id) { return service.view(id); }
    @PostMapping("/jobs/{id}/verify") public Checked verify(@PathVariable UUID id,@RequestBody @Valid Verify input) { return service.verify(id,input); }
    @PostMapping("/containers/{id}/jobs") public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID id,@RequestBody @Valid Create input,@RequestHeader("Idempotency-Key") String key) { return result(service.create(id,input,key)); }
    @PostMapping("/jobs/{id}/{action}") public ResponseEntity<IdempotentCommands.Result> change(@PathVariable UUID id,@PathVariable String action,@RequestBody @Valid Change input,@RequestHeader("Idempotency-Key") String key) {
        String operation=switch(action) { case "reprint"->"REPRINT"; case "simulate-failure"->"FAIL"; case "retry"->"RETRY"; case "cancel"->"CANCEL"; default->throw new com.pis.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LABEL_NOT_FOUND","Unknown label operation"); };
        return result(service.change(id,input,key,operation));
    }
    private static ResponseEntity<IdempotentCommands.Result> result(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
