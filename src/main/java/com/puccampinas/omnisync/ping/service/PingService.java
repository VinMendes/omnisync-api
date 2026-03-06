package com.puccampinas.omnisync.ping.service;

import org.springframework.stereotype.Service;

@Service
public class PingService {

    public String ping() {
        return "pong";
    }
}