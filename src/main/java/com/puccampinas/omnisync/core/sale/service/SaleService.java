package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class SaleService {

    private final SaleRepository saleRepository;
    private final SystemClientRepository systemClientRepository;
    private final ProductRepository productRepository;

    public SaleService(SaleRepository saleRepository, SystemClientRepository systemClientRepository, ProductRepository productRepository) {
        this.saleRepository = saleRepository;
        this.systemClientRepository = systemClientRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Sale createSale(SaleCreateRequest request) {
        Long clientID = request.getSystemClientId();
        SystemClient client = systemClientRepository.findById(clientID)
                .orElseThrow(() -> new RuntimeException("Cliente nao encontrado com o ID: " + clientID));

        // CORREÇÃO 1 e 2: Garante que o produto existe, está ATIVO e PERTENCE a este cliente
        Product product = productRepository.findByIdAndSystemClientIdAndActiveTrue(request.getProductId(), clientID)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado, inativo ou não pertence a este cliente. ID: " + request.getProductId()));

        // CORREÇÃO 3: Valida se há estoque suficiente
        if (product.getStock() < request.getQuantity()) {
            throw new RuntimeException("Estoque insuficiente para o produto: " + product.getName());
        }

        // Dá baixa no estoque do produto
        product.setStock(product.getStock() - request.getQuantity());
        productRepository.save(product); // Salva o estoque atualizado

        // Cria a venda
        Sale sale = new Sale();
        sale.setSystemClient(client);
        sale.setProduct(product);
        sale.setQuantity(request.getQuantity());
        sale.setTotalValue(request.getTotalValue());
        sale.setChannel(request.getChannel());
        sale.setExternalReferenceId(request.getExternalReferenceId());
        sale.setStatus(SaleStatus.CONFIRMED);

        return saleRepository.save(sale);
    }
}