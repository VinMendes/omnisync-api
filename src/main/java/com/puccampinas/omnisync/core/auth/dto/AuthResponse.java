package com.puccampinas.omnisync.core.auth.dto;

/**
 * DTO de resposta usado nas operações de autenticação.
 *
 * <p>
 * Ele é retornado principalmente nos endpoints de:
 * </p>
 * <ul>
 *     <li>login</li>
 *     <li>registro</li>
 * </ul>
 *
 * <p>
 * A resposta contém:
 * </p>
 * <ul>
 *     <li>mensagem descritiva da operação</li>
 *     <li>dados básicos do usuário autenticado</li>
 *     <li>access token gerado</li>
 *     <li>refresh token gerado</li>
 * </ul>
 *
 * <p>
 * Mesmo com os tokens presentes no body, a aplicação continua
 * enviando os cookies normalmente. Isso permite um modelo híbrido:
 * </p>
 * <ul>
 *     <li>navegador pode continuar usando cookies</li>
 *     <li>Postman ou frontend customizado pode usar Bearer Token</li>
 * </ul>
 */
public record AuthResponse(
        String message,
        Long userId,
        String name,
        String email,
        Boolean active,
        String accessToken,
        String refreshToken
) {
}