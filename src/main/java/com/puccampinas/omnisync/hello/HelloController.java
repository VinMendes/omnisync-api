package com.puccampinas.omnisync.hello;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller simples para validar se a aplicação está online
 * pela rota raiz do domínio.
 */
@RestController
public class HelloController {

    /**
     * Endpoint público na raiz da aplicação.
     *
     * @return mensagem simples confirmando que o OmniSync está online
     */
    @GetMapping("/")
    public ResponseEntity<String> home(Authentication auth) {
        return ResponseEntity.ok("OmniSync online");
    }
}