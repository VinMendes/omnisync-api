package com.puccampinas.omnisync.core.auth.security;

import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Carrega a identidade atual do banco tanto para o login quanto para tokens já validados. */
@Service
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final SystemClientRepository systemClientRepository;
    private final AccountStatusUserDetailsChecker accountStatusChecker = new AccountStatusUserDetailsChecker();

    public CustomUserDetailsService(UserRepository userRepository, SystemClientRepository systemClientRepository) {
        this.userRepository = userRepository;
        this.systemClientRepository = systemClientRepository;
    }

    @Override
    public OmniUserPrincipal loadUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Usuário não encontrado.");
        }

        String email = username.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
        SystemClient client = systemClientRepository.findById(user.getSystemClientId())
                .orElseThrow(() -> new UsernameNotFoundException("Empresa do usuário não encontrada."));

        // UserResource é o contrato tipado persistido no JSONB; textos desconhecidos não viram authorities.
        return new OmniUserPrincipal(
                user.getId(), user.getSystemClientId(), user.getName(), user.getEmail(),
                user.getPasswordHash(), Boolean.TRUE.equals(user.getActive()), client.getActive(), authoritiesOf(user)
        );
    }

    private List<GrantedAuthority> authoritiesOf(User user) {
        Set<String> authorities = new TreeSet<>();
        UserResource resource = user.getResource();
        authorities.add("ROLE_" + resource.role().name());
        resource.permissions().forEach(permission -> authorities.add(permission.name()));
        return authorities.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    /**
     * Usado somente depois da validação criptográfica do access/refresh token.
     * Não substitui a autenticação de senha feita pelo DaoAuthenticationProvider.
     */
    public OmniUserPrincipal loadActiveUserByUsername(String username) {
        OmniUserPrincipal principal = loadUserByUsername(username);
        try {
            accountStatusChecker.check(principal);
            return principal;
        } finally {
            principal.eraseCredentials();
        }
    }
}
