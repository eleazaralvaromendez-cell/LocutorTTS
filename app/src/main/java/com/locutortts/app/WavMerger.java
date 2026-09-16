package com.locutortts.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class WavMerger {
    private WavMerger() {}

    public static void merge(List<File> parts, File output) throws Exception {
        if (parts == null || parts.isEmpty()) throw new Exception("No hay fragmentos de audio.");

        WavInfo first = readInfo(parts.get(0));
        long totalData = 0;
        for (File f : parts) {
            WavInfo info = readInfo(f);
            if (!sameFormat(first.fmt, info.fmt)) throw new Exception("El motor TTS generó fragmentos incompatibles.");
            totalData += info.dataLength;
        }
        if (totalData > 0xFFFFFFFFL - 100) throw new Exception("El audio es demasiado grande para WAV estándar.");

        try (FileOutputStream out = new FileOutputStream(output)) {
            int fmtPad = first.fmt.length & 1;
            long riffSize = 4L + 8L + first.fmt.length + fmtPad + 8L + totalData;
            writeAscii(out, "RIFF"); writeLE32(out, riffSize); writeAscii(out, "WAVE");
            writeAscii(out, "fmt "); writeLE32(out, first.fmt.length); out.write(first.fmt); if (fmtPad != 0) out.write(0);
            writeAscii(out, "data"); writeLE32(out, totalData);

            byte[] buffer = new byte[32768];
            for (File f : parts) {
                WavInfo info = readInfo(f);
                try (FileInputStream in = new FileInputStream(f)) {
                    long skipped = 0;
                    while (skipped < info.dataOffset) {
                        long s = in.skip(info.dataOffset - skipped);
                        if (s <= 0) throw new Exception("No se pudo leer un fragmento WAV.");
                        skipped += s;
                    }
                    long remaining = info.dataLength;
                    while (remaining > 0) {
                        int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (n < 0) break;
                        out.write(buffer, 0, n);
                        remaining -= n;
                    }
                }
            }
        }
    }

    private static WavInfo readInfo(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 12) throw new Exception("El motor TTS no produjo un WAV válido.");
            if (!"RIFF".equals(readAscii(raf, 4))) throw new Exception("El motor TTS no produjo WAV/RIFF.");
            readLE32(raf);
            if (!"WAVE".equals(readAscii(raf, 4))) throw new Exception("Archivo WAV inválido.");

            byte[] fmt = null;
            long dataOffset = -1, dataLength = -1;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                String id = readAscii(raf, 4);
                long len = readLE32(raf);
                long chunkStart = raf.getFilePointer();
                if ("fmt ".equals(id)) {
                    if (len > 1024) throw new Exception("Formato WAV no compatible.");
                    fmt = new byte[(int)len];
                    raf.readFully(fmt);
                } else if ("data".equals(id)) {
                    dataOffset = raf.getFilePointer();
                    dataLength = len;
                    break;
                }
                long next = chunkStart + len + (len & 1);
                if (next > raf.length()) break;
                raf.seek(next);
            }
            if (fmt == null || dataOffset < 0 || dataLength < 0) throw new Exception("No encontré audio PCM dentro del WAV.");
            return new WavInfo(fmt, dataOffset, dataLength);
        }
    }

    private static boolean sameFormat(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static String readAscii(RandomAccessFile raf, int n) throws Exception {
        byte[] b = new byte[n]; raf.readFully(b); return new String(b, StandardCharsets.US_ASCII);
    }

    private static long readLE32(RandomAccessFile raf) throws Exception {
        long b0 = raf.readUnsignedByte(), b1 = raf.readUnsignedByte(), b2 = raf.readUnsignedByte(), b3 = raf.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeAscii(FileOutputStream out, String s) throws Exception { out.write(s.getBytes(StandardCharsets.US_ASCII)); }
    private static void writeLE32(FileOutputStream out, long v) throws Exception {
        out.write((int)(v & 0xFF)); out.write((int)((v >> 8) & 0xFF)); out.write((int)((v >> 16) & 0xFF)); out.write((int)((v >> 24) & 0xFF));
    }

    private static final class WavInfo {
        final byte[] fmt; final long dataOffset; final long dataLength;
        WavInfo(byte[] fmt, long dataOffset, long dataLength) { this.fmt = fmt; this.dataOffset = dataOffset; this.dataLength = dataLength; }
    }
}
