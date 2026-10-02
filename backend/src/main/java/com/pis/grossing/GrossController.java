package com.pis.grossing;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.grossing.GrossContracts.*;
@RestController
@RequestMapping("/api/grossing")
public class GrossController {
    private final GrossService service;
    public GrossController(GrossService service) { this.service=service; }
    @GetMapping("/requests/{id}") public View view(@PathVariable UUID id) { return service.view(id); }
    @GetMapping("/requests/{id}/sample") public ResponseEntity<byte[]> sample(@PathVariable UUID id) { return png(service.sample(id)); }
    @GetMapping("/photos/{id}/content") public ResponseEntity<byte[]> photo(@PathVariable UUID id) { return png(service.photo(id)); }
    @PostMapping("/requests/{id}") public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID id,@RequestBody @Valid Create body,@RequestHeader("Idempotency-Key") String key) { return result(service.create(id,body,key)); }
    @PostMapping("/records/{id}/description") public ResponseEntity<IdempotentCommands.Result> describe(@PathVariable UUID id,@RequestBody @Valid Description body,@RequestHeader("Idempotency-Key") String key) { return result(service.describe(id,body,key,false)); }
    @PostMapping("/records/{id}/correction") public ResponseEntity<IdempotentCommands.Result> correct(@PathVariable UUID id,@RequestBody @Valid Description body,@RequestHeader("Idempotency-Key") String key) { return result(service.describe(id,body,key,true)); }
    @PostMapping("/records/{id}/cassettes") public ResponseEntity<IdempotentCommands.Result> box(@PathVariable UUID id,@RequestBody @Valid AddCassette body,@RequestHeader("Idempotency-Key") String key) { return result(service.addCassette(id,body,key)); }
    @PostMapping("/records/{id}/photos") public ResponseEntity<IdempotentCommands.Result> attach(@PathVariable UUID id,@RequestBody @Valid AddPhoto body,@RequestHeader("Idempotency-Key") String key) { return result(service.addPhoto(id,body,key)); }
    @PostMapping("/records/{id}/{action}") public ResponseEntity<IdempotentCommands.Result> decide(@PathVariable UUID id,@PathVariable String action,@RequestBody @Valid Decision body,@RequestHeader("Idempotency-Key") String key) {
        return result(service.decide(id,null,body,key,switch(action) { case "complete"->"COMPLETE"; case "cancel"->"CANCEL"; default->throw new com.pis.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"GROSS_NOT_FOUND","Unknown action"); }));
    }
    @PostMapping("/records/{id}/cassettes/{target}/cancel") public ResponseEntity<IdempotentCommands.Result> cancelBox(@PathVariable UUID id,@PathVariable UUID target,@RequestBody @Valid Decision body,@RequestHeader("Idempotency-Key") String key) { return result(service.decide(id,target,body,key,"CANCEL_CASSETTE")); }
    @PostMapping("/records/{id}/photos/{target}/withdraw") public ResponseEntity<IdempotentCommands.Result> withdraw(@PathVariable UUID id,@PathVariable UUID target,@RequestBody @Valid Decision body,@RequestHeader("Idempotency-Key") String key) { return result(service.decide(id,target,body,key,"WITHDRAW_PHOTO")); }
    private static ResponseEntity<IdempotentCommands.Result> result(IdempotentCommands.Result r) { return ResponseEntity.status(r.receipt().status()).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
    private static ResponseEntity<byte[]> png(byte[] bytes) { return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=synthetic-gross.png").contentType(org.springframework.http.MediaType.IMAGE_PNG).body(bytes); }
}
