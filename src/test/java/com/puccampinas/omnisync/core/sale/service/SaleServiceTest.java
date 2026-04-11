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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // Liga a mágica automática dos Mocks
@DisplayName("Testes do Serviço de Vendas (SaleService)")
class SaleServiceTest {

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private SystemClientRepository systemClientRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks // Injeta os repositórios falsos acima diretamente no SaleService
    private SaleService saleService;

    @Test
    @DisplayName("Deve bloquear a venda se o Cliente não existir")
    void createSaleShouldThrowExceptionWhenClientNotFound() {
        SaleCreateRequest request = validRequest();

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.empty());

        RuntimeException error = assertThrows(RuntimeException.class, () -> saleService.createSale(request));
        assertEquals("Cliente nao encontrado com o ID: 1", error.getMessage());
        verifyNoInteractions(saleRepository);
    }

    @Test
    @DisplayName("Deve bloquear a venda se o Produto for de outro cliente, não existir ou estiver inativo")
    void createSaleShouldThrowExceptionWhenProductIsInvalid() {
        SaleCreateRequest request = validRequest();
        SystemClient mockClient = new SystemClient();

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.of(mockClient));
        // Note que agora mockamos o novo método de busca!
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(request.getProductId(), request.getSystemClientId()))
                .thenReturn(Optional.empty());

        RuntimeException error = assertThrows(RuntimeException.class, () -> saleService.createSale(request));
        assertTrue(error.getMessage().contains("Produto não encontrado, inativo ou não pertence a este cliente"));
        verifyNoInteractions(saleRepository);
    }

    @Test
    @DisplayName("Deve bloquear a venda se não houver estoque suficiente")
    void createSaleShouldThrowExceptionWhenStockIsInsufficient() {
        SaleCreateRequest request = validRequest();
        request.setQuantity(10); // Usuário quer comprar 10

        SystemClient mockClient = new SystemClient();
        Product mockProduct = new Product();
        mockProduct.setName("Teclado");
        mockProduct.setStock(5); // Mas só temos 5 no estoque!

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.of(mockClient));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(request.getProductId(), request.getSystemClientId()))
                .thenReturn(Optional.of(mockProduct));

        RuntimeException error = assertThrows(RuntimeException.class, () -> saleService.createSale(request));
        assertEquals("Estoque insuficiente para o produto: Teclado", error.getMessage());
        verifyNoInteractions(saleRepository); // A venda não pode ser salva
    }

    @Test
    @DisplayName("Deve salvar a venda e descontar o estoque quando os dados forem válidos")
    void createSaleShouldReturnSavedSaleAndDeductStock() {
        SaleCreateRequest request = validRequest();
        request.setQuantity(2);

        SystemClient mockClient = new SystemClient();
        Product mockProduct = new Product();
        mockProduct.setStock(10); // Começa com 10 no estoque

        when(systemClientRepository.findById(request.getSystemClientId())).thenReturn(Optional.of(mockClient));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(request.getProductId(), request.getSystemClientId()))
                .thenReturn(Optional.of(mockProduct));

        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale saleToSave = invocation.getArgument(0);
            saleToSave.setId(99L);
            return saleToSave;
        });

        Sale result = saleService.createSale(request);

        assertNotNull(result);
        assertEquals(99L, result.getId());
        assertEquals(SaleStatus.CONFIRMED, result.getStatus());

        // Verifica se o estoque foi atualizado (10 - 2 = 8)
        assertEquals(8, mockProduct.getStock());
        verify(productRepository, times(1)).save(mockProduct);
        verify(saleRepository, times(1)).save(any(Sale.class));
    }

    private SaleCreateRequest validRequest() {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setSystemClientId(1L);
        request.setProductId(10L);
        request.setQuantity(2);
        request.setTotalValue(new BigDecimal("150.50"));
        request.setChannel(SaleChannel.PHYSICAL);
        return request;
    }
}