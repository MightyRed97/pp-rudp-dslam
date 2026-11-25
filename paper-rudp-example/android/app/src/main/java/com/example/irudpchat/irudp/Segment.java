package com.example.irudpchat.irudp;

import java.nio.charset.StandardCharsets;

/**
 * IRUDP wire format (paper, Sec. 4.2 and Fig. 3a), the same as server/src/irudp/segment.js.
 * All header fields are big-endian.
 * <pre>
 *  byte 0     : version (2 bits) | type (3 bits) | flags (3 bits)
 *  byte 1     : DATA: ACK frequency requested from the receiver; ACK: number of bitmap words k
 *  bytes 2-3  : receive window (segments)
 *  bytes 4-7  : connection ID (chosen by the client, carried in every segment)
 *  bytes 8-11 : DATA: sequence number (file offset / 1384); ACK: cumulative ACK (next expected)
 *  bytes 12-15: DATA: send time (microseconds, wraps at 2^32); ACK: echoed time + ACK hold time
 * </pre>
 * ACK payload: k x 8 bytes of bitmap. Bit i (byte i >> 3, bit i & 7, least significant first) reports
 * segment (cumulative ACK + 1 + i), i.e. k little-endian 64-bit words.
 */
public final class Segment {
    public static final int VERSION = 1;
    public static final int HDR = 16;
    public static final int MSS = 1384;          // data bytes per segment (1,400-byte UDP payload)
    public static final int RING = 1024;         // ring capacity = maximum span of a window
    public static final int BLOCK_SEGS = 256;    // segments per block of the resume map
    public static final int KMAX = 16;           // at most 16 bitmap words (1,024 segments) per ACK

    public static final int DATA = 0, ACK = 1, SYN = 2, SYNACK = 3, FIN = 4, FINACK = 5, RST = 6, PING = 7;
    public static final int F_RETX = 1, F_PROBE = 2;
    public static final int ROLE_CLIENT_SENDS = 0, ROLE_CLIENT_RECEIVES = 1;
    public static final int STATUS_OK = 0, STATUS_UNKNOWN_TRANSFER = 1, STATUS_BAD_HASH = 2, STATUS_BUSY = 3;

    public final int type, flags, aux, rwnd, connId;
    public final long num, ts;                   // unsigned 32-bit values
    public final byte[] buf;                     // the whole packet; the payload starts at HDR
    public final int payloadLen;

    private Segment(byte[] buf, int len) {
        this.buf = buf;
        type = (buf[0] >> 3) & 7;
        flags = buf[0] & 7;
        aux = buf[1] & 0xff;
        rwnd = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
        connId = (int) getU32(buf, 4);
        num = getU32(buf, 8);
        ts = getU32(buf, 12);
        payloadLen = len - HDR;
    }

    /** Reads the first len bytes of buf as a segment, or returns null if they are not one. */
    public static Segment parse(byte[] buf, int len) {
        if (len < HDR || ((buf[0] & 0xff) >> 6) != VERSION) return null;
        return new Segment(buf, len);
    }

    public static Segment parse(byte[] buf) {
        return parse(buf, buf.length);
    }

    /** A packet with the header filled in and room for payloadLen bytes after it. */
    public static byte[] header(int type, int flags, int aux, int rwnd, int connId, long num, long ts, int payloadLen) {
        byte[] b = new byte[HDR + payloadLen];
        b[0] = (byte) ((VERSION << 6) | ((type & 7) << 3) | (flags & 7));
        b[1] = (byte) aux;
        b[2] = (byte) (rwnd >> 8);
        b[3] = (byte) rwnd;
        putU32(b, 4, connId & 0xffffffffL);
        putU32(b, 8, num);
        putU32(b, 12, ts);
        return b;
    }

    public static int segmentsFor(long size) {
        return (int) ((size + MSS - 1) / MSS);
    }

    public static int blocksFor(long size) {
        return (segmentsFor(size) + BLOCK_SEGS - 1) / BLOCK_SEGS;
    }

    public static int bitmapBytes(int n) {
        return (n + 7) / 8;
    }

    public static int bitGet(byte[] bm, int i) {
        return (bm[i >> 3] >> (i & 7)) & 1;
    }

