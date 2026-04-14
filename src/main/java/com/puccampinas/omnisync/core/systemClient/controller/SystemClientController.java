package com.puccampinas.omnisync.core.systemClient.controller;

import com.puccampinas.omnisync.core.systemClient.dto.SystemClientCreateRequest;
import com.puccampinas.omnisync.core.systemClient.dto.SystemClientUpdateRequest;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/client")
public class SystemClientController {

    private final SystemClientService service;

    public SystemClientController(SystemClientService service) {
        this.service = service;
    }

    @PostMapping()
    public ResponseEntity<SystemClient> create(@RequestBody SystemClientCreateRequest data) {
        return ResponseEntity.ok(this.service.create(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SystemClient> getById(@PathVariable long id) {
        return ResponseEntity.ok(this.service.getById(id));
    }

    @GetMapping("/checkCNPJ/{document}")
    public Boolean existsByCNPJ(@PathVariable String document) {
        return service.existsByDocument(document);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SystemClient> update(@PathVariable long id, @RequestBody SystemClientUpdateRequest data) {
        return ResponseEntity.ok(this.service.update(id, data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/updateMarketplaces/{id}")
    public ResponseEntity<SystemClient> updateMarketplaces(@PathVariable long id, @RequestBody Map<String, Object> data) {
        return ResponseEntity.ok(this.service.updateClientsMarketplaces(id, data));
    }

}
