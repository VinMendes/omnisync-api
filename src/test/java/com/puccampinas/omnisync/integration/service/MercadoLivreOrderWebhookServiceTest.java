package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.sale.service.SaleLogService;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MercadoLivreOrderWebhookServiceTest {

    private MarketplaceIntegrationRepository marketplaceIntegrationRepository;
    private MarketplaceTokenService marketplaceTokenService;
    private MercadoLivreClient mercadoLivreClient;
    private ProductRepository productRepository;
    private SaleRepository saleRepository;
    private SaleLogService saleLogService;
    private MercadoLivreOrderWebhookService service;

    @BeforeEach
    void setUp() {
        marketplaceIntegrationRepository = mock(MarketplaceIntegrationRepository.class);
        marketplaceTokenService = mock(MarketplaceTokenService.class);
        mercadoLivreClient = mock(MercadoLivreClient.class);
        productRepository = mock(ProductRepository.class);
        saleRepository = mock(SaleRepository.class);
        saleLogService = mock(SaleLogService.class);
        service = new MercadoLivreOrderWebhookService(
                marketplaceIntegrationRepository,
                marketplaceTokenService,
                mercadoLivreClient,
                productRepository,
                saleRepository,
                saleLogService
        );
    }

    @Test
    void handleNotificationShouldCreateSaleAndDiscountStock() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale savedSale = buildSale(55L, "CONFIRMED");
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2001",
                123456789L,
                "orders_v2",
                999L,
                1,
                "2026-04-13T10:00:00Z",
                "2026-04-13T10:00:01Z"
        );

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue("123456789", "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE))
                .thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2001"))
                .thenReturn(buildOrder("paid"));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2001"))
                .thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenReturn(savedSale);

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(8, product.getStock());
        assertEquals("CONFIRMED", result.get("sale_status"));
        verify(productRepository).save(product);
        verify(saleRepository).save(any(Sale.class));
        verify(saleLogService).logCreated(eq(savedSale), any(Map.class));
    }

    @Test
    void handleNotificationShouldNotDiscountStockTwiceForExistingConfirmedSale() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale existingSale = buildSale(55L, "CONFIRMED");
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2001",
                123456789L,
                "orders_v2",
                999L,
                2,
                "2026-04-13T10:05:00Z",
                "2026-04-13T10:05:01Z"
        );

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue("123456789", "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE))
                .thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2001"))
                .thenReturn(buildOrder("paid"));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2001"))
                .thenReturn(Optional.of(existingSale));
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any(Product.class));
        verify(saleLogService, never()).logCreated(any(Sale.class), any(Map.class));
    }

    private MarketplaceIntegration buildIntegration() {
        MarketplaceIntegration integration = new MarketplaceIntegration();
        integration.setSystemClientId(1L);
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setResource(Map.of("user_id", 123456789L));
        integration.setActive(true);
        return integration;
    }

    private Product buildProduct() {
        Product product = new Product();
        product.setId(10L);
        product.setSystemClientId(1L);
        product.setSku("SKU-1");
        product.setName("Caneca");
        product.setDescription("Caneca preta");
        product.setStock(10);
        product.setReservedStock(0);
        product.setPrice(new BigDecimal("29.90"));
        product.setActive(true);
        product.setResource(Map.of(
                "mercado_livre", Map.of(
                        "item_id", "MLB123"
                )
        ));
        return product;
    }

    private Sale buildSale(Long id, String status) {
        Sale sale = new Sale();
        sale.setId(id);
        sale.setSystemClientId(1L);
        sale.setProductId(10L);
        sale.setQuantity(2);
        sale.setTotalValue(new BigDecimal("59.80"));
        sale.setChannel("MERCADO_LIVRE");
        sale.setExternalReferenceId("2001");
        sale.setStatus(status);
        sale.setResource(Map.of());
        return sale;
    }

    private Map<String, Object> buildOrder(String status) {
        return Map.of(
                "id", 2001L,
                "status", status,
                "total_amount", new BigDecimal("59.80"),
                "tags", List.of("paid"),
                "order_items", List.of(
                        Map.of(
                                "quantity", 2,
                                "unit_price", new BigDecimal("29.90"),
                                "item", Map.of(
                                        "id", "MLB123",
                                        "title", "Caneca preta"
                                )
                        )
                )
        );
    }
}
