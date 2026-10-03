package com.pis.report;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.report.ReviewContracts.*;
@RestController
@RequestMapping("/api/requests/reports/cases/{id}/review")
public class ReviewController {
 private final ReviewService service;
 public ReviewController(ReviewService service) { this.service=service; }
 @GetMapping public Detail detail(@PathVariable UUID id) { return service.detail(id); }
 @GetMapping("/history") public History history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page) { return service.history(id,page); }
 @PostMapping("/{action}") public ResponseEntity<IdempotentCommands.Result> decide(@PathVariable UUID id,@PathVariable Action action,@Valid @RequestBody Decision input,@RequestHeader("Idempotency-Key") String key) { var r=service.decide(id,input,key,action);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
