package com.example.irudpchat.irudp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The client side of IRUDP, a port of server/src/irudp/endpoint.js without the passive (relay) part:
 * one UDP socket and one thread for all sessions, which are found by connection ID, and one timing
 * wheel for all their timers. The client always opens the session itself (SYN), so it works behind NAT
 * and in the emulator. The public methods may be called from any thread; the callbacks of a session
 * run on the endpoint thread.
 */
public final class IrudpEndpoint {
    public interface Log {
        void log(String message);
    }

    /** Identifies a session, to cancel it. */
    public static final class Handle {
        final int connId;

        Handle(int connId) {
            this.connId = connId;
        }
    }

    private static final int IDLE_MS = 60000;
    private static final long U32 = 1L << 32;

    private static final class Session {
        final int connId, role;
        final long size;
        final SenderCallbacks scb;
        InetSocketAddress peer;
        Link link;
        byte[] syn;
        int synTries;
        boolean established;
        IrudpSender sender;
        IrudpReceiver receiver;

        Session(int connId, int role, long size, SenderCallbacks scb, InetSocketAddress peer) {
            this.connId = connId;
            this.role = role;
            this.size = size;
            this.scb = scb;
            this.peer = peer;
        }
    }

    private final LossClassifier clf;
    private final double theta;
    private final Log log;
    private final TimingWheel wheel = new TimingWheel();
    private final HashMap<Integer, Session> sessions = new HashMap<>();
    private final HashMap<Integer, Integer> finished = new HashMap<>();   // FIN verdicts, kept 30 s for repeated FINs
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final SecureRandom random = new SecureRandom();
    private final long t0 = System.nanoTime();
    private DatagramChannel channel;
    private volatile Selector selector;
    private volatile boolean running;
    private volatile boolean closed;                  // the loop has ended: post() runs tasks on the caller's thread

    public IrudpEndpoint(LossClassifier clf, double theta, Log log) {
        this.clf = clf;
        this.theta = theta;
        this.log = log;
        wheel.setErrorHandler(e -> log.log("irudp timer error: " + e));
    }

    private long nowUs() {
        return (System.nanoTime() - t0) / 1000;
    }

    private long nowMs() {
        return (System.nanoTime() - t0) / 1000000;
    }

    public void start() throws IOException {
        DatagramChannel ch = DatagramChannel.open();
        Selector s = null;
        try {
            ch.configureBlocking(false);
            try {
                ch.socket().setReceiveBufferSize(4 << 20);
                ch.socket().setSendBufferSize(4 << 20);
            } catch (IOException e) {
                // the system keeps its own limit
            }
            ch.socket().bind(new InetSocketAddress(0));
            s = Selector.open();
            ch.register(s, SelectionKey.OP_READ);
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            closeQuietly(ch);
            closed = true;                             // never ran: post() reports at once instead of waiting
            throw e;
        }
        channel = ch;
        selector = s;
        running = true;
        Thread t = new Thread(this::loop, "irudp");
        t.setDaemon(true);
        t.start();
    }

    /** Ends every session (each owner hears "stopped") and then the thread. */
    public void stop() {
        post(() -> {
            try {
                for (Session s : new ArrayList<>(sessions.values())) {
                    try {
                        close(s, "stopped");
                    } catch (RuntimeException e) {
                        log.log("irudp: " + e);
                    }
                }
            } finally {
                running = false;
            }
        });
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // closing anyway
        }
    }

    private void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.log("irudp: " + e);
        }
    }

    /** Sends a file to the relay (role 0). */
    public Handle send(InetSocketAddress server, long size, String sha1, String transferId, SenderCallbacks cb) {
        return connect(server, Segment.ROLE_CLIENT_SENDS, size, sha1, transferId, null, cb, null);
    }

    /** Fetches a file from the relay (role 1); held is the bitmap of blocks already on disk, or null. */
    public Handle receive(InetSocketAddress server, long size, String sha1, String transferId, byte[] held, ReceiverCallbacks cb) {
        return connect(server, Segment.ROLE_CLIENT_RECEIVES, size, sha1, transferId, held, null, cb);
    }

    public void cancel(Handle h) {
        post(() -> {
            Session s = sessions.get(h.connId);
            if (s != null) close(s, "cancelled");
        });
    }

    private void post(Runnable r) {
        tasks.add(r);
        if (closed) {                                  // the loop has ended: run what is left here (no session is open)
            Runnable x;
            while ((x = tasks.poll()) != null) runQuietly(x);
        } else {
            Selector s = selector;
            if (s != null) s.wakeup();
        }
    }

    private void loop() {
        ByteBuffer in = ByteBuffer.allocate(65536);
        while (running) {
            try {
                selector.select(sessions.isEmpty() ? 200 : 1);
                selector.selectedKeys().clear();
                for (int i = 0; i < 4096; i++) {
                    in.clear();
                    SocketAddress from = channel.receive(in);
                    if (from == null) break;
                    in.flip();
                    byte[] b = new byte[in.remaining()];
                    in.get(b);
                    if (from instanceof InetSocketAddress) onPacket(b, (InetSocketAddress) from);
                }
                Runnable r;
                while ((r = tasks.poll()) != null) r.run();
                wheel.advanceTo(nowMs());
            } catch (IOException | RuntimeException e) {
                log.log("irudp: " + e);
            }
        }
        closed = true;                                 // first closed, then the last drain: post() covers the rest
        Runnable r;
        while ((r = tasks.poll()) != null) runQuietly(r);   // tasks that came after stop(): they report "stopped"
        closeQuietly(selector);
        closeQuietly(channel);
    }

    private void sendTo(InetSocketAddress to, byte[] b) {
        try {
            channel.send(ByteBuffer.wrap(b), to);
        } catch (IOException e) {
            // like a lost datagram: the timers send again
        }
    }

    private void touch(Session sess) {
        wheel.after(IDLE_MS, () -> close(sess, "idle"), "idle:" + sess.connId);
    }

    /** Ends a session; its owner hears about it exactly once. */
    private void close(Session sess, String reason) {
        if (sessions.remove(sess.connId) == null) return;
        wheel.cancel("idle:" + sess.connId);
        wheel.cancel("syn:" + sess.connId);
        wheel.cancel("ack:" + sess.connId);
        if (sess.sender != null) {
            if (!sess.sender.isDone()) sess.sender.finish(false, reason);
        } else if (sess.receiver != null) {
            sess.receiver.fail(reason);
        } else {
            sess.scb.onDone(false, reason, null);          // a sending session closed before its SYNACK
        }
    }

    private void onPacket(byte[] b, InetSocketAddress from) {
        Segment h = Segment.parse(b);
        if (h == null || h.type == Segment.SYN) return;     // the client never accepts sessions
        Session sess = sessions.get(h.connId);
        if (sess == null) {
            if (h.type == Segment.FIN) {                     // a FIN for a finished receiving session: confirm it again
                Integer verdict = finished.get(h.connId);
                if (verdict != null) {
                    byte[] a = Segment.header(Segment.FINACK, 0, 0, 0, h.connId, h.num, 0, 1);
                    a[Segment.HDR] = verdict.byteValue();
                    sendTo(from, a);
                }
            }
            return;
        }
        if (!from.equals(sess.peer)) sess.peer = from;
        touch(sess);
        try {
            switch (h.type) {
                case Segment.DATA:
                    if (sess.receiver != null) sess.receiver.onData(h);
                    break;
                case Segment.ACK:
                    if (sess.sender != null) sess.sender.onAck(h);
                    break;
                case Segment.FIN:
                    if (sess.receiver != null) {
                        sess.receiver.onFin(h);
                        if (sess.receiver.finStatus() >= 0) {   // remember the verdict for repeated FINs, then end the session
                            final int id = sess.connId;
                            finished.put(id, sess.receiver.finStatus());
                            wheel.after(30000, () -> finished.remove(id), "fin-memo:" + id);
                            sessions.remove(id);
                            wheel.cancel("idle:" + id);
                        }
                    }
                    break;
                case Segment.FINACK:
                    if (sess.sender != null) sess.sender.onFinAck(h);
                    break;
                case Segment.SYNACK:
                    onSynAck(sess, h);
                    break;
                case Segment.RST:
                    close(sess, "reset by peer");
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {                       // for example an I/O error in the file callbacks
            close(sess, "error: " + e.getMessage());
        }
    }

    private Handle connect(InetSocketAddress server, int role, long size, String sha1, String transferId, byte[] held,
                           SenderCallbacks scb, ReceiverCallbacks rcb) {
        int id;
        synchronized (random) {
            do {
                id = random.nextInt();
            } while (id == 0);
        }
        final int connId = id;
        post(() -> {
            if (!running) {                            // the endpoint has stopped: tell the owner at once
                if (scb != null) scb.onDone(false, "stopped", null);
                else rcb.onDone(false, "stopped");
                return;
            }
            final Session sess = new Session(connId, role, size, scb, server);
            sess.link = new Link() {
                @Override
                public void send(byte[] packet) {
                    sendTo(sess.peer, packet);
                }

                @Override
                public long nowUs() {
                    return IrudpEndpoint.this.nowUs();
                }

                @Override
                public TimingWheel wheel() {
                    return wheel;
                }
            };
            sessions.put(connId, sess);
            byte[] p = Segment.synPayload(role, size, sha1, transferId, role == Segment.ROLE_CLIENT_RECEIVES ? held : null);
            sess.syn = Segment.header(Segment.SYN, 0, 0, Segment.RING, connId, 0, nowUs() % U32, p.length);
            System.arraycopy(p, 0, sess.syn, Segment.HDR, p.length);
            if (role == Segment.ROLE_CLIENT_RECEIVES) {       // the receiver exists from the start: DATA may beat the SYNACK
                sess.receiver = new IrudpReceiver(connId, size, held, sess.link, rcb);
            }
            touch(sess);
            sendSyn(sess);
        });
        return new Handle(connId);
    }

    private void sendSyn(Session sess) {
        if (sess.established || !sessions.containsKey(sess.connId)) return;
        if (++sess.synTries > 10) {
            close(sess, "no SYNACK");
            return;
        }
        sendTo(sess.peer, sess.syn);
        wheel.after(500, () -> sendSyn(sess), "syn:" + sess.connId);
    }

    private void onSynAck(Session sess, Segment h) {
        if (sess.established) return;
        Segment.Handshake a = Segment.parseSynAck(h);
        sess.established = true;
        wheel.cancel("syn:" + sess.connId);
        if (a == null || a.status != Segment.STATUS_OK) {
            close(sess, "refused (" + (a == null ? "?" : String.valueOf(a.status)) + ")");
            return;
        }
        if (sess.role == Segment.ROLE_CLIENT_SENDS) {
            final int connId = sess.connId;
            final SenderCallbacks owner = sess.scb;
            sess.sender = new IrudpSender(connId, sess.size, a.held, sess.link, clf, theta, new SenderCallbacks() {
                @Override
                public void read(int seq, byte[] dst, int off, int len) {
                    owner.read(seq, dst, off, len);
                }

                @Override
                public void onProgress(long done, long total) {
                    owner.onProgress(done, total);
                }

                @Override
                public void onDone(boolean ok, String reason, IrudpSender.Stats stats) {
                    sessions.remove(connId);
                    wheel.cancel("idle:" + connId);
                    owner.onDone(ok, reason, stats);
                }
            });
            sess.sender.start();
        }
    }
}
