package com.pis.statistics;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/statistics/scopes/{scopeId}")
public class StatisticsController {
 private final StatisticsService service;public StatisticsController(StatisticsService service){this.service=service;}
 @PostMapping public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID scopeId,@Valid @RequestBody StatisticsContracts.Query body,@RequestHeader("Idempotency-Key") String key){var r=service.create(scopeId,body,key);return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r);}
 @GetMapping("/{id}") public StatisticsContracts.View view(@PathVariable UUID scopeId,@PathVariable UUID id,@RequestParam(required=false) StatisticsContracts.Metric metric,@RequestParam(defaultValue="ENTITY") StatisticsContracts.Sort sort,@RequestParam(defaultValue="1") int page){return service.view(scopeId,id,metric,sort,page);}
}
