package com.puccampinas.omnisync.core.auth.service;

import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.ForgotPasswordRequest;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.dto.ResetPasswordRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetEmailService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetToken;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Serviço responsável pela regra de negócio da autenticação.
 *
 * <p>
 * Esta classe centraliza a lógica de:
 * </p>
 * <ul>
 *     <li>registro de novos usuários</li>
 *     <li>validação de login</li>
 *     <li>geração de access token e refresh token</li>
 *     <li>renovação do access token a partir do refresh token</li>
 *     <li>recuperação e redefinição de senha</li>
 *     <li>montagem da resposta de autenticação</li>
 * </ul>
 */
@Service
public class AuthService {

    /**
     * Repositório responsável pelo acesso à tabela de usuários.
     */
    private final UserRepository userRepository;

    /**
     * Componente de segurança usado para gerar e validar hash de senha.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Serviço responsável por gerar e validar tokens JWT.
     */
    private final JwtService jwtService;

    /**
     * Repositório responsável pelos tokens de recuperação de senha.
     */
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    /**
     * Serviço responsável pelo envio do link de recuperação de senha.
     *
     * <p>
     * Por enquanto, ele apenas imprime o link no console.
     * Futuramente pode ser substituído por envio real de e-mail.
     * </p>
     */
    private final PasswordResetEmailService passwordResetEmailService;

    private final String resetPasswordUrl;

