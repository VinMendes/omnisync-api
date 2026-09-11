package com.puccampinas.omnisync.core.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AuditResponse(
        Long id,
        @JsonProperty("system_client_id") Long systemClientId,
        Actor user,
        AuditAction action,
        @JsonProperty("entity_type") AuditEntityType entityType,
        @JsonProperty("entity_id") String entityId,
        String description,
        @JsonProperty("previous_data") Map<String, Object> previousData,
        @JsonProperty("new_data") Map<String, Object> newData,
        Map<String, Object> metadata,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
    public record Actor(Long id, String name, String email, String role) {}
    public record Page(List<AuditResponse> content, long offset, int limit,
                       @JsonProperty("total_elements") long totalElements,
                       @JsonProperty("has_next") boolean hasNext) {}
}
