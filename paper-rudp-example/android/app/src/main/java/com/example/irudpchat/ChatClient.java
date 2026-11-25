package com.example.irudpchat;

import com.example.irudpchat.irudp.IrudpEndpoint;
import com.example.irudpchat.irudp.IrudpSender;
import com.example.irudpchat.irudp.LossClassifier;
import com.example.irudpchat.irudp.ReceiverCallbacks;
import com.example.irudpchat.irudp.Segment;
import com.example.irudpchat.irudp.SenderCallbacks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The chat client of the example, the Java twin of server/client/chat-client.js: SIP-style REGISTER,
 * MESSAGE and INVITE over UDP to the relay, and IRUDP for files (paper, Fig. 2). Every public method
 * returns at once; the work runs on background threads and the results come back through the Listener,
 * on those threads. A client is started once and stopped once; to reconnect, make a new one.
 */
public final class ChatClient {
    public interface Listener {
        /** Connection state, for the status line (only while the client runs). */
        void onStatus(String text);

        void onMessage(String from, String text);

        /** The relay accepted (or did not accept) the message with this id from sendMessage. */
        void onMessageResult(long localId, boolean accepted);

        /** A file transfer started, progressed or ended. */
        void onTransfer(Transfer t);
    }

    /** A snapshot of one file transfer. */
    public static final class Transfer {
        public static final int RUNNING = 0, DONE = 1, FAILED = 2;
        public final String key, peer, name, mime, detail;
        public final boolean outgoing;
        public final long size, done, total;          // done and total count segments of 1,384 bytes
        public final int state;
        public final File file;                       // the received file, once it is complete

        Transfer(String key, boolean outgoing, String peer, String name, String mime, long size, long done, long total,
                 int state, String detail, File file) {
            this.key = key;
            this.outgoing = outgoing;
            this.peer = peer;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.done = done;
            this.total = total;
            this.state = state;
            this.detail = detail;
            this.file = file;
        }
    }

    /** User names: also what the relay's SIP parser accepts in a request URI. */
    public static final String NAME_PATTERN = "[A-Za-z0-9._-]{1,32}";

    private static final int REGISTER_EVERY_S = 25, EXPIRES_S = 120, ATTEMPTS = 3;
    private static final double THETA = 0.4;              // Eq. (11)
    private static final long MAX_FILE = 16L << 30;       // 16 GiB
    private static final String RESUMED = "resumed in a new session";
    private static final AtomicLong NEXT_MESSAGE_ID = new AtomicLong();   // unique across clients

    private final String host, user;
    private final int port;
    private final File downloadDir;
    private final LossClassifier classifier;
    private final Listener listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, BlockingQueue<Sip.Message>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IrudpEndpoint.Handle> receiving = new ConcurrentHashMap<>();   // sha1 -> session
    private final Map<String, String> answers = lru(256);          // SIP thread only: transfer id -> our SDP answer
    private final Map<String, Boolean> seenMessages = lru(256);    // SIP thread only: Call-IDs of recent MESSAGEs
    private final AtomicInteger cseq = new AtomicInteger(1);
    private volatile boolean running;
    private volatile Boolean registered;
    private volatile DatagramSocket sip;
    private volatile InetAddress server;
    private volatile IrudpEndpoint irudp;
    private volatile String localIp = "0.0.0.0";

