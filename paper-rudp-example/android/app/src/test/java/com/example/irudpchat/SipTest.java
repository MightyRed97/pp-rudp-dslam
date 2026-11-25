package com.example.irudpchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** SIP and SDP as the Node relay writes them (the INVITE below was printed by server/src/sip.js). */
public class SipTest {
    private static final String INVITE = "INVITE sip:bob@10.0.2.15:40000 SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP 10.0.2.2:5070;branch=z9hG4bK1234\r\n"
            + "Max-Forwards: 70\r\n"
            + "From: <sip:alice@irudp.example>;tag=ab12\r\n"
            + "To: <sip:bob@irudp.example>\r\n"
            + "Call-ID: c0ffee@relay\r\n"
            + "CSeq: 1 INVITE\r\n"
            + "Contact: <sip:relay@10.0.2.2:5070>\r\n"
            + "Content-Type: application/sdp\r\n"
            + "Content-Length: 354\r\n"
            + "\r\n"
            + "v=0\r\n"
            + "o=relay 1700000000000 1 IN IP4 10.0.2.2\r\n"
            + "s=-\r\n"
            + "c=IN IP4 10.0.2.2\r\n"
            + "t=0 0\r\n"
            + "m=application 5080 UDP/IRUDP *\r\n"
            + "a=setup:passive\r\n"
            + "a=sendonly\r\n"
            + "a=file-selector:name:\"site photo 1.jpg\" type:image/jpeg size:4194304 "
            + "hash:sha-1:0A:1B:2C:3D:4E:5F:60:71:82:93:A4:B5:C6:D7:E8:F9:01:23:45:67\r\n"
            + "a=file-transfer-id:f3a9c2d18b7e6054\r\n"
            + "a=irudp:mss=1384;ring=1024;block=256\r\n";

    private static Sip.Message parse(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return Sip.parse(b, b.length);
    }

    @Test
    public void parsesTheInviteOfTheRelay() {
        Sip.Message m = parse(INVITE);
        assertNotNull(m);
        assertEquals("INVITE", m.method);
        assertEquals("sip:bob@10.0.2.15:40000", m.uri);
        assertEquals("c0ffee@relay", m.header("call-id"));
        assertEquals("alice", Sip.userOf(m.header("from")));
        assertEquals(1, Sip.cseqNumber(m));
        assertEquals(354, m.body.getBytes(StandardCharsets.UTF_8).length);
        Sdp.Offer o = Sdp.parse(m.body);
        assertEquals(5080, o.port);
        assertEquals("UDP/IRUDP", o.proto);
        assertEquals("passive", o.setup);
        assertEquals("sendonly", o.direction);
        assertEquals("site photo 1.jpg", o.name);
        assertEquals("image/jpeg", o.type);
        assertEquals(4194304L, o.size);
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f901234567", o.sha1);
        assertEquals("f3a9c2d18b7e6054", o.transferId);
    }

    @Test
    public void aResponseCopiesTheTransactionHeaders() {
        Sip.Message req = parse(INVITE);
        byte[] b = Sip.response(req, 200, "OK", "application/sdp", "v=0\r\n");
        Sip.Message m = Sip.parse(b, b.length);
        assertNotNull(m);
        assertEquals(200, m.status);
        assertEquals("OK", m.reason);
        assertEquals("SIP/2.0/UDP 10.0.2.2:5070;branch=z9hG4bK1234", m.header("via"));
        assertEquals("<sip:alice@irudp.example>;tag=ab12", m.header("from"));
        assertTrue(m.header("to").startsWith("<sip:bob@irudp.example>;tag="));
        assertEquals("c0ffee@relay", m.header("call-id"));
        assertEquals("1 INVITE", m.header("cseq"));
        assertEquals("application/sdp", m.header("content-type"));
        assertEquals("v=0\r\n", m.body);
    }

    @Test
    public void theBodyEndsAtContentLength() {
        Sip.Message m = parse("MESSAGE sip:bob@irudp.example SIP/2.0\r\nCall-ID: x\r\nContent-Length: 5\r\n\r\nhello and more");
        assertNotNull(m);
        assertEquals("hello", m.body);
    }

    @Test
    public void sdpRoundTrip() {
        String sdp = Sdp.build("bob", "0.0.0.0", 9, "active", "recvonly", "a\"b.txt", "text/plain", 0,
                "00112233445566778899aabbccddeeff00112233", "t1");
        Sdp.Offer o = Sdp.parse(sdp);
        assertEquals(9, o.port);
        assertEquals("active", o.setup);
        assertEquals("recvonly", o.direction);
        assertEquals("ab.txt", o.name);
        assertEquals(0L, o.size);
        assertEquals("00112233445566778899aabbccddeeff00112233", o.sha1);
        assertEquals("t1", o.transferId);
        assertTrue(sdp.contains(" hash:sha-1:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33\r\n"));
        assertTrue(sdp.endsWith("a=irudp:mss=1384;ring=1024;block=256\r\n"));
    }

    @Test
    public void aReceivedFileNameCannotLeaveTheDownloadFolder() {
        assertEquals("passwd", ChatClient.safeName("../../etc/passwd"));
        assertEquals("win.ini", ChatClient.safeName("..\\..\\Windows\\win.ini"));
        assertEquals("file", ChatClient.safeName(".."));
        assertEquals("a__b.txt", ChatClient.safeName("a\r\nb.txt"));
        assertEquals("photo 1.jpg", ChatClient.safeName("photo 1.jpg"));
        assertEquals("image/jpeg", ChatClient.cleanMime("image/jpeg; q=1"));
        assertEquals("application/octet-stream", ChatClient.cleanMime("not a type"));
    }
}
