package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class XlsxWriterTest {

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void writesAValidPackageWithTypedEscapedCells() throws IOException {
        byte[] bytes = new XlsxWriter("A very long sheet name that exceeds the limit")
                .row(List.of("Name", "Value"))
                .row(Arrays.asList("A & <B>", 1.5))
                .row(Arrays.asList("=HYPERLINK(\"x\")", null, "tab\tok\u0001"))
                .toBytes();
        Map<String, String> parts = unzip(bytes);
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\"><is><t>Name</t>");
        assertThat(sheet).contains("A &amp; &lt;B&gt;");
        assertThat(sheet).contains("<c r=\"B2\"><v>1.5</v></c>");
        assertThat(sheet).contains("'=HYPERLINK(&quot;x&quot;)");
        assertThat(sheet).doesNotContain("r=\"B3\"").contains("tab\tok<");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"A very long sheet name that exc\"");
    }

    @Test
    void columnsAndSafeText() {
        assertThat(XlsxWriter.column(0)).isEqualTo("A");
        assertThat(XlsxWriter.column(25)).isEqualTo("Z");
        assertThat(XlsxWriter.column(26)).isEqualTo("AA");
        assertThat(XlsxWriter.column(701)).isEqualTo("ZZ");
        assertThat(XlsxWriter.safeText("")).isEmpty();
        assertThat(XlsxWriter.safeText("-1")).isEqualTo("'-1");
        assertThat(XlsxWriter.safeText("ok")).isEqualTo("ok");
        assertThat(XlsxWriter.xml("a\r\nb")).isEqualTo("a\r\nb");
    }
}
