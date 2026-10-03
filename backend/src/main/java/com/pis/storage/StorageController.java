package com.pis.storage;
import java.io.IOException;import java.util.UUID;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{requestId}/storage")
public class StorageController {
 private final StorageService storage;public StorageController(StorageService storage){this.storage=storage;}
 @GetMapping public StorageContracts.View list(@PathVariable UUID requestId,@RequestParam(defaultValue="1") int page){return storage.list(requestId,page);}
 @GetMapping("/{id}") public StorageContracts.Version detail(@PathVariable UUID requestId,@PathVariable UUID id){return storage.detail(requestId,id);}
 @GetMapping("/capacity") public StorageContracts.Capacity capacity(@PathVariable UUID requestId){return storage.capacity(requestId);}
 @PostMapping public ResponseEntity<IdempotentCommands.Result> reserve(@PathVariable UUID requestId,@Valid @RequestBody StorageContracts.Reserve input,@RequestHeader("Idempotency-Key") String key){var result=storage.reserve(requestId,input,key);return ResponseEntity.status(201).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result);}
 @PutMapping(value="/{id}/bytes",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) public StorageContracts.Version upload(@PathVariable UUID requestId,@PathVariable UUID id,@RequestHeader("X-Storage-Version") long version,HttpServletRequest request)throws IOException{try(var input=request.getInputStream()){return storage.upload(requestId,id,version,request.getContentLengthLong(),input);}}
 @PostMapping("/{id}/finish") public StorageContracts.Version finish(@PathVariable UUID requestId,@PathVariable UUID id,@Valid @RequestBody StorageContracts.Command input,@RequestHeader("Idempotency-Key") String key){return storage.finish(requestId,id,input,key);}
 @PostMapping("/{id}/reconcile") public StorageContracts.Version reconcile(@PathVariable UUID requestId,@PathVariable UUID id){return storage.reconcile(requestId,id);}
 @PostMapping("/cleanup-dry-run") public StorageContracts.Cleanup cleanup(@PathVariable UUID requestId){return storage.cleanup(requestId);}
 @GetMapping("/{id}/bytes") public ResponseEntity<byte[]> bytes(@PathVariable UUID requestId,@PathVariable UUID id,@RequestHeader(value="Range",required=false) String range,@RequestHeader("X-Storage-Purpose") String purpose){var result=storage.bytes(requestId,id,range,purpose);var response=ResponseEntity.status(range==null?200:206).contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(result.bytes().length).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Accept-Ranges","bytes").header("Content-Disposition","attachment; filename=\"synthetic-"+id+".bin\"");if(range!=null)response.header("Content-Range","bytes "+result.start()+"-"+result.end()+"/"+result.total());return response.body(result.bytes());}
}
