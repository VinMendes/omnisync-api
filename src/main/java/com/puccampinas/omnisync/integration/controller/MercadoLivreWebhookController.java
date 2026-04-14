package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.service.MercadoLivreOrderWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MercadoLivreWebhookController {

    private final MercadoLivreOrderWebhookService mercadoLivreOrderWebhookService;

    public MercadoLivreWebhookController(MercadoLivreOrderWebhookService mercadoLivreOrderWebhookService) {
        this.mercadoLivreOrderWebhookService = mercadoLivreOrderWebhookService;
    }

    @PostMapping("/api/integrations/mercadolivre/webhooks/orders")
    public ResponseEntity<Map<String, Object>> orders(@RequestBody MercadoLivreNotificationRequest notification) {
        return ResponseEntity.ok(mercadoLivreOrderWebhookService.handleNotification(notification));
    }

    @PostMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notifications(
            @RequestBody MercadoLivreNotificationRequest notification
    ) {
        return ResponseEntity.ok(mercadoLivreOrderWebhookService.handleNotification(notification));
    }
}
