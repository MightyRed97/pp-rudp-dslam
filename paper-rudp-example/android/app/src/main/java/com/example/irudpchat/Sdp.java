package com.example.irudpchat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SDP (RFC 4566) with the file transfer attributes of RFC 5547 and a private irudp attribute, the same
 * as buildSdp and parseSdp in server/src/sip.js.
 */
final class Sdp {
    private static final Pattern MEDIA = Pattern.compile("^m=application (\\d+) (\\S+)");
    private static final Pattern SETUP = Pattern.compile("^a=setup:(\\w+)");
    private static final Pattern DIRECTION = Pattern.compile("^a=(sendonly|recvonly|sendrecv)$");
    private static final Pattern TRANSFER_ID = Pattern.compile("^a=file-transfer-id:(\\S+)");
    private static final Pattern NAME = Pattern.compile("name:\"([^\"]*)\"");
    private static final Pattern TYPE = Pattern.compile("type:(\\S+)");
    private static final Pattern SIZE = Pattern.compile("size:(\\d+)");
    private static final Pattern HASH = Pattern.compile("hash:sha-1:([0-9A-Fa-f:]+)");

    private Sdp() {
    }

    /** The fields of a file offer or answer. */
    static final class Offer {
        int port;
        long size;
        String proto, setup, direction, transferId;
        String name = "file", type = "application/octet-stream", sha1 = "";
    }

    static String build(String user, String ip, int port, String setup, String direction, String name, String type,
                        long size, String sha1, String transferId) {
        String[] lines = {
            "v=0", "o=" + user + " " + System.currentTimeMillis() + " 1 IN IP4 " + ip, "s=-", "c=IN IP4 " + ip, "t=0 0",
            "m=application " + port + " UDP/IRUDP *", "a=setup:" + setup, "a=" + direction,
            "a=file-selector:name:\"" + name.replace("\"", "") + "\" type:" + type + " size:" + size
                + " hash:sha-1:" + colons(sha1),
            "a=file-transfer-id:" + transferId, "a=irudp:mss=1384;ring=1024;block=256", ""
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\r\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String colons(String hex) {
        String up = hex.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < up.length(); i += 2) {
            if (i > 0) sb.append(':');
            sb.append(up, i, i + 2);
        }
        return sb.toString();
    }

    /** Parses an SDP body; throws NumberFormatException if the port or the size is not a number we can hold. */
    static Offer parse(String text) {
        Offer o = new Offer();
        for (String line : String.valueOf(text).split("\r?\n")) {
            Matcher m = MEDIA.matcher(line);
            if (m.find()) {
                o.port = Integer.parseInt(m.group(1));
                o.proto = m.group(2);
                continue;
            }
            m = SETUP.matcher(line);
            if (m.find()) {
                o.setup = m.group(1);
                continue;
            }
            if (DIRECTION.matcher(line).matches()) {
                o.direction = line.substring(2);
                continue;
            }
            m = TRANSFER_ID.matcher(line);
            if (m.find()) {
                o.transferId = m.group(1);
                continue;
            }
            if (line.startsWith("a=file-selector:")) {
                String s = line.substring(16);
                Matcher name = NAME.matcher(s), type = TYPE.matcher(s), size = SIZE.matcher(s), hash = HASH.matcher(s);
                o.name = name.find() ? name.group(1) : "file";
                o.type = type.find() ? type.group(1) : "application/octet-stream";
                o.size = size.find() ? Long.parseLong(size.group(1)) : 0;
                o.sha1 = hash.find() ? hash.group(1).replace(":", "").toLowerCase(Locale.ROOT) : "";
            }
        }
        return o;
    }
}
