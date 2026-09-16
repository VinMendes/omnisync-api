package com.puccampinas.omnisync.core.report;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfRendererTest {
    @Test void paginatesRepeatsHeaderAndPreservesLongCellsAndFieldOrder() throws Exception {
        var query = ReportQuery.validate(new ReportRequest("INVENTORY", "PDF", null, null,
                List.of("id", "sku", "product_name", "stock", "reserved_stock", "available_stock", "minimum_stock", "price", "inventory_value")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) rows.add(Map.of("id", i, "sku", "CAM-PRETA-" + i,
                "product_name", "Camiseta preta tamanho M - algodão confortável para uso diário",
                "stock", 10, "reserved_stock", 2, "available_stock", 8, "minimum_stock", 5,
                "price", new BigDecimal("54.90"), "inventory_value", new BigDecimal("549.00")));
        rows.add(Map.of("sku", "LONG", "product_name", "Texto extenso ".repeat(500) + "FIM-DO-TEXTO"));
        byte[] pdf = new ReportPdfRenderer().render("Empresa de demonstração", query, rows, LocalDateTime.parse("2026-09-16T10:30:00"));
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("CAM-PRETA-100", "FIM-DO-TEXTO", "Empresa de demonstração", "549,00");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                stripper.setStartPage(i); stripper.setEndPage(i);
                assertThat(stripper.getText(document)).contains("Relatório de Estoque", "Identificador", "Página " + i + " de");
            }
        }
        // Amostra determinística para QA visual, somente no diretório de build.
        Path sample = Path.of("build/reports/report-preview.pdf");
        Files.createDirectories(sample.getParent()); Files.write(sample, pdf);
    }
}
