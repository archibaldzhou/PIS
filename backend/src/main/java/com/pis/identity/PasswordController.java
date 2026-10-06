package com.pis.identity;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PasswordController {
    private final PasswordChangeService service;
    public PasswordController(PasswordChangeService service){this.service=service;}
    @PostMapping("/api/auth/password") public ResponseEntity<Void> change(@RequestBody @Valid IdentityContracts.ChangePassword body){service.change(body);return ResponseEntity.noContent().build();}
}
