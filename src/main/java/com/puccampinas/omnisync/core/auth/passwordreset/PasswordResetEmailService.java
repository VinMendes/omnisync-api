package com.puccampinas.omnisync.core.auth.passwordreset;

import org.springframework.stereotype.Service;

@Service
public class PasswordResetEmailService {

    public void sendPasswordResetEmail(String to, String resetLink) {
        System.out.println("=================================================");
        System.out.println("RECUPERAÇÃO DE SENHA");
        System.out.println("Enviar para: " + to);
        System.out.println("Link: " + resetLink);
        System.out.println("=================================================");
    }
}