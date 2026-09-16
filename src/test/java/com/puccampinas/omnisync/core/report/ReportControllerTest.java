package com.puccampinas.omnisync.core.report;

import com.puccampinas.omnisync.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.IOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportControllerTest {
    @Test void exposesGenerationFailureAs422WithoutReturningPartialPdf() throws Exception {
        ReportService service = mock(ReportService.class);
        when(service.generate(eq(1L), any())).thenThrow(new ReportGenerationException(new IOException("test-only")));
        var mvc = MockMvcBuilders.standaloneSetup(new ReportController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/reports/1/generate").contentType("application/json")
                .content("{\"report_type\":\"SALES\",\"format\":\"PDF\",\"fields\":[\"sku\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Não foi possível gerar o documento."));
    }
}
