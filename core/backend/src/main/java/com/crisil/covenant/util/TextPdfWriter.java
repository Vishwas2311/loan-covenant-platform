package com.crisil.covenant.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal text-only PDF (FR-32 management report) with no third-party dependency: A4 landscape pages of
 * monospaced lines, a bold title on the first page, automatic page breaks. Characters outside printable
 * ASCII are replaced with '?' because the built-in Courier font has no other glyphs.
 */
public final class TextPdfWriter {

    static final int LINES_PER_PAGE = 46;
    static final int MAX_COLUMNS = 130;

    private final String title;
    private final List<String> lines = new ArrayList<>();

    public TextPdfWriter(String title) {
        this.title = title;
    }

    public TextPdfWriter line(String text) {
        String s = text == null ? "" : text;
        lines.add(s.length() > MAX_COLUMNS ? s.substring(0, MAX_COLUMNS - 1) + "~" : s);
        return this;
    }

    public byte[] toBytes() {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(1, lines.size()); i += LINES_PER_PAGE) {
            pages.add(lines.subList(Math.min(i, lines.size()), Math.min(i + LINES_PER_PAGE, lines.size())));
        }
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        int firstPageObj = 5;
        for (int p = 0; p < pages.size(); p++) {
            kids.append(firstPageObj + p * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");
        for (int p = 0; p < pages.size(); p++) {
            int contentObj = firstPageObj + p * 2 + 1;
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 3 0 R "
                    + "/F2 4 0 R >> >> /Contents " + contentObj + " 0 R >>");
            String stream = content(pages.get(p), p == 0, p + 1, pages.size());
            objects.add("<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n"
                    + stream + "\nendstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            // PDF xref entries are exactly 20 bytes ending in a literal LF, whatever the platform.
            String digits = Integer.toString(off);
            sb.append("0".repeat(10 - digits.length())).append(digits).append(" 00000 n \n");
        }
        sb.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        write(out, sb.toString());
        return out.toByteArray();
    }

    private String content(List<String> pageLines, boolean first, int page, int pages) {
        StringBuilder sb = new StringBuilder();
        int y = 555;
        if (first) {
            sb.append("BT /F2 14 Tf 40 ").append(y).append(" Td (").append(escape(title)).append(") Tj ET\n");
            y -= 24;
        }
        sb.append("BT /F1 8 Tf 40 ").append(y).append(" Td 11 TL\n");
        for (String l : pageLines) {
            sb.append('(').append(escape(l)).append(") Tj T*\n");
        }
        sb.append("ET\nBT /F1 7 Tf 760 20 Td (Page ").append(page).append(" of ").append(pages).append(") Tj ET");
        return sb.toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 32 && c < 127) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }
}
