package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes do Serviço de Vendas (SaleService)")
class SaleServiceTest {

    private SaleRepository saleRepository;
    private SystemClientRepository systemClientRepository;
    private ProductRepository productRepository;

    private SaleService saleService;

    @BeforeEach
    void setUp() {
        saleRepository = mock(SaleRepository.class);
        systemClientRepository = mock(SystemClientRepository.class);
        productRepository = mock(ProductRepository.class);

        saleService = new SaleService(saleRepository, systemClientRepository, productRepository);
    }

    @Test
    @DisplayName("Deve bloquear a venda e lançar erro se o Cliente não existir")
    void createSaleShouldThrowExceptionWhenClientNotFound() {
        SaleCreateRequest request = validRequest();

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.empty());

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> saleService.createSale(request)
        );

        assertEquals("Cliente nao encontrado com o ID: 1", error.getMessage());

        verifyNoInteractions(saleRepository);
    }

    @Test
    @DisplayName("Deve bloquear a venda e lançar erro se o Produto não for encontrado")
    void createSaleShouldThrowExceptionWhenProductNotFound() {
        SaleCreateRequest request = validRequest();
        SystemClient systemClient = new SystemClient();

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.of(systemClient));
        when(productRepository.findById(request.getProductId())).thenReturn(Optional.empty());

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> saleService.createSale(request)
        );

        assertEquals("Produto não encontrado com ID: 10", error.getMessage());
        verifyNoInteractions(saleRepository);

    }

    @Test
    @DisplayName("Deve salvar a venda com Status CONFIRMED quando os dados forem válidos")
    void createSaleShouldReturnSavedSaleWhenRequestIsValid() {
        SaleCreateRequest request = validRequest();
        SystemClient mockClient = new SystemClient();
        Product mockProduct = new Product();

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.of(mockClient));
        when(productRepository.findById(request.getProductId())).thenReturn(Optional.of(mockProduct));

        // Quando o repositório tentar gravar, devolvemos a própria venda para confirmar que passou por lá
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale saleToSave = invocation.getArgument(0);
            saleToSave.setId(99L); // Simulamos o PostgreSQL a gerar o ID
            return saleToSave;
        });

        // Act
        Sale result = saleService.createSale(request);
        assertNotNull(result);
        assertEquals(99L, result.getId());
        assertEquals(mockClient, result.getSystemClient());
        assertEquals(mockProduct, result.getProduct());
        assertEquals(SaleStatus.CONFIRMED, result.getStatus());
        assertEquals(SaleChannel.PHYSICAL, result.getChannel());

        // CONFIRMAR que o repositorio foi chamado exatamente uma vez
        verify(saleRepository, times(1)).save(any(Sale.class));
    }

    private SaleCreateRequest validRequest() {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setSystemClientId(1L);
        request.setProductId(10L);
        request.setQuantity(2);
        request.setTotalValue(new BigDecimal("150.50"));
        request.setChannel(SaleChannel.PHYSICAL);
        request.setExternalReferenceId("REF-123");
        return request;
    }
}