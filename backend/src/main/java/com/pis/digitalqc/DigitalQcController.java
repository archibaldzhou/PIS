package com.pis.digitalqc;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.pis.idempotency.IdempotentCommands;
@RestController
@RequestMapping("/api/requests/{requestId}/scans/{scanId}/digital-qc")
public class DigitalQcController {
    private final DigitalQcService service;
    public DigitalQcController(DigitalQcService service){this.service=service;}
    @GetMapping public DigitalQcContracts.View view(@PathVariable UUID requestId,@PathVariable UUID scanId){return service.view(requestId,scanId);}
    @GetMapping("/history") public List<DigitalQcContracts.Assessment> history(@PathVariable UUID requestId,@PathVariable UUID scanId){return service.history(requestId,scanId);}
    @PostMapping public IdempotentCommands.Result evaluate(@PathVariable UUID requestId,@PathVariable UUID scanId,@Valid @RequestBody DigitalQcContracts.Evaluation input,@RequestHeader("Idempotency-Key")String key){return service.evaluate(requestId,scanId,input,key);}
    @PostMapping("/{action}") public IdempotentCommands.Result command(@PathVariable UUID requestId,@PathVariable UUID scanId,@PathVariable String action,@Valid @RequestBody DigitalQcContracts.Command input,@RequestHeader("Idempotency-Key")String key){return service.command(requestId,scanId,action,input,key);}
    @GetMapping("/bytes") public ResponseEntity<byte[]> bytes(@PathVariable UUID requestId,@PathVariable UUID scanId,@RequestParam long publicationVersion,@RequestHeader(value="Range",required=false)String range){
        var data=service.consume(requestId,scanId,publicationVersion,range);
        var response=ResponseEntity.status(range==null?200:206).contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(data.bytes().length)
            .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("X-PIS-Capability","SYNTHETIC_CONTRACT_ONLY_NO_VIEWER")
            .header("Accept-Ranges","bytes").header("Content-Disposition","attachment; filename=\"synthetic-qc-"+scanId+".bin\"");
        if(range!=null)response.header("Content-Range","bytes "+data.start()+"-"+data.end()+"/"+data.total());
        return response.body(data.bytes());
    }
}
