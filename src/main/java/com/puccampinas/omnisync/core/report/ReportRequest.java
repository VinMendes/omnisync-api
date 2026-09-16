package com.puccampinas.omnisync.core.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReportRequest(
        @JsonProperty("report_type") String reportType,
        String format,
        Period period,
        List<String> marketplaces,
        List<String> fields
) {
    public record Period(String start, String end) {}
}
