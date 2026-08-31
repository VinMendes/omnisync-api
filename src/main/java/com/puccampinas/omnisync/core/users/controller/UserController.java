package com.puccampinas.omnisync.core.users.controller;

import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.dto.UserStatusUpdateRequest;
import com.puccampinas.omnisync.core.users.dto.UserUpdateRequest;
import com.puccampinas.omnisync.core.users.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

        try {
            String email = authentication.getName();
            return ResponseEntity.ok(userService.findMe(email));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id, Authentication authentication) {
        try {
            return ResponseEntity.ok(userService.findById(authentication.getName(), id));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> findAll(Authentication authentication) {
        return ResponseEntity.ok(userService.findAll(authentication.getName()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody UserUpdateRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(userService.update(authentication.getName(), id, request));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestBody UserStatusUpdateRequest request,
            Authentication authentication
    ) {
        try {
            return ResponseEntity.ok(userService.updateStatus(authentication.getName(), id, request));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
    }
}
