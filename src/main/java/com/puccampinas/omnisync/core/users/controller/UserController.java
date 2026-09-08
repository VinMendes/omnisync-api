package com.puccampinas.omnisync.core.users.controller;

import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.dto.UserStatusUpdateRequest;
import com.puccampinas.omnisync.core.users.dto.UserUpdateRequest;
import com.puccampinas.omnisync.core.users.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_USER_MANAGE;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> findMe(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).body("Usuário não autenticado.");
        }

        return ResponseEntity.ok(userService.findMe(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> findById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(userService.findById(authentication.getName(), id));
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> findAll(Authentication authentication) {
        return ResponseEntity.ok(userService.findAll(authentication.getName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize(HAS_USER_MANAGE)
    public ResponseEntity<UserResponse> update(
            @PathVariable Long id,
            @RequestBody UserUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(userService.update(authentication.getName(), id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(HAS_USER_MANAGE)
    public ResponseEntity<UserResponse> updateStatus(
            @PathVariable Long id,
            @RequestBody UserStatusUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(userService.updateStatus(authentication.getName(), id, request));
    }
}
