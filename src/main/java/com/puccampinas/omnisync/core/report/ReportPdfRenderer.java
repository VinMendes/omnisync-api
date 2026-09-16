package com.puccampinas.omnisync.core.report;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Layout tabular com quebra de células, páginas e cabeçalho repetido. */
@Component
public class ReportPdfRenderer {
    private static final float MARGIN = 32, SIZE = 8, LINE = 11, PADDING = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public byte[] render(String company, ReportQuery query, List<Map<String, Object>> rows, LocalDateTime generatedAt) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Relatório de " + query.type().title());
            document.getDocumentInformation().setAuthor("OmniSync");
            Layout layout = new Layout(document, query, company, generatedAt);
            try {
                layout.newPage();
                if (rows.isEmpty()) layout.text("Não existem registros para os filtros selecionados.", MARGIN, layout.y - 22, SIZE);
                for (Map<String, Object> row : rows) layout.row(query.fields().stream()
                        .map(field -> format(field, row.get(field))).toList());
            } finally { layout.close(); }
            PDFont footerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    footer.beginText(); footer.setFont(footerFont, 8);
                    footer.newLineAtOffset(MARGIN, 18); footer.showText("OmniSync | Página " + (i + 1) + " de " + document.getNumberOfPages());
                    footer.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException ex) { throw new ReportGenerationException(ex); }
    }

    private static String format(String field, Object value) {
        if (value == null) return "-";
        if (value instanceof Timestamp timestamp) return DATE.format(timestamp.toLocalDateTime());
        if (value instanceof LocalDateTime date) return DATE.format(date);
        if (List.of("price", "total_value", "inventory_value").contains(field)) {
            try { return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(new BigDecimal(value.toString())); }
            catch (NumberFormatException ignored) { return value.toString(); }
        }
        return value.toString();
    }

    private static class Layout implements AutoCloseable {
        private final PDDocument document;
        private final ReportQuery query;
        private final String company;
        private final LocalDateTime generatedAt;
        private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDRectangle size;
        private final float width;
        private PDPageContentStream stream;
        private float y;
        private float tableTop;

        Layout(PDDocument document, ReportQuery query, String company, LocalDateTime generatedAt) {
            this.document = document; this.query = query; this.company = company; this.generatedAt = generatedAt;
            this.size = query.fields().size() > 5 ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            this.width = (size.getWidth() - 2 * MARGIN) / query.fields().size();
        }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(size); document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = size.getHeight() - MARGIN;
            stream.setNonStrokingColor(new Color(25, 60, 90));
            text("Relatório de " + query.type().title(), MARGIN, y, 16, bold); y -= 24;
            stream.setNonStrokingColor(Color.BLACK);
            metadata("Empresa: " + company);
            metadata("Período: " + (query.start() == null
                    ? query.type() == ReportType.SALES ? "Todo o histórico" : "Posição atual"
                    : query.start() + " a " + query.end() + " (inclusive)"));
            metadata("Gerado em: " + DATE.format(generatedAt));
            if (!query.marketplaces().isEmpty()) metadata("Canais/marketplaces: " + String.join(", ", query.marketplaces()));
            y -= 7;
            cells(query.fields().stream().map(field -> wrap(query.type().fields().get(field), bold)).toList(), true);
            tableTop = y;
        }

        void metadata(String text) throws IOException {
            // O cabeçalho também respeita os limites da página para nomes longos.
            for (String line : wrap(text, regular, size.getWidth() - 2 * MARGIN)) {
                text(line, MARGIN, y, SIZE); y -= LINE;
            }
        }

        void row(List<String> values) throws IOException {
            List<List<String>> lines = values.stream().map(value -> wrap(value, regular)).toList();
            int lineCount = lines.stream().mapToInt(List::size).max().orElse(1);
            int offset = 0;
            while (offset < lineCount) {
                int capacity = (int) ((y - MARGIN - 2 * PADDING) / LINE);
                if (capacity < 1) { newPage(); continue; }
                // Uma linha comum não é dividida entre duas páginas.
                if (offset == 0 && lineCount > capacity && y < tableTop) {
                    newPage(); continue;
                }
                int count = Math.min(capacity, lineCount - offset);
                List<List<String>> segment = new ArrayList<>();
                for (List<String> cell : lines) {
                    int from = Math.min(offset, cell.size()), to = Math.min(offset + count, cell.size());
                    segment.add(cell.subList(from, to));
                }
                cells(segment, false); offset += count;
                if (offset < lineCount) newPage();
            }
        }

        void cells(List<List<String>> cells, boolean header) throws IOException {
            int count = cells.stream().mapToInt(List::size).max().orElse(1);
            float height = Math.max(1, count) * LINE + 2 * PADDING;
            for (int i = 0; i < cells.size(); i++) {
                float x = MARGIN + i * width;
                if (header) {
                    stream.setNonStrokingColor(new Color(232, 239, 246));
                    stream.addRect(x, y - height, width, height); stream.fill();
                }
                stream.setStrokingColor(new Color(195, 205, 215)); stream.setLineWidth(0.4f);
                stream.addRect(x, y - height, width, height); stream.stroke();
                stream.setNonStrokingColor(Color.BLACK);
                for (int j = 0; j < cells.get(i).size(); j++)
                    text(cells.get(i).get(j), x + PADDING, y - PADDING - SIZE - j * LINE, SIZE, header ? bold : regular);
            }
            y -= height;
        }

        List<String> wrap(String value, PDFont font) { return wrap(value, font, width - 2 * PADDING); }
        List<String> wrap(String value, PDFont font, float maxWidth) {
            String sanitized = printable(value, font);
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            try {
                for (int i = 0; i < sanitized.length(); i++) {
                    char character = sanitized.charAt(i);
                    if (character == '\n') { lines.add(line.toString()); line.setLength(0); continue; }
                    if (font.getStringWidth(line.toString() + character) / 1000 * SIZE > maxWidth && !line.isEmpty()) {
                        int boundary = line.lastIndexOf(" ");
                        if (boundary > 0) {
                            lines.add(line.substring(0, boundary));
                            String remaining = line.substring(boundary + 1);
                            line.setLength(0); line.append(remaining);
                        } else { lines.add(line.toString()); line.setLength(0); }
                    }
                    if (character == ' ' && line.isEmpty()) continue;
                    line.append(character);
                }
                if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
                return lines;
            } catch (IOException ex) { throw new ReportGenerationException(ex); }
        }

        String printable(String value, PDFont font) {
            StringBuilder text = new StringBuilder();
            value.codePoints().forEach(code -> {
                if (code == '\n') { text.append('\n'); return; }
                if (Character.isISOControl(code)) { text.append(' '); return; }
                String character = new String(Character.toChars(code));
                try { font.encode(character); text.append(character); }
                catch (IOException | IllegalArgumentException ex) { text.append('?'); }
            });
            return text.toString();
        }

        void text(String text, float x, float baseline, float fontSize) throws IOException { text(text, x, baseline, fontSize, regular); }
        void text(String text, float x, float baseline, float fontSize, PDFont font) throws IOException {
            stream.beginText(); stream.setFont(font, fontSize); stream.newLineAtOffset(x, baseline);
            stream.showText(printable(text, font)); stream.endText();
        }
        @Override public void close() throws IOException { if (stream != null) { stream.close(); stream = null; } }
    }
}
