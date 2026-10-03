package com.pis.roi;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{requestId}/scans/{scanId}/roi")
public class RoiController {
 private final RoiService service;
 public RoiController(RoiService service){this.service=service;}
 @GetMapping public RoiService.View view(@PathVariable UUID requestId,@PathVariable UUID scanId,@RequestParam long publicationVersion){return service.view(requestId,scanId,publicationVersion);}
 @GetMapping("/{roiId}/history") public RoiService.View history(@PathVariable UUID requestId,@PathVariable UUID scanId,@PathVariable UUID roiId){return service.history(requestId,scanId,roiId);}
 @PostMapping public IdempotentCommands.Result save(@PathVariable UUID requestId,@PathVariable UUID scanId,@Valid @RequestBody RoiService.Save input,@RequestHeader("Idempotency-Key")String key){return service.save(requestId,scanId,input,key);}
 @PostMapping("/calibration") public IdempotentCommands.Result calibrate(@PathVariable UUID requestId,@PathVariable UUID scanId,@Valid @RequestBody RoiService.CalibrationInput input,@RequestHeader("Idempotency-Key")String key){return service.calibrate(requestId,scanId,input,key);}
}
