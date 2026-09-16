package com.puccampinas.omnisync.core.report;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class ReportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportRepository repository;
    private long tenant, otherTenant, product;

    @BeforeEach void setup() {
        tenant = tenant("Empresa de relatórios", "reports-main");
        otherTenant = tenant("Empresa secreta", "reports-other");
        product = product(tenant, "CAM-PRETA", "Camiseta", true);
        product(otherTenant, "SKU-SECRETO", "Produto secreto", true);
        product(tenant, "INATIVO", "Produto inativo", false);
    }

    @Test void inventoryUsesOnlySelectedFieldsAndTenantAndCorrectCalculations() throws Exception {
        String body = """
                {"report_type":"INVENTORY","format":"PDF","fields":["sku","available_stock","inventory_value"]}
                """;
        byte[] pdf = mvc.perform(request(tenant, body, "PRODUCT_READ"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Disposition")).andReturn().getResponse().getContentAsByteArray();
        String text = text(pdf);
        assertThat(text).contains("Relatório de Estoque", "Empresa de relatórios", "Posição atual", "Gerado em:", "CAM-PRETA", "8", "549,00")
                .doesNotContain("SKU-SECRETO", "Produto secreto", "Produto inativo", "Camiseta", "Estoque mínimo");
        var rows = repository.rows(tenant, ReportQuery.validate(new ReportRequest("INVENTORY", "PDF", null, null,
                List.of("sku", "available_stock", "inventory_value"))));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("available_stock")).isEqualTo(8);
        assertThat((java.math.BigDecimal) rows.getFirst().get("inventory_value")).isEqualByComparingTo("549.00");
    }

    @Test void salesFiltersInclusivePeriodAndMarketplaceWithoutLeakingOtherTenant() throws Exception {
        sale(tenant, product, "2026-09-01 00:00:00", "MANUAL", "FIRST");
        sale(tenant, product, "2026-09-15 23:59:59.999999", "MANUAL", "LAST");
        sale(tenant, product, "2026-09-16 00:00:00", "MANUAL", "NEXT-DAY");
        sale(tenant, product, "2026-09-10 10:00:00", "MERCADO_LIVRE", "WRONG-CHANNEL");
        long secretProduct = product(otherTenant, "SECOND-SECRET", "Outro secreto", true);
        sale(otherTenant, secretProduct, "2026-09-10 10:00:00", "MANUAL", "SECRET-REF");
        String body = """
                {"report_type":"SALES","format":"PDF","period":{"start":"2026-09-01","end":"2026-09-15"},
                 "marketplaces":["MANUAL"],"fields":["external_reference_id","created_at","quantity","total_value","status"]}
                """;
        String text = text(mvc.perform(request(tenant, body, "SALE_READ")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(text).contains("FIRST", "LAST", "2026-09-01 a 2026-09-15", "CONFIRMED")
                .doesNotContain("NEXT-DAY", "WRONG-CHANNEL", "SECRET-REF", "Camiseta");
    }

    @Test void listingsUsePersistedMarketplaceValuesAndFilterMarketplace() throws Exception {
        jdbc.update("""
                UPDATE products SET resource = '{"mercado_livre":{"item_id":"MLB42","status":"paused",
                "raw":{"price":99.90,"available_quantity":3}},
                "shopee":{"item_id":"SHOPEE42","status":"active","raw":{"price":20,"available_quantity":8}}}'::jsonb
                WHERE id = ?
                """, product);
        String body = """
                {"report_type":"LISTINGS","format":"PDF","marketplaces":["MERCADO_LIVRE"],
                "fields":["product_name","sku","marketplace","external_id","price","stock","status"]}
                """;
        String text = text(mvc.perform(request(tenant, body, "PRODUCT_READ")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(text).contains("Camiseta", "MLB42", "99,90", "paused", "3").doesNotContain("SHOPEE42", "SKU-SECRETO");
    }

    @Test void emptyReportsRemainValidForAllTypes() throws Exception {
        long empty = tenant("Empresa vazia", "reports-empty");
        for (String type : List.of("SALES", "INVENTORY", "LISTINGS")) {
            String body = "{\"report_type\":\"" + type + "\",\"format\":\"PDF\",\"fields\":[\"sku\"]}";
            assertThat(text(mvc.perform(request(empty, body, "PRODUCT_READ", "SALE_READ"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()))
                    .contains("Não existem registros", "Empresa vazia");
        }
    }

    @Test void enforcesDynamicPermissionsAuthenticationAndTenantIsolation() throws Exception {
        String sales = "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\"]}";
        String inventory = sales.replace("SALES", "INVENTORY");
        String listings = sales.replace("SALES", "LISTINGS");
        mvc.perform(request(tenant, sales, "PRODUCT_READ")).andExpect(status().isForbidden());
        mvc.perform(request(tenant, inventory, "SALE_READ")).andExpect(status().isForbidden());
        mvc.perform(request(tenant, listings, "SALE_READ")).andExpect(status().isForbidden());
        mvc.perform(request(tenant, sales, "SALE_READ").with(authentication(auth(otherTenant, "SALE_READ"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/reports/" + tenant + "/generate").contentType(MediaType.APPLICATION_JSON).content(sales))
                .andExpect(status().isUnauthorized());
        long nonexistent = Long.MAX_VALUE;
        mvc.perform(request(nonexistent, inventory, "PRODUCT_READ")).andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidRequestsWith400() throws Exception {
        for (String body : List.of(
                "{\"report_type\":\"UNKNOWN\",\"format\":\"PDF\",\"fields\":[\"sku\"]}",
                "{\"report_type\":\"SALES\",\"format\":\"CSV\",\"fields\":[\"sku\"]}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"secret\"]}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[]}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\",\"sku\"]}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\"],\"period\":{\"start\":\"2026-09-15\",\"end\":\"2026-09-01\"}}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\"],\"period\":{\"start\":\"2026-02-30\",\"end\":\"2026-09-01\"}}",
                "{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\"],\"marketplaces\":[\"UNKNOWN\"]}")) {
            mvc.perform(request(tenant, body, "SALE_READ")).andExpect(status().isBadRequest());
        }
    }

    private MockHttpServletRequestBuilder request(long tenant, String body, String... permissions) {
        return post("/api/reports/" + tenant + "/generate").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(authentication(auth(tenant, permissions)));
    }
    private UsernamePasswordAuthenticationToken auth(long tenant, String... permissions) {
        var authorities = java.util.Arrays.stream(permissions).map(p -> new SimpleGrantedAuthority("PERM_" + p)).toList();
        var principal = new OmniUserPrincipal(1L, tenant, "Reports", "reports@example.invalid", null, true, true, authorities);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
    private String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) { return new PDFTextStripper().getText(document); }
    }
    private long tenant(String name, String document) {
        return jdbc.queryForObject("INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id", Long.class, name, document);
    }
    private long product(long tenant, String sku, String name, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO products(system_client_id, sku, name, stock, reserved_stock, minimum_stock, price, active)
                VALUES (?, ?, ?, 10, 2, 5, 54.90, ?) RETURNING id
                """, Long.class, tenant, sku, name, active);
    }
    private void sale(long tenant, long product, String date, String channel, String reference) {
        jdbc.update("""
                INSERT INTO sales(system_client_id,product_id,quantity,total_value,channel,status,created_at,external_reference_id)
                VALUES (?,?,2,109.80,?,'CONFIRMED',?::timestamp,?)
                """, tenant, product, channel, date, reference);
    }
}
