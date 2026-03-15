package com.puccampinas.omnisync.core.auth.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "minha-chave-super-segura-com-mais-de-32-caracteres-123456",
            15,
            7
    );

    @Test
    void generateAccessToken_shouldGenerateValidToken() {
        String token = jwtService.generateAccessToken("vinicius@email.com");

        assertNotNull(token);
        assertFalse(token.isBlank());

        Claims claims = jwtService.validateAndGetClaims(token);

        assertEquals("vinicius@email.com", claims.getSubject());
        assertEquals(JwtService.TYPE_ACCESS, claims.get(JwtService.CLAIM_TOKEN_TYPE, String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generateRefreshToken_shouldGenerateValidToken() {
        String token = jwtService.generateRefreshToken("vinicius@email.com");

        assertNotNull(token);
        assertFalse(token.isBlank());

        Claims claims = jwtService.validateAndGetClaims(token);

        assertEquals("vinicius@email.com", claims.getSubject());
        assertEquals(JwtService.TYPE_REFRESH, claims.get(JwtService.CLAIM_TOKEN_TYPE, String.class));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void validateAndGetClaims_shouldReturnClaimsForValidToken() {
        String token = jwtService.generateAccessToken("teste@email.com");

        Claims claims = jwtService.validateAndGetClaims(token);

        assertNotNull(claims);
        assertEquals("teste@email.com", claims.getSubject());
        assertEquals(JwtService.TYPE_ACCESS, claims.get(JwtService.CLAIM_TOKEN_TYPE, String.class));
    }

    @Test
    void validateAndGetClaimsWithExpectedType_shouldAcceptCorrectType() {
        String token = jwtService.generateRefreshToken("teste@email.com");

        Claims claims = jwtService.validateAndGetClaims(token, JwtService.TYPE_REFRESH);

        assertNotNull(claims);
        assertEquals("teste@email.com", claims.getSubject());
        assertEquals(JwtService.TYPE_REFRESH, claims.get(JwtService.CLAIM_TOKEN_TYPE, String.class));
    }

    @Test
    void validateAndGetClaimsWithExpectedType_shouldThrowWhenTypeIsWrong() {
        String token = jwtService.generateRefreshToken("teste@email.com");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> jwtService.validateAndGetClaims(token, JwtService.TYPE_ACCESS)
        );

        assertTrue(exception.getMessage().contains("Token type inválido"));
    }

    @Test
    void accessTokenAndRefreshTokenShouldBeDifferent() {
        String accessToken = jwtService.generateAccessToken("teste@email.com");
        String refreshToken = jwtService.generateRefreshToken("teste@email.com");

        assertNotNull(accessToken);
        assertNotNull(refreshToken);
        assertNotEquals(accessToken, refreshToken);
    }
}