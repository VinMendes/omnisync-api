package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreCodeExchangeRequest;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationResponse;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationStatusResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations/mercadolivre")
public class MercadoLivreAuthController {

    private final MercadoLivreAuthService service;
    private final UserService userService;

    public MercadoLivreAuthController(MercadoLivreAuthService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    @GetMapping("/status")
    public ResponseEntity<MercadoLivreIntegrationStatusResponse> getStatus(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long systemClientId = userService.findActiveEntityByEmail(authentication.getName()).getSystemClientId();
        return ResponseEntity.ok(service.getStatus(systemClientId));
    }

    @GetMapping("/connect-url")
    public ResponseEntity<Map<String, String>> connectUrl(@RequestParam Long systemClientId) {
        return ResponseEntity.ok(Map.of(
                "authorizationUrl",
                service.generateAuthorizationUrl(systemClientId)
        ));
    }

    @PostMapping("/exchange")
    public ResponseEntity<MercadoLivreIntegrationResponse> exchangeCode(
            @Valid @RequestBody MercadoLivreCodeExchangeRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(
                service.exchangeCodeForAuthenticatedUser(
                        authentication.getName(),
                        request.state(),
                        request.code()
                )
        );
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription
    ) {
        if (error != null) {
            String message = "Mercado Livre OAuth denied: " + error;
            if (errorDescription != null && !errorDescription.isBlank()) {
                message += " - " + errorDescription;
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
        }

        if (code == null || state == null) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body("Missing required query params: code and state.");
        }

        Long systemClientId = service.handleCallback(state, code);
        return ResponseEntity.ok("Mercado Livre integration successful for systemClientId=" + systemClientId);
    }
}
