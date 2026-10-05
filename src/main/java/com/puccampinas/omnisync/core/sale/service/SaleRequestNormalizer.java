package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.exception.IdempotencyConflictException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class SaleRequestNormalizer {

    public SaleCreateRequest normalize(SaleCreateRequest source) {
        if (source == null) {
            throw new IllegalArgumentException("Item de venda é obrigatório.");
        }
        SaleCreateRequest normalized = new SaleCreateRequest();
        normalized.setSystemClientId(source.getSystemClientId());
        normalized.setProductId(source.getProductId());
        normalized.setQuantity(source.getQuantity());
        normalized.setTotalValue(normalizeMoney(source.getTotalValue()));
        normalized.setChannel(source.getChannel());
        normalized.setExternalReferenceId(source.getExternalReferenceId() == null
                ? null
                : source.getExternalReferenceId().trim());
        normalized.setResource(source.getResource() == null
                ? null
                : new LinkedHashMap<>(source.getResource()));
        return normalized;
    }

    public boolean equivalent(SaleCreateRequest first, SaleCreateRequest second) {
        SaleCreateRequest left = normalize(first);
        SaleCreateRequest right = normalize(second);
        return Objects.equals(left.getProductId(), right.getProductId())
                && Objects.equals(left.getQuantity(), right.getQuantity())
                && numericEquals(left.getTotalValue(), right.getTotalValue())
                && Objects.equals(left.getResource(), right.getResource());
    }

    public boolean equivalent(Sale sale, SaleCreateRequest request) {
        SaleCreateRequest normalized = normalize(request);
        return Objects.equals(sale.getProductId(), normalized.getProductId())
                && Objects.equals(sale.getQuantity(), normalized.getQuantity())
                && numericEquals(sale.getTotalValue(), normalized.getTotalValue())
                && Objects.equals(sale.getResource(), normalized.getResource());
    }

    public List<RequestGroup> normalizeAndGroup(List<SaleCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Ao menos um item de venda é obrigatório.");
        }
        Map<RequestKey, MutableGroup> grouped = new LinkedHashMap<>();
        for (int position = 0; position < requests.size(); position++) {
            SaleCreateRequest normalized = normalize(requests.get(position));
            RequestKey key = new RequestKey(
                    normalized.getChannel().name(),
                    normalized.getExternalReferenceId()
            );
            MutableGroup existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, new MutableGroup(normalized, new ArrayList<>(List.of(position))));
            } else if (!equivalent(existing.request, normalized)) {
                throw new IdempotencyConflictException();
            } else {
                existing.positions.add(position);
            }
        }
        return grouped.values().stream()
                .map(group -> new RequestGroup(group.request, List.copyOf(group.positions)))
                .toList();
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.scale() > 2) {
            throw new IllegalArgumentException("Valor total deve ter no máximo duas casas decimais.");
        }
        return value.setScale(2);
    }

    private boolean numericEquals(BigDecimal first, BigDecimal second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.compareTo(second) == 0;
    }

    public record RequestGroup(SaleCreateRequest request, List<Integer> positions) {
        public RequestKey key() {
            return new RequestKey(request.getChannel().name(), request.getExternalReferenceId());
        }
    }

    public record RequestKey(String channel, String externalReferenceId) implements Comparable<RequestKey> {
        @Override
        public int compareTo(RequestKey other) {
            int channelOrder = channel.compareTo(other.channel);
            return channelOrder != 0
                    ? channelOrder
                    : externalReferenceId.compareTo(other.externalReferenceId);
        }
    }

    private static final class MutableGroup {
        private final SaleCreateRequest request;
        private final List<Integer> positions;

        private MutableGroup(SaleCreateRequest request, List<Integer> positions) {
            this.request = request;
            this.positions = positions;
        }
    }
}
