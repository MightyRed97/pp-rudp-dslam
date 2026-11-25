package com.example.irudpchat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SIP subset of the example (RFC 3261 syntax), the same as server/src/sip.js: REGISTER, MESSAGE,
 * INVITE, ACK, BYE and responses, over UDP.
 */
final class Sip {
    static final String DOMAIN = "irudp.example";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern STATUS_LINE = Pattern.compile("^SIP/2\\.0 (\\d{3}) (.*)$");
    private static final Pattern REQUEST_LINE = Pattern.compile("^([A-Z]+) (\\S+) SIP/2\\.0$");
    private static final Pattern USER = Pattern.compile("sip:([^@>;]+)@");
    private static final Pattern CSEQ = Pattern.compile("^(\\d+)\\s+([A-Z]+)");

    private Sip() {
    }

    /** A parsed request (method != null) or response (status > 0). Header names are lower case. */
    static final class Message {
        int status;
        String reason, method, uri;
        String body = "";
        final LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();

        String header(String name) {
            List<String> v = headers.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> all(String name) {
            List<String> v = headers.get(name);
            return v == null ? Collections.<String>emptyList() : v;
        }
    }

    static String rand(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    static Message parse(byte[] data, int len) {
        int cut = -1;
        for (int i = 0; i + 3 < len; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                cut = i;
                break;
            }
        }
        String head = new String(data, 0, cut < 0 ? len : cut, StandardCharsets.UTF_8);
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return null;                // nothing but line breaks
        Message m = new Message();
        Matcher x = STATUS_LINE.matcher(lines[0]);
        if (x.matches()) {
            m.status = Integer.parseInt(x.group(1));
            m.reason = x.group(2);
        } else {
            x = REQUEST_LINE.matcher(lines[0]);
            if (!x.matches()) return null;
            m.method = x.group(1);
            m.uri = x.group(2);
        }
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String name = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT);
            List<String> list = m.headers.get(name);
            if (list == null) {
                list = new ArrayList<>();
                m.headers.put(name, list);
            }
            list.add(lines[i].substring(c + 1).trim());
        }
        int contentLength = 0;
        try {
            String cl = m.header("content-length");
            if (cl != null) contentLength = Integer.parseInt(cl);
        } catch (NumberFormatException e) {
            contentLength = 0;
        }
        if (cut >= 0) {
            int start = cut + 4;
            int n = Math.max(0, Math.min(contentLength, len - start));
            m.body = new String(data, start, n, StandardCharsets.UTF_8);
        }
        return m;
    }

    private static byte[] build(String firstLine, List<String[]> headers, String body) {
        byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(firstLine).append("\r\n");
        for (String[] h : headers) if (h[1] != null) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        sb.append("Content-Length: ").append(b.length).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + b.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(b, 0, out, head.length, b.length);
        return out;
    }

    private static String[] h(String name, String value) {
        return new String[] {name, value};
    }

    /** A request; expires and contentType may be null (header left out). */
    static byte[] request(String method, String uri, String via, String from, String to, String callId, int cseq,
                          String contact, String contentType, String expires, String body) {
        List<String[]> headers = new ArrayList<>();
        headers.add(h("Via", via));
        headers.add(h("Max-Forwards", "70"));
        headers.add(h("From", from));
        headers.add(h("To", to));
        headers.add(h("Call-ID", callId));
        headers.add(h("CSeq", cseq + " " + method));
        headers.add(h("Contact", contact));
        headers.add(h("Expires", expires));
        headers.add(h("Content-Type", contentType));
        return build(method + " " + uri + " SIP/2.0", headers, body);
    }

    /** A response to req: copies Via, From, To (with a tag for final responses), Call-ID and CSeq. */
    static byte[] response(Message req, int status, String reason, String contentType, String body) {
        String to = req.header("to");
        if (to != null && !to.contains(";tag=") && status >= 200) to += ";tag=" + rand(4);
        List<String[]> headers = new ArrayList<>();
        for (String v : req.all("via")) headers.add(h("Via", v));
        headers.add(h("From", req.header("from")));
        headers.add(h("To", to));
        headers.add(h("Call-ID", req.header("call-id")));
        headers.add(h("CSeq", req.header("cseq")));
        headers.add(h("Content-Type", contentType));
        return build("SIP/2.0 " + status + " " + reason, headers, body);
    }

    /** The user part of a SIP URI in a header such as "&lt;sip:alice@irudp.example&gt;;tag=1", or null. */
    static String userOf(String header) {
        if (header == null) return null;
        Matcher m = USER.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    /** The number in a CSeq header, or 1. */
    static int cseqNumber(Message msg) {
        String v = msg.header("cseq");
        if (v == null) return 1;
        Matcher m = CSEQ.matcher(v);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
