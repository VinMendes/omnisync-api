package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleRequestNormalizerTest {

    private static final String NORMALIZER_CLASS =
            "com.puccampinas.omnisync.core.sale.service.SaleRequestNormalizer";

    @Test
    void trimsExternalReferenceButPreservesCaseAndInternalCharacters() throws Exception {
        SaleCreateRequest upper = request("  Ref-Ab C  ", new BigDecimal("109.80"), resource("store", "front"));
        SaleCreateRequest lower = request("  ref-Ab C  ", new BigDecimal("109.80"), resource("store", "front"));

        SaleCreateRequest normalizedUpper = normalize(upper);
        SaleCreateRequest normalizedLower = normalize(lower);

        assertThat(normalizedUpper.getExternalReferenceId()).isEqualTo("Ref-Ab C");
        assertThat(normalizedLower.getExternalReferenceId()).isEqualTo("ref-Ab C");
        assertThat(normalizedUpper.getExternalReferenceId())
                .isNotEqualTo(normalizedLower.getExternalReferenceId());
    }

    @Test
    void treatsNumericallyEqualMoneyAndSemanticJsonAsEquivalent() throws Exception {
        Map<String, Object> firstResource = new LinkedHashMap<>();
        firstResource.put("origin", "PHYSICAL_STORE");
        firstResource.put("metadata", resource("counter", 3, "cashier", "Ana"));

        Map<String, Object> reorderedResource = new LinkedHashMap<>();
        reorderedResource.put("metadata", resource("cashier", "Ana", "counter", 3));
        reorderedResource.put("origin", "PHYSICAL_STORE");

        SaleCreateRequest first = request("REF-1", new BigDecimal("109.8"), firstResource);
        SaleCreateRequest reordered = request("REF-1", new BigDecimal("109.80"), reorderedResource);

        assertThat(equivalent(first, reordered)).isTrue();
    }

    @Test
    void distinguishesAbsentJsonPropertyFromExplicitNullAndBusinessChanges() throws Exception {
        SaleCreateRequest absent = request("REF-1", new BigDecimal("109.80"), Map.of("origin", "STORE"));
        Map<String, Object> withNullResource = new LinkedHashMap<>();
        withNullResource.put("origin", "STORE");
        withNullResource.put("note", null);
        SaleCreateRequest explicitNull = request("REF-1", new BigDecimal("109.80"), withNullResource);
        SaleCreateRequest differentQuantity = request("REF-1", new BigDecimal("109.80"), Map.of("origin", "STORE"));
        differentQuantity.setQuantity(3);

        assertThat(equivalent(absent, explicitNull)).isFalse();
        assertThat(equivalent(absent, differentQuantity)).isFalse();
    }

    @Test
    void groupsEquivalentDuplicateKeysAndPreservesAllInputPositions() throws Exception {
        SaleCreateRequest first = request("  SAME-REF ", new BigDecimal("109.8"), resource("origin", "STORE"));
        SaleCreateRequest duplicate = request("SAME-REF", new BigDecimal("109.80"), resource("origin", "STORE"));

        List<?> groups = normalizeAndGroup(List.of(first, duplicate));

        assertThat(groups).hasSize(1);
        Object group = groups.getFirst();
        assertThat(invokeAccessor(group, "positions")).isEqualTo(List.of(0, 1));
        Object normalizedRequest = invokeAccessor(group, "request");
        assertThat(normalizedRequest).isInstanceOf(SaleCreateRequest.class);
        assertThat(((SaleCreateRequest) normalizedRequest).getExternalReferenceId()).isEqualTo("SAME-REF");
    }

    @Test
    void rejectsConflictingDuplicateKeysInsideOneRequest() throws Exception {
        SaleCreateRequest first = request("SAME-REF", new BigDecimal("109.80"), resource("origin", "STORE"));
        SaleCreateRequest conflicting = request(" SAME-REF ", new BigDecimal("109.80"), resource("origin", "STORE"));
        conflicting.setProductId(99L);

        assertThatThrownBy(() -> normalizeAndGroup(List.of(first, conflicting)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("referência externa");
    }

    private SaleCreateRequest normalize(SaleCreateRequest request) throws Exception {
        return (SaleCreateRequest) invoke("normalize", new Class<?>[]{SaleCreateRequest.class}, request);
    }

    private boolean equivalent(SaleCreateRequest first, SaleCreateRequest second) throws Exception {
        return (boolean) invoke(
                "equivalent",
                new Class<?>[]{SaleCreateRequest.class, SaleCreateRequest.class},
                first,
                second
        );
    }

    @SuppressWarnings("unchecked")
    private List<?> normalizeAndGroup(List<SaleCreateRequest> requests) throws Exception {
        return (List<?>) invoke("normalizeAndGroup", new Class<?>[]{List.class}, requests);
    }

    private Object invoke(String methodName, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Class<?> type = Class.forName(NORMALIZER_CLASS);
        Object target = type.getConstructor().newInstance();
        Method method = type.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private Object invokeAccessor(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private SaleCreateRequest request(String reference, BigDecimal totalValue, Map<String, Object> resource) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(42L);
        request.setQuantity(2);
        request.setTotalValue(totalValue);
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId(reference);
        request.setResource(resource);
        return request;
    }

    private Map<String, Object> resource(Object... entries) {
        Map<String, Object> resource = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            resource.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return resource;
    }
}
