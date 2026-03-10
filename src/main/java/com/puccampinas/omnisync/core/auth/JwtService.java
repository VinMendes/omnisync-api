package com.puccampinas.omnisync.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Serviço responsável por gerar e validar tokens JWT (JSON Web Tokens).
 *
 * <p>
 * Neste projeto usamos o padrão de autenticação com <strong>2 tokens</strong>:
 * </p>
 *
 * <ul>
 *     <li><strong>Access Token</strong>: curto, usado para autenticar rotas protegidas.</li>
 *     <li><strong>Refresh Token</strong>: longo, usado apenas para renovar o Access Token.</li>
 * </ul>
 *
 * <p>
 * Os tokens são "stateless": o servidor não guarda sessão em memória nem em banco
 * (nesta fase do projeto). A confiança acontece porque os tokens são
 * <strong>assinados</strong> com uma chave secreta conhecida apenas pelo servidor.
 * </p>
 *
 * <h2>O que a validação garante</h2>
 * <p>
 * Quando validamos um JWT, a biblioteca verifica automaticamente:
 * </p>
 * <ul>
 *     <li><strong>Assinatura</strong>: se o token foi gerado por este servidor e não foi alterado</li>
 *     <li><strong>Expiração</strong>: se o token ainda está dentro do prazo de validade (claim {@code exp})</li>
 * </ul>
 *
 * <p>
 * Além disso, este serviço também impõe uma regra adicional:
 * validar se o token é do tipo esperado (access ou refresh) usando a claim
 * {@link #CLAIM_TOKEN_TYPE}.
 * </p>
 *
 * <h2>Configurações externas</h2>
 * <p>
 * Valores carregados do {@code application.properties}:
 * </p>
 *
 * <ul>
 *     <li>{@code app.jwt.secret} → segredo usado para assinar tokens</li>
 *     <li>{@code app.jwt.accessExpMinutes} → expiração do access token (minutos)</li>
 *     <li>{@code app.jwt.refreshExpDays} → expiração do refresh token (dias)</li>
 * </ul>
 *
 * <p>
 * Observação: o segredo precisa ter tamanho suficiente para HS256.
 * A biblioteca {@code jjwt} exige uma chave forte (ex.: 32+ caracteres).
 * </p>
 */
@Service
public class JwtService {

    /**
     * Nome da claim customizada que indica o "tipo" do token.
     *
     * <p>
     * Essa claim é usada para diferenciar:
     * </p>
     * <ul>
     *     <li>Access Token: usado pelo filtro para autenticar requests</li>
     *     <li>Refresh Token: usado apenas para renovar o access</li>
     * </ul>
     */
    public static final String CLAIM_TOKEN_TYPE = "token_type";

    /**
     * Valor da claim {@link #CLAIM_TOKEN_TYPE} para Access Token.
     */
    public static final String TYPE_ACCESS = "access";

    /**
     * Valor da claim {@link #CLAIM_TOKEN_TYPE} para Refresh Token.
     */
    public static final String TYPE_REFRESH = "refresh";

    /**
     * Chave criptográfica (HMAC) usada para assinar e validar o JWT.
     *
     * <p>
     * Ela é derivada do {@code app.jwt.secret}. Como usamos HS256,
     * trata-se de uma chave simétrica (mesma chave para assinar e validar).
     * </p>
     */
    private final Key key;

    /**
     * Tempo de expiração do Access Token, em minutos.
     */
    private final long accessExpMinutes;

    /**
     * Tempo de expiração do Refresh Token, em dias.
     */
    private final long refreshExpDays;

    /**
     * Construtor com injeção das propriedades de configuração.
     *
     * @param secret segredo que será convertido para uma chave HMAC
     * @param accessExpMinutes expiração do access token em minutos
     * @param refreshExpDays expiração do refresh token em dias
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.accessExpMinutes}") long accessExpMinutes,
            @Value("${app.jwt.refreshExpDays}") long refreshExpDays
    ) {
        /*
         * Converte o segredo (String) em bytes e cria uma Key HMAC segura.
         * Se o segredo for curto/fraco para HS256, a biblioteca lança exception.
         */
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpMinutes = accessExpMinutes;
        this.refreshExpDays = refreshExpDays;
    }

    /**
     * Gera um Access Token para um usuário.
     *
     * <p>
     * O Access Token tem vida curta e é o token usado para autenticar requests
     * em rotas protegidas (via filtro {@code JwtCookieAuthFilter}).
     * </p>
     *
     * @param username usuário que será definido como subject do token
     * @return JWT assinado do tipo "access"
     */
    public String generateAccessToken(String username) {
        Instant exp = Instant.now().plus(accessExpMinutes, ChronoUnit.MINUTES);
        return generateToken(username, TYPE_ACCESS, exp);
    }

    /**
     * Gera um Refresh Token para um usuário.
     *
     * <p>
     * O Refresh Token tem vida longa e não deve ser usado para acessar rotas protegidas.
     * Ele existe apenas para ser enviado ao endpoint {@code /auth/refresh}
     * e então gerar um novo Access Token quando o Access expirar.
     * </p>
     *
     * @param username usuário que será definido como subject do token
     * @return JWT assinado do tipo "refresh"
     */
    public String generateRefreshToken(String username) {
        Instant exp = Instant.now().plus(refreshExpDays, ChronoUnit.DAYS);
        return generateToken(username, TYPE_REFRESH, exp);
    }

    /**
     * Método interno que constrói um JWT com:
     * <ul>
     *     <li>subject (username)</li>
     *     <li>claim customizada {@link #CLAIM_TOKEN_TYPE}</li>
     *     <li>issuedAt (iat)</li>
     *     <li>expiration (exp)</li>
     *     <li>assinatura HS256</li>
     * </ul>
     *
     * @param username subject do token
     * @param tokenType tipo do token ("access" ou "refresh")
     * @param exp instante de expiração do token
     * @return JWT assinado e compactado em String
     */
    private String generateToken(String username, String tokenType, Instant exp) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Valida um token e devolve as {@link Claims} do payload.
     *
     * <p>
     * Nesta validação a biblioteca verifica automaticamente:
     * </p>
     * <ul>
     *     <li>Assinatura (se o token foi assinado com a chave correta)</li>
     *     <li>Expiração (claim exp)</li>
     * </ul>
     *
     * <p>
     * Se o token estiver inválido/expirado/malformado, a biblioteca lança uma exception.
     * </p>
     *
     * @param token token JWT recebido do cliente
     * @return payload (claims) do token
     */
    public Claims validateAndGetClaims(String token) {
        return Jwts.parser()
                .verifyWith((javax.crypto.SecretKey) key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Valida um token e garante que ele possui o tipo esperado
     * (claim {@link #CLAIM_TOKEN_TYPE}).
     *
     * <p>
     * Essa validação extra impede, por exemplo, que um Refresh Token
     * seja usado no lugar de um Access Token.
     * </p>
     *
     * @param token token JWT recebido do cliente
     * @param expectedType tipo esperado ("access" ou "refresh")
     * @return payload (claims) do token validado
     * @throws IllegalArgumentException se o token for válido mas o tipo for diferente do esperado
     */
    public Claims validateAndGetClaims(String token, String expectedType) {
        Claims claims = validateAndGetClaims(token);

        String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!expectedType.equals(type)) {
            throw new IllegalArgumentException("Token type inválido: " + type);
        }

        return claims;
    }
}