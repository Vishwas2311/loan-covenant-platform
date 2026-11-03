package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** C12: the Word writer escapes text and builds the three parts Word needs. */
class DocxWriterTest {

    @Test
    void escapesMarkupAndDropsControlCharacters() {
        assertThat(DocxWriter.escape("a & b < c > d \"e\"\tf\u0001g")).isEqualTo("a &amp; b &lt; c &gt; d &quot;e&quot;\tfg");
    }

    @Test
    void writesAWordPackage() throws Exception {
        byte[] docx = new DocxWriter().heading("Title").paragraph(null).bold("Label: ", "value").blank().toBytes();
        List<String> names = new ArrayList<>();
        String document = "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                names.add(e.getName());
                if (e.getName().equals("word/document.xml")) {
                    document = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(names).containsExactly("[Content_Types].xml", "_rels/.rels", "word/document.xml");
        assertThat(document).contains("Title", "Label: ", "value", "<w:p/>");
    }
}
