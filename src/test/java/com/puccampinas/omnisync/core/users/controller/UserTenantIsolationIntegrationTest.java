package com.puccampinas.omnisync.core.users.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(UserService.class)
class UserTenantIsolationIntegrationTest {

    private static final String ADMIN_A_EMAIL = "admin-a@empresa.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TenantRoleRepository tenantRoleRepository;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void listReturnsOnlyUsersFromAuthenticatedTenant() throws Exception {
        User adminA = user(1L, 10L, ADMIN_A_EMAIL, "Admin A");
        User memberA = user(2L, 10L, "member-a@empresa.com", "Member A");
        User memberB = user(3L, 20L, "member-b@empresa.com", "Member B");

        when(userRepository.findByEmail(ADMIN_A_EMAIL)).thenReturn(Optional.of(adminA));
        when(userRepository.findAll()).thenReturn(List.of(adminA, memberA, memberB));
        when(userRepository.findAllBySystemClientId(10L)).thenReturn(List.of(adminA, memberA));

        mockMvc.perform(get("/api/users")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].systemClientId").value(10L))
                .andExpect(jsonPath("$[1].systemClientId").value(10L))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("member-b@empresa.com"))));

        verify(userRepository).findAllBySystemClientId(10L);
        verify(userRepository, never()).findAll();
    }

    @Test
    void statusUpdateFromAnotherTenantReturnsNotFound() throws Exception {
        User adminA = user(1L, 10L, ADMIN_A_EMAIL, "Admin A");
        when(userRepository.findByEmail(ADMIN_A_EMAIL)).thenReturn(Optional.of(adminA));
        when(userRepository.findByIdAndSystemClientId(20L, 10L)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/users/20/status")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void changingOwnRoleReturnsForbidden() throws Exception {
        User managerA = user(1L, 10L, ADMIN_A_EMAIL, "Manager A", "manager");
        when(userRepository.findByEmail(ADMIN_A_EMAIL)).thenReturn(Optional.of(managerA));
        when(userRepository.findByIdAndSystemClientId(1L, 10L)).thenReturn(Optional.of(managerA));

        mockMvc.perform(put("/api/users/1")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\",\"permissions\":[\"USER_MANAGE\"]}"))
                .andExpect(status().isForbidden());

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void adminCanChangeOwnRoleAndPermissions() throws Exception {
        User adminA = user(1L, 10L, ADMIN_A_EMAIL, "Admin A");
        when(userRepository.findByEmail(ADMIN_A_EMAIL)).thenReturn(Optional.of(adminA));
        when(userRepository.findByIdAndSystemClientId(1L, 10L)).thenReturn(Optional.of(adminA));
        when(userRepository.save(adminA)).thenReturn(adminA);

        mockMvc.perform(put("/api/users/1")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.role").value("manager"));

        verify(tenantRoleRepository).save(adminA.getTenantRole());
        verify(userRepository).save(adminA);
    }

    @Test
    void selfDeactivationReturnsForbidden() throws Exception {
        User adminA = user(1L, 10L, ADMIN_A_EMAIL, "Admin A");
        when(userRepository.findByEmail(ADMIN_A_EMAIL)).thenReturn(Optional.of(adminA));
        when(userRepository.findByIdAndSystemClientId(1L, 10L)).thenReturn(Optional.of(adminA));

        mockMvc.perform(patch("/api/users/1/status")
                        .principal(authentication())
                        .contentType("application/json")
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    private User user(Long id, Long systemClientId, String email, String name) {
        return user(id, systemClientId, email, name, "admin");
    }

    private User user(Long id, Long systemClientId, String email, String name, String role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setSystemClientId(systemClientId);
        user.setEmail(email);
        user.setName(name);
        user.setActive(true);
        user.setResource(UserResource.defaults());
        user.setTenantRole(role(systemClientId, Role.parse(role)));
        return user;
    }

    private TenantRole role(Long systemClientId, Role name) {
        TenantRole role = new TenantRole();
        role.setSystemClientId(systemClientId);
        role.setName(name);
        role.setResource(RoleResource.of(name.defaultPermissions()));
        return role;
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(ADMIN_A_EMAIL, null, List.of());
    }
}
