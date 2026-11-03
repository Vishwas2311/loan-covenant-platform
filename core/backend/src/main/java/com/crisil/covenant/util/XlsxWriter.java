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
 * Minimal single-sheet .xlsx writer (FR-24 exports) with no third-party dependency. Numbers are written
 * as numeric cells, everything else as inline strings; the first row is the header and is bold.
 * Text that a spreadsheet would read as a formula (=, +, -, @) is prefixed with an apostrophe so an
 * exported value can never execute (CSV/formula injection).
 */
public final class XlsxWriter {

    private final String sheetName;
    private final List<List<Object>> rows = new ArrayList<>();

    public XlsxWriter(String sheetName) {
        this.sheetName = sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName;
    }

    public XlsxWriter row(List<?> cells) {
        rows.add(new ArrayList<>(cells));
        return this;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"" + xml(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
                    + "<borders count=\"1\"><border/></borders>"
                    + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf fontId=\"0\"/><xf fontId=\"1\" applyFont=\"1\"/></cellXfs>"
                    + "</styleSheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private String sheet() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<row r=\"").append(r + 1).append("\">");
            List<Object> cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++) {
                Object v = cells.get(c);
                if (v == null) {
                    continue;
                }
                String ref = column(c) + (r + 1);
                String style = r == 0 ? " s=\"1\"" : "";
                if (v instanceof Number n) {
                    sb.append("<c r=\"").append(ref).append('"').append(style).append("><v>")
                            .append(n).append("</v></c>");
                } else {
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style).append("><is><t>")
                            .append(xml(safeText(String.valueOf(v)))).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index + 1;
        while (i > 0) {
            int rem = (i - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            i = (i - 1) / 26;
        }
        return sb.toString();
    }

    static String safeText(String s) {
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }

    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
