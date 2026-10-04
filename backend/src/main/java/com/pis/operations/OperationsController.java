package com.pis.operations;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/requests/{request}/operations")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service) { this.service=service; }
    @GetMapping public ResponseEntity<OperationsService.Snapshot> read(@PathVariable UUID request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(request));
    }
}
