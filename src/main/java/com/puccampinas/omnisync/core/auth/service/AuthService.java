package com.puccampinas.omnisync.core.auth.service;

import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.dto.AuthResponse;
import com.puccampinas.omnisync.core.auth.dto.LoginRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
 *     <li>montagem da resposta de autenticação</li>
 * </ul>
 *
 * <p>
 * O controller apenas recebe a requisição HTTP e delega a lógica para este serviço.
 * </p>
 */
@Service
public class AuthService {

    /**
     * Repositório responsável pelo acesso à tabela de usuários.
     */
    private final UserRepository userRepository;

    /**
     * Componente de segurança usado para gerar e validar hash de senha.
     *
     * <p>
     * No cadastro, ele transforma a senha em hash.
     * No login, ele compara a senha digitada com o hash salvo no banco.
     * </p>
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Serviço responsável por gerar e validar tokens JWT.
     */
    private final JwtService jwtService;

    /**
     * Construtor com injeção de dependências.
     *
     * @param userRepository repositório de usuários
     * @param passwordEncoder encoder de senha
     * @param jwtService serviço de JWT
     */
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Registra um novo usuário no sistema.
     *
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Normaliza o email (trim + lowercase)</li>
     *     <li>Verifica se já existe usuário com o mesmo email</li>
     *     <li>Cria a entidade {@link User}</li>
     *     <li>Aplica hash na senha com {@link PasswordEncoder}</li>
     *     <li>Salva o usuário no banco</li>
     * </ol>
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
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Normaliza o email</li>
     *     <li>Busca o usuário no banco</li>
     *     <li>Valida se o usuário está ativo</li>
     *     <li>Compara a senha digitada com o hash salvo</li>
     * </ol>
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
     * Gera um access token para o usuário informado.
     *
     * <p>
     * O subject do token é o email do usuário.
     * </p>
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
     * <p>
     * Fluxo:
     * </p>
     * <ol>
     *     <li>Valida o refresh token e garante que ele é do tipo refresh</li>
     *     <li>Extrai o email do subject</li>
     *     <li>Busca o usuário no banco</li>
     *     <li>Verifica se o usuário ainda está ativo</li>
     *     <li>Gera um novo access token</li>
     * </ol>
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
     * Monta um DTO de resposta de autenticação com os dados básicos do usuário.
     *
     * @param message mensagem de retorno da operação
     * @param user usuário autenticado ou registrado
     * @return resposta padronizada de autenticação
     */
    public AuthResponse buildAuthResponse(String message, User user) {
        return new AuthResponse(
                message,
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getActive()
        );
    }

    /**
     * Normaliza o email para evitar diferenças de caixa ou espaços.
     *
     * <p>
     * Exemplo:
     * </p>
     * <pre>
     * "  VINI@EMAIL.COM  " -> "vini@email.com"
     * </pre>
     *
     * @param email email original
     * @return email normalizado
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}