    public ChatClient(String host, int port, String user, File downloadDir, LossClassifier classifier, Listener listener) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (!user.matches(NAME_PATTERN)) throw new IllegalArgumentException("bad user name: " + user);
        this.host = host;
        this.port = port;
        this.user = user;
        this.downloadDir = downloadDir;
        this.classifier = classifier;
        this.listener = listener;
    }

    private static <V> Map<String, V> lru(final int max) {
        return new LinkedHashMap<String, V>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    // ---- start and stop ----------------------------------------------------------------------------

    public void start() {
        synchronized (this) {
            running = true;
        }
        if (!submit(this::open)) running = false;
    }

    private void open() {
        DatagramSocket s = null;
        IrudpEndpoint ep = null;
        try {
            InetAddress srv = InetAddress.getByName(host);
            String ip = localAddressTowards(srv, port);
            if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) throw new IOException("cannot create " + downloadDir);
            s = new DatagramSocket();
            ep = new IrudpEndpoint(classifier, THETA, message -> { });
            ep.start();
            synchronized (this) {
                if (!running) throw new IllegalStateException("stopped");   // stop() came first: close what was opened
                server = srv;
                localIp = ip;
                sip = s;
                irudp = ep;
                Thread rx = new Thread(this::receiveLoop, "sip");
                rx.setDaemon(true);
                rx.start();
                status("contacting " + host + ":" + port + " ...");
                timer.scheduleWithFixedDelay(this::register, 0, REGISTER_EVERY_S, TimeUnit.SECONDS);
            }
        } catch (IOException | RuntimeException e) {
            boolean report;
            synchronized (this) {                        // a client that could not open stays closed
                report = running;
                running = false;
                sip = null;
                irudp = null;
            }
            if (ep != null) ep.stop();
            if (s != null) s.close();
            timer.shutdownNow();
            workers.shutdownNow();
            if (report) listener.onStatus("cannot start: " + e.getMessage());
        }
    }

    /** Leaves (REGISTER with Expires 0), ends the transfers and closes the sockets. */
    public void stop() {
        final DatagramSocket s;
        final IrudpEndpoint ep;
        synchronized (this) {
            if (!running) return;
            running = false;
            s = sip;
            ep = irudp;
        }
        timer.shutdownNow();
        workers.shutdownNow();
        if (ep != null) ep.stop();
        if (s == null) return;
        Thread close = new Thread(() -> {                    // the socket may not be used on the main thread
            send(buildRequest("REGISTER", user, null, "", "0", Sip.rand(8) + "@" + user));
            s.close();
        }, "sip-close");
        close.setDaemon(true);
        close.start();
    }

    /** Runs r on a worker thread; false if the client has stopped. */
    private boolean submit(Runnable r) {
        if (!running) return false;
        try {
            workers.execute(r);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void status(String text) {
        if (running) listener.onStatus(text);
    }

    private void register() {
        try {
            Sip.Message r = request("REGISTER", user, null, "", String.valueOf(EXPIRES_S));
            // Android freezes an app in the background (for example while the file picker is open), so a
            // refresh can miss its answer: try once more at once before reporting the server as silent
            if (r == null && Boolean.TRUE.equals(registered) && running) {
                r = request("REGISTER", user, null, "", String.valueOf(EXPIRES_S));
            }
            if (!running) return;
            boolean ok = r != null && r.status == 200;
            Boolean before = registered;
            if (before == null || before != ok) {
                registered = ok;
                status(ok ? "registered as " + user + " at " + host + ":" + port
                        : r == null ? "no answer from " + host + ":" + port + " (trying again)"
                        : "the server refused: " + r.status + " " + r.reason);
            }
        } catch (RuntimeException e) {                       // must not escape: it would end the schedule
            status("error: " + e.getMessage());
        }
    }

    // ---- SIP transactions ------------------------------------------------------------------------------

    private byte[] buildRequest(String method, String to, String contentType, String body, String expires, String callId) {
        DatagramSocket s = sip;
        int localPort = s == null ? 0 : s.getLocalPort();
        String uri = "REGISTER".equals(method) ? "sip:" + Sip.DOMAIN : "sip:" + to + "@" + Sip.DOMAIN;
        String via = "SIP/2.0/UDP " + localIp + ":" + localPort + ";branch=z9hG4bK" + Sip.rand(6) + ";rport";
        return Sip.request(method, uri, via, "<sip:" + user + "@" + Sip.DOMAIN + ">;tag=" + Sip.rand(4),
                "<sip:" + to + "@" + Sip.DOMAIN + ">", callId, cseq.getAndIncrement(),
                "<sip:" + user + "@" + localIp + ":" + localPort + ">", contentType, expires, body);
    }

    /** Sends a request and waits for its final response (null after 7 tries, about 11 s), like chat-client.js. */
    private Sip.Message request(String method, String to, String contentType, String body, String expires) {
        if (sip == null || !running) return null;
        String callId = Sip.rand(8) + "@" + user;
        byte[] msg = buildRequest(method, to, contentType, body, expires, callId);
        BlockingQueue<Sip.Message> answer = new ArrayBlockingQueue<>(4);
        pending.put(callId, answer);
        try {
            long wait = 500;
            for (int tries = 0; tries < 7 && running; tries++) {
                send(msg);
                Sip.Message r = answer.poll(wait, TimeUnit.MILLISECONDS);
                if (r != null) return r;
                wait = Math.min(2 * wait, 2000);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(callId);
        }
    }

    private void send(byte[] msg) {
        DatagramSocket s = sip;
        InetAddress srv = server;
        if (s == null || srv == null) return;
        try {
            s.send(new DatagramPacket(msg, msg.length, srv, port));
        } catch (IOException | RuntimeException e) {
            // lost like a datagram: the retransmission sends it again
        }
    }

    private void reply(Sip.Message req, SocketAddress to, int status, String reason, String contentType, String body) {
        DatagramSocket s = sip;
        if (s == null) return;
        byte[] b = Sip.response(req, status, reason, contentType, body);
        try {
            s.send(new DatagramPacket(b, b.length, to));
        } catch (IOException | RuntimeException e) {
            // the relay sends its request again
        }
    }

    private void sendAck(Sip.Message resp) {
        DatagramSocket s = sip;
        if (s == null) return;
        String via = "SIP/2.0/UDP " + localIp + ":" + s.getLocalPort() + ";branch=z9hG4bK" + Sip.rand(6) + ";rport";
        send(Sip.request("ACK", "sip:relay@" + host + ":" + port, via, resp.header("from"), resp.header("to"),
                resp.header("call-id"), Sip.cseqNumber(resp), null, null, null, ""));
    }

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            DatagramSocket s = sip;
            if (s == null) return;
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (IOException e) {
                if (!running || s.isClosed()) return;
                continue;
            }
            try {
                Sip.Message m = Sip.parse(p.getData(), p.getLength());
                if (m != null) onSip(m, p.getSocketAddress());
            } catch (RuntimeException e) {
                // a malformed message: ignore it
            }
        }
    }

    private void onSip(Sip.Message m, SocketAddress from) {
        if (m.status > 0) {
            String callId = m.header("call-id");
            BlockingQueue<Sip.Message> q = callId == null ? null : pending.get(callId);
            if (q != null && m.status >= 200) q.offer(m);
            return;
        }
        if (!running) return;
        switch (m.method) {
            case "MESSAGE": {
                reply(m, from, 200, "OK", null, "");
                String callId = m.header("call-id");
                if (callId != null && seenMessages.put(callId, Boolean.TRUE) != null) return;   // a retransmission
                String sender = Sip.userOf(m.header("from"));
                listener.onMessage(sender == null ? "?" : sender, m.body);
                break;
            }
            case "INVITE":
                onInvite(m, from);
                break;
            case "ACK":
                break;
            default:
                reply(m, from, 200, "OK", null, "");
                break;
        }
    }

    // ---- chat messages -------------------------------------------------------------------------------

    /** Sends a chat message through the relay; returns the id reported to onMessageResult. */
    public long sendMessage(String to, String text) {
        final long id = NEXT_MESSAGE_ID.incrementAndGet();
        boolean queued = submit(() -> {
            boolean ok;
            try {
                Sip.Message r = request("MESSAGE", to, "text/plain;charset=UTF-8", text, null);
                ok = r != null && r.status < 300;
            } catch (RuntimeException e) {
                ok = false;
            }
            listener.onMessageResult(id, ok);
        });
        if (!queued) listener.onMessageResult(id, false);
        return id;
    }

    // ---- sending a file ------------------------------------------------------------------------------

    private static final class Upload {
        boolean ok;
        String reason = "interrupted";
        IrudpSender.Stats stats;
        long ms;
    }

    /**
     * Offers a file to another user through the relay (INVITE with SDP) and sends it over IRUDP. A failed
     * attempt is offered again; the relay answers the new SYN with the blocks it already holds, so the
     * transfer resumes (paper, Sec. 4.6). With deleteWhenDone the file is deleted at the end.
     */
    public void sendFile(String to, File file, String displayName, String mimeType, boolean deleteWhenDone) {
        final String key = "out:" + Sip.rand(6);
        final String name = safeName(displayName);
        final String mime = cleanMime(mimeType);
        boolean queued = submit(() -> {
            try {
                offerAndSend(key, to, file, name, mime);
            } catch (RuntimeException e) {
                emit(key, true, to, name, mime, file.length(), 0, Segment.segmentsFor(file.length()), Transfer.FAILED,
                        "error: " + e.getMessage(), null);
            } finally {
                lastProgress.remove(key);
                if (deleteWhenDone && !file.delete()) file.deleteOnExit();
            }
        });
        if (!queued) {
            emit(key, true, to, name, mime, file.length(), 0, Segment.segmentsFor(file.length()), Transfer.FAILED,
                    "not connected", null);
            if (deleteWhenDone && !file.delete()) file.deleteOnExit();
        }
    }

    private void offerAndSend(String key, String to, File file, String name, String mime) {
        long size = file.length();
        long total = Segment.segmentsFor(size);
        try {
            emit(key, true, to, name, mime, size, 0, total, Transfer.RUNNING, "checking the file", null);
            String sha1 = sha1Of(file);
            String failure = "not sent";
            for (int attempt = 1; attempt <= ATTEMPTS && running; attempt++) {
                String tid = Sip.rand(8);
                emit(key, true, to, name, mime, size, 0, total, Transfer.RUNNING,
                        attempt == 1 ? "offering" : "resuming (attempt " + attempt + ")", null);
                String sdp = Sdp.build(user, "0.0.0.0", 9, "active", "sendonly", name, mime, size, sha1, tid);
                Sip.Message resp = request("INVITE", to, "application/sdp", sdp, null);
                if (resp == null || resp.status >= 300) {
                    failure = resp == null ? "no answer from the server" : resp.status + " " + resp.reason;
                    continue;
                }
                sendAck(resp);
                Sdp.Offer answer;
                try {
                    answer = Sdp.parse(resp.body);
                } catch (NumberFormatException e) {
                    failure = "unreadable answer";
                    continue;
                }
                if (answer.port <= 0 || answer.port > 65535) {
                    failure = "unreadable answer";
                    continue;
                }
                Upload r = sendOnce(key, to, name, mime, file, size, sha1, tid, answer.port);
                if (r.ok) {
                    emit(key, true, to, name, mime, size, total, total, Transfer.DONE, describe(r, total), null);
                    submit(() -> request("BYE", to, null, "", null));
                    return;
                }
                failure = r.reason;
            }
            emit(key, true, to, name, mime, size, 0, total, Transfer.FAILED, failure, null);
        } catch (IOException e) {
            emit(key, true, to, name, mime, size, 0, total, Transfer.FAILED, "cannot read the file: " + e.getMessage(), null);
        }
    }

    private Upload sendOnce(String key, String to, String name, String mime, File file, long size, String sha1, String tid,
                            int remotePort) throws IOException {
        final Upload result = new Upload();
        final CountDownLatch finished = new CountDownLatch(1);
        final long t0 = System.currentTimeMillis();
        IrudpEndpoint ep = irudp;
        if (ep == null) {
            result.reason = "not connected";
            return result;
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            ep.send(new InetSocketAddress(server, remotePort), size, sha1, tid, new SenderCallbacks() {
                @Override
                public void read(int seq, byte[] dst, int off, int len) {
                    try {
                        in.seek((long) seq * Segment.MSS);
                        in.readFully(dst, off, len);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read the file: " + e.getMessage(), e);
                    }
                }

                @Override
                public void onProgress(long done, long all) {
                    progress(key, true, to, name, mime, size, done, all, "sending");
                }

                @Override
                public void onDone(boolean ok, String reason, IrudpSender.Stats stats) {
                    result.ok = ok;
                    result.reason = reason;
                    result.stats = stats;
                    result.ms = System.currentTimeMillis() - t0;
                    finished.countDown();
                }
            });
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private static String describe(Upload r, long total) {
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "sent in %.1f s", r.ms / 1000.0));
        IrudpSender.Stats s = r.stats;
        if (s != null) {
            if (s.skipped > 0) sb.append(String.format(Locale.US, "; %d of %d segments were already on the server", s.skipped, total));
            sb.append(String.format(Locale.US, "; %d of %d segments resent", s.retx, s.sent));
            int events = s.keptWindow + s.reductions;
            if (events > 0) sb.append(String.format(Locale.US, "; window kept on %d of %d loss events", s.keptWindow, events));
        }
        return sb.toString();
    }

    // ---- receiving a file ------------------------------------------------------------------------------

    private void onInvite(Sip.Message m, SocketAddress from) {
        Sdp.Offer parsed;
        try {
            parsed = Sdp.parse(m.body);
        } catch (NumberFormatException e) {
            reply(m, from, 488, "Not Acceptable Here", null, "");
            return;
        }
        final Sdp.Offer o = parsed;
        String cached = o.transferId == null ? null : answers.get(o.transferId);
        if (cached != null) {                                // a retransmitted INVITE: same answer, no second download
            reply(m, from, 200, "OK", "application/sdp", cached);
            return;
        }
        if (!"UDP/IRUDP".equals(o.proto) || o.transferId == null || !o.sha1.matches("[0-9a-f]{40}")
                || o.size < 0 || o.size > MAX_FILE || o.port <= 0 || o.port > 65535) {
            reply(m, from, 488, "Not Acceptable Here", null, "");
            return;
        }
        // The relay offers a file again only after its last delivery ended, so a session still receiving
        // that file is dead (it would wait for its idle timeout): end it, and the new one resumes from its blocks
        IrudpEndpoint.Handle old = receiving.remove(o.sha1);
        IrudpEndpoint ep = irudp;
        if (old != null && ep != null) ep.cancel(old, RESUMED);
        String answer = Sdp.build(user, "0.0.0.0", 9, "active", "recvonly", o.name, o.type, o.size, o.sha1, o.transferId);
        answers.put(o.transferId, answer);
        reply(m, from, 200, "OK", "application/sdp", answer);
        String sender = Sip.userOf(m.header("from"));
        final String peer = sender == null ? "?" : sender;
        submit(() -> download(o, peer));
    }

    private void download(Sdp.Offer o, String from) {
        try {
            startDownload(o, from);
        } catch (RuntimeException e) {
            emit("in:" + o.transferId, false, from, safeName(o.name), cleanMime(o.type), o.size, 0, Segment.segmentsFor(o.size),
                    Transfer.FAILED, "error: " + e.getMessage(), null);
        }
    }

    /** Opens the partial file and starts the IRUDP session; true if the session was started. */
    private boolean startDownload(Sdp.Offer o, String from) {
        final String key = "in:" + o.transferId;
        final String name = safeName(o.name);
        final String mime = cleanMime(o.type);
        final long total = Segment.segmentsFor(o.size);
        final File part = new File(downloadDir, o.sha1 + ".part");
        final File map = new File(downloadDir, o.sha1 + ".map");
        boolean resumable = part.isFile() && part.length() == o.size;     // the block map is trusted only next to its file
        RandomAccessFile opened;
        try {
            opened = new RandomAccessFile(part, "rw");
        } catch (IOException e) {
            emit(key, false, from, name, mime, o.size, 0, total, Transfer.FAILED, "cannot write: " + e.getMessage(), null);
            return false;
        }
        final RandomAccessFile out = opened;
        byte[] held = new byte[Segment.bitmapBytes(Segment.blocksFor(o.size))];
        if (resumable) {
            byte[] saved = readFile(map);                    // blocks kept from an interrupted download
            if (saved != null && saved.length == held.length) held = saved;
        } else {
            try {
                out.setLength(o.size);
            } catch (IOException e) {
                closeQuietly(out);
                emit(key, false, from, name, mime, o.size, 0, total, Transfer.FAILED, "cannot write: " + e.getMessage(), null);
                return false;
            }
            if (map.exists() && !map.delete()) map.deleteOnExit();
        }
        IrudpEndpoint ep = irudp;
        if (ep == null) {
            closeQuietly(out);
            return false;
        }
        final long t0 = System.currentTimeMillis();
        final IrudpEndpoint.Handle[] self = new IrudpEndpoint.Handle[1];
        emit(key, false, from, name, mime, o.size, 0, total, Transfer.RUNNING, "receiving", null);
        self[0] = ep.receive(new InetSocketAddress(server, o.port), o.size, o.sha1, o.transferId, held, new ReceiverCallbacks() {
            @Override
            public void write(int seq, byte[] src, int off, int len) {
                try {
                    out.seek((long) seq * Segment.MSS);
                    out.write(src, off, len);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot write the file: " + e.getMessage(), e);
                }
            }

            @Override
            public void onBlock(int b, byte[] heldBitmap) {
                writeFile(map, heldBitmap);
            }

            @Override
            public boolean verify() {
                try {
                    out.getFD().sync();
                    return o.sha1.equals(sha1Of(part));
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public void onProgress(long done, long all) {
                progress(key, false, from, name, mime, o.size, done, all, "receiving");
            }

            @Override
            public void onDone(boolean ok, String reason) {
                closeQuietly(out);
                lastProgress.remove(key);
                if (self[0] != null) receiving.remove(o.sha1, self[0]);   // not a newer session for the same file
                if (!ok) {
                    if ("hash mismatch".equals(reason)) {     // not resumable: its block map says every block is held
                        if (!part.delete()) part.deleteOnExit();
                        if (!map.delete()) map.deleteOnExit();
                    }
                    emit(key, false, from, name, mime, o.size, 0, total, Transfer.FAILED, RESUMED.equals(reason)
                            ? "the connection was lost; the transfer goes on below, from the blocks already here"
                            : reason + "; it resumes when the server offers it again", null);
                    return;
                }
                File dest = unique(downloadDir, name);
                File result = part.renameTo(dest) ? dest : part;
                if (!map.delete()) map.deleteOnExit();
                emit(key, false, from, name, mime, o.size, total, total, Transfer.DONE,
                        String.format(Locale.US, "received in %.1f s", (System.currentTimeMillis() - t0) / 1000.0), result);
            }
        });
        receiving.put(o.sha1, self[0]);
        return true;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private void progress(String key, boolean outgoing, String peer, String name, String mime, long size, long done,
                          long total, String detail) {
        long now = System.currentTimeMillis();
        Long last = lastProgress.get(key);
        if (done < total && last != null && now - last < 150) return;   // at most about 7 updates a second
        lastProgress.put(key, now);
        emit(key, outgoing, peer, name, mime, size, done, total, Transfer.RUNNING, detail, null);
    }

    private void emit(String key, boolean outgoing, String peer, String name, String mime, long size, long done, long total,
                      int state, String detail, File file) {
        listener.onTransfer(new Transfer(key, outgoing, peer, name, mime, size, done, total, state, detail, file));
    }

    /** The file name comes from the other user: keep its last path element only, without control characters. */
    static String safeName(String name) {
        String s = name == null ? "" : name.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "file" : s;
    }

    static String cleanMime(String mime) {
        if (mime == null) return "application/octet-stream";
        String m = mime.trim().split("[;\\s]")[0];
        return m.matches("[\\w.+-]+/[\\w.+-]+") ? m : "application/octet-stream";
    }

    static String sha1Of(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return Segment.toHex(md.digest());
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    private static byte[] readFile(File f) {
        if (!f.isFile()) return null;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (IOException e) {
            // without the block map an interrupted download starts over
        }
    }

    private static void closeQuietly(RandomAccessFile f) {
        try {
            f.close();
        } catch (IOException e) {
            // nothing more to do
        }
    }

    private static String localAddressTowards(InetAddress target, int targetPort) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(target, targetPort);             // no packet is sent; it only picks the interface
            InetAddress a = probe.getLocalAddress();
            return a == null || a.isAnyLocalAddress() ? "0.0.0.0" : a.getHostAddress();
        } catch (IOException | RuntimeException e) {
            return "0.0.0.0";
        }
    }
}
