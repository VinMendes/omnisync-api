package com.puccampinas.omnisync.core.users.controller;

import com.puccampinas.omnisync.core.users.dto.AdminPasswordResetRequest;
import com.puccampinas.omnisync.core.users.dto.ChangePasswordRequest;
import com.puccampinas.omnisync.core.users.service.UserPasswordService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_USER_MANAGE;

@RestController
@RequestMapping("/api/users")
public class UserPasswordController {
    private final UserPasswordService service;
    public UserPasswordController(UserPasswordService service) { this.service = service; }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changeOwn(@Valid @RequestBody ChangePasswordRequest request) {
        service.changeOwn(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/password")
    @PreAuthorize(HAS_USER_MANAGE)
    public ResponseEntity<Void> resetOther(@PathVariable Long userId, @Valid @RequestBody AdminPasswordResetRequest request) {
        service.resetOther(userId, request);
        return ResponseEntity.noContent().build();
    }
}
