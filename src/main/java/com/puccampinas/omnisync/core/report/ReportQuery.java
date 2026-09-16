package com.puccampinas.omnisync.core.report;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

public record ReportQuery(ReportType type, LocalDate start, LocalDate end,
                          List<String> marketplaces, List<String> fields) {
    public static ReportQuery validate(ReportRequest request) {
        if (request == null) throw new IllegalArgumentException("Informe os parâmetros do relatório.");
        ReportType type;
        try { type = ReportType.valueOf(request.reportType() == null ? "" : request.reportType()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Tipo de relatório inválido."); }
        if (!"PDF".equals(request.format())) throw new IllegalArgumentException("Formato inválido. Utilize PDF.");
        if (request.fields() == null || request.fields().isEmpty())
            throw new IllegalArgumentException("Selecione ao menos um campo.");
        if (request.fields().stream().anyMatch(field -> field == null || !type.fields().containsKey(field)))
            throw new IllegalArgumentException("Campo desconhecido para o tipo de relatório.");
        if (new HashSet<>(request.fields()).size() != request.fields().size())
            throw new IllegalArgumentException("Não repita campos no relatório.");
        LocalDate start = null, end = null;
        if (request.period() != null) {
            try {
                start = LocalDate.parse(request.period().start());
                end = LocalDate.parse(request.period().end());
                end.plusDays(1); // Valida também o limite superior usado pela consulta inclusiva.
            } catch (NullPointerException | java.time.DateTimeException ex) {
                throw new IllegalArgumentException("Período inválido. Utilize datas no formato yyyy-MM-dd.");
            }
            if (end.isBefore(start)) throw new IllegalArgumentException("Período final anterior ao inicial.");
        }
        if (type != ReportType.SALES && request.period() != null)
            throw new IllegalArgumentException("Estoque e anúncios representam a posição atual e não aceitam período histórico.");
        List<String> marketplaces = request.marketplaces() == null ? List.of() : request.marketplaces();
        if (type == ReportType.INVENTORY && !marketplaces.isEmpty())
            throw new IllegalArgumentException("Relatório de estoque não aceita filtro de marketplace.");
        for (String marketplace : marketplaces) {
            try {
                if (type == ReportType.SALES) SaleChannel.valueOf(marketplace == null ? "" : marketplace);
                else Marketplace.valueOf(marketplace == null ? "" : marketplace);
            } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Marketplace inválido."); }
        }
        return new ReportQuery(type, start, end, List.copyOf(marketplaces), List.copyOf(request.fields()));
    }
}
