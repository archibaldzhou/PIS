package com.pis.report;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.report.ReportContracts.*;
@RestController
@RequestMapping("/api/requests/reports/cases/{id}")
public class ReportController {
 private final ReportService service;
 public ReportController(ReportService service) { this.service=service; }
 @GetMapping public Detail detail(@PathVariable UUID id) { return service.detail(id); }
 @GetMapping("/history") public History history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page) { return service.history(id,page); }
 @PostMapping("/draft") public ResponseEntity<IdempotentCommands.Result> save(@PathVariable UUID id,@Valid @RequestBody Save input,@RequestHeader("Idempotency-Key") String key) { var r=service.save(id,input,key);return ResponseEntity.status(200).header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
