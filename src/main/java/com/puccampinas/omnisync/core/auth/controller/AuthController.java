package com.puccampinas.omnisync.core.auth.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.ForgotPasswordRequest;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RefreshResponse;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.dto.ResetPasswordRequest;
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
 *     <li>Retornar tokens também no corpo da resposta</li>
 *     <li>Renovar o access token a partir do refresh token</li>
 *     <li>Realizar logout limpando os cookies</li>
 *     <li>Solicitar recuperação de senha</li>
 *     <li>Redefinir senha usando token de recuperação</li>
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
 * Além dos cookies, o sistema também retorna os tokens no corpo da resposta
 * de login/registro. Isso permite que outros clientes, como Postman ou
 * frontends que prefiram usar Bearer Token, aproveitem os mesmos tokens.
 * </p>
 *
 * <p>
 * No caso do refresh, o endpoint também aceita o refresh token:
 * </p>
 * <ul>
 *     <li>via header {@code Authorization: Bearer ...}</li>
 *     <li>ou via cookie</li>
 * </ul>
 *
 * <p>
 * A recuperação de senha utiliza um token aleatório salvo no banco,
 * separado dos JWTs de autenticação. Esse token tem expiração e só pode ser
 * usado uma vez.
 * </p>
 *
 * <p>
 * Os cookies são gerenciados pelo {@link AuthCookieService}, enquanto
 * a regra de criação, autenticação, renovação e recuperação de senha
 * fica concentrada no {@link AuthService}.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Serviço responsável pela regra de negócio da autenticação.
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
     * @param req dados enviados para registro
     * @param response resposta HTTP usada para adicionar os cookies
     * @return 200 OK com os dados do usuário cadastrado e os tokens, ou 400 em caso de erro de negócio
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
                    user,
                    access,
                    refresh
            );

            return ResponseEntity.ok(authResponse);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Autentica um usuário já cadastrado.
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
                    user,
                    access,
                    refresh
            );

            return ResponseEntity.ok(authResponse);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }

    /**
     * Solicita recuperação de senha.
     *
     * <p>
     * Essa rota recebe o e-mail do usuário e, se ele existir no sistema,
     * gera um token de recuperação de senha.
     * </p>
     *
     * <p>
     * Por segurança, a resposta é sempre genérica. Assim, a API não revela
     * se determinado e-mail está cadastrado ou não.
     * </p>
     *
     * @param req requisição contendo o e-mail do usuário
     * @return 200 OK com mensagem genérica
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);

        return ResponseEntity.ok(
                "Se o e-mail existir, enviaremos instruções para recuperação de senha"
        );
    }

    /**
     * Redefine a senha do usuário usando um token de recuperação válido.
     *
     * <p>
     * Essa rota recebe o token gerado no fluxo de esqueci senha e a nova senha.
     * Se o token existir, não estiver expirado e ainda não tiver sido usado,
     * a senha do usuário será atualizada.
     * </p>
     *
     * @param req requisição contendo token e nova senha
     * @return 200 OK se a senha foi alterada, ou 400 em caso de token inválido
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        try {
            authService.resetPassword(req);
            return ResponseEntity.ok("Senha alterada com sucesso");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Renova o access token usando um refresh token válido.
     *
     * @param request requisição HTTP atual, usada para ler header e cookies
     * @param response resposta HTTP usada para atualizar o cookie do access token
     * @return 200 OK se renovou com sucesso, ou 401 se o refresh for inválido
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
                                     HttpServletResponse response) {

        String refreshToken = extractBearerToken(request);

        if (refreshToken == null) {
            refreshToken = extractCookie(request, authCookieService.getRefreshCookieName());
        }

        if (refreshToken == null) {
            return ResponseEntity.status(401).body("Refresh token ausente");
        }

        try {
            String newAccess = authService.refreshAccessToken(refreshToken);

            int accessMaxAge = 15 * 60;
            authCookieService.setAccessCookie(response, newAccess, accessMaxAge);

            RefreshResponse refreshResponse = new RefreshResponse(
                    "Access renovado com sucesso",
                    newAccess
            );

            return ResponseEntity.ok(refreshResponse);
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
     * Extrai o token do header Authorization no formato Bearer.
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