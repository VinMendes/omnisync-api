package com.puccampinas.omnisync.core.systemClient.service;

import com.puccampinas.omnisync.core.systemClient.dto.SystemClientCreateRequest;
import com.puccampinas.omnisync.core.systemClient.dto.SystemClientUpdateRequest;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class SystemClientService {

    private final SystemClientRepository repository;

    public SystemClientService(SystemClientRepository repository) {
        this.repository = repository;
    }

    public SystemClient create(SystemClientCreateRequest data) {
        String normalizedDocument = validateForCreate(data);

        SystemClient client = new SystemClient();
        client.setName(data.getName());
        client.setDocument(normalizedDocument);
        client.setResource(data.getResource());
        client.setActive(true);

        return this.repository.save(client);
    }

    public SystemClient getById(long id) {
        return this.repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Client not found for id=" + id));
    }

    public SystemClient update(long id, SystemClientUpdateRequest data) {
        SystemClient existing = this.repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Client not found for id=" + id));

        if (data == null) {
            throw new IllegalArgumentException("Client payload is required.");
        }

        boolean hasAnyField =
                data.getName() != null
                        || data.getDocument() != null
                        || data.getResource() != null;

        if (!hasAnyField) {
            throw new IllegalArgumentException("At least one field must be provided to update.");
        }

        if (data.getName() != null) {
            existing.setName(data.getName());
        }

        if (data.getDocument() != null) {
            String normalizedDocument = validateAndNormalizeDocument(data.getDocument(), id);
            existing.setDocument(normalizedDocument);
        }

        if (data.getResource() != null) {
            existing.setResource(data.getResource());
        }

        return this.repository.save(existing);
    }

    public void delete(long id) {
        SystemClient response = this.repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Client not found for id"));

        response.setActive(false);

        this.repository.save(response);
    }

    public SystemClient updateClientsMarketplaces(long id, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Client payload is required.");
        }

        SystemClient client = this.repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Client not found for id=" + id));

        client.setResource(data);

        return this.repository.save(client);
    }

    private String validateForCreate(SystemClientCreateRequest data) {
        if (data == null) {
            throw new IllegalArgumentException("Client payload is required.");
        }

        if (data.getName() == null || data.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required.");
        }

        return validateAndNormalizeDocument(data.getDocument(), null);
    }

    private String validateAndNormalizeDocument(String document, Long currentId) {
        String normalizedDocument = normalizeDocument(document);
        if (!isValidCnpj(normalizedDocument)) {
            throw new IllegalArgumentException("Document must be a valid CNPJ.");
        }

        boolean exists = currentId == null
                ? repository.existsByDocument(normalizedDocument)
                : repository.existsByDocumentAndIdNot(normalizedDocument, currentId);

        if (exists) {
            throw new IllegalArgumentException("Document already exists.");
        }

        return normalizedDocument;
    }

    private String normalizeDocument(String document) {
        if (document == null) {
            return "";
        }
        return document.replaceAll("\\D", "");
    }

    private boolean isValidCnpj(String cnpj) {
        if (cnpj == null || cnpj.length() != 14) {
            return false;
        }

        if (cnpj.chars().distinct().count() == 1) {
            return false;
        }

        int firstDigit = calculateDigit(cnpj, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int secondDigit = calculateDigit(cnpj, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});

        return firstDigit == Character.getNumericValue(cnpj.charAt(12))
                && secondDigit == Character.getNumericValue(cnpj.charAt(13));
    }

    private int calculateDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += Character.getNumericValue(cnpj.charAt(i)) * weights[i];
        }

        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
