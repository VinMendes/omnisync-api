package com.puccampinas.omnisync.core.auth.passwordreset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetEmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public PasswordResetEmailService(JavaMailSender mailSender,
                                     @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject("OmniSync - Recuperação de senha");
        message.setText("""
                Olá!

                Recebemos uma solicitação para redefinir sua senha no OmniSync.

                Clique no link abaixo para criar uma nova senha:

                %s

                Esse link expira em 30 minutos.

                Se você não solicitou essa alteração, ignore este e-mail.

                Atenciosamente,
                Equipe OmniSync
                """.formatted(resetLink));

        mailSender.send(message);
    }
}