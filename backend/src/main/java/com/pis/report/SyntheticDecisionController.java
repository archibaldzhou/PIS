package com.pis.report;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/reports/cases/{caseId}/synthetic-decisions")
public class SyntheticDecisionController {
 private final SyntheticDecisionService service;
 public SyntheticDecisionController(SyntheticDecisionService service){this.service=service;}
 @GetMapping public SyntheticDecisionService.View view(@PathVariable UUID caseId,@RequestParam UUID resultId,@RequestParam(defaultValue="1")int page){return service.view(caseId,resultId,page);}
 @PostMapping public IdempotentCommands.Result decide(@PathVariable UUID caseId,@Valid @RequestBody SyntheticDecisionService.Command command,@RequestHeader("Idempotency-Key")String key){return service.decide(caseId,command,key);}
 @GetMapping("/{decisionId}/impact") public SyntheticDecisionService.Impact impact(@PathVariable UUID caseId,@PathVariable UUID decisionId){return service.impact(caseId,decisionId);}
 @GetMapping("/{decisionId}/current-reference") public SyntheticDecisionService.Event current(@PathVariable UUID caseId,@PathVariable UUID decisionId){return service.consumeReference(caseId,decisionId);}
 @PostMapping("/{decisionId}/impact-reviews") public IdempotentCommands.Result review(@PathVariable UUID caseId,@PathVariable UUID decisionId,@Valid @RequestBody SyntheticDecisionService.ReviewCommand command,@RequestHeader("Idempotency-Key")String key){return service.reviewImpact(caseId,decisionId,command,key);}

}
