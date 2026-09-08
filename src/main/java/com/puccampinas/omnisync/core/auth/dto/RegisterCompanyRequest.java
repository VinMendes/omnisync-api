package com.puccampinas.omnisync.core.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Cadastro público de uma empresa nova; não aceita empresa ou permissões a serem assumidas. */
public record RegisterCompanyRequest(
        @NotBlank @Size(max = 150) String companyName,
        @NotBlank String document,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(min = 6, max = 100) String password,
        Map<String, Object> resource
) {
}
