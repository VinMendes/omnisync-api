package com.puccampinas.omnisync.core.users.service;

import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.dto.UserStatusUpdateRequest;
import com.puccampinas.omnisync.core.users.dto.UserUpdateRequest;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Objects;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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


    public UserResponse update(String authenticatedEmail, Long id, UserUpdateRequest request) {
        User authenticatedUser = findActiveEntityByEmail(authenticatedEmail);
        User user = findUserEntityByIdAndSystemClientId(id, authenticatedUser.getSystemClientId());
        UserResource updatedResource = user.getResource()
                .update(request.resource(), request.role(), request.permissions());

        if (!isAdmin(authenticatedUser)
                && Objects.equals(authenticatedUser.getId(), user.getId())
                && changesOwnPrivileges(user.getResource(), updatedResource)) {
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

    private boolean changesOwnPrivileges(UserResource currentResource, UserResource updatedResource) {
        return currentResource.role() != updatedResource.role()
                || !currentResource.permissions().equals(updatedResource.permissions());
    }

    private boolean isAdmin(User user) {
        return user.getResource().role() == Role.ADMIN;
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getSystemClientId(),
                user.getName(),
                user.getEmail(),
                user.getResource().toLegacyJson(),
                user.getActive(),
                user.getCreatedAt(),
                user.getResource().role().name(),
                user.getResource().permissionNames()
        );
    }
}
