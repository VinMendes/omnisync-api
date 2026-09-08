package com.puccampinas.omnisync.core.auth.security;

import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SystemClientRepository systemClientRepository;

    private CustomUserDetailsService service;
    private User user;
    private SystemClient client;

    @BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(userRepository, systemClientRepository);
        user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setSystemClientId(10L);
        user.setName("Usuário de teste");
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");
        user.setActive(true);
        user.setTenantRole(role(Role.VIEWER, Role.VIEWER.defaultPermissions()));
        client = new SystemClient();
        client.setId(10L);
        client.setActive(true);
    }

    @Test
    void shouldNormalizeEmailAndLoadIdentityAndAccountStatus() {
        stubUserAndClient();

        OmniUserPrincipal principal = service.loadUserByUsername(" USER@Example.COM ");

        assertThat(principal.getUserId()).isEqualTo(1L);
        assertThat(principal.getSystemClientId()).isEqualTo(10L);
        assertThat(principal.getUsername()).isEqualTo("user@example.com");
        assertThat(principal.getPassword()).isEqualTo("hash");
        assertThat(principal.isEnabled()).isTrue();
        verify(userRepository).findByEmail("user@example.com");
        verify(systemClientRepository).findById(10L);
    }

    @Test
    void shouldNeverGrantUnknownAuthoritiesFromMalformedLegacyResource() {
        stubUserAndClient();
        user.setTenantRole(role(Role.VIEWER, RoleResource.fromStoredJson(Map.of(
                "permissions", java.util.List.of("PRODUCT_READ", "ALL_POWERS"))).permissions()));

        assertThat(service.loadUserByUsername("user@example.com").getAuthorities()).extracting("authority")
                .containsExactly("PERM_PRODUCT_READ", "ROLE_VIEWER");
    }

    @Test
    void shouldRejectMissingUserWithoutLookingUpCompany() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
        verifyNoInteractions(systemClientRepository);
    }

    @Test
    void shouldRejectMissingCompany() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(systemClientRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("user@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void shouldRejectBlankUsernameBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.loadUserByUsername(null)).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername("  ")).isInstanceOf(UsernameNotFoundException.class);
        verifyNoInteractions(userRepository, systemClientRepository);
    }

    @Test
    void shouldErasePasswordFromPrincipalLoadedForTokenAuthentication() {
        stubUserAndClient();

        assertThat(service.loadActiveUserByUsername("user@example.com").getPassword()).isNull();
    }

    @Test
    void shouldRecheckDisabledUserOnNextTokenRequest() {
        stubUserAndClient();
        assertThat(service.loadActiveUserByUsername("user@example.com").isEnabled()).isTrue();
        user.setActive(false);

        assertThatThrownBy(() -> service.loadActiveUserByUsername("user@example.com"))
                .isInstanceOf(DisabledException.class);
        verify(userRepository, times(2)).findByEmail("user@example.com");
    }

    @Test
    void shouldRejectTokenAuthenticationForInactiveCompany() {
        stubUserAndClient();
        client.setActive(false);

        assertThatThrownBy(() -> service.loadActiveUserByUsername("user@example.com"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void shouldLoadPersistedRoleAndPermissionAuthorities() {
        stubUserAndClient();
        user.setTenantRole(role(Role.ADMIN, Set.of(Permission.PRODUCT_READ, Permission.USER_MANAGE)));

        assertThat(service.loadUserByUsername("user@example.com").getAuthorities()).extracting("authority")
                .containsExactly("PERM_PRODUCT_READ", "PERM_USER_MANAGE", "ROLE_ADMIN");
    }

    @Test
    void shouldReloadPermissionsWithoutMutatingAnExistingPrincipal() {
        stubUserAndClient();
        user.setTenantRole(role(Role.VIEWER, Set.of(Permission.PRODUCT_READ)));
        OmniUserPrincipal first = service.loadActiveUserByUsername("user@example.com");

        user.setTenantRole(role(Role.VIEWER, Set.of()));
        OmniUserPrincipal current = service.loadActiveUserByUsername("user@example.com");

        assertThat(first.getAuthorities()).extracting("authority")
                .containsExactly("PERM_PRODUCT_READ", "ROLE_VIEWER");
        assertThat(current.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
    }

    private void stubUserAndClient() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(systemClientRepository.findById(10L)).thenReturn(Optional.of(client));
    }

    private TenantRole role(Role name, Set<Permission> permissions) {
        TenantRole role = new TenantRole();
        role.setSystemClientId(10L);
        role.setName(name);
        role.setResource(RoleResource.of(permissions));
        return role;
    }
}
