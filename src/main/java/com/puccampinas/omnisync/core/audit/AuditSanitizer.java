package com.puccampinas.omnisync.core.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Defense in depth in addition to typed snapshot allowlists; deep copies mutable payloads. */
final class AuditSanitizer {
    private AuditSanitizer() {}

    static Map<String, Object> clean(Map<String, ?> source) {
        return source == null ? null : cleanMap(source, 0);
    }

    private static Map<String, Object> cleanMap(Map<?, ?> source, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (depth > 8) return result;
        source.forEach((key, value) -> {
            if (key instanceof String name && !sensitive(name)) result.put(name, cleanValue(value, depth + 1));
        });
        return result;
    }

    private static Object cleanValue(Object value, int depth) {
        if (depth > 8) return null;
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) return cleanMap(map, depth);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) result.add(cleanValue(element, depth + 1));
            return result;
        }
        // Do not invoke toString() on arbitrary objects (tokens, requests, entities, etc).
        return null;
    }

    private static boolean sensitive(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.contains("password") || key.contains("senha") || key.contains("token")
                || key.contains("secret") || key.contains("credential") || key.contains("cookie")
                || key.contains("authorization") || key.contains("oauth") || key.contains("apikey")
                || key.contains("privatekey") || key.equals("code") || key.equals("state")
                || key.equals("headers") || key.equals("resource") || key.equals("payload")
                || key.equals("request") || key.equals("response");
    }
}
