package com.puccampinas.omnisync.core.audit;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) { this.service = service; }

    @GetMapping("/{systemClientId}")
    public AuditResponse.Page find(@PathVariable Long systemClientId,
            @RequestParam(required = false) Long userId, @RequestParam(required = false) String role,
            @RequestParam(required = false) String action, @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") long offset, @RequestParam(defaultValue = "20") int limit) {
        return service.find(systemClientId, userId, role, action, entityType, from, to, offset, limit);
    }
}
