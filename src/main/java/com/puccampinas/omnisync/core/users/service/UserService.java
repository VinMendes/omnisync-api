package com.puccampinas.omnisync.core.users.service;

import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.dto.UserStatusUpdateRequest;
import com.puccampinas.omnisync.core.users.dto.UserUpdateRequest;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserAccess;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRoleRepository tenantRoleRepository;

    public UserService(UserRepository userRepository, TenantRoleRepository tenantRoleRepository) {
        this.userRepository = userRepository;
        this.tenantRoleRepository = tenantRoleRepository;
    }

    public UserResponse findMe(String email) {
        User user = findActiveEntityByEmail(email);

        return toResponse(user);
    }

    public User findActiveEntityByEmail(String email) {
        User user = userRepository.findByEmail(email == null ? null : email.trim().toLowerCase())
                .orElseThrow(() -> new EntityNotFoundException("Usuário autenticado não encontrado."));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new EntityNotFoundException("Usuário autenticado não encontrado.");
        }

        return user;
    }

    public UserResponse findById(String authenticatedEmail, Long id) {
        User authenticatedUser = findActiveEntityByEmail(authenticatedEmail);
        User user = findUserEntityByIdAndSystemClientId(id, authenticatedUser.getSystemClientId());
        return toResponse(user);
    }

    public List<UserResponse> findAll(String authenticatedEmail) {
        User authenticatedUser = findActiveEntityByEmail(authenticatedEmail);

        return userRepository.findAllBySystemClientId(authenticatedUser.getSystemClientId())
                .stream()
                .map(this::toResponse)
                .toList();
    }


    @Transactional
    public UserResponse update(String authenticatedEmail, Long id, UserUpdateRequest request) {
        User authenticatedUser = findActiveEntityByEmail(authenticatedEmail);
        User user = findUserEntityByIdAndSystemClientId(id, authenticatedUser.getSystemClientId());
        TenantRole currentRole = requireRole(user);
        UserAccess access = UserAccess.forUpdate(
                request.resource(), request.role(), request.permissions(), currentRole.getName());
        Set<Permission> selectedPermissions = access.effectivePermissions(currentRole);
        UserResource updatedResource = user.getResource().update(request.resource());

        if (!isAdmin(authenticatedUser)
                && Objects.equals(authenticatedUser.getId(), user.getId())
                && (currentRole.getName() != access.role()
                    || !currentRole.getPermissions().equals(selectedPermissions))) {
            throw new AccessDeniedException("Não é permitido alterar o próprio papel ou permissões.");
        }


        if (request.email() != null) {
            String normalizedEmail = request.email().trim().toLowerCase();

            if (normalizedEmail.isEmpty()) {
                throw new IllegalArgumentException("O e-mail não pode ser vazio.");
            }

            if (userRepository.existsByEmailAndIdNot(normalizedEmail, id)) {
                throw new IllegalArgumentException("Já existe um usuário com este e-mail.");
            }

            user.setEmail(normalizedEmail);
        }

        if (request.name() != null) {
            String trimmedName = request.name().trim();

            if (trimmedName.isEmpty()) {
                throw new IllegalArgumentException("O nome não pode ser vazio.");
            }

            user.setName(trimmedName);
        }

        if (!Objects.equals(currentRole.getSystemClientId(), user.getSystemClientId())) {
            throw new IllegalStateException("A role do usuário pertence a outra empresa.");
        }

        currentRole.setName(access.role());
        currentRole.setResource(RoleResource.of(selectedPermissions));
        tenantRoleRepository.save(currentRole);
        user.setResource(updatedResource);

        User savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    public UserResponse updateStatus(String authenticatedEmail, Long id, UserStatusUpdateRequest request) {
        User authenticatedUser = findActiveEntityByEmail(authenticatedEmail);
        User user = findUserEntityByIdAndSystemClientId(id, authenticatedUser.getSystemClientId());

        if (request.active() == null) {
            throw new IllegalArgumentException("O campo active é obrigatório.");
        }

        if (Objects.equals(authenticatedUser.getId(), user.getId()) && Boolean.FALSE.equals(request.active())) {
            throw new AccessDeniedException("Não é permitido desativar o próprio usuário.");
        }

        user.setActive(request.active());

        User savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    private User findUserEntityByIdAndSystemClientId(Long id, Long systemClientId) {
        return userRepository.findByIdAndSystemClientId(id, systemClientId)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado."));
    }

    private boolean isAdmin(User user) {
        return requireRole(user).getName() == Role.ADMIN;
    }

    private TenantRole requireRole(User user) {
        if (user.getTenantRole() == null) {
            throw new IllegalStateException("Usuário sem role configurada.");
        }
        return user.getTenantRole();
    }

    private UserResponse toResponse(User user) {
        TenantRole tenantRole = requireRole(user);
        return new UserResponse(
                user.getId(),
                user.getSystemClientId(),
                user.getName(),
                user.getEmail(),
                user.getResource().toLegacyJson(tenantRole.getName(), tenantRole.getPermissions()),
                user.getActive(),
                user.getCreatedAt(),
                tenantRole.getName().name(),
                tenantRole.permissionNames()
        );
    }
}
