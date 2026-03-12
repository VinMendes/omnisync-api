package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filtro de autenticação que lê o <strong>Access Token</strong> (JWT)
 * tanto do header Authorization quanto do cookie HttpOnly e transforma
 * isso em uma autenticação válida dentro do Spring Security.
 *
 * <p>
 * A ordem de prioridade adotada é:
 * </p>
 * <ol>
 *     <li>Header {@code Authorization: Bearer ...}</li>
 *     <li>Cookie do Access Token</li>
 * </ol>
 *
 * <p>
 * No modelo com <strong>2 tokens</strong> (Access + Refresh), este filtro deve usar
 * <strong>somente o Access Token</strong>.
 * O Refresh Token não deve autenticar rotas diretamente: ele existe apenas para renovar o Access.
 * </p>
 *
 * <h2>Fluxo resumido</h2>
 * <ol>
 *     <li>Verifica se já existe autenticação no SecurityContext</li>
 *     <li>Tenta extrair o Access Token do header Bearer</li>
 *     <li>Se não existir Bearer, tenta extrair do cookie</li>
 *     <li>Valida o JWT (assinatura + expiração + tipo=access)</li>
 *     <li>Se for válido, cria um Authentication e salva no SecurityContext</li>
 *     <li>Segue o fluxo chamando {@code filterChain.doFilter()}</li>
 * </ol>
 *
 * <p>
 * Importante: se o token for inválido/expirado, o filtro não bloqueia diretamente.
 * Ele apenas não autentica e deixa o Spring Security decidir o que fazer (401/403 em rotas protegidas).
 * </p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Serviço responsável por validar o JWT e extrair claims/usuário.
     */
    private final JwtService jwtService;

    /**
     * Serviço responsável por informar os nomes dos cookies (access/refresh).
     */
    private final AuthCookieService authCookieService;

    /**
     * Construtor com injeção de dependências.
     *
     * @param jwtService serviço para validação e extração de dados do JWT
     * @param authCookieService serviço de utilidades para cookies de autenticação
     */
    public JwtAuthenticationFilter(JwtService jwtService, AuthCookieService authCookieService) {
        this.jwtService = jwtService;
        this.authCookieService = authCookieService;
    }

    /**
     * Método principal do filtro, executado automaticamente a cada requisição.
     *
     * @param request requisição HTTP atual
     * @param response resposta HTTP atual
     * @param filterChain cadeia de filtros do Spring
     * @throws ServletException em caso de erros de servlet
     * @throws IOException em caso de erros de I/O
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Se já existe auth no contexto, não reprocessa
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        /*
         * Primeiro tenta o header Authorization: Bearer ...
         * Se não existir, tenta o cookie do Access Token.
         */
        String accessToken = extractBearerToken(request);

        if (accessToken == null) {
            accessToken = extractAccessTokenFromCookie(request);
        }

        if (accessToken != null) {
            try {
                /*
                 * Valida assinatura + expiração + garante que token_type == "access".
                 * Se estiver inválido/expirado/tipo errado, lança exception.
                 */
                Claims claims = jwtService.validateAndGetClaims(accessToken, JwtService.TYPE_ACCESS);

                String username = claims.getSubject();

                // ROLE fixa só pra demo
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                username,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))
                        );

                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (Exception ignored) {
                // Token inválido/expirado -> não autentica
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extrai o Access Token do header Authorization no formato Bearer.
     *
     * <p>
     * Exemplo esperado:
     * </p>
     * <pre>
     * Authorization: Bearer eyJhbGciOi...
     * </pre>
     *
     * @param request requisição HTTP atual
     * @return token se existir e estiver no formato correto; caso contrário, {@code null}
     */
    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");

        if (authorization == null || authorization.isBlank()) {
            return null;
        }

        if (!authorization.startsWith("Bearer ")) {
            return null;
        }

        String token = authorization.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    /**
     * Extrai o Access Token do cookie configurado.
     *
     * @param request requisição HTTP atual
     * @return token do access cookie se existir; caso contrário, {@code null}
     */
    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        String name = authCookieService.getAccessCookieName();

        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}