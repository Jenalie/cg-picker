package com.jenalie.cgpicker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal PNG reader for unit tests (8-bit RGB / RGBA, non-interlaced).
 * Android unit tests compile against android.jar, which has no java.awt / javax.imageio.
 */
final class TestPng {
    final int width;
    final int height;
    /** 0xRRGGBB per pixel, row-major. */
    final int[] rgb;

    private TestPng(int width, int height, int[] rgb) {
        this.width = width;
        this.height = height;
        this.rgb = rgb;
    }

    int rgb(int x, int y) {
        return rgb[y * width + x];
    }

    static TestPng read(InputStream in) throws IOException {
        byte[] data = readAll(in);
        int pos = 8; // signature
        int width = 0, height = 0, colorType = -1;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (pos + 8 <= data.length) {
            int len = readInt(data, pos);
            String type = new String(data, pos + 4, 4, "US-ASCII");
            int start = pos + 8;
            if (type.equals("IHDR")) {
                width = readInt(data, start);
                height = readInt(data, start + 4);
                int bitDepth = data[start + 8] & 0xFF;
                colorType = data[start + 9] & 0xFF;
                int interlace = data[start + 12] & 0xFF;
                if (bitDepth != 8 || (colorType != 2 && colorType != 6) || interlace != 0) {
                    throw new IOException("Unsupported PNG: depth=" + bitDepth + " color=" + colorType + " interlace=" + interlace);
                }
            } else if (type.equals("IDAT")) {
                idat.write(data, start, len);
            } else if (type.equals("IEND")) {
                break;
            }
            pos = start + len + 4; // skip CRC
        }

        int bpp = colorType == 6 ? 4 : 3;
        int stride = width * bpp;
        byte[] raw = inflate(idat.toByteArray(), (stride + 1) * height);

        byte[] prev = new byte[stride];
        byte[] cur = new byte[stride];
        int[] out = new int[width * height];
        int p = 0;
        for (int y = 0; y < height; y++) {
            int filter = raw[p++] & 0xFF;
            System.arraycopy(raw, p, cur, 0, stride);
            p += stride;
            for (int i = 0; i < stride; i++) {
                int a = i >= bpp ? cur[i - bpp] & 0xFF : 0;
                int b = prev[i] & 0xFF;
                int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                int x = cur[i] & 0xFF;
                switch (filter) {
                    case 0: break;
                    case 1: x += a; break;
                    case 2: x += b; break;
                    case 3: x += (a + b) >>> 1; break;
                    case 4: x += paeth(a, b, c); break;
                    default: throw new IOException("Bad filter " + filter + " on row " + y);
                }
                cur[i] = (byte) x;
            }
            for (int xPix = 0; xPix < width; xPix++) {
                int o = xPix * bpp;
                out[y * width + xPix] = ((cur[o] & 0xFF) << 16) | ((cur[o + 1] & 0xFF) << 8) | (cur[o + 2] & 0xFF);
            }
            byte[] t = prev; prev = cur; cur = t;
        }
        return new TestPng(width, height, out);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    private static byte[] inflate(byte[] compressed, int expected) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(compressed);
        byte[] out = new byte[expected];
        int n = 0;
        try {
            while (n < expected && !inf.finished()) {
                int got = inf.inflate(out, n, expected - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        if (n != expected) throw new IOException("PNG data too short: " + n + " of " + expected);
        return out;
    }

    private static int readInt(byte[] d, int i) {
        return ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        in.close();
        return bos.toByteArray();
    }
}
