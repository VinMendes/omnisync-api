package com.puccampinas.omnisync.core.users.service;

import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.dto.UserStatusUpdateRequest;
import com.puccampinas.omnisync.core.users.dto.UserUpdateRequest;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse findById(Long id) {
        User user = findUserEntityById(id);
        return toResponse(user);
    }

    public List<UserResponse> findAll() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = findUserEntityById(id);

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

        if (request.resource() != null) {
            user.setResource(request.resource());
        }

        User savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    public UserResponse updateStatus(Long id, UserStatusUpdateRequest request) {
        User user = findUserEntityById(id);

        if (request.active() == null) {
            throw new IllegalArgumentException("O campo active é obrigatório.");
        }

        user.setActive(request.active());

        User savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    private User findUserEntityById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado."));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getSystemClientId(),
                user.getName(),
                user.getEmail(),
                user.getResource(),
                user.getActive(),
                user.getCreatedAt()
        );
    }
}