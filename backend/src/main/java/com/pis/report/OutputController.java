package com.pis.report;
import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.report.OutputContracts.*;
@RestController
@RequestMapping("/api/requests/reports/cases/{id}/output")
public class OutputController {
 private final OutputService service;
 public OutputController(OutputService service) { this.service=service; }
 @GetMapping public Detail detail(@PathVariable UUID id) { return service.detail(id); }
 @PostMapping public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID id,@Valid @RequestBody Create input,@RequestHeader("Idempotency-Key") String key) { return receipt(service.create(id,input,key)); }
 @GetMapping("/{artifact}/history") public History history(@PathVariable UUID id,@PathVariable UUID artifact,@RequestParam(defaultValue="1") int page) { return service.history(id,artifact,page); }
 @PostMapping("/{artifact}/events/{kind}") public ResponseEntity<IdempotentCommands.Result> record(@PathVariable UUID id,@PathVariable UUID artifact,@PathVariable Kind kind,@Valid @RequestBody Operation input,@RequestHeader("Idempotency-Key") String key) { return receipt(service.record(id,artifact,input,key,kind)); }
 @PostMapping("/{artifact}/bytes/{kind}") public ResponseEntity<byte[]> bytes(@PathVariable UUID id,@PathVariable UUID artifact,@PathVariable Kind kind,@Valid @RequestBody Operation input,@RequestHeader("Idempotency-Key") String key) {
  if(kind!=Kind.PREVIEW&&kind!=Kind.DOWNLOAD) throw new com.pis.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"REPORT_OUTPUT_ACTION_INVALID","Invalid binary purpose");var result=service.bytes(id,artifact,input,key,kind);var a=result.artifact();
  return ResponseEntity.ok().header("Content-Type","application/pdf").header("Content-Disposition",(kind==Kind.PREVIEW?"inline":"attachment")+"; filename=\"synthetic-"+a.id()+"-v0.pdf\"").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("X-Artifact-Id",a.id().toString()).header("X-Artifact-SHA256",a.sha256()).header("X-Artifact-Version","0").header("Idempotency-Replayed",Boolean.toString(result.replayed())).contentLength(a.byteSize()).body(result.bytes());
 }
 private ResponseEntity<IdempotentCommands.Result> receipt(IdempotentCommands.Result r) { return ResponseEntity.ok().header("Idempotency-Replayed",Boolean.toString(r.replayed())).body(r); }
}
