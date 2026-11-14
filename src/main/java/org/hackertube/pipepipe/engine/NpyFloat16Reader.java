package org.hackertube.pipepipe.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java reader and vector math helper for NumPy {@code .npy} float16 matrices
 * ({@code embeddings.npy}, shape {@code (N, 384)}).
 */
public final class NpyFloat16Reader {

    private static final int MAGIC_LEN = 6;
    private static final int CACHE_MAGIC = 0x48544531; // "HTE1"
    private static final Pattern SHAPE_PATTERN = Pattern.compile("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern DESCR_PATTERN = Pattern.compile("'descr'\\s*:\\s*'([^']+)'");

    private static final float[] HALF_TO_FLOAT = buildHalfToFloatTable();

    public final int rows;
    public final int dims;
    private final short[] rawHalf;

    public NpyFloat16Reader(final int rows, final int dims, final short[] rawHalf) {
        if (rows < 0 || dims <= 0 || rawHalf.length != rows * dims) {
            throw new IllegalArgumentException("Invalid matrix dimensions");
        }
        this.rows = rows;
        this.dims = dims;
        this.rawHalf = rawHalf;
    }

    public static NpyFloat16Reader readNpy(final InputStream in) throws IOException {
        final BufferedInputStream bis = new BufferedInputStream(in, 65536);
        final byte[] magic = readExact(bis, MAGIC_LEN);
        if (magic[0] != (byte) 0x93 || magic[1] != 'N' || magic[2] != 'U'
                || magic[3] != 'M' || magic[4] != 'P' || magic[5] != 'Y') {
            throw new IOException("Not a valid .npy file");
        }
        final int major = bis.read();
        final int minor = bis.read();
        if (major < 1 || major > 3 || minor < 0) {
            throw new IOException("Unsupported .npy version " + major + "." + minor);
        }
        final int headerLen;
        if (major == 1) {
            final byte[] lenBytes = readExact(bis, 2);
            headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        } else {
            final byte[] lenBytes = readExact(bis, 4);
            headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        if (headerLen <= 0 || headerLen > 65536) {
            throw new IOException("Invalid .npy header length: " + headerLen);
        }
        final String header = new String(readExact(bis, headerLen), StandardCharsets.US_ASCII);
        final Matcher descrMatcher = DESCR_PATTERN.matcher(header);
        if (!descrMatcher.find() || !"<f2".equals(descrMatcher.group(1))) {
            throw new IOException("Expected little-endian float16 (<f2) in .npy header: " + header);
        }
        final Matcher shapeMatcher = SHAPE_PATTERN.matcher(header);
        if (!shapeMatcher.find()) {
            throw new IOException("Expected 2D shape in .npy header: " + header);
        }
        final int rows = Integer.parseInt(shapeMatcher.group(1));
        final int dims = Integer.parseInt(shapeMatcher.group(2));
        final int total = rows * dims;
        final short[] data = new short[total];
        final byte[] rowBuf = new byte[dims * 2];
        final ByteBuffer bb = ByteBuffer.wrap(rowBuf).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < rows; r++) {
            readExactInto(bis, rowBuf);
            bb.rewind();
            final int base = r * dims;
            for (int c = 0; c < dims; c++) {
                data[base + c] = bb.getShort();
            }
        }
        return new NpyFloat16Reader(rows, dims, data);
    }

    public void saveBinaryCache(final File file) throws IOException {
        final File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp), 65536))) {
            out.writeInt(CACHE_MAGIC);
            out.writeInt(rows);
            out.writeInt(dims);
            for (final short h : rawHalf) {
                out.writeShort(h);
            }
        }
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("Failed to rename " + tmp + " -> " + file);
        }
    }

    public static NpyFloat16Reader loadBinaryCache(final File file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file), 65536))) {
            final int magic = in.readInt();
            if (magic != CACHE_MAGIC) {
                throw new IOException("Unexpected cache header");
            }
            final int rows = in.readInt();
            final int dims = in.readInt();
            final short[] raw = new short[rows * dims];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = in.readShort();
            }
            return new NpyFloat16Reader(rows, dims, raw);
        }
    }

    public static float halfToFloat(final short bits) {
        return HALF_TO_FLOAT[bits & 0xFFFF];
    }

    public float[] getRowAsFloat(final int rowIdx) {
        final float[] vec = new float[dims];
        final int base = rowIdx * dims;
        for (int d = 0; d < dims; d++) {
            vec[d] = HALF_TO_FLOAT[rawHalf[base + d] & 0xFFFF];
        }
        return vec;
    }

    public void addScaledRow(final int rowIdx, final float scale, final float[] acc) {
        final int base = rowIdx * dims;
        for (int d = 0; d < dims; d++) {
            acc[d] += scale * HALF_TO_FLOAT[rawHalf[base + d] & 0xFFFF];
        }
    }

    public float dotRow(final int rowIdx, final float[] unitVec) {
        float sum = 0f;
        final int base = rowIdx * dims;
        for (int d = 0; d < dims; d++) {
            sum += HALF_TO_FLOAT[rawHalf[base + d] & 0xFFFF] * unitVec[d];
        }
        return sum;
    }

    public float[] buildTasteVector(final Collection<Integer> likeRows,
                                    final Collection<Integer> dislikeRows) {
        final float[] taste = new float[dims];
        int weight = 0;
        if (likeRows != null && !likeRows.isEmpty()) {
            final float inv = 1.0f / likeRows.size();
            for (final int r : likeRows) {
                addScaledRow(r, inv, taste);
                weight++;
            }
        }
        if (dislikeRows != null && !dislikeRows.isEmpty()) {
            final float scale = -0.45f / dislikeRows.size();
            for (final int r : dislikeRows) {
                addScaledRow(r, scale, taste);
                weight++;
            }
        }
        if (weight == 0) {
            return taste;
        }
        normalizeInPlace(taste);
        return taste;
    }

    public static void normalizeInPlace(final float[] vec) {
        double sq = 0.0;
        for (final float v : vec) {
            sq += (double) v * (double) v;
        }
        if (sq > 1e-12) {
            final float inv = (float) (1.0 / Math.sqrt(sq));
            for (int i = 0; i < vec.length; i++) {
                vec[i] *= inv;
            }
        }
    }

    private static float[] buildHalfToFloatTable() {
        final float[] table = new float[65536];
        for (int i = 0; i < 65536; i++) {
            table[i] = decodeHalfBits(i);
        }
        return table;
    }

    private static float decodeHalfBits(final int h) {
        final int sign = (h & 0x8000) << 16;
        final int exp = (h >>> 10) & 0x1F;
        int mant = h & 0x03FF;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            int e = -1;
            do {
                e++;
                mant <<= 1;
            } while ((mant & 0x0400) == 0);
            mant &= 0x03FF;
            final int fExp = (127 - 15 - e) << 23;
            return Float.intBitsToFloat(sign | fExp | (mant << 13));
        } else if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        }
        final int fExp = (exp - 15 + 127) << 23;
        return Float.intBitsToFloat(sign | fExp | (mant << 13));
    }

    private static byte[] readExact(final InputStream in, final int len) throws IOException {
        final byte[] buf = new byte[len];
        readExactInto(in, buf);
        return buf;
    }

    private static void readExactInto(final InputStream in, final byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            final int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading .npy");
            }
            off += n;
        }
    }
}