    public static void bitSet(byte[] bm, int i) {
        bm[i >> 3] |= (byte) (1 << (i & 7));
    }

    // ---- SYN and SYNACK payloads ---------------------------------------------------------------

    /** SYN payload: role(1) reserved(1) blockSegs(2) fileSize(8) sha1(20) transferId(16) [blockBitmap]. */
    public static byte[] synPayload(int role, long size, String sha1, String transferId, byte[] held) {
        byte[] p = new byte[48 + (held != null ? held.length : 0)];
        p[0] = (byte) role;
        p[2] = (byte) (BLOCK_SEGS >> 8);
        p[3] = (byte) BLOCK_SEGS;
        putU64(p, 4, size);
        if (sha1 != null) {
            byte[] h = fromHex(sha1);
            System.arraycopy(h, 0, p, 12, Math.min(20, h.length));
        }
        byte[] tid = transferId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tid, 0, p, 32, Math.min(16, tid.length));
        if (held != null) System.arraycopy(held, 0, p, 48, held.length);
        return p;
    }

    /** SYNACK payload: status(1) reserved(1) blockSegs(2) fileSize(8) sha1(20) [blockBitmap]. */
    public static byte[] synAckPayload(int status, long size, String sha1, byte[] held) {
        byte[] p = new byte[32 + (held != null ? held.length : 0)];
        p[0] = (byte) status;
        p[2] = (byte) (BLOCK_SEGS >> 8);
        p[3] = (byte) BLOCK_SEGS;
        putU64(p, 4, size);
        if (sha1 != null) {
            byte[] h = fromHex(sha1);
            System.arraycopy(h, 0, p, 12, Math.min(20, h.length));
        }
        if (held != null) System.arraycopy(held, 0, p, 32, held.length);
        return p;
    }

    /** The fields of a SYN or SYNACK payload. */
    public static final class Handshake {
        public int role, status, blockSegs;
        public long size;
        public String sha1, transferId;
        public byte[] held;                      // block bitmap, or null
    }

    public static Handshake parseSyn(Segment h) {
        if (h.payloadLen < 48) return null;
        byte[] b = h.buf;
        int p = HDR;
        Handshake s = new Handshake();
        s.role = b[p] & 0xff;
        s.blockSegs = ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
        s.size = getU64(b, p + 4);
        s.sha1 = toHex(b, p + 12, 20);
        int end = 0;
        while (end < 16 && b[p + 32 + end] != 0) end++;
        s.transferId = new String(b, p + 32, end, StandardCharsets.US_ASCII);
        if (h.payloadLen > 48) s.held = copy(b, p + 48, h.payloadLen - 48);
        return s;
    }

    public static Handshake parseSynAck(Segment h) {
        if (h.payloadLen < 32) return null;
        byte[] b = h.buf;
        int p = HDR;
        Handshake s = new Handshake();
        s.status = b[p] & 0xff;
        s.blockSegs = ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
        s.size = getU64(b, p + 4);
        s.sha1 = toHex(b, p + 12, 20);
        if (h.payloadLen > 32) s.held = copy(b, p + 32, h.payloadLen - 32);
        return s;
    }

    // ---- byte helpers ----------------------------------------------------------------------------

    static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static long getU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static void putU64(byte[] b, int off, long v) {     // two big-endian 32-bit halves, as segment.js writes it
        putU32(b, off, v >>> 32);
        putU32(b, off + 4, v & 0xffffffffL);
    }

    static long getU64(byte[] b, int off) {
        return (getU32(b, off) << 32) | getU32(b, off + 4);
    }

    /** Four bytes as a little-endian unsigned 32-bit value (the halves of an ACK bitmap word). */
    static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] copy(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    public static String toHex(byte[] b) {
        return toHex(b, 0, b.length);
    }

    public static String toHex(byte[] b, int off, int len) {
        final String digits = "0123456789abcdef";
        char[] c = new char[2 * len];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xff;
            c[2 * i] = digits.charAt(v >> 4);
            c[2 * i + 1] = digits.charAt(v & 15);
        }
        return new String(c);
    }

    public static byte[] fromHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
