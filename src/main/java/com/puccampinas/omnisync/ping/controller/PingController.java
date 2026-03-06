package com.puccampinas.omnisync.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.puccampinas.omnisync.service.PingService;

@RestController
public class PingController {

    private final PingService pingService;

    public PingController(PingService pingService) {
        this.pingService = pingService;
    }

    @GetMapping("/ping")
    public String ping() {
        return pingService.ping();
    }
}