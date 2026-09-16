package com.locutortts.app;

import android.content.ContentResolver;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DocumentReader {
    private DocumentReader() {}

    public static String read(ContentResolver resolver, Uri uri, String name, String mime) throws Exception {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) return clean(readPdf(resolver, uri));
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime) || lower.endsWith(".docx")) return clean(readDocx(resolver, uri));
        return clean(readTxt(resolver, uri));
    }

    private static String readTxt(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n;
            while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readPdf(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri); PDDocument doc = PDDocument.load(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String readDocx(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream base = resolver.openInputStream(uri); ZipInputStream zip = new ZipInputStream(base)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(e.getName())) continue;
                XmlPullParserFactory f = XmlPullParserFactory.newInstance(); f.setNamespaceAware(true);
                XmlPullParser p = f.newPullParser(); p.setInput(zip, "UTF-8");
                StringBuilder out = new StringBuilder();
                int event;
                while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        if ("t".equals(p.getName())) out.append(p.nextText());
                        else if ("tab".equals(p.getName())) out.append('\t');
                        else if ("br".equals(p.getName())) out.append('\n');
                    } else if (event == XmlPullParser.END_TAG && "p".equals(p.getName())) out.append('\n');
                }
                return out.toString();
            }
        }
        return "";
    }

    public static String clean(String s) {
        if (s == null) return "";
        return s.replace("\u00AD", "")
                .replaceAll("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{L})", "")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
