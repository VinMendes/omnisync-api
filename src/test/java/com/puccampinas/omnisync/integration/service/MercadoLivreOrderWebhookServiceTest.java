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
import com.puccampinas.omnisync.integration.repository.MarketplaceStockSyncOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
                saleLogService,
                mock(com.puccampinas.omnisync.core.audit.AuditService.class)
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
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2001:MLB123"))
                .thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenReturn(savedSale);

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(8, product.getStock());
        assertEquals("CONFIRMED", result.get("sale_status"));
        assertEquals(1, result.get("items_processed"));
        verify(productRepository).save(product);
        verify(saleRepository).save(any(Sale.class));
        verify(saleLogService).logCreated(eq(savedSale), any(Map.class));
    }

    @Test
    void handleNotificationRejectsOrderOwnedByAnotherSeller() {
        MarketplaceIntegration integration = buildIntegration();
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2001", 123456789L, "orders_v2", 999L, 1,
                "2026-04-13T10:00:00Z", "2026-04-13T10:00:01Z");
        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                "123456789", "MERCADO_LIVRE")).thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE)).thenReturn("token");
        Map<String, Object> foreignOrder = new java.util.LinkedHashMap<>(buildOrder("paid"));
        foreignOrder.put("seller", Map.of("id", 987654321L));
        when(mercadoLivreClient.getOrder("token", "2001")).thenReturn(foreignOrder);

        assertThrows(IllegalArgumentException.class, () -> service.handleNotification(notification));

        verifyNoInteractions(productRepository);
        verifyNoInteractions(saleRepository);
    }

    @Test
    void handleNotificationRejectsUnexpectedConfiguredApplication() {
        MarketplaceIntegration integration = buildIntegration();
        ReflectionTestUtils.setField(service, "configuredApplicationId", "555");
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2001", 123456789L, "orders_v2", 999L, 1,
                "2026-04-13T10:00:00Z", "2026-04-13T10:00:01Z");
        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                "123456789", "MERCADO_LIVRE")).thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE)).thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2001")).thenReturn(buildOrder("paid"));

        assertThrows(IllegalArgumentException.class, () -> service.handleNotification(notification));

        verifyNoInteractions(productRepository);
        verifyNoInteractions(saleRepository);
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
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2001:MLB123"))
                .thenReturn(Optional.of(existingSale));
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any(Product.class));
        verify(saleLogService, never()).logCreated(any(Sale.class), any(Map.class));
    }

    @Test
    void handleNotificationShouldKeepPendingSaleWithoutDiscountingStock() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale savedSale = buildSale(55L, "PENDING");
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2002",
                123456789L,
                "orders_v2",
                999L,
                1,
                "2026-04-13T10:10:00Z",
                "2026-04-13T10:10:01Z"
        );

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue("123456789", "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE))
                .thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2002"))
                .thenReturn(buildPendingOrder());
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2002:MLB123"))
                .thenReturn(Optional.empty());
        when(saleRepository.save(any(Sale.class))).thenReturn(savedSale);

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals("PENDING", result.get("sale_status"));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void handleNotificationShouldAdjustStockWhenConfirmedQuantityChanges() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale existingSale = buildSale(55L, "CONFIRMED");
        existingSale.setQuantity(1);
        existingSale.setExternalReferenceId("2003:MLB123");
        product.setStock(9);

        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2003",
                123456789L,
                "orders_v2",
                999L,
                2,
                "2026-04-13T10:20:00Z",
                "2026-04-13T10:20:01Z"
        );

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue("123456789", "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE))
                .thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2003"))
                .thenReturn(buildOrder("paid"));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2003:MLB123"))
                .thenReturn(Optional.of(existingSale));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(8, product.getStock());
        verify(productRepository).save(product);
        verify(saleLogService).logUpdated(any(Sale.class), eq("CONFIRMED"), any(Map.class));
    }

    @Test
    void handleNotificationShouldProcessMultipleOrderItems() {
        MarketplaceIntegration integration = buildIntegration();
        Product productA = buildProduct();
        Product productB = buildProduct();
        productB.setId(11L);
        productB.setSku("SKU-2");
        productB.setResource(Map.of("mercado_livre", Map.of("item_id", "MLB456")));

        Sale saleA = buildSale(55L, "CONFIRMED");
        Sale saleB = buildSale(56L, "CONFIRMED");
        saleB.setProductId(11L);
        saleB.setExternalReferenceId("2004:MLB456");

        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                "/orders/2004",
                123456789L,
                "orders_v2",
                999L,
                1,
                "2026-04-13T10:30:00Z",
                "2026-04-13T10:30:01Z"
        );

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue("123456789", "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE))
                .thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "2004"))
                .thenReturn(buildMultiItemOrder());
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(productA));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB456"))
                .thenReturn(Optional.of(productB));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2004:MLB123"))
                .thenReturn(Optional.empty());
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(1L, "MERCADO_LIVRE", "2004:MLB456"))
                .thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenReturn(saleA, saleB);

        Map<String, Object> result = service.handleNotification(notification);

        assertTrue((Boolean) result.get("processed"));
        assertEquals(2, result.get("items_processed"));
        assertEquals(8, productA.getStock());
        assertEquals(9, productB.getStock());
    }

    @Test
    void handleNotificationShouldApplyPendingConfirmedCancelledLifecycleExactlyOnce() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale persistedSale = buildSale(77L, "PENDING");
        persistedSale.setExternalReferenceId("3001:MLB123");

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                "123456789", "MERCADO_LIVRE"
        )).thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE)).thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "3001")).thenReturn(
                buildSingleItemOrder(3001L, "payment_required", 2, "2026-09-10T15:00:00Z"),
                buildSingleItemOrder(3001L, "paid", 2, "2026-09-10T15:01:00Z"),
                buildSingleItemOrder(3001L, "cancelled", 2, "2026-09-10T15:02:00Z")
        );
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                1L, "MERCADO_LIVRE", "3001:MLB123"
        )).thenReturn(Optional.empty(), Optional.of(persistedSale), Optional.of(persistedSale));
        when(saleRepository.save(any(Sale.class))).thenReturn(persistedSale);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> pending = service.handleNotification(notification("3001", 1, "2026-09-10T15:00:00Z"));
        assertEquals("PENDING", pending.get("sale_status"));
        assertEquals(10, product.getStock());

        Map<String, Object> confirmed = service.handleNotification(notification("3001", 2, "2026-09-10T15:01:00Z"));
        assertEquals("CONFIRMED", confirmed.get("sale_status"));
        assertEquals(8, product.getStock());

        Map<String, Object> cancelled = service.handleNotification(notification("3001", 3, "2026-09-10T15:02:00Z"));
        assertEquals("CANCELLED", cancelled.get("sale_status"));
        assertEquals(10, product.getStock());

        verify(productRepository, times(2)).save(product);
        verify(saleLogService).logCreated(eq(persistedSale), any(Map.class));
        verify(saleLogService).logUpdated(eq(persistedSale), eq("PENDING"), any(Map.class));
        verify(saleLogService).logCancelled(eq(persistedSale), eq("CONFIRMED"), any(Map.class));
    }

    @Test
    void duplicateCancellationShouldNotRestoreStockOrRewriteSale() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale cancelledSale = buildSale(78L, "CANCELLED");
        cancelledSale.setExternalReferenceId("3002:MLB123");
        cancelledSale.setResource(Map.of(
                "order", Map.of("date_last_updated", "2026-09-10T15:02:00Z")
        ));

        stubExistingOrder(
                integration,
                product,
                cancelledSale,
                "3002",
                buildSingleItemOrder(3002L, "cancelled", 2, "2026-09-10T15:02:00Z")
        );

        Map<String, Object> result = service.handleNotification(
                notification("3002", 2, "2026-09-10T15:03:00Z")
        );

        assertEquals("CANCELLED", result.get("sale_status"));
        assertEquals(10, product.getStock());
        verify(productRepository, never()).save(any(Product.class));
        verify(saleRepository, never()).save(any(Sale.class));
        verifyNoInteractions(saleLogService);
    }

    @Test
    void staleCancellationShouldNotRegressConfirmedSaleOrStock() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        product.setStock(8);
        Sale confirmedSale = buildSale(79L, "CONFIRMED");
        confirmedSale.setExternalReferenceId("3003:MLB123");
        confirmedSale.setResource(Map.of(
                "order", Map.of("date_last_updated", "2026-09-10T15:10:00Z")
        ));

        stubExistingOrder(
                integration,
                product,
                confirmedSale,
                "3003",
                buildSingleItemOrder(3003L, "cancelled", 2, "2026-09-10T15:05:00Z")
        );

        Map<String, Object> result = service.handleNotification(
                notification("3003", 3, "2026-09-10T15:11:00Z")
        );

        assertEquals("CONFIRMED", result.get("sale_status"));
        assertEquals(8, product.getStock());
        assertEquals("CONFIRMED", confirmedSale.getStatus());
        verify(productRepository, never()).save(any(Product.class));
        verify(saleRepository, never()).save(any(Sale.class));
        verifyNoInteractions(saleLogService);
    }

    @Test
    void repeatedItemLinesShouldBeAggregatedBeforeApplyingStockAndSale() {
        MarketplaceIntegration integration = buildIntegration();
        Product product = buildProduct();
        Sale savedSale = buildSale(80L, "CONFIRMED");
        savedSale.setQuantity(3);
        savedSale.setTotalValue(new BigDecimal("89.70"));
        savedSale.setExternalReferenceId("3004:MLB123");

        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                "123456789", "MERCADO_LIVRE"
        )).thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE)).thenReturn("token");
        when(mercadoLivreClient.getOrder("token", "3004")).thenReturn(buildRepeatedItemOrder());
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                1L, "MERCADO_LIVRE", "3004:MLB123"
        )).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenReturn(savedSale);

        Map<String, Object> result = service.handleNotification(
                notification("3004", 1, "2026-09-10T15:20:00Z")
        );

        assertEquals(7, product.getStock());
        assertEquals(1, result.get("items_processed"));
        ArgumentCaptor<Sale> saleCaptor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository).save(saleCaptor.capture());
        assertEquals(3, saleCaptor.getValue().getQuantity());
        assertEquals(new BigDecimal("89.70"), saleCaptor.getValue().getTotalValue());
        verify(productRepository).save(product);
        verify(saleLogService).logCreated(eq(savedSale), any(Map.class));
    }

    @Test
    void mercadoLivreOriginSaleShouldNotCreateCircularStockOutbox() {
        MarketplaceStockSyncOutboxRepository outboxRepository = mock(MarketplaceStockSyncOutboxRepository.class);
        MarketplaceStockSyncOutboxService outboxService = new MarketplaceStockSyncOutboxService(outboxRepository);
        Sale mercadoLivreSale = buildSale(81L, "CONFIRMED");

        assertTrue(outboxService.enqueueIfRequired(mercadoLivreSale, buildProduct()).isEmpty());
        verifyNoInteractions(outboxRepository);
    }

    private void stubExistingOrder(
            MarketplaceIntegration integration,
            Product product,
            Sale sale,
            String orderId,
            Map<String, Object> order
    ) {
        when(marketplaceIntegrationRepository.findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                "123456789", "MERCADO_LIVRE"
        )).thenReturn(Optional.of(integration));
        when(marketplaceTokenService.getValidAccessToken(1L, Marketplace.MERCADO_LIVRE)).thenReturn("token");
        when(mercadoLivreClient.getOrder("token", orderId)).thenReturn(order);
        when(productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(1L, "MLB123"))
                .thenReturn(Optional.of(product));
        when(saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                1L, "MERCADO_LIVRE", orderId + ":MLB123"
        )).thenReturn(Optional.of(sale));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MercadoLivreNotificationRequest notification(String orderId, int attempts, String sent) {
        return new MercadoLivreNotificationRequest(
                "/orders/" + orderId,
                123456789L,
                "orders_v2",
                999L,
                attempts,
                sent,
                sent
        );
    }

    private Map<String, Object> buildSingleItemOrder(
            Long orderId,
            String status,
            int quantity,
            String dateLastUpdated
    ) {
        return Map.of(
                "id", orderId,
                "seller", Map.of("id", 123456789L),
                "status", status,
                "date_last_updated", dateLastUpdated,
                "tags", "paid".equals(status) ? List.of("paid") : List.of("not_paid"),
                "order_items", List.of(
                        Map.of(
                                "quantity", quantity,
                                "unit_price", new BigDecimal("29.90"),
                                "item", Map.of("id", "MLB123", "title", "Caneca preta")
                        )
                )
        );
    }

    private Map<String, Object> buildRepeatedItemOrder() {
        return Map.of(
                "id", 3004L,
                "seller", Map.of("id", 123456789L),
                "status", "paid",
                "date_last_updated", "2026-09-10T15:20:00Z",
                "tags", List.of("paid"),
                "order_items", List.of(
                        Map.of(
                                "quantity", 1,
                                "unit_price", new BigDecimal("29.90"),
                                "item", Map.of("id", "MLB123", "title", "Caneca preta")
                        ),
                        Map.of(
                                "quantity", 2,
                                "unit_price", new BigDecimal("29.90"),
                                "item", Map.of("id", "MLB123", "title", "Caneca preta")
                        )
                )
        );
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
        sale.setExternalReferenceId("2001:MLB123");
        sale.setStatus(status);
        sale.setResource(Map.of());
        return sale;
    }

    private Map<String, Object> buildOrder(String status) {
        return Map.of(
                "id", 2001L,
                "seller", Map.of("id", 123456789L),
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

    private Map<String, Object> buildPendingOrder() {
        return Map.of(
                "id", 2002L,
                "seller", Map.of("id", 123456789L),
                "status", "payment_required",
                "tags", List.of("not_paid"),
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

    private Map<String, Object> buildMultiItemOrder() {
        return Map.of(
                "id", 2004L,
                "seller", Map.of("id", 123456789L),
                "status", "paid",
                "tags", List.of("paid"),
                "order_items", List.of(
                        Map.of(
                                "quantity", 2,
                                "unit_price", new BigDecimal("29.90"),
                                "item", Map.of(
                                        "id", "MLB123",
                                        "title", "Caneca preta"
                                )
                        ),
                        Map.of(
                                "quantity", 1,
                                "unit_price", new BigDecimal("19.90"),
                                "item", Map.of(
                                        "id", "MLB456",
                                        "title", "Copo"
                                )
                        )
                )
        );
    }
}
