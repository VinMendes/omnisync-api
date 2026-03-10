package com.puccampinas.omnisync.core.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Serviço responsável por gerenciar os cookies de autenticação da aplicação.
 *
 * <p>
 * Neste projeto utilizamos um modelo baseado em <strong>2 tokens</strong>:
 * </p>
 *
 * <ul>
 *     <li><strong>Access Token</strong> → usado para acessar rotas protegidas</li>
 *     <li><strong>Refresh Token</strong> → usado apenas para gerar um novo access token</li>
 * </ul>
 *
 * <p>
 * Ambos são armazenados no cliente através de <strong>cookies HttpOnly</strong>.
 * Isso significa que o token não pode ser acessado via JavaScript,
 * reduzindo significativamente o risco de ataques XSS.
 * </p>
 *
 * <h2>Responsabilidades desta classe</h2>
 * <ul>
 *     <li>Criar cookies de autenticação (access e refresh)</li>
 *     <li>Remover cookies no logout</li>
 *     <li>Centralizar configuração de segurança do cookie</li>
 * </ul>
 *
 * <p>
 * Centralizar essa lógica em um serviço evita duplicação de código
 * e facilita manutenção caso as regras de cookie mudem.
 * </p>
 *
 * <h2>Configuração externa</h2>
 *
 * Os valores são carregados do {@code application.properties}:
 *
 * <ul>
 *     <li>{@code app.cookie.accessName} → nome do cookie do access token</li>
 *     <li>{@code app.cookie.refreshName} → nome do cookie do refresh token</li>
 *     <li>{@code app.cookie.secure} → define se o cookie só será enviado via HTTPS</li>
 * </ul>
 *
 * <p>
 * Em produção, o ideal é:
 * </p>
 *
 * <ul>
 *     <li>{@code secure = true}</li>
 *     <li>usar HTTPS obrigatório</li>
 *     <li>configurar SameSite adequadamente</li>
 * </ul>
 */
@Service
public class AuthCookieService {

    /**
     * Nome do cookie que armazenará o Access Token.
     *
     * <p>
     * O Access Token é utilizado pelo filtro de autenticação
     * para validar o usuário em cada requisição protegida.
     * </p>
     */
    private final String accessCookieName;

    /**
     * Nome do cookie que armazenará o Refresh Token.
     *
     * <p>
     * O Refresh Token não é usado diretamente para autenticar
     * endpoints protegidos. Ele é utilizado apenas na rota
     * {@code /auth/refresh} para gerar um novo Access Token.
     * </p>
     */
    private final String refreshCookieName;

    /**
     * Define se o cookie será enviado apenas em conexões HTTPS.
     *
     * <p>
     * Quando {@code true}, o navegador só envia o cookie se a
     * requisição for feita via HTTPS.
     * </p>
     *
     * <p>
     * Em ambiente de desenvolvimento normalmente usamos {@code false},
     * pois o servidor local roda em HTTP.
     * </p>
     */
    private final boolean secure;

    /**
     * Construtor com injeção de propriedades externas.
     *
     * @param accessCookieName nome do cookie do access token
     * @param refreshCookieName nome do cookie do refresh token
     * @param secure define se o cookie é enviado apenas via HTTPS
     */
    public AuthCookieService(
            @Value("${app.cookie.accessName}") String accessCookieName,
            @Value("${app.cookie.refreshName}") String refreshCookieName,
            @Value("${app.cookie.secure}") boolean secure
    ) {
        this.accessCookieName = accessCookieName;
        this.refreshCookieName = refreshCookieName;
        this.secure = secure;
    }

    /**
     * Cria e adiciona o cookie do Access Token na resposta HTTP.
     *
     * <p>
     * O Access Token é utilizado pelo filtro de segurança para
     * autenticar o usuário em rotas protegidas.
     * </p>
     *
     * @param response resposta HTTP atual
     * @param token token JWT do tipo access
     * @param maxAgeSeconds tempo de vida do cookie em segundos
     */
    public void setAccessCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        response.addCookie(buildCookie(accessCookieName, token, maxAgeSeconds));
    }

    /**
     * Cria e adiciona o cookie do Refresh Token na resposta HTTP.
     *
     * <p>
     * O Refresh Token é usado apenas para renovar o Access Token
     * quando ele expira.
     * </p>
     *
     * @param response resposta HTTP atual
     * @param token token JWT do tipo refresh
     * @param maxAgeSeconds tempo de vida do cookie em segundos
     */
    public void setRefreshCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        response.addCookie(buildCookie(refreshCookieName, token, maxAgeSeconds));
    }

    /**
     * Remove o cookie do Access Token.
     *
     * <p>
     * A remoção ocorre definindo:
     * </p>
     *
     * <ul>
     *     <li>valor vazio</li>
     *     <li>MaxAge = 0</li>
     * </ul>
     *
     * <p>
     * Isso instrui o navegador a apagar o cookie imediatamente.
     * </p>
     *
     * @param response resposta HTTP atual
     */
    public void clearAccessCookie(HttpServletResponse response) {
        response.addCookie(buildCookie(accessCookieName, "", 0));
    }

    /**
     * Remove o cookie do Refresh Token.
     *
     * @param response resposta HTTP atual
     */
    public void clearRefreshCookie(HttpServletResponse response) {
        response.addCookie(buildCookie(refreshCookieName, "", 0));
    }

    /**
     * Remove todos os cookies de autenticação da aplicação.
     *
     * <p>
     * Utilizado no fluxo de logout para limpar completamente
     * o estado de autenticação do cliente.
     * </p>
     *
     * @param response resposta HTTP atual
     */
    public void clearAuthCookies(HttpServletResponse response) {
        clearAccessCookie(response);
        clearRefreshCookie(response);
    }

    /**
     * Constrói um cookie com as configurações padrão de segurança.
     *
     * <p>
     * Todos os cookies criados por este serviço compartilham
     * as seguintes configurações:
     * </p>
     *
     * <ul>
     *     <li><strong>HttpOnly</strong> → impede acesso via JavaScript</li>
     *     <li><strong>Secure</strong> → envia apenas via HTTPS (quando true)</li>
     *     <li><strong>Path=/</strong> → cookie válido para toda aplicação</li>
     *     <li><strong>MaxAge</strong> → tempo de vida em segundos</li>
     * </ul>
     *
     * @param name nome do cookie
     * @param value valor do cookie (token JWT)
     * @param maxAgeSeconds tempo de vida do cookie
     * @return objeto {@link Cookie} configurado
     */
    private Cookie buildCookie(String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        return cookie;
    }

    /**
     * Retorna o nome do cookie do Access Token.
     *
     * <p>
     * Esse método é utilizado principalmente pelo filtro
     * de autenticação para localizar o token correto
     * na requisição HTTP.
     * </p>
     *
     * @return nome do cookie do access token
     */
    public String getAccessCookieName() {
        return accessCookieName;
    }

    /**
     * Retorna o nome do cookie do Refresh Token.
     *
     * @return nome do cookie do refresh token
     */
    public String getRefreshCookieName() {
        return refreshCookieName;
    }
}