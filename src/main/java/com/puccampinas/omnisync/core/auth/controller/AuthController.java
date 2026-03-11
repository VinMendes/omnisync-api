package com.puccampinas.omnisync.core.auth.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.users.entity.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller responsável pelas operações de autenticação da aplicação.
 *
 * <p>
 * Todas as rotas deste controller ficam sob o prefixo {@code /api/auth}.
 * Ele atua como porta de entrada da API para o fluxo de autenticação,
 * delegando a regra de negócio para o {@link AuthService}.
 * </p>
 *
 * <h2>Principais responsabilidades</h2>
 * <ul>
 *     <li>Registrar novos usuários</li>
 *     <li>Autenticar usuários existentes</li>
 *     <li>Emitir cookies de access token e refresh token</li>
 *     <li>Renovar o access token a partir do refresh token</li>
 *     <li>Realizar logout limpando os cookies</li>
 * </ul>
 *
 * <h2>Modelo de autenticação utilizado</h2>
 * <p>
 * A aplicação utiliza autenticação baseada em <strong>JWT + cookies HttpOnly</strong>,
 * com dois tokens:
 * </p>
 * <ul>
 *     <li><strong>Access Token</strong>: curta duração, usado nas rotas protegidas</li>
 *     <li><strong>Refresh Token</strong>: longa duração, usado para gerar um novo access token</li>
 * </ul>
 *
 * <p>
 * Os cookies são gerenciados pelo {@link AuthCookieService}, enquanto
 * a regra de criação, autenticação e renovação dos tokens fica concentrada
 * no {@link AuthService}.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Serviço responsável pela regra de negócio da autenticação.
     *
     * <p>
     * Ele realiza cadastro, login, geração de tokens e renovação do access token.
     * </p>
     */
    private final AuthService authService;

    /**
     * Serviço responsável por criar, atualizar e remover os cookies de autenticação.
     */
    private final AuthCookieService authCookieService;

    /**
     * Construtor com injeção de dependências.
     *
     * @param authService serviço principal de autenticação
     * @param authCookieService serviço responsável pelos cookies de auth
     */
    public AuthController(AuthService authService,
                          AuthCookieService authCookieService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    /**
     * Registra um novo usuário no sistema.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Recebe os dados do usuário via {@link RegisterRequest}</li>
     *     <li>Chama o service para cadastrar o usuário no banco</li>
     *     <li>Gera access token e refresh token</li>
     *     <li>Envia os tokens em cookies HttpOnly</li>
     *     <li>Retorna um {@link AuthResponse} com os dados principais do usuário</li>
     * </ol>
     *
     * @param req dados enviados para registro
     * @param response resposta HTTP usada para adicionar os cookies
     * @return 200 OK com os dados do usuário cadastrado, ou 400 em caso de erro de negócio
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req,
                                      HttpServletResponse response) {
        try {
            User user = authService.register(req);

            String access = authService.generateAccessToken(user);
            String refresh = authService.generateRefreshToken(user);

            setAuthCookies(response, access, refresh);

            AuthResponse authResponse = authService.buildAuthResponse(
                    "Usuário registrado com sucesso",
                    user
            );

            return ResponseEntity.ok(authResponse);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Autentica um usuário já cadastrado.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Recebe email e senha via {@link LoginRequest}</li>
     *     <li>Valida as credenciais no {@link AuthService}</li>
     *     <li>Gera access token e refresh token</li>
     *     <li>Adiciona ambos nos cookies da resposta</li>
     *     <li>Retorna um {@link AuthResponse} com informações do usuário autenticado</li>
     * </ol>
     *
     * @param req credenciais do usuário
     * @param response resposta HTTP usada para adicionar os cookies
     * @return 200 OK em caso de sucesso, ou 401 em caso de credenciais inválidas
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletResponse response) {
        try {
            User user = authService.authenticate(req);

            String access = authService.generateAccessToken(user);
            String refresh = authService.generateRefreshToken(user);

            setAuthCookies(response, access, refresh);

            AuthResponse authResponse = authService.buildAuthResponse(
                    "Login realizado com sucesso",
                    user
            );

            return ResponseEntity.ok(authResponse);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }

    /**
     * Renova o access token usando o refresh token enviado em cookie.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Lê o cookie do refresh token</li>
     *     <li>Valida o refresh token através do service</li>
     *     <li>Gera um novo access token</li>
     *     <li>Atualiza apenas o cookie do access token</li>
     * </ol>
     *
     * <p>
     * Caso o refresh token esteja ausente, inválido ou expirado,
     * os cookies de autenticação são removidos.
     * </p>
     *
     * @param request requisição HTTP atual, usada para ler os cookies
     * @param response resposta HTTP usada para atualizar o cookie do access token
     * @return 200 OK se renovou com sucesso, ou 401 se o refresh for inválido
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
                                     HttpServletResponse response) {
        String refreshToken = extractCookie(request, authCookieService.getRefreshCookieName());

        if (refreshToken == null) {
            return ResponseEntity.status(401).body("Refresh token ausente");
        }

        try {
            String newAccess = authService.refreshAccessToken(refreshToken);

            int accessMaxAge = 15 * 60;
            authCookieService.setAccessCookie(response, newAccess, accessMaxAge);

            return ResponseEntity.ok("Access renovado");
        } catch (RuntimeException e) {
            authCookieService.clearAuthCookies(response);
            return ResponseEntity.status(401).body(e.getMessage());
        } catch (Exception e) {
            authCookieService.clearAuthCookies(response);
            return ResponseEntity.status(401).body("Refresh inválido ou expirado");
        }
    }

    /**
     * Realiza logout removendo os cookies de autenticação do cliente.
     *
     * <p>
     * Como a aplicação utiliza JWT stateless, o logout consiste
     * em limpar os cookies do navegador, impedindo o envio futuro
     * dos tokens.
     * </p>
     *
     * @param response resposta HTTP usada para remover os cookies
     * @return 200 OK confirmando o logout
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        authCookieService.clearAuthCookies(response);
        return ResponseEntity.ok("Logout realizado com sucesso");
    }

    /**
     * Método utilitário para definir os cookies de autenticação.
     *
     * <p>
     * Ele configura:
     * </p>
     * <ul>
     *     <li>cookie do access token com curta duração</li>
     *     <li>cookie do refresh token com longa duração</li>
     * </ul>
     *
     * @param response resposta HTTP atual
     * @param access access token gerado
     * @param refresh refresh token gerado
     */
    private void setAuthCookies(HttpServletResponse response, String access, String refresh) {
        int accessMaxAge = 15 * 60;
        int refreshMaxAge = 7 * 24 * 60 * 60;

        authCookieService.setAccessCookie(response, access, accessMaxAge);
        authCookieService.setRefreshCookie(response, refresh, refreshMaxAge);
    }

    /**
     * Extrai o valor de um cookie específico da requisição.
     *
     * @param request requisição HTTP atual
     * @param cookieName nome do cookie desejado
     * @return valor do cookie se encontrado; caso contrário, {@code null}
     */
    private String extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }

        return null;
    }

    /**
     * Endpoint simples de teste para verificar se o controller está acessível.
     *
     * @return resposta fixa "pong"
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}