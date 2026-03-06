package com.mendesvincs.security.security;

import com.mendesvincs.security.auth.AuthCookieService;
import com.mendesvincs.security.auth.JwtService;
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
 * Filtro de autenticação que lê o <strong>Access Token</strong> (JWT) vindo de um cookie HttpOnly
 * e transforma isso em uma autenticação válida dentro do Spring Security.
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
 *     <li>Se não existir, procura o cookie do Access Token</li>
 *     <li>Se achar, valida o JWT (assinatura + expiração + tipo=access)</li>
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
public class JwtCookieAuthFilter extends OncePerRequestFilter {

    /**
     * Serviço responsável por validar o JWT e extrair claims/usuário.
     */
    private final JwtService jwtService;

    /**
     * Serviço responsável por informar os nomes dos cookies (access/refresh).
     */
    private final AuthCookieService cookieService;

    /**
     * Construtor com injeção de dependências.
     *
     * @param jwtService serviço para validação e extração de dados do JWT
     * @param cookieService serviço de utilidades para cookies de autenticação
     */
    public JwtCookieAuthFilter(JwtService jwtService, AuthCookieService cookieService) {
        this.jwtService = jwtService;
        this.cookieService = cookieService;
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

        // Pega o ACCESS token do cookie
        String accessToken = extractAccessTokenFromCookie(request);

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
     * Extrai o Access Token do cookie configurado.
     *
     * @param request requisição HTTP atual
     * @return token do access cookie se existir; caso contrário, null
     */
    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        String name = cookieService.getAccessCookieName();

        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}