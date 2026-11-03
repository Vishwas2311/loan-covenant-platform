package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextPdfWriterTest {

    @Test
    void writesPagedPdfWithEscapedText() {
        TextPdfWriter w = new TextPdfWriter("Report (Q3)");
        for (int i = 0; i < TextPdfWriter.LINES_PER_PAGE + 5; i++) {
            w.line("row " + i + " \\ ok");
        }
        w.line("x".repeat(200)).line(null).line("caf\u00e9");
        String pdf = new String(w.toBytes(), StandardCharsets.US_ASCII);
        assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        assertThat(pdf).contains("/Count 2").contains("(Report \\(Q3\\)) Tj").contains("Page 2 of 2");
        assertThat(pdf).contains("caf?").contains("~)");
        assertThat(pdf).contains("row 0 \\\\ ok");
    }

    @Test
    void emptyReportStillHasOnePage() {
        String pdf = new String(new TextPdfWriter("Empty").toBytes(), StandardCharsets.US_ASCII);
        assertThat(pdf).contains("/Count 1").contains("Page 1 of 1");
    }
}
