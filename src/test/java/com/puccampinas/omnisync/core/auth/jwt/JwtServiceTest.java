package com.puccampinas.omnisync.core.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String TEST_SECRET = "minha-chave-super-segura-com-mais-de-32-caracteres-123456";

    private final JwtService jwtService = new JwtService(
            TEST_SECRET,
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

    @Test
    void shouldRejectExpiredToken() {
        String token = sign(Jwts.builder().subject("user@example.com")
                .expiration(Date.from(Instant.now().minusSeconds(60))));

        assertThrows(ExpiredJwtException.class, () -> jwtService.validateAndGetClaims(token));
    }

    @Test
    void shouldRejectTokenSignedWithAnotherKey() {
        String token = new JwtService("another-test-key-with-at-least-32-bytes-123456789", 15, 7)
                .generateAccessToken("user@example.com");

        assertThrows(JwtException.class, () -> jwtService.validateAndGetClaims(token));
    }

    @Test
    void shouldRejectTokenWithoutSubject() {
        String token = sign(Jwts.builder().expiration(Date.from(Instant.now().plusSeconds(60))));

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndGetClaims(token));
    }

    @Test
    void shouldRejectTokenWithoutExpiration() {
        String token = sign(Jwts.builder().subject("user@example.com"));

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndGetClaims(token));
    }

    @Test
    void shouldRejectTokenWithoutTokenType() {
        String token = sign(Jwts.builder().subject("user@example.com")
                .expiration(Date.from(Instant.now().plusSeconds(60))));

        assertThrows(IllegalArgumentException.class,
                () -> jwtService.validateAndGetClaims(token, JwtService.TYPE_ACCESS));
    }

    @Test
    void shouldRejectMalformedAndUnsignedTokens() {
        String unsigned = Jwts.builder().subject("user@example.com")
                .expiration(Date.from(Instant.now().plusSeconds(60))).compact();

        assertThrows(JwtException.class, () -> jwtService.validateAndGetClaims("not-a-jwt"));
        assertThrows(JwtException.class, () -> jwtService.validateAndGetClaims(unsigned));
    }

    private String sign(JwtBuilder builder) {
        return builder.signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
