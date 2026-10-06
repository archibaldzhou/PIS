package com.pis.identity;

import com.pis.idempotency.IdempotentCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.pis.identity.IdentityContracts.*;

@RestController
@RequestMapping("/api/requests")
public class IdentityController {
    private final IdentityAdministration service;
    public IdentityController(IdentityAdministration service) { this.service=service; }
    @GetMapping("/work-context") public WorkContext context(){return service.context();}
    @GetMapping("/admin/hospitals") public List<IdentityAdministration.Hospital> hospitals(){return service.hospitals();}
    @GetMapping("/admin/{hospital}/catalog") public Catalog catalog(@PathVariable UUID hospital){return service.catalog(hospital);}
    @GetMapping("/admin/{hospital}/users") public UserPage users(@PathVariable UUID hospital,@RequestParam(defaultValue="") @Size(max=128) String search,@RequestParam(defaultValue="1") @Min(1) @Max(10000) int page){return service.users(hospital,search,page);}
    @GetMapping("/admin/{hospital}/users/{id}") public User user(@PathVariable UUID hospital,@PathVariable UUID id){return service.user(hospital,id);}
    @PostMapping("/admin/{hospital}/users") public ResponseEntity<IdempotentCommands.Result> create(@PathVariable UUID hospital,@RequestBody @Valid SaveUser body,@RequestHeader("Idempotency-Key") String key){return response(service.saveUser(hospital,null,body,key));}
    @PutMapping("/admin/{hospital}/users/{id}") public ResponseEntity<IdempotentCommands.Result> update(@PathVariable UUID hospital,@PathVariable UUID id,@RequestBody @Valid SaveUser body,@RequestHeader("Idempotency-Key") String key){return response(service.saveUser(hospital,id,body,key));}
    @PostMapping("/admin/{hospital}/users/{id}/password") public ResponseEntity<IdempotentCommands.Result> reset(@PathVariable UUID hospital,@PathVariable UUID id,@RequestBody @Valid ResetPassword body,@RequestHeader("Idempotency-Key") String key){return response(service.reset(hospital,id,body,key));}
    @PostMapping("/admin/{hospital}/scopes") public ResponseEntity<IdempotentCommands.Result> createScope(@PathVariable UUID hospital,@RequestBody @Valid SaveScope body,@RequestHeader("Idempotency-Key") String key){return response(service.saveScope(hospital,null,body,key));}
    @PutMapping("/admin/{hospital}/scopes/{id}") public ResponseEntity<IdempotentCommands.Result> updateScope(@PathVariable UUID hospital,@PathVariable UUID id,@RequestBody @Valid SaveScope body,@RequestHeader("Idempotency-Key") String key){return response(service.saveScope(hospital,id,body,key));}
    @PostMapping("/admin/{hospital}/organizations") public ResponseEntity<IdempotentCommands.Result> organization(@PathVariable UUID hospital,@RequestBody @Valid CreateOrganization body,@RequestHeader("Idempotency-Key") String key){return response(service.organization(hospital,body,key));}
    @GetMapping("/admin/{hospital}/audit") public List<Event> events(@PathVariable UUID hospital,@RequestParam(defaultValue="1") @Min(1) @Max(10000) int page){return service.events(hospital,page);}
    private static ResponseEntity<IdempotentCommands.Result> response(IdempotentCommands.Result value){return ResponseEntity.status(value.receipt().status()).header("Idempotency-Replayed",Boolean.toString(value.replayed())).body(value);}
}
