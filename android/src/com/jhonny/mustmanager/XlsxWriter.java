package com.jhonny.mustmanager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escritor mínimo de Excel (.xlsx) sin librerías: un .xlsx es un zip con unos XML dentro.
 * Las hojas se escriben fila a fila (streaming), así que sirve para cientos de miles de filas.
 * Tipos de celda: número, texto y fecha real de Excel (fecha y hora, fecha sola u hora sola).
 */
final class XlsxWriter {

    /**
     * Marca para que una celda se escriba como fecha/hora real de Excel (ms epoch). Según el tipo se guarda
     * el día (dd/mm/aaaa), la hora (hh:mm:ss o hh:mm) o ambos; Excel puede ordenar y filtrar por ellas.
     */
    static final class Date {
        static final int DATETIME = 1, DAY = 3, TIME = 4, HOUR_MIN = 5; // = índice del estilo en styles.xml
        final long ms;
        final int kind;

        Date(long ms) {
            this(ms, DATETIME);
        }

        Date(long ms, int kind) {
            this.ms = ms;
            this.kind = kind;
        }

        static Date day(long ms) {
            return new Date(ms, DAY);
        }

        static Date time(long ms) {
            return new Date(ms, TIME);
        }

        static Date hourMin(long ms) {
            return new Date(ms, HOUR_MIN);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final ZipOutputStream zip;
    private final Writer w;
    private final List<String> sheetNames = new ArrayList<String>();
    private final TimeZone tz = TimeZone.getDefault();
    private boolean inSheet;

    XlsxWriter(OutputStream out) {
        zip = new ZipOutputStream(out);
        w = new OutputStreamWriter(zip, UTF8);
    }

    /** Empieza una hoja con su fila de cabecera (en negrita, fija al hacer scroll) y anchos de columna. */
    void startSheet(String name, String[] header, int[] widths) throws IOException {
        if (inSheet) endSheet();
        sheetNames.add(name);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + sheetNames.size() + ".xml"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
            + "</sheetView></sheetViews><cols>");
        for (int i = 0; i < header.length; i++) {
            int width = widths != null && i < widths.length ? widths[i] : 12;
            w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + width + "\" customWidth=\"1\"/>");
        }
        w.write("</cols><sheetData><row>");
        for (String h : header) {
            w.write("<c t=\"inlineStr\" s=\"2\"><is><t>");
            w.write(esc(h));
            w.write("</t></is></c>");
        }
        w.write("</row>");
        inSheet = true;
    }

    /** Una fila: Number, String, XlsxWriter.Date o null (celda vacía). */
    void row(Object... cells) throws IOException {
        w.write("<row>");
        for (Object c : cells) {
            if (c == null) {
                w.write("<c/>");
            } else if (c instanceof Date) {
                Date d = (Date) c;
                double serial = (d.ms + tz.getOffset(d.ms)) / 86400000.0 + 25569.0; // días desde 1900 (Excel)
                // Fecha sola: el día entero. Hora sola: solo la fracción del día.
                if (d.kind == Date.DAY) serial = Math.floor(serial);
                else if (d.kind == Date.TIME || d.kind == Date.HOUR_MIN) serial = serial - Math.floor(serial);
                w.write("<c s=\"" + d.kind + "\"><v>" + serial + "</v></c>");
            } else if (c instanceof Number) {
                double d = ((Number) c).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) w.write("<c/>");
                else w.write("<c><v>" + num(d) + "</v></c>");
            } else {
                w.write("<c t=\"inlineStr\"><is><t>" + esc(c.toString()) + "</t></is></c>");
            }
        }
        w.write("</row>");
    }

    private void endSheet() throws IOException {
        w.write("</sheetData></worksheet>");
        w.flush();
        zip.closeEntry();
        inSheet = false;
    }

    void finish() throws IOException {
        if (inSheet) endSheet();
        put("[Content_Types].xml", contentTypes());
        put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheetNames.size(); i++) {
            int n = i + 1;
            wb.append("<sheet name=\"").append(esc(sheetNames.get(i))).append("\" sheetId=\"").append(n)
                .append("\" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(n).append(".xml\"/>");
        }
        int stylesId = sheetNames.size() + 1;
        rels.append("<Relationship Id=\"rId").append(stylesId)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        put("xl/workbook.xml", wb.append("</sheets></workbook>").toString());
        put("xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"4\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd hh:mm:ss\"/>"
            + "<numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy\"/><numFmt numFmtId=\"166\" formatCode=\"hh:mm:ss\"/>"
            + "<numFmt numFmtId=\"167\" formatCode=\"hh:mm\"/></numFmts>"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            // Estilos: 0 normal · 1 fecha y hora · 2 cabecera en negrita · 3 fecha · 4 hora · 5 hora sin segundos
            + "<cellXfs count=\"6\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"167\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
            + "</styleSheet>");
        w.flush();
        zip.finish();
    }

    private String contentTypes() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheetNames.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private void put(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        w.write(content);
        w.flush();
        zip.closeEntry();
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n') sb.append(c);
            }
        }
        return sb.toString();
    }
}
