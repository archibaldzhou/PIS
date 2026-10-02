package com.pis.material;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.material.MaterialContracts.*;
@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final MaterialService service;
    public MaterialController(MaterialService service) { this.service=service; }
    @GetMapping("/requests/{id}") public View view(@PathVariable UUID id) { return service.view(id); }
    @GetMapping("/{id}") public Detail detail(@PathVariable UUID id) { return service.detail(id); }
    @GetMapping("/lookup") public Detail lookup(@RequestParam String barcode) { return service.barcode(barcode); }
    @PostMapping("/requests/{id}/blocks") public ResponseEntity<IdempotentCommands.Result> block(@PathVariable UUID id,@RequestBody @Valid BlockCreate input,@RequestHeader("Idempotency-Key") String key) { return result(service.block(id,input,key)); }
    @PostMapping("/requests/{id}/direct-slides") public ResponseEntity<IdempotentCommands.Result> direct(@PathVariable UUID id,@RequestBody @Valid DirectCreate input,@RequestHeader("Idempotency-Key") String key) { return result(service.direct(id,input,key)); }
    @PostMapping("/{id}/slides") public ResponseEntity<IdempotentCommands.Result> slide(@PathVariable UUID id,@RequestBody @Valid SlideCreate input,@RequestHeader("Idempotency-Key") String key) { return result(service.slide(id,input,key)); }
    @PostMapping("/{id}/recut") public ResponseEntity<IdempotentCommands.Result> recut(@PathVariable UUID id,@RequestBody @Valid Repeat input,@RequestHeader("Idempotency-Key") String key) { return result(service.repeat(id,input,key,"RECUT")); }
    @PostMapping("/{id}/deeper") public ResponseEntity<IdempotentCommands.Result> deeper(@PathVariable UUID id,@RequestBody @Valid Repeat input,@RequestHeader("Idempotency-Key") String key) { return result(service.repeat(id,input,key,"DEEPER")); }
    @PostMapping("/{id}/void") public ResponseEntity<IdempotentCommands.Result> voidMaterial(@PathVariable UUID id,@RequestBody @Valid VoidMaterial input,@RequestHeader("Idempotency-Key") String key) { return result(service.voidMaterial(id,input,key)); }
    private static ResponseEntity<IdempotentCommands.Result> result(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
