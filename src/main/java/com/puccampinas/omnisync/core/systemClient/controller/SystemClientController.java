package com.puccampinas.omnisync.core.systemClient.controller;

import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.systemClient.dto.SystemClientUpdateRequest;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_INTEGRATION_MANAGE;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_SETTINGS_MANAGE;

@RestController
@RequestMapping("/api/client")
public class SystemClientController {

    private final SystemClientService service;
    private final TenantAccess access;

    public SystemClientController(SystemClientService service, TenantAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/{id}")
    public ResponseEntity<SystemClient> getById(@PathVariable long id) {
        access.requireTenant(id);
        return ResponseEntity.ok(this.service.getById(id));
    }

    @GetMapping("/checkCNPJ/{document}")
    public Boolean existsByCNPJ(@PathVariable String document) {
        return service.existsByDocument(document);
    }

    @PutMapping("/{id}")
    @PreAuthorize(HAS_SETTINGS_MANAGE)
    public ResponseEntity<SystemClient> update(@PathVariable long id, @RequestBody SystemClientUpdateRequest data) {
        access.requireTenant(id);
        return ResponseEntity.ok(this.service.update(id, data));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(HAS_SETTINGS_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable long id) {
        access.requireTenant(id);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/updateMarketplaces/{id}")
    @PreAuthorize(HAS_INTEGRATION_MANAGE)
    public ResponseEntity<SystemClient> updateMarketplaces(@PathVariable long id, @RequestBody Map<String, Object> data) {
        access.requireTenant(id);
        return ResponseEntity.ok(this.service.updateClientsMarketplaces(id, data));
    }

}
