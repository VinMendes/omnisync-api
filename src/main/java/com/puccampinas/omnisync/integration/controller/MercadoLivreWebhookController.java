package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.service.MarketplaceWebhookInboxService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MercadoLivreWebhookController {

    private final MarketplaceWebhookInboxService inboxService;

    public MercadoLivreWebhookController(MarketplaceWebhookInboxService inboxService) {
        this.inboxService = inboxService;
    }

    @PostMapping("/api/integrations/mercadolivre/webhooks/orders")
    public ResponseEntity<Map<String, Object>> orders(@Valid @RequestBody MercadoLivreNotificationRequest notification) {
        return ResponseEntity.ok(inboxService.receive(notification));
    }

    @PostMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notifications(
            @Valid @RequestBody MercadoLivreNotificationRequest notification
    ) {
        return ResponseEntity.ok(inboxService.receive(notification));
    }
}
