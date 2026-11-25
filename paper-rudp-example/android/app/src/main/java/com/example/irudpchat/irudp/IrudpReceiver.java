package com.example.irudpchat.irudp;

/**
 * IRUDP receiver (paper, Sec. 4.4 and Listing 2), a port of server/src/irudp/receiver.js. Each payload
 * is written at its file offset, a bit is set in the bitmap, and the ACK carries the bitmap words above
 * the cumulative ACK. ACKs are sent every n_a segments (requested by the sender, Eq. 7), at once on a
 * new gap, a filled gap or a duplicate, and otherwise after at most 10 ms. The echoed time is advanced
 * by the hold time. Used from the endpoint thread only.
 */
public final class IrudpReceiver {
    private static final long U32 = 1L << 32;

    private final int connId, n, nBlocks;
    private final long size;
    private final Link link;
    private final TimingWheel wheel;
    private final ReceiverCallbacks cb;
    private final byte[] got, held;
    private final int[] blockCount;
    private long doneSegs;
    private int highest = -1, rcvNxt, pending, ackFreq = 2;
    private long lastTs, lastArrUs;
    private int finStatus = -1;                       // -1 until the FIN has been answered
    private boolean ended;
    private final String ackTimer;
    private final Runnable ackTask = this::sendAck;

    public IrudpReceiver(int connId, long size, byte[] heldIn, Link link, ReceiverCallbacks cb) {
        this.connId = connId;
        this.size = size;
        this.n = Segment.segmentsFor(size);
        this.link = link;
        this.wheel = link.wheel();
        this.cb = cb;
        this.got = new byte[Segment.bitmapBytes(n)];
        this.nBlocks = Segment.blocksFor(size);
        this.held = new byte[Segment.bitmapBytes(nBlocks)];
        this.blockCount = new int[nBlocks];
        for (int b = 0; b < nBlocks; b++) {            // blocks already on disk count as received
            if (heldIn == null || (b >> 3) >= heldIn.length || Segment.bitGet(heldIn, b) == 0) continue;
            Segment.bitSet(held, b);
            for (int s = b * Segment.BLOCK_SEGS; s < Math.min(n, (b + 1) * Segment.BLOCK_SEGS); s++) {
                Segment.bitSet(got, s);
                doneSegs++;
                highest = s;
            }
            blockCount[b] = segsIn(b);
        }
        while (rcvNxt < n && Segment.bitGet(got, rcvNxt) == 1) rcvNxt++;
        ackTimer = "ack:" + connId;
    }

    private int segsIn(int b) {
        return Math.min(Segment.BLOCK_SEGS, n - b * Segment.BLOCK_SEGS);
    }

    boolean has(int seq) {
        return Segment.bitGet(got, seq) == 1;
    }

    /** The verdict sent in the FINACK (Segment.STATUS_OK or STATUS_BAD_HASH), or -1 before that. */
    public int finStatus() {
        return finStatus;
    }

    public void onData(Segment h) {
        if (h.num >= n) return;
        int s = (int) h.num;
        boolean immediate = false;
        if (Segment.bitGet(got, s) == 1) {
            immediate = true;                          // duplicate: acknowledge at once
        } else {
            if (h.payloadLen != (int) Math.min(Segment.MSS, size - (long) s * Segment.MSS)) return;
            cb.write(s, h.buf, Segment.HDR, h.payloadLen);   // no reordering buffer: straight to the file offset
            Segment.bitSet(got, s);
            doneSegs++;
            int b = s / Segment.BLOCK_SEGS;
            if (++blockCount[b] == segsIn(b)) {
                Segment.bitSet(held, b);
                cb.onBlock(b, held.clone());
            }
            if (s == rcvNxt) {
                if (highest > s) immediate = true;     // filled the first gap
                while (rcvNxt < n && Segment.bitGet(got, rcvNxt) == 1) rcvNxt++;
            } else if (s > highest + 1 || s < highest) {
                immediate = true;                      // new gap, or a gap filled
            }
            if (s > highest) highest = s;
        }
        lastTs = h.ts;
        lastArrUs = link.nowUs();
        ackFreq = h.aux != 0 ? h.aux : 2;
        pending++;
        if (rcvNxt >= n) immediate = true;
        if (immediate || pending >= ackFreq) sendAck();
        else if (!wheel.has(ackTimer)) wheel.after(10, ackTask, ackTimer);
        cb.onProgress(doneSegs, n);
    }

    public void sendAck() {
        wheel.cancel(ackTimer);
        pending = 0;
        int cum = rcvNxt;
        int top = Math.min(highest, cum + 64 * Segment.KMAX);
        int k = top > cum ? (top - cum + 63) / 64 : 0;
        long echo = (lastTs + (link.nowUs() - lastArrUs)) % U32;
        byte[] b = Segment.header(Segment.ACK, 0, k, Segment.RING, connId, cum, echo, 8 * k);
        for (int i = 0; i < 64 * k; i++) {
            int s = cum + 1 + i;
            if (s < n && Segment.bitGet(got, s) == 1) b[Segment.HDR + (i >> 3)] |= (byte) (1 << (i & 7));
        }
        link.send(b);
    }

    public void onFin(Segment h) {
        if (rcvNxt < n) {                              // not complete yet: tell the sender what is missing
            sendAck();
            return;
        }
        if (finStatus < 0) {
            boolean ok = cb.verify();
            finStatus = ok ? Segment.STATUS_OK : Segment.STATUS_BAD_HASH;
            wheel.cancel(ackTimer);
            ended = true;
            cb.onDone(ok, ok ? "ok" : "hash mismatch");
        }
        byte[] b = Segment.header(Segment.FINACK, 0, 0, 0, connId, n, link.nowUs() % U32, 1);
        b[Segment.HDR] = (byte) finStatus;
        link.send(b);
    }

    /** The endpoint closed the session before the FIN (cancelled, idle, stopped). */
    void fail(String reason) {
        if (ended) return;
        ended = true;
        wheel.cancel(ackTimer);
        cb.onDone(false, reason);
    }
}
