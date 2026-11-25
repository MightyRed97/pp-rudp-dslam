package com.example.irudpchat.irudp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The Java packets must be byte for byte those of the Node server (wire-vectors.txt, made by server/test/unit-test.js). */
public class SegmentTest {
    private static final String SHA1 = "00112233445566778899aabbccddeeff00112233";

    private static Map<String, String> vectors() throws IOException {
        Map<String, String> v = new HashMap<>();
        InputStream in = SegmentTest.class.getResourceAsStream("/wire-vectors.txt");
        assertNotNull("wire-vectors.txt is missing from src/test/resources", in);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(" ");
                v.put(p[0], p[1]);
            }
        }
        return v;
    }

    private static void putLongLE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    @Test
    public void packetsMatchTheNodeServer() throws IOException {
        Map<String, String> v = vectors();

        byte[] data = Segment.header(Segment.DATA, Segment.F_RETX, 8, 1024, 0xdeadbeef, 123456, 4000000000L, 3);
        data[16] = 1;
        data[17] = 2;
        data[18] = 3;
        assertEquals(v.get("data"), Segment.toHex(data));

        byte[] ack = Segment.header(Segment.ACK, 0, 2, 1024, 0x01020304, 10, 77, 16);
        putLongLE(ack, 16, 0x1ffffffeL);
        putLongLE(ack, 24, 0x8000000000000001L);
        assertEquals(v.get("ack"), Segment.toHex(ack));

        byte[] sp = Segment.synPayload(Segment.ROLE_CLIENT_RECEIVES, 5000000000L, SHA1, "k3T9qwab", new byte[] {0x05, (byte) 0x80});
        byte[] syn = Segment.header(Segment.SYN, 0, 0, 1024, 42, 0, 1000, sp.length);
        System.arraycopy(sp, 0, syn, Segment.HDR, sp.length);
        assertEquals(v.get("syn"), Segment.toHex(syn));

        byte[] ap = Segment.synAckPayload(Segment.STATUS_OK, 5000000000L, SHA1, new byte[] {(byte) 0xff, 0x01});
        byte[] synAck = Segment.header(Segment.SYNACK, 0, 0, 1024, 42, 0, 1000, ap.length);
        System.arraycopy(ap, 0, synAck, Segment.HDR, ap.length);
        assertEquals(v.get("synack"), Segment.toHex(synAck));

        byte[] finAck = Segment.header(Segment.FINACK, 0, 0, 0, 42, 3613, 5, 1);
        finAck[Segment.HDR] = Segment.STATUS_BAD_HASH;
        assertEquals(v.get("finack"), Segment.toHex(finAck));
    }

    @Test
    public void nodePacketsParseToTheirFields() throws IOException {
        Map<String, String> v = vectors();

        Segment d = Segment.parse(Segment.fromHex(v.get("data")));
        assertNotNull(d);
        assertEquals(Segment.DATA, d.type);
        assertEquals(Segment.F_RETX, d.flags);
        assertEquals(8, d.aux);
        assertEquals(1024, d.rwnd);
        assertEquals(0xdeadbeef, d.connId);
        assertEquals(123456L, d.num);
        assertEquals(4000000000L, d.ts);
        assertEquals(3, d.payloadLen);

        Segment s = Segment.parse(Segment.fromHex(v.get("syn")));
        assertNotNull(s);
        Segment.Handshake syn = Segment.parseSyn(s);
        assertNotNull(syn);
        assertEquals(Segment.ROLE_CLIENT_RECEIVES, syn.role);
        assertEquals(256, syn.blockSegs);
        assertEquals(5000000000L, syn.size);
        assertEquals(SHA1, syn.sha1);
        assertEquals("k3T9qwab", syn.transferId);
        assertArrayEquals(new byte[] {0x05, (byte) 0x80}, syn.held);

        Segment a = Segment.parse(Segment.fromHex(v.get("synack")));
        assertNotNull(a);
        Segment.Handshake synAck = Segment.parseSynAck(a);
        assertNotNull(synAck);
        assertEquals(Segment.STATUS_OK, synAck.status);
        assertEquals(5000000000L, synAck.size);
        assertArrayEquals(new byte[] {(byte) 0xff, 0x01}, synAck.held);
    }

    @Test
    public void sizesAndBitmaps() {
        assertEquals(0, Segment.segmentsFor(0));
        assertEquals(1, Segment.segmentsFor(1));
        assertEquals(1, Segment.segmentsFor(1384));
        assertEquals(2, Segment.segmentsFor(1385));
        assertEquals(3031, Segment.segmentsFor(4L * 1024 * 1024));
        assertEquals(12, Segment.blocksFor(4L * 1024 * 1024));
        byte[] bm = new byte[2];
        Segment.bitSet(bm, 0);
        Segment.bitSet(bm, 15);
        assertEquals(1, Segment.bitGet(bm, 0));
        assertEquals(0, Segment.bitGet(bm, 1));
        assertEquals(1, Segment.bitGet(bm, 15));
        assertArrayEquals(new byte[] {0x01, (byte) 0x80}, bm);
    }
}
