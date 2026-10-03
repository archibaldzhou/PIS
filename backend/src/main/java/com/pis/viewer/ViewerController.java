package com.pis.viewer;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{requestId}/scans/{scanId}/viewer")
public class ViewerController {
    private final ViewerService service;
    public ViewerController(ViewerService service){this.service=service;}
    @PostMapping public IdempotentCommands.Result prepare(@PathVariable UUID requestId,@PathVariable UUID scanId,@Valid @RequestBody ViewerService.Prepare input,@RequestHeader("Idempotency-Key")String key){return service.prepare(requestId,scanId,input.publicationVersion(),key);}
    @GetMapping public ViewerService.Manifest manifest(@PathVariable UUID requestId,@PathVariable UUID scanId,@RequestParam long publicationVersion){return service.manifest(requestId,scanId,publicationVersion);}
    @GetMapping("/thumbnail") public ResponseEntity<byte[]> thumbnail(@PathVariable UUID requestId,@PathVariable UUID scanId,@RequestParam long publicationVersion){return response(service.tile(requestId,scanId,publicationVersion,0,0,0,true));}
    @GetMapping("/tiles/{level}/{x}/{y}") public ResponseEntity<byte[]> tile(@PathVariable UUID requestId,@PathVariable UUID scanId,@RequestParam long publicationVersion,@PathVariable int level,@PathVariable int x,@PathVariable int y){return response(service.tile(requestId,scanId,publicationVersion,level,x,y,false));}
    private ResponseEntity<byte[]> response(ViewerService.Binary b){return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(b.bytes().length).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").header("X-Content-SHA256",b.hash()).header("X-PIS-Capability","SYNTHETIC_RGB_ONLY").body(b.bytes());}
}