    /**
     * Construtor com injeção de dependências.
     *
     * @param userRepository repositório de usuários
     * @param passwordEncoder encoder de senha
     * @param jwtService serviço de JWT
     * @param passwordResetTokenRepository repositório de tokens de recuperação
     * @param passwordResetEmailService serviço de envio de recuperação de senha
     */
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordResetEmailService passwordResetEmailService,
                       @Value("${app.frontend.reset-password-url}") String resetPasswordUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordResetEmailService = passwordResetEmailService;
        this.resetPasswordUrl = resetPasswordUrl;
    }

    /**
     * Registra um novo usuário no sistema.
     *
     * @param req dados enviados para cadastro
     * @return usuário salvo no banco
     * @throws RuntimeException se já existir usuário com o email informado
     */
    public User register(RegisterRequest req) {
        String normalizedEmail = normalizeEmail(req.email());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new RuntimeException("Já existe usuário com esse email");
        }

        User user = new User();
        user.setSystemClientId(req.systemClientId());
        user.setName(req.name().trim());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setActive(true);

        if (req.resource() != null) {
            user.setResource(req.resource());
        }

        return userRepository.save(user);
    }

    /**
     * Autentica um usuário a partir de email e senha.
     *
     * @param req credenciais informadas no login
     * @return usuário autenticado
     * @throws RuntimeException se o usuário não existir, estiver inativo ou a senha estiver incorreta
     */
    public User authenticate(LoginRequest req) {
        String normalizedEmail = normalizeEmail(req.email());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new RuntimeException("Credenciais inválidas"));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new RuntimeException("Usuário inativo");
        }

        boolean passwordMatches = passwordEncoder.matches(req.password(), user.getPasswordHash());
        if (!passwordMatches) {
            throw new RuntimeException("Credenciais inválidas");
        }

        return user;
    }

    /**
     * Inicia o fluxo de recuperação de senha.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Normaliza o e-mail recebido</li>
     *     <li>Busca o usuário pelo e-mail</li>
     *     <li>Se o usuário não existir, encerra silenciosamente</li>
     *     <li>Remove tokens antigos daquele usuário</li>
     *     <li>Gera um novo token seguro</li>
     *     <li>Salva o token com expiração de 30 minutos</li>
     *     <li>Gera o link de recuperação</li>
     *     <li>Envia ou imprime o link pelo {@link PasswordResetEmailService}</li>
     * </ol>
     *
     * <p>
     * A resposta do controller deve ser sempre genérica para não revelar
     * se o e-mail existe ou não na base.
     * </p>
     *
     * @param req objeto contendo o e-mail do usuário
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        String normalizedEmail = normalizeEmail(req.email());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElse(null);

        /*
         * Não revelamos se o e-mail existe ou não.
         * Isso evita enumeração de usuários.
         */
        if (user == null) {
            return;
        }

        if (!Boolean.TRUE.equals(user.getActive())) {
            return;
        }

        /*
         * Remove tokens antigos desse usuário.
         * Assim, apenas o último link gerado fica válido.
         */
        passwordResetTokenRepository.deleteByUser(user);

        String token = generateSecureToken();

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setToken(token);
        resetToken.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));

        passwordResetTokenRepository.save(resetToken);

        /*
         * Depois, quando tiver frontend em produção, trocar localhost pela URL real.
         */
        String resetLink = "http://localhost:5173/reset-password?token=" + token;

        passwordResetEmailService.sendPasswordResetEmail(user.getEmail(), resetLink);
    }

    /**
     * Redefine a senha do usuário usando um token de recuperação válido.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Busca o token recebido no banco</li>
     *     <li>Valida se o token existe</li>
     *     <li>Valida se o token ainda não foi usado</li>
     *     <li>Valida se o token ainda não expirou</li>
     *     <li>Busca o usuário associado ao token</li>
     *     <li>Criptografa a nova senha</li>
     *     <li>Atualiza a senha do usuário</li>
     *     <li>Marca o token como utilizado</li>
     * </ol>
     *
     * @param req objeto contendo token e nova senha
     * @throws RuntimeException se o token for inválido, expirado ou já utilizado
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(req.token())
                .orElseThrow(() -> new RuntimeException("Token inválido"));

        if (resetToken.isUsed()) {
            throw new RuntimeException("Token já utilizado");
        }

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new RuntimeException("Token expirado");
        }

        User user = resetToken.getUser();

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new RuntimeException("Usuário inativo");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);
    }

    /**
     * Gera um access token para o usuário informado.
     *
     * @param user usuário autenticado
     * @return access token JWT
     */
    public String generateAccessToken(User user) {
        return jwtService.generateAccessToken(user.getEmail());
    }

    /**
     * Gera um refresh token para o usuário informado.
     *
     * @param user usuário autenticado
     * @return refresh token JWT
     */
    public String generateRefreshToken(User user) {
        return jwtService.generateRefreshToken(user.getEmail());
    }

    /**
     * Gera um novo access token a partir de um refresh token válido.
     *
     * @param refreshToken token de refresh recebido do cliente
     * @return novo access token
     * @throws RuntimeException se o usuário não existir ou estiver inativo
     */
    public String refreshAccessToken(String refreshToken) {
        Claims claims = jwtService.validateAndGetClaims(refreshToken, JwtService.TYPE_REFRESH);
        String email = claims.getSubject();

        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new RuntimeException("Usuário do refresh não encontrado"));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new RuntimeException("Usuário inativo");
        }

        return jwtService.generateAccessToken(user.getEmail());
    }

    /**
     * Monta um DTO de resposta de autenticação com os dados básicos do usuário
     * e com os tokens recém-gerados.
     *
     * @param message mensagem de retorno da operação
     * @param user usuário autenticado ou registrado
     * @param accessToken access token gerado
     * @param refreshToken refresh token gerado
     * @return resposta padronizada de autenticação
     */
    public AuthResponse buildAuthResponse(String message,
                                          User user,
                                          String accessToken,
                                          String refreshToken) {
        return new AuthResponse(
                message,
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getActive(),
                accessToken,
                refreshToken
        );
    }

    /**
     * Gera um token aleatório e seguro para recuperação de senha.
     *
     * <p>
     * Este token não é JWT. Ele é apenas uma string aleatória, salva no banco,
     * usada uma única vez e com tempo de expiração.
     * </p>
     *
     * @return token seguro em Base64 URL-safe
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    /**
     * Normaliza o email para evitar diferenças de caixa ou espaços.
     *
     * @param email email original
     * @return email normalizado
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}