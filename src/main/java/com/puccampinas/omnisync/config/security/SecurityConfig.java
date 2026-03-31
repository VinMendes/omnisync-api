package com.puccampinas.omnisync.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Classe de configuração principal do Spring Security.
 *
 * <p>
 * Essa classe define:
 * </p>
 *
 * <ul>
 *     <li>Quais rotas são públicas ou protegidas</li>
 *     <li>Se a aplicação usa sessão ou é stateless</li>
 *     <li>Se CSRF está ativo ou desativado</li>
 *     <li>Em que posição o filtro JWT será inserido</li>
 * </ul>
 *
 * <p>
 * Esse é o ponto central onde toda a estratégia de segurança
 * da aplicação é definida.
 * </p>
 */
@Configuration
public class SecurityConfig {

    /**
     * Define a cadeia de filtros de segurança da aplicação.
     *
     * <p>
     * O Spring Security funciona como uma cadeia (chain) de filtros.
     * Cada requisição HTTP passa por vários filtros antes de chegar
     * no controller.
     * </p>
     *
     * <p>
     * Aqui estamos configurando:
     * </p>
     *
     * <ul>
     *     <li>CSRF desativado (apenas para ambiente de teste/demo)</li>
     *     <li>CORS com configuração padrão</li>
     *     <li>Aplicação stateless (sem sessão HTTP)</li>
     *     <li>Regras de autorização por rota</li>
     *     <li>Posição do filtro JWT dentro da cadeia</li>
     * </ul>
     *
     * @param http objeto de configuração do Spring Security
     * @param jwtAuthenticationFilter filtro personalizado que valida JWT via cookie
     * @return SecurityFilterChain configurada
     * @throws Exception caso ocorra erro na configuração
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http

                /*
                 * CSRF desativado.
                 *
                 * CSRF é um mecanismo de proteção contra requisições forjadas.
                 * Em aplicações stateless com JWT, muitas vezes ele é desativado.
                 *
                 * Aqui está desativado apenas para facilitar testes via Postman.
                 * Em produção, é necessário avaliar a estratégia corretamente.
                 */
                .csrf(csrf -> csrf.disable())

                /*
                 * Habilita CORS com configuração padrão.
                 * Necessário caso o frontend esteja em outro domínio.
                 */
                .cors(Customizer.withDefaults())

                /*
                 * Define que a aplicação NÃO utilizará sessão HTTP.
                 *
                 * SessionCreationPolicy.STATELESS significa:
                 * - O servidor não cria sessão
                 * - O servidor não guarda estado do usuário
                 * - Toda requisição deve se autenticar via token
                 */
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /*
                 * Define as regras de autorização das rotas.
                 *
                 * "/auth/**" → público (login/logout)
                 * qualquer outra rota → exige autenticação
                 */
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/api/auth/**",
                                "/api/integrations/mercadolivre/callback",
                                "/api/client"
                        ).permitAll()
                        .anyRequest().authenticated()
                )

                /*
                 * Adiciona o filtro JWT antes do filtro padrão
                 * UsernamePasswordAuthenticationFilter.
                 *
                 * Isso garante que o JWT seja processado antes
                 * do mecanismo tradicional de login do Spring.
                 */
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }
}
