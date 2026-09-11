package com.puccampinas.omnisync.core.audit;

import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.config.security.PermissionDeniedException;
import com.puccampinas.omnisync.core.users.enums.Permission;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class AuditQueryService {
    private final EntityManager em;
    private final TenantAccess access;

    public AuditQueryService(EntityManager em, TenantAccess access) {
        this.em = em;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public AuditResponse.Page find(Long tenantId, Long userId, String role, String action, String entityType,
                                   String from, String to, long offset, int limit) {
        access.requireTenant(tenantId);
        boolean allowed = access.principal().getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals(
                        com.puccampinas.omnisync.config.security.PermissionAuthority.AUDIT_READ));
        if (!allowed) throw new PermissionDeniedException(Permission.AUDIT_READ);
        if (offset < 0 || offset > Integer.MAX_VALUE || limit < 1 || limit > 100 || (userId != null && userId <= 0))
            throw new IllegalArgumentException("Paginação ou userId inválido: offset >= 0 e limit entre 1 e 100.");

        LocalDateTime start = date(from);
        LocalDateTime end = date(to);
        if (start != null && end != null && start.isAfter(end))
            throw new IllegalArgumentException("Período inválido: from deve ser anterior ou igual a to.");

        StringBuilder where = new StringBuilder(" WHERE a.systemClientId = :tenant");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tenant", tenantId);
        add(where, parameters, "userId", userId);
        if (role != null) {
            String normalized = role.trim().toUpperCase(Locale.ROOT);
            if (!java.util.Set.of("ADMIN", "MANAGER", "SELLER", "VIEWER", "SYSTEM").contains(normalized))
                throw new IllegalArgumentException("Filtro role inválido.");
            add(where, parameters, "userRole", normalized);
        }
        add(where, parameters, "action", value(AuditAction.class, action));
        add(where, parameters, "entityType", value(AuditEntityType.class, entityType));
        if (start != null) { where.append(" AND a.createdAt >= :from"); parameters.put("from", start); }
        if (end != null) { where.append(" AND a.createdAt <= :to"); parameters.put("to", end); }
        var count = em.createQuery("SELECT count(a) FROM AuditLog a" + where, Long.class);
        var query = em.createQuery("SELECT a FROM AuditLog a" + where + " ORDER BY a.createdAt DESC, a.id DESC", AuditLog.class);
        parameters.forEach((name, parameter) -> { count.setParameter(name, parameter); query.setParameter(name, parameter); });
        long total = count.getSingleResult();
        var content = query.setFirstResult((int) offset).setMaxResults(limit).getResultList().stream()
                .map(AuditLog::response).toList();
        return new AuditResponse.Page(content, offset, limit, total, offset + content.size() < total);
    }

    private static void add(StringBuilder where, Map<String, Object> parameters, String field, Object value) {
        if (value != null) { where.append(" AND a.").append(field).append(" = :").append(field); parameters.put(field, value); }
    }

    private static <T extends Enum<T>> T value(Class<T> type, String text) {
        if (text == null) return null;
        try { return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Filtro " + (type == AuditAction.class ? "action" : "entityType") + " inválido.");
        }
    }

    private static LocalDateTime date(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Data inválida; use ISO-8601, por exemplo 2026-09-01T00:00:00 (UTC)."); }
    }
}
