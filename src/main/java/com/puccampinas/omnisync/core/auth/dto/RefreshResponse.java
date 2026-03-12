package com.puccampinas.omnisync.core.auth.dto;

/**
 * DTO de resposta usado na renovação do access token.
 *
 * <p>
 * O endpoint de refresh continua atualizando o cookie do access token,
 * mas agora também devolve o novo access token no corpo da resposta.
 * </p>
 *
 * <p>
 * Isso permite que clientes que trabalham com Bearer Token também
 * aproveitem o mesmo fluxo de renovação.
 * </p>
 */
public record RefreshResponse(
        String message,
        String accessToken
) {
}