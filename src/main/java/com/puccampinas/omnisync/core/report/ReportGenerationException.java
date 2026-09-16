package com.puccampinas.omnisync.core.report;

public class ReportGenerationException extends RuntimeException {
    public ReportGenerationException(Throwable cause) {
        super("Não foi possível gerar o documento.", cause);
    }
}
