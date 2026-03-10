package com.puccampinas.omnisync.core.auth.dto;

/**
 * DTO (Data Transfer Object) responsável por representar
 * os dados enviados pelo cliente no momento do login.
 *
 * <p>
 * Essa classe modela o corpo (body) da requisição HTTP
 * enviada para o endpoint "/auth/login".
 * </p>
 *
 * <p>
 * Exemplo de JSON esperado:
 * </p>
 *
 * <pre>
 * {
 *   "username": "vini",
 *   "password": "123"
 * }
 * </pre>
 *
 * <p>
 * O Spring converte automaticamente esse JSON em um objeto
 * LoginRequest quando usamos a anotação @RequestBody
 * no controller.
 * </p>
 *
 * <p>
 * Essa classe foi implementada como um <strong>record</strong>,
 * que é um tipo especial introduzido no Java 16+ para representar
 * objetos imutáveis de forma mais simples e enxuta.
 * </p>
 *
 * <p>
 * Um record:
 * <ul>
 *   <li>É imutável (não possui setters)</li>
 *   <li>Gera automaticamente construtor</li>
 *   <li>Gera equals(), hashCode() e toString()</li>
 *   <li>Gera métodos de acesso (username() e password())</li>
 * </ul>
 * </p>
 *
 * <p>
 * Como ele é apenas um objeto de transporte de dados,
 * ele não contém regra de negócio.
 * </p>
 *
 * @param username Nome do usuário enviado pelo cliente
 * @param password Senha enviada pelo cliente
 */
public record LoginRequest(String username, String password) {}