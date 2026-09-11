package com.puccampinas.omnisync.integration.client;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

class MercadoLivreClientTest {

    @Test
    void preservesRetryMetadataWithoutLeakingProviderBodyOrRetrying() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo("https://api.mercadolibre.com/items/MLB1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withTooManyRequests()
                        .body("{\"error\":\"too_many_requests\",\"access_token\":\"secret\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Retry-After", "17"));
        MercadoLivreClient client = new MercadoLivreClient(restTemplate);

        assertThatThrownBy(() -> client.getItem("access-secret", "MLB1"))
                .isInstanceOfSatisfying(ExternalApiException.class, error -> {
                    assertThat(error.isRateLimited()).isTrue();
                    assertThat(error.getRetryAfterSeconds()).isEqualTo(17);
                    assertThat(error.getProviderCode()).isEqualTo("too_many_requests");
                    assertThat(error.getMessage()).doesNotContain("secret");
                });
        server.verify();
    }
}
