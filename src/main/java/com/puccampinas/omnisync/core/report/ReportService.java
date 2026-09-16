package com.puccampinas.omnisync.core.report;

import com.puccampinas.omnisync.config.security.TenantAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class ReportService {
    private final ReportRepository repository;
    private final ReportPdfRenderer renderer;
    private final TenantAccess access;
    public ReportService(ReportRepository repository, ReportPdfRenderer renderer, TenantAccess access) {
        this.repository = repository;
        this.renderer = renderer;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public GeneratedReport generate(Long tenant, ReportRequest request) {
        access.requireTenant(tenant);
        ReportQuery query = ReportQuery.validate(request);
        access.requirePermission(query.type().permission());
        String company = repository.companyName(tenant);
        LocalDateTime generatedAt = LocalDateTime.now();
        byte[] document = renderer.render(company, query, repository.rows(tenant, query), generatedAt);
        return new GeneratedReport(document, "relatorio-" + query.type().filename() + "-" + generatedAt.toLocalDate() + ".pdf");
    }

    public record GeneratedReport(byte[] content, String filename) {}
}
