package com.crisil.covenant.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal Word (.docx) writer for plain letters: headings and paragraphs, nothing else. Builds the
 * three parts Word needs (content types, package relationships, the document) so no library is
 * required. Text is XML-escaped.
 */
public final class DocxWriter {

    private final List<String> body = new ArrayList<>();

    public DocxWriter heading(String text) {
        body.add("<w:p><w:pPr><w:spacing w:after=\"200\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr>"
                + run(text) + "</w:r></w:p>");
        return this;
    }

    public DocxWriter paragraph(String text) {
        body.add("<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:r>" + run(text) + "</w:r></w:p>");
        return this;
    }

    public DocxWriter bold(String label, String text) {
        body.add("<w:p><w:r><w:rPr><w:b/></w:rPr>" + run(label) + "</w:r><w:r>" + run(text) + "</w:r></w:p>");
        return this;
    }

    public DocxWriter blank() {
        body.add("<w:p/>");
        return this;
    }

    private static String run(String text) {
        return "<w:t xml:space=\"preserve\">" + escape(text == null ? "" : text) + "</w:t>";
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    public byte[] toBytes() {
        String doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + String.join("", body)
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" "
                + "w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", types);
            put(zip, "_rels/.rels", rels);
            put(zip, "word/document.xml", doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
