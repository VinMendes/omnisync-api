package com.puccampinas.omnisync.core.product.service;

import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreSyncResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private static final long OFFSET = 0;
    private static final int LIMIT = 10;

    private ProductRepository productRepository;
    private ProductLogService productLogService;
    private MercadoLivreListingService mercadoLivreListingService;
    private MarketplaceIntegrationRepository marketplaceIntegrationRepository;
    private UserService userService;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productLogService = mock(ProductLogService.class);
        mercadoLivreListingService = mock(MercadoLivreListingService.class);
        marketplaceIntegrationRepository = mock(MarketplaceIntegrationRepository.class);
        userService = mock(UserService.class);
        productService = new ProductService(
                productRepository,
                productLogService,
                mercadoLivreListingService,
                marketplaceIntegrationRepository,
                userService
        );
        reset(productRepository, productLogService, mercadoLivreListingService, marketplaceIntegrationRepository, userService);
    }

    @Test
    void getAllShouldThrowWhenSystemClientIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getAll(null, OFFSET, LIMIT)
        );

        assertEquals("System client id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getAllShouldThrowWhenOffsetIsNegative() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getAll(1L, -1, LIMIT)
        );

        assertEquals("Offset must be greater than or equal to 0.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getAllShouldThrowWhenLimitIsInvalid() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getAll(1L, OFFSET, 0)
        );

        assertEquals("Limit must be greater than 0.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getAllShouldReturnProductsWhenSystemClientIdIsProvided() {
        Product product = buildProduct(10L, 1L);
        when(productRepository.findAllBySystemClientIdAndActiveTrue(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));

        Page<ProductDto> result = productService.getAll(1L, OFFSET, LIMIT);

        assertEquals(1, result.getTotalElements());
        assertEquals(10L, result.getContent().getFirst().getId());
        assertEquals(1L, result.getContent().getFirst().getSystemClientId());
        assertEquals("SKU-1", result.getContent().getFirst().getSku());
        verify(productRepository).findAllBySystemClientIdAndActiveTrue(
                eq(1L),
                argThat(pageable -> pageable.getOffset() == OFFSET && pageable.getPageSize() == LIMIT)
        );
    }

    @Test
    void getBySkuShouldThrowWhenSkuIsNotFound() {
        when(productRepository.findBySkuAndSystemClientIdAndActiveTrue("SKU-404", 1L))
                .thenReturn(Optional.empty());

        EntityNotFoundException error = assertThrows(
                EntityNotFoundException.class,
                () -> productService.getBySku(1L, "SKU-404")
        );

        assertEquals("SKU não encontrada.", error.getMessage());
        verify(productRepository).findBySkuAndSystemClientIdAndActiveTrue("SKU-404", 1L);
    }

    @Test
    void getBySkuShouldReturnProductWhenSkuIsFound() {
        Product product = buildProduct(10L, 1L);
        when(productRepository.findBySkuAndSystemClientIdAndActiveTrue("SKU-1", 1L))
                .thenReturn(Optional.of(product));

        ProductDto result = productService.getBySku(1L, "SKU-1");

        assertEquals(10L, result.getId());
        assertEquals(1L, result.getSystemClientId());
        assertEquals("SKU-1", result.getSku());
        verify(productRepository).findBySkuAndSystemClientIdAndActiveTrue("SKU-1", 1L);
    }

    @Test
    void getByIdShouldThrowWhenSystemClientIdAndIdAreNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getById(null, null)
        );

        assertEquals("System client id and id are required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getByIdShouldThrowWhenSystemClientIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getById(null, 9L)
        );

        assertEquals("System client id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getByIdShouldThrowWhenIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getById(1L, null)
        );

        assertEquals("Id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void getByIdShouldReturnProductWhenIdentifiersAreProvided() {
        Product product = buildProduct(10L, 1L);
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(10L, 1L))
                .thenReturn(Optional.of(product));

        ProductDto result = productService.getById(1L, 10L);

        assertEquals(10L, result.getId());
        assertEquals(1L, result.getSystemClientId());
        assertEquals("Product 1", result.getName());
        verify(productRepository).findByIdAndSystemClientIdAndActiveTrue(10L, 1L);
    }

    @Test
    void createShouldThrowWhenSystemClientIdAndPayloadAreNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.create(null, null)
        );

        assertEquals("System client id and product payload are required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void createShouldThrowWhenSystemClientIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.create(null, validDto())
        );

        assertEquals("System client id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void createShouldThrowWhenPayloadSystemClientIdIsNull() {
        ProductDto dto = validDto();
        dto.setSystemClientId(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.create(1L, dto)
        );

        assertEquals("System client is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void createShouldThrowWhenNameIsMissing() {
        assertCreateValidationError(dto -> dto.setName(" "), "Name is required.");
    }

    @Test
    void createShouldThrowWhenSkuIsMissing() {
        assertCreateValidationError(dto -> dto.setSku(" "), "SKU is required.");
    }

    @Test
    void createShouldThrowWhenDescriptionIsMissing() {
        assertCreateValidationError(dto -> dto.setDescription(" "), "Description is required.");
    }

    @Test
    void createShouldThrowWhenPriceIsMissing() {
        assertCreateValidationError(dto -> dto.setPrice(null), "Price is required.");
    }

    @Test
    void createShouldThrowWhenPriceHasMoreThanTwoDecimalPlaces() {
        assertCreateValidationError(
                dto -> dto.setPrice(new BigDecimal("10.999")),
                "Price must have at most 2 decimal places."
        );
    }

    @Test
    void createShouldThrowWhenPriceIsNegative() {
        assertCreateValidationError(dto -> dto.setPrice(new BigDecimal("-1.00")), "Price has to be above 0.");
    }

    @Test
    void createShouldThrowWhenStockIsNegative() {
        assertCreateValidationError(dto -> dto.setStock(-1), "Stock has to be above 0.");
    }

    @Test
    void createShouldThrowWhenReservedStockIsNegative() {
        assertCreateValidationError(dto -> dto.setReservedStock(-1), "Reserved stock has to be above 0.");
    }

    @Test
    void createShouldReturnDtoWhenPayloadIsValid() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(10L);
            return product;
        });

        ProductDto result = productService.create(1L, validDto());

        assertEquals(10L, result.getId());
        assertEquals(1L, result.getSystemClientId());
        assertTrue(result.isActive());
        assertNotNull(result.getCreatedAt());
        verify(productRepository).save(any(Product.class));
        verify(mercadoLivreListingService).createListing(eq(1L), any(Product.class));
        verify(productLogService).logCreate(any(Product.class));
    }

    @Test
    void syncMercadoLivreProductsBySellerUserIdShouldCreateReactivateAndDeactivateOnlyMercadoLivreProducts() {
        MarketplaceIntegration integration = new MarketplaceIntegration();
        integration.setSystemClientId(1L);
        integration.setResource(Map.of("user_id", "123456"));
        User authenticatedUser = new User();
        authenticatedUser.setSystemClientId(1L);

        Product inactiveProduct = buildProduct(10L, 1L);
        inactiveProduct.setActive(false);
        inactiveProduct.setSku("SKU-ML-1");
        inactiveProduct.setResource(Map.of(
                "mercado_livre", Map.of("item_id", "MLB1")
        ));

        Product staleMercadoLivreProduct = buildProduct(20L, 1L);
        staleMercadoLivreProduct.setActive(true);
        staleMercadoLivreProduct.setSku("SKU-OLD");
        staleMercadoLivreProduct.setResource(Map.of(
                "mercado_livre", Map.of("item_id", "MLB-OLD")
        ));

        Product nonMercadoLivreProduct = buildProduct(30L, 1L);
        nonMercadoLivreProduct.setActive(true);
        nonMercadoLivreProduct.setResource(Map.of(
                "shopee", Map.of("item_id", "SHP1")
        ));

        when(userService.findActiveEntityByEmail("user@test.com"))
                .thenReturn(authenticatedUser);
        when(marketplaceIntegrationRepository.findMercadoLivreActiveIntegrationForSync(1L, "MERCADO_LIVRE"))
                .thenReturn(Optional.of(integration));
        when(mercadoLivreListingService.listAllClientListings(1L)).thenReturn(Map.of(
                "items", List.of(
                        Map.of("body", mercadolivreItem("MLB1", "SKU-ML-1", "Produto 1")),
                        Map.of("body", mercadolivreItem("MLB2", "SKU-ML-2", "Produto 2"))
                )
        ));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemId(1L, "MLB1"))
                .thenReturn(Optional.of(inactiveProduct));
        when(productRepository.findBySystemClientIdAndMercadoLivreItemId(1L, "MLB2"))
                .thenReturn(Optional.empty());
        when(productRepository.findBySkuAndSystemClientId("SKU-ML-2", 1L))
                .thenReturn(Optional.empty());
        when(productRepository.findAllMercadoLivreProductsBySystemClientId(1L))
                .thenReturn(List.of(inactiveProduct, staleMercadoLivreProduct));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            if (product.getId() == null) {
                product.setId(99L);
            }
            return product;
        });

        MercadoLivreSyncResponse response = productService.syncMercadoLivreProducts("user@test.com", 1L);

        assertEquals(2, response.getTotalListings());
        assertEquals(2, response.getSyncedProducts());
        assertEquals(1, response.getCreated());
        assertEquals(0, response.getUpdated());
        assertEquals(1, response.getReactivated());
        assertEquals(1, response.getDeactivated());
        assertTrue(inactiveProduct.getActive());
        assertFalse(staleMercadoLivreProduct.getActive());
        assertTrue(nonMercadoLivreProduct.getActive());
        verify(productLogService).logCreate(any(Product.class));
        verify(productLogService).logEdit(any(Product.class), any(Product.class));
        verify(productLogService).logDelete(any(Product.class), any(Product.class));
    }

    @Test
    void updateShouldThrowWhenSystemClientIdAndIdAreNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.update(null, null, validDto())
        );

        assertEquals("System client id and id are required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void updateShouldThrowWhenSystemClientIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.update(null, 10L, validDto())
        );

        assertEquals("System client id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void updateShouldThrowWhenIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.update(1L, null, validDto())
        );

        assertEquals("Id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void updateShouldThrowWhenPayloadDoesNotPassValidation() {
        ProductDto dto = validDto();
        dto.setPrice(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.update(1L, 10L, dto)
        );

        assertEquals("Price is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void updateShouldReturnUpdatedDtoWhenPayloadIsValid() {
        Product existing = buildProduct(10L, 1L);
        ProductDto dto = validDto();
        dto.setName("Updated Product");
        dto.setPrice(new BigDecimal("159.90"));

        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(10L, 1L))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductDto result = productService.update(1L, 10L, dto);

        assertEquals(10L, result.getId());
        assertEquals("Updated Product", result.getName());
        assertEquals(new BigDecimal("159.90"), result.getPrice());
        assertEquals(1L, result.getSystemClientId());
        verify(productRepository).findByIdAndSystemClientIdAndActiveTrue(10L, 1L);
        verify(productRepository).save(any(Product.class));
        verify(mercadoLivreListingService).updateListing(eq(1L), eq(10L), eq("MLB123456789"), any(Product.class));
        verify(productLogService).logEdit(any(Product.class), any(Product.class));
    }

    @Test
    void updateShouldUseMercadoLivreItemIdStoredInDatabaseWhenPayloadDoesNotContainIt() {
        Product existing = buildProduct(10L, 1L);
        ProductDto dto = validDto();
        dto.setResource(Map.of(
                "color", "blue",
                "mercado_livre", Map.of(
                        "category_id", "MLB9206",
                        "condition", "new",
                        "pictures", List.of(Map.of("id", "MLA123")),
                        "attributes", List.of(Map.of("id", "BRAND", "value_name", "Test Brand"))
                )
        ));

        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(10L, 1L))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productService.update(1L, 10L, dto);

        verify(mercadoLivreListingService).updateListing(eq(1L), eq(10L), eq("MLB123456789"), any(Product.class));
    }

    @Test
    void deleteShouldThrowWhenSystemClientIdAndIdAreNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.delete(null, null)
        );

        assertEquals("System client id and id are required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void deleteShouldThrowWhenSystemClientIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.delete(null, 10L)
        );

        assertEquals("System client id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void deleteShouldThrowWhenIdIsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.delete(1L, null)
        );

        assertEquals("Id is required.", error.getMessage());
        verifyNoInteractions(productRepository);
    }

    @Test
    void deleteShouldReturnDtoWithInactiveStatusWhenIdentifiersAreProvided() {
        Product existing = buildProduct(10L, 1L);
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(10L, 1L))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductDto result = productService.delete(1L, 10L);

        assertEquals(10L, result.getId());
        assertFalse(result.isActive());
        verify(productRepository).findByIdAndSystemClientIdAndActiveTrue(10L, 1L);
        verify(productRepository).save(any(Product.class));
        verify(mercadoLivreListingService).deleteListing(eq(1L), any(Product.class));
        verify(productLogService).logDelete(any(Product.class), any(Product.class));
    }

    private void assertCreateValidationError(DtoMutator mutator, String expectedMessage) {
        ProductDto dto = validDto();
        mutator.mutate(dto);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> productService.create(1L, dto)
        );

        assertEquals(expectedMessage, error.getMessage());
        verifyNoInteractions(productRepository);
    }

    private ProductDto validDto() {
        ProductDto dto = new ProductDto();
        dto.setSystemClientId(1L);
        dto.setSku("SKU-1");
        dto.setName("Product 1");
        dto.setDescription("Valid description");
        dto.setStock(10);
        dto.setReservedStock(2);
        dto.setPrice(new BigDecimal("99.90"));
        dto.setResource(validResource());
        return dto;
    }

    private Product buildProduct(Long id, Long systemClientId) {
        Product product = new Product();
        product.setId(id);
        product.setSystemClientId(systemClientId);
        product.setSku("SKU-1");
        product.setName("Product 1");
        product.setDescription("Valid description");
        product.setStock(10);
        product.setReservedStock(2);
        product.setPrice(new BigDecimal("99.90"));
        product.setResource(validResource());
        product.setActive(true);
        return product;
    }

    private Map<String, Object> validResource() {
        return Map.of(
                "color", "blue",
                "mercado_livre", Map.of(
                        "item_id", "MLB123456789",
                        "category_id", "MLB1055",
                        "condition", "new",
                        "pictures", List.of(Map.of("source", "https://example.com/product.jpg")),
                        "attributes", List.of(Map.of("id", "BRAND", "value_name", "Test Brand"))
                )
        );
    }

    private Map<String, Object> mercadolivreItem(String itemId, String sku, String title) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("id", itemId);
        item.put("title", title);
        item.put("seller_custom_field", sku);
        item.put("available_quantity", 7);
        item.put("price", new BigDecimal("149.90"));
        item.put("status", "active");
        item.put("seller_id", 123456);
        item.put("category_id", "MLB1055");
        item.put("listing_type_id", "free");
        item.put("permalink", "https://mercadolivre.com.br/" + itemId);
        item.put("last_updated", "2026-04-16T12:00:00.000Z");
        return item;
    }

    @FunctionalInterface
    private interface DtoMutator {
        void mutate(ProductDto dto);
    }
}
