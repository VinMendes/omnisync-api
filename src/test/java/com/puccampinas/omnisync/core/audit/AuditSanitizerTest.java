package com.puccampinas.omnisync.core.audit;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuditSanitizerTest {
    @Test void stripsSensitiveKeysRecursivelyWithoutSerializingArbitraryObjects() {
        var input = new LinkedHashMap<String, Object>();
        for (String key : List.of("password", "passwordHash", "senha", "access_token", "refreshToken",
                "cookies", "Authorization", "oauthCode", "code", "state", "clientSecret", "api-key", "resource")) {
            input.put(key, "secret-sentinel");
        }
        input.put("items", List.of(Map.of("name", "kept", "token", "nested-secret")));
        input.put("object", new Object() { @Override public String toString() { return "object-secret"; } });
        var result = AuditSanitizer.clean(input);
        assertThat(result).containsOnlyKeys("items", "object");
        assertThat(result.toString()).doesNotContain("secret");
        assertThat(result.get("items")).isEqualTo(List.of(Map.of("name", "kept")));
        assertThat(result.get("object")).isNull();
    }

    @Test void deepCopiesMutableListsAndHandlesNulls() {
        List<Object> permissions = new ArrayList<>(Arrays.asList("AUDIT_READ", null));
        Map<String, Object> input = new LinkedHashMap<>(Map.of("permissions", permissions));
        var result = AuditSanitizer.clean(input);
        permissions.clear(); input.clear();
        assertThat(result.get("permissions")).isEqualTo(Arrays.asList("AUDIT_READ", null));
        assertThat(AuditSanitizer.clean(null)).isNull();
    }
}
