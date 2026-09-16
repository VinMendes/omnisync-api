package com.puccampinas.omnisync.core.report;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/{systemClientId}")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service) { this.service = service; }

    @PostMapping("/generate")
    public ResponseEntity<byte[]> generate(@PathVariable Long systemClientId, @RequestBody ReportRequest request) {
        ReportService.GeneratedReport report = service.generate(systemClientId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(report.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(report.content().length)
                .body(report.content());
    }
}
