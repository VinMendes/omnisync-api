package com.puccampinas.omnisync.core.auth.service;

import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.auth.dto.RegisterCompanyRequest;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.systemClient.dto.SystemClientCreateRequest;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final AuthService authService;
    private final SystemClientService clients;
    private final UserService users;
    private final TenantAccess access;

    public RegistrationService(AuthService authService, SystemClientService clients,
                               UserService users, TenantAccess access) {
        this.authService = authService;
        this.clients = clients;
        this.users = users;
        this.access = access;
    }

    @Transactional
    public User registerCompany(RegisterCompanyRequest request) {
        SystemClientCreateRequest companyRequest = new SystemClientCreateRequest();
        companyRequest.setName(request.companyName().trim());
        companyRequest.setDocument(request.document());
        SystemClient company = clients.create(companyRequest);

        // A empresa é sempre recém-criada; o papel do primeiro usuário é definido pelo servidor.
        return authService.register(new RegisterRequest(
                company.getId(), request.name(), request.email(), request.password(),
                UserResource.create(request.resource()).toStoredJson(), Role.ADMIN.name(),
                Permission.names(Role.ADMIN.defaultPermissions())
        ));
    }

    @Transactional
    public UserResponse createMember(RegisterRequest request) {
        access.requirePermission(Permission.USER_MANAGE);
        access.requireTenant(request.systemClientId());
        User created = authService.register(request);
        return users.findById(access.principal().getUsername(), created.getId());
    }
}
