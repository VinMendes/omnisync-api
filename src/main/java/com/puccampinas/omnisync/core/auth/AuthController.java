package com.puccampinas.omnisync.core.auth;

import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller responsável pelas operações de autenticação da aplicação.
 *
 * <p>
 * Todas as rotas deste controller ficam sob o prefixo {@code /auth}.
 * Pela configuração do Spring Security, essas rotas normalmente são públicas
 * (permitAll), ou seja, não exigem autenticação prévia.
 * </p>
 *
 * <h2>Modelo de autenticação utilizado</h2>
 * <p>
 * Este projeto implementa um fluxo baseado em <strong>2 tokens</strong>:
 * </p>
 *
 * <ul>
 *     <li><strong>Access Token</strong>: curta duração, usado para acessar rotas protegidas.</li>
 *     <li><strong>Refresh Token</strong>: longa duração, usado apenas para gerar um novo Access Token.</li>
 * </ul>
 *
 * <p>
 * Ambos os tokens são armazenados no cliente via <strong>cookies HttpOnly</strong>:
 * isso impede acesso ao token via JavaScript (reduzindo risco de XSS).
 * </p>
 *
 * <h2>Rotas disponíveis</h2>
 * <ul>
 *     <li>{@code POST /auth/login}: autentica e gera access + refresh</li>
 *     <li>{@code POST /auth/refresh}: renova o access usando o refresh</li>
 *     <li>{@code POST /auth/logout}: remove os cookies (logout)</li>
 * </ul>
 *
 * <p>
 * Observação importante (versão acadêmica):
 * <br/>
 * Nesta etapa, o refresh token <strong>não</strong> é persistido no banco,
 * então não há revogação real do token no servidor.
 * O "logout" remove os cookies do cliente, mas se alguém tiver o refresh token
 * (roubado), ele pode continuar renovando até expirar.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Serviço responsável por gerar e validar tokens JWT.
     *
     * <p>
     * Aqui ele gera Access/Refresh com claims adequadas (ex: token_type),
     * assina com a chave secreta do servidor e valida assinatura/expiração.
     * </p>
     */
    private final JwtService jwtService;

    /**
     * Serviço responsável por criar/remover cookies de autenticação.
     *
     * <p>
     * Ele encapsula:
     * <ul>
     *     <li>Nome dos cookies (ACCESS_TOKEN e REFRESH_TOKEN)</li>
     *     <li>Flags HttpOnly/Secure</li>
     *     <li>MaxAge</li>
     * </ul>
     * </p>
     */
    private final AuthCookieService cookieService;

    /**
     * Construtor com injeção de dependências.
     *
     * @param jwtService serviço de JWT (gerar/validar tokens)
     * @param cookieService serviço para manipular cookies (set/clear)
     */
    public AuthController(JwtService jwtService, AuthCookieService cookieService) {
        this.jwtService = jwtService;
        this.cookieService = cookieService;
    }

    /**
     * Endpoint responsável por autenticar o usuário e setar cookies de access + refresh.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Recebe credenciais ({@link LoginRequest})</li>
     *     <li>Valida credenciais (aqui: hardcoded para fins didáticos)</li>
     *     <li>Gera Access Token (curto) e Refresh Token (longo)</li>
     *     <li>Envia ambos ao cliente via cookies HttpOnly</li>
     * </ol>
     *
     * <p>
     * Observação:
     * Nesta versão, a validação de usuário/senha é fixa.
     * Em um cenário real, isso seria feito via banco com UserDetailsService,
     * PasswordEncoder, etc.
     * </p>
     *
     * @param req credenciais enviadas pelo cliente
     * @param response resposta HTTP usada para adicionar os cookies
     * @return 200 OK se autenticado, 401 Unauthorized se credenciais inválidas
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletResponse response) {

        // DEMO: validação fake (depois plugamos em banco / UserDetailsService)
        if (!"vini".equals(req.username()) || !"123".equals(req.password())) {
            return ResponseEntity.status(401).body("Credenciais inválidas");
        }

        // Access token: usado para acessar endpoints protegidos (curta duração)
        String access = jwtService.generateAccessToken(req.username());

        // Refresh token: usado apenas para renovar o access (longa duração)
        String refresh = jwtService.generateRefreshToken(req.username());

        /*
         * MaxAge do cookie controla por quanto tempo o navegador mantém o cookie.
         * Idealmente deve ser compatível com o exp do JWT (senão fica estranho):
         *
         * - cookie expira antes do JWT: o cliente perde o token antes do necessário (ok, só atrapalha UX).
         * - JWT expira antes do cookie: o cliente manda um token expirado (vai falhar e precisar refresh/login).
         */
        int accessMaxAge = 15 * 60;            // 15 min (deve bater com app.jwt.accessExpMinutes)
        int refreshMaxAge = 7 * 24 * 60 * 60;  // 7 dias (deve bater com app.jwt.refreshExpDays)

        cookieService.setAccessCookie(response, access, accessMaxAge);
        cookieService.setRefreshCookie(response, refresh, refreshMaxAge);

        return ResponseEntity.ok("Logado: access + refresh cookies setados (HttpOnly)");
    }

    /**
     * Endpoint responsável por renovar o Access Token usando o Refresh Token.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Lê o refresh token do cookie {@code REFRESH_TOKEN}</li>
     *     <li>Valida o refresh: assinatura, expiração e tipo (= refresh)</li>
     *     <li>Extrai o username (subject) do refresh</li>
     *     <li>Gera um novo access token e atualiza o cookie {@code ACCESS_TOKEN}</li>
     * </ol>
     *
     * <p>
     * Importante:
     * O refresh token não autentica endpoints diretamente.
     * Ele só serve para este endpoint {@code /auth/refresh}.
     * </p>
     *
     * @param request requisição HTTP (para ler cookies)
     * @param response resposta HTTP (para setar novo access cookie)
     * @return 200 OK se renovou, 401 se refresh ausente/inválido/expirado
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {

        String refreshToken = extractCookie(request, cookieService.getRefreshCookieName());
        if (refreshToken == null) {
            return ResponseEntity.status(401).body("Refresh token ausente");
        }

        try {
            /*
             * Valida e garante que é um token do tipo "refresh".
             * Se assinatura não bater ou expirar, vai lançar exception.
             */
            Claims claims = jwtService.validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH);
            String username = claims.getSubject();

            // Gera um novo access token para o mesmo usuário
            String newAccess = jwtService.generateAccessToken(username);

            // Atualiza o cookie do access (curta duração)
            int accessMaxAge = 15 * 60;
            cookieService.setAccessCookie(response, newAccess, accessMaxAge);

            return ResponseEntity.ok("Access renovado");
        } catch (Exception e) {
            /*
             * Se o refresh estiver inválido/expirado, removemos os cookies
             * para limpar o estado do cliente e forçar login novamente.
             */
            cookieService.clearAuthCookies(response);
            return ResponseEntity.status(401).body("Refresh inválido/expirado");
        }
    }

    /**
     * Endpoint responsável por realizar logout.
     *
     * <p>
     * Como o fluxo é stateless, o "logout" aqui consiste em remover os cookies
     * do cliente (access e refresh). Sem cookie, o cliente não consegue mais
     * autenticar ou renovar o access.
     * </p>
     *
     * <p>
     * Observação:
     * Sem persistência do refresh token no banco, não existe revogação no servidor.
     * Então, tecnicamente, tokens emitidos continuam válidos até expirar.
     * </p>
     *
     * @param response resposta HTTP usada para remover os cookies
     * @return 200 OK confirmando logout
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        cookieService.clearAuthCookies(response);
        return ResponseEntity.ok("Logout ok (cookies removidos)");
    }

    /**
     * Utilitário para extrair um cookie específico da requisição.
     *
     * <p>
     * Percorre todos os cookies enviados pelo navegador e retorna o valor do cookie
     * cujo nome for igual ao parâmetro {@code cookieName}.
     * </p>
     *
     * @param request requisição HTTP atual
     * @param cookieName nome do cookie que queremos encontrar
     * @return valor do cookie se encontrado; caso contrário, null
     */
    private String extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}