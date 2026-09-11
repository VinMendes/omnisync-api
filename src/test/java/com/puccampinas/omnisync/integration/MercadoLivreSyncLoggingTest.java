package com.puccampinas.omnisync.integration;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.product.service.ProductLogService;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.repository.MercadoLivreSyncLockRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class MercadoLivreSyncLoggingTest {

    @Test
    void lifecycleLogsContainTenantAndOutcomeButNoCredentialsOrEmail(CapturedOutput output) {
        Fixture fixture = new Fixture();
        when(fixture.listingService.listAllClientListings(7L))
                .thenReturn(Map.of("items", List.of()));

        fixture.service.syncMercadoLivreProducts("private@example.invalid", 7L);

        assertThat(output).contains("event=ml_sync_started systemClientId=7")
                .contains("event=ml_sync_succeeded systemClientId=7")
                .doesNotContain("private@example.invalid")
                .doesNotContain("access-secret")
                .doesNotContain("refresh-secret");
    }

    @Test
    void rateLimitIsLoggedAsAStableOutcomeWithoutProviderBody(CapturedOutput output) {
        Fixture fixture = new Fixture();
        when(fixture.listingService.listAllClientListings(7L)).thenThrow(
                new ExternalApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "provider-secret-body",
                        "too_many_requests",
                        30
                )
        );

        assertThatThrownBy(() -> fixture.service.syncMercadoLivreProducts("private@example.invalid", 7L))
                .isInstanceOf(ExternalApiException.class);
        assertThat(output).contains("event=ml_sync_rate_limited systemClientId=7")
                .doesNotContain("provider-secret-body")
                .doesNotContain("private@example.invalid");
    }

    private static final class Fixture {
        private final ProductRepository productRepository = mock(ProductRepository.class);
        private final ProductLogService productLogService = mock(ProductLogService.class);
        private final MercadoLivreListingService listingService = mock(MercadoLivreListingService.class);
        private final MarketplaceIntegrationRepository integrationRepository = mock(MarketplaceIntegrationRepository.class);
        private final UserService userService = mock(UserService.class);
        private final SystemClientService systemClientService = mock(SystemClientService.class);
        private final MercadoLivreSyncLockRepository lockRepository = mock(MercadoLivreSyncLockRepository.class);
        private final ProductService service = new ProductService(
                productRepository,
                productLogService,
                listingService,
                integrationRepository,
                userService,
                systemClientService,
                lockRepository,
                mock(com.puccampinas.omnisync.core.audit.AuditService.class)
        );

        private Fixture() {
            User user = new User();
            user.setSystemClientId(7L);
            MarketplaceIntegration integration = new MarketplaceIntegration();
            integration.setSystemClientId(7L);
            integration.setResource(Map.of("user_id", "123"));
            when(userService.findActiveEntityByEmail("private@example.invalid")).thenReturn(user);
            when(lockRepository.tryAcquire(7L)).thenReturn(true);
            when(integrationRepository.findMercadoLivreActiveIntegrationForSync(7L, "MERCADO_LIVRE"))
                    .thenReturn(Optional.of(integration));
            when(productRepository.findAllMercadoLivreProductsBySystemClientId(7L)).thenReturn(List.of());
        }
    }
}
