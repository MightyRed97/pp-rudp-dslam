package com.example.irudpchat.irudp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * IRUDP sender (paper, Sec. 4.3 and 4.7, Listing 1 and Algorithm 1), a line-by-line port of
 * server/src/irudp/sender.js. Ring of 1,024 slots indexed by sequence number, a scoreboard bitmap
 * aligned with the ring, time-based loss marking (Eq. 5), one probe timer per session in the shared
 * wheel (Eq. 6), CUBIC (Eq. 8) with pacing, and the GBDT classifier gating each window reduction
 * (Eqs. 9-11). Used from the endpoint thread only.
 */
public final class IrudpSender {
    /** Counters reported at the end of a transfer. */
    public static final class Stats {
        public int sent, retx, events, reductions, keptWindow, skipped;

        @Override
        public String toString() {
            return String.format(Locale.US, "sent=%d retx=%d events=%d reductions=%d keptWindow=%d skipped=%d",
                    sent, retx, events, reductions, keptWindow, skipped);
        }
    }

    private static final int MASK = Segment.RING - 1;
    private static final byte FREE = 0, INFLIGHT = 1, SACKED = 2, LOST = 3;
    private static final double BETA = 0.7, C = 0.4;
    private static final double RTO_MIN_US = 200000, ACK_DELAY_US = 10000, PROBE_CAP_US = 2000000;
    private static final int MAX_PROBES = 15;
    private static final long U32 = 1L << 32;
    private static final double INF = Double.POSITIVE_INFINITY;

    private final int connId, n;
    private final long size;
    private final byte[] held;                       // block bitmap of the receiver (resume), or null
    private final Link link;
    private final TimingWheel wheel;
    private final LossClassifier clf;
    private final double theta;
    private final SenderCallbacks cb;

    // ring and scoreboard (Listing 1)
    private final byte[] state = new byte[Segment.RING];
    private final long[] sentAt = new long[Segment.RING];
    private final int[] tx = new int[Segment.RING];
    private final int[] seqAt = new int[Segment.RING];
    private final int[] board = new int[Segment.RING / 32];       // bit (seq & 1023) set = delivered
    private int una, nxt, pipe;
    private long rackTs = -1;
    private final IntMinHeap lostQ = new IntMinHeap();
    private int[] oSeq = new int[2048], oTx = new int[2048];      // send order: [seq, tx, sentAt]
    private long[] oAt = new long[2048];
    private int oHead, oLen;
    private int[] lostBuf = new int[Segment.RING];
    // round-trip estimator (Eq. 6)
    private double srtt, rttvar, minRtt = INF, latest, rto = 1000000;
    private final ArrayList<Double> rtts = new ArrayList<>();
    // CUBIC (Eq. 8)
    private double cwnd = 10, ssthresh = INF, wmax, k, origin, epoch, west;
    private boolean epochSet, hystart = true;
    private int recEnd = -1, decEnd = -1;
    // classifier inputs (Eq. 9)
    private double lossEwma;
    private long lastEventUs, delivered;
    private final ArrayDeque<long[]> rateHist = new ArrayDeque<>();
    private final ArrayDeque<double[]> bwHist = new ArrayDeque<>();
    // pacing, probe timer and FIN
    private double nextSendUs, backoff = 1;
    private int probes, finTries;
    private boolean finSent, done;
    private final String idProbe, idPace, idFin;
    private final Runnable pumpTask, probeTask, finTask;
    private final Stats stats = new Stats();
    private long doneSegs;

    public IrudpSender(int connId, long size, byte[] held, Link link, LossClassifier clf, double theta, SenderCallbacks cb) {
        this.connId = connId;
        this.size = size;
        this.n = Segment.segmentsFor(size);
        this.held = held;
        this.link = link;
        this.wheel = link.wheel();
        this.clf = clf;
        this.theta = theta;
        this.cb = cb;
        Arrays.fill(seqAt, -1);
        lastEventUs = link.nowUs();
        idProbe = "probe:" + connId;
        idPace = "pace:" + connId;
        idFin = "fin:" + connId;
        pumpTask = guard(this::pump);
        probeTask = guard(this::onProbe);
        finTask = guard(this::sendFin);
        // segments of blocks the receiver already holds count as done; a held prefix is skipped outright
        if (held != null) for (int s = 0; s < n; s++) if (isHeld(s)) doneSegs++;
        while (una < n && isHeld(una)) {
            una++;
            stats.skipped++;
        }
        nxt = una;
    }

    /** Timer callbacks: an I/O error while reading the file ends the transfer instead of escaping. */
    private Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                finish(false, "error: " + e.getMessage());
            }
        };
    }

    public Stats stats() {
        return stats;
    }

    public boolean isDone() {
        return done;
    }

    int pipe() {
        return pipe;
    }

    private boolean isHeld(int seq) {
        int b = seq / Segment.BLOCK_SEGS;
        return held != null && (b >> 3) < held.length && Segment.bitGet(held, b) == 1;
    }

    private int boardGet32(int seq) {                       // 32 scoreboard bits for seq .. seq + 31
        int i = seq & MASK, w = i >>> 5, off = i & 31;
        int v = board[w] >>> off;
        if (off != 0) v |= board[(w + 1) & 31] << (32 - off);
        return v;
    }

    private void boardSet(int seq) {
        int i = seq & MASK;
        board[i >>> 5] |= 1 << (i & 31);
    }

    private void boardClear(int seq) {
        int i = seq & MASK;
        board[i >>> 5] &= ~(1 << (i & 31));
    }

    public void start() {
        cb.onProgress(doneSegs, n);
        pump();
    }

    private int ackFreq() {                                  // Eq. (7)
        return Math.min(Math.max((int) Math.floor(cwnd / 8), 2), 8);
    }

    private void pump() {
        if (done) return;
        for (int budget = 256; budget > 0; budget--) {
            if (una >= n) {
                sendFin();
                return;
            }
            while (lostQ.size() > 0) {                       // drop queue entries that were delivered meanwhile
                int s = lostQ.peek(), i = s & MASK;
                if (s >= una && seqAt[i] == s && state[i] == LOST) break;
                lostQ.pop();
            }
            boolean retx = lostQ.size() > 0;
            if (!retx) {
                while (nxt < n && nxt < una + Segment.RING && isHeld(nxt)) {
                    int i = nxt & MASK;                      // a held block inside the window: pre-acknowledged
                    state[i] = SACKED;
                    seqAt[i] = nxt;
                    tx[i] = 0;
                    boardSet(nxt);
                    nxt++;
                    stats.skipped++;
                }
                if (nxt >= n || nxt >= una + Segment.RING) return;
            }
            if (pipe >= cwnd) return;                         // window full: the next ACK pumps again
            long nowUs = link.nowUs();
            if (srtt > 0 && nowUs < nextSendUs) {             // paced: wait for the release time in the wheel
                wheel.after((long) Math.ceil((nextSendUs - nowUs) / 1000), pumpTask, idPace);
                return;
            }
            int seq;
            if (retx) {
                seq = lostQ.pop();
                stats.retx++;
            } else {
                seq = nxt++;
            }
            transmit(seq, retx);
            if (srtt > 0) {
                double g = cwnd < ssthresh ? 2 : 1.25;
                nextSendUs = Math.max(nextSendUs, nowUs - 20000) + srtt / (g * cwnd);
            }
        }
        wheel.after(1, pumpTask, idPace);
    }

    private void transmit(int seq, boolean retx) {
        int i = seq & MASK;
        long nowUs = link.nowUs();
        int len = (int) Math.min(Segment.MSS, size - (long) seq * Segment.MSS);
        byte[] b = Segment.header(Segment.DATA, retx ? Segment.F_RETX : 0, ackFreq(), Segment.RING, connId, seq, nowUs % U32, len);
        cb.read(seq, b, Segment.HDR, len);
        if (seqAt[i] != seq) tx[i] = 0;
        state[i] = INFLIGHT;
        seqAt[i] = seq;
        sentAt[i] = nowUs;
        tx[i]++;
        orderPush(seq, tx[i], nowUs);
        pipe++;
        stats.sent++;
        link.send(b);
        if (!wheel.has(idProbe)) armProbe();
    }

    private void orderPush(int seq, int txn, long at) {
        if (oLen == oSeq.length) {
            if (oHead > oSeq.length / 2) {
                orderCompact();
            } else {
                oSeq = Arrays.copyOf(oSeq, 2 * oLen);
                oTx = Arrays.copyOf(oTx, 2 * oLen);
                oAt = Arrays.copyOf(oAt, 2 * oLen);
            }
        }
        oSeq[oLen] = seq;
        oTx[oLen] = txn;
        oAt[oLen] = at;
        oLen++;
    }

    private void orderCompact() {
        int m = oLen - oHead;
        System.arraycopy(oSeq, oHead, oSeq, 0, m);
        System.arraycopy(oTx, oHead, oTx, 0, m);
        System.arraycopy(oAt, oHead, oAt, 0, m);
        oLen = m;
        oHead = 0;
    }

    private int deliver(int s, long nowUs, long echo) {
        int i = s & MASK;
        if (s < una || seqAt[i] != s) return 0;
        byte st = state[i];
        if (st != INFLIGHT && st != LOST) return 0;
        if (st == INFLIGHT) pipe--;
        state[i] = SACKED;
        doneSegs++;
        // RFC 8985, 6.2: a resent segment may have been delivered by its first copy (after a delay spike, the
        // probe resends a segment that was only late). Then its last send time says nothing about the
        // segments sent after the first copy and must not advance the RACK time. That is so when the ACK
        // echoes a time before the resend, or comes sooner than min RTT after it.
        if (tx[i] > 1) {
            long echoBefore = ((sentAt[i] % U32) - echo + U32) % U32;
            if ((echoBefore > 0 && echoBefore < U32 / 2) || nowUs - sentAt[i] < minRtt) return 1;
        }
        if (sentAt[i] > rackTs) rackTs = sentAt[i];
        return 1;
    }

    public void onAck(Segment h) {
        if (done || h.num < una) return;                     // stale or reordered acknowledgement
        long nowUs = link.nowUs();
        long rtt = ((nowUs % U32) - h.ts + U32) % U32;
        if (rtt > 0 && rtt < 60000000L) updateRtt(rtt);
        int newly = 0;
        int cum = (int) Math.min(h.num, (long) nxt);
        while (una < cum) {                                  // cumulative part frees slots in order
            newly += deliver(una, nowUs, h.ts);
            int i = una & MASK;
            state[i] = FREE;
            tx[i] = 0;
            seqAt[i] = -1;
            boardClear(una);
            una++;
        }
        int words = h.aux;                                   // selective part: 64-bit words as two 32-bit halves
        outer:
        for (int w = 0; w < words && 8 * w + 8 <= h.payloadLen; w++) {
            for (int half = 0; half < 2; half++) {
                long start = h.num + 1 + 64L * w + 32L * half;
                long room = nxt - start;                     // bits at or beyond nxt are outside the window
                if (room <= 0) break outer;
                int bits = Segment.getIntLE(h.buf, Segment.HDR + 8 * w + 4 * half);
                if (bits == 0) continue;
                int inWindow = room >= 32 ? -1 : (1 << (int) room) - 1;
                int s0 = (int) start;
                bits = bits & ~boardGet32(s0) & inWindow;    // new bits in the window only
                while (bits != 0) {
                    int b = Integer.numberOfTrailingZeros(bits);
                    boardSet(s0 + b);
                    newly += deliver(s0 + b, nowUs, h.ts);
                    bits &= bits - 1;
                }
            }
        }
        if (newly > 0) {
            probes = 0;
            backoff = 1;
            delivered += newly;
            bwSample(nowUs);
            lossEwma *= Math.pow(0.99, newly);
            armProbe();
        }
        double reo = minRtt < INF ? minRtt / 8 : 0;          // Eq. (5)
        int nLost = 0;
        while (oHead < oLen) {
            int s = oSeq[oHead], txn = oTx[oHead], i = s & MASK;
            long at = oAt[oHead];
            if (s < una || seqAt[i] != s || state[i] != INFLIGHT || tx[i] != txn) {
                oHead++;
                continue;
            }
            if (at + reo >= rackTs) break;
            state[i] = LOST;
            pipe--;
            lostQ.push(s);
            if (nLost == lostBuf.length) lostBuf = Arrays.copyOf(lostBuf, 2 * nLost);
            lostBuf[nLost++] = s;
            oHead++;
        }
        if (oHead > 8192) orderCompact();
        if (nLost > 0) {
            for (int j = 0; j < nLost; j++) lossEwma = 0.99 * lossEwma + 0.01;
            onLoss(lostBuf, nLost, nowUs);
        }
        if (newly > 0) grow(newly, nowUs);
        cb.onProgress(doneSegs, n);
        pump();
    }

    private void updateRtt(double r) {
        latest = r;
        rtts.add(r);
        if (rtts.size() > 16) rtts.remove(0);
        if (r < minRtt) minRtt = r;
        if (srtt == 0) {
            srtt = r;
            rttvar = r / 2;
        } else {
            rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - r);
            srtt = 0.875 * srtt + 0.125 * r;
        }
        rto = Math.max(srtt + Math.max(1000, 4 * rttvar) + ACK_DELAY_US, RTO_MIN_US);
        if (hystart && cwnd < ssthresh && cwnd >= 16 && rtts.size() >= 8) {
            double thr = Math.min(Math.max(minRtt / 8, 4000), 16000);
            double lowest = INF;
            for (int j = rtts.size() - 8; j < rtts.size(); j++) lowest = Math.min(lowest, rtts.get(j));
            if (lowest >= minRtt + thr) {
                ssthresh = cwnd;
                hystart = false;
            }
        }
    }

    private void bwSample(long nowUs) {                      // delivery rate over one round trip, max over ten
        rateHist.addLast(new long[] {nowUs, delivered});
        double win = srtt > 0 ? srtt : 100000;
        while (rateHist.size() > 2) {
            Iterator<long[]> it = rateHist.iterator();
            it.next();
            if (it.next()[0] <= nowUs - win) rateHist.pollFirst();
            else break;
        }
        long[] first = rateHist.peekFirst();
        if (nowUs > first[0]) {
            double r = (double) (delivered - first[1]) / (nowUs - first[0]);
            while (!bwHist.isEmpty() && bwHist.peekLast()[1] <= r) bwHist.pollLast();
            bwHist.addLast(new double[] {nowUs, r});
            while (bwHist.peekFirst()[0] < nowUs - 10 * win) bwHist.pollFirst();
        }
    }

    private double btlbw() {
        return bwHist.isEmpty() ? 0 : bwHist.peekFirst()[1];
    }

    private void onLoss(int[] lost, int count, long nowUs) {
        int[] sorted = Arrays.copyOf(lost, count);
        Arrays.sort(sorted);
        int first = sorted[0];
        if (first < recEnd || first < decEnd) return;        // one decision per window of data
        stats.events++;
        boolean cong = true;
        if (clf != null && srtt > 0) {
            int run = 1;
            while (run < count && sorted[run] == first + run) run++;
            double[] x = LossClassifier.features(latest, minRtt, rtts, run, lossEwma, pipe + count, btlbw(),
                    nowUs - lastEventUs, srtt);
            cong = clf.prob(x) >= theta;                     // Eq. (11)
        }
        lastEventUs = nowUs;
        decEnd = nxt;
        if (cong) {
            recEnd = nxt;
            reduce();
        } else {
            stats.keptWindow++;
        }
    }

    private void reduce() {
        stats.reductions++;
        wmax = cwnd < wmax ? cwnd * (1 + BETA) / 2 : cwnd;   // fast convergence
        cwnd = Math.max(cwnd * BETA, 2);
        ssthresh = cwnd;
        epochSet = false;
        hystart = false;
    }

    private void grow(int newly, long nowUs) {
        if (una < recEnd) return;
        if (cwnd < ssthresh) {
            cwnd = Math.min(cwnd + newly, Segment.RING);
            return;
        }
        double t = nowUs / 1e6;
        if (!epochSet) {
            epochSet = true;
            epoch = t;
            if (cwnd < wmax) {
                k = Math.cbrt((wmax - cwnd) / C);
                origin = wmax;
            } else {
                k = 0;
                origin = cwnd;
            }
            west = cwnd;
        }
        double tt = t - epoch + (minRtt < INF ? minRtt / 1e6 : 0.1);
        double target = origin + C * Math.pow(tt - k, 3);
        double inc = target > cwnd ? (target - cwnd) / cwnd * newly : 0.01 / cwnd * newly;
        west += 3 * (1 - BETA) / (1 + BETA) * newly / cwnd;  // Reno-friendly estimate
        cwnd = Math.min(Math.max(cwnd + inc, west), Segment.RING);
    }

    private void armProbe() {
        if (done) return;
        if (una >= nxt && lostQ.size() == 0) {
            wheel.cancel(idProbe);
            return;
        }
        double us = Math.min(rto * backoff, PROBE_CAP_US);
        wheel.after((long) Math.ceil(us / 1000), probeTask, idProbe);
    }

    private void onProbe() {                                 // RTO: the oldest segment in flight becomes a probe
        if (done) return;
        if (++probes > MAX_PROBES) {
            finish(false, "peer not responding");
            return;
        }
        for (int s = una; s < nxt; s++) {
            int i = s & MASK;
            if (seqAt[i] == s && state[i] == INFLIGHT) {
                state[i] = LOST;
                pipe--;
                lostQ.push(s);
                break;
            }
        }
        if (probes >= 3) {                                   // persistent congestion
            ssthresh = Math.max(cwnd * BETA, 2);
            cwnd = 2;
            epochSet = false;
            recEnd = nxt;
        }
        backoff = Math.min(backoff * 1.5, 64);
        while (lostQ.size() > 0) {                           // send one segment even if the window is full
            int s = lostQ.pop(), i = s & MASK;
            if (s >= una && seqAt[i] == s && state[i] == LOST) {
                stats.retx++;
                transmit(s, true);
                break;
            }
        }
        armProbe();
        pump();
    }

    private void sendFin() {
        if (done || (finSent && wheel.has(idFin))) return;
        wheel.cancel(idProbe);
        if (++finTries > 12) {
            finish(false, "no FINACK");
            return;
        }
        finSent = true;
        link.send(Segment.header(Segment.FIN, 0, 0, 0, connId, n, link.nowUs() % U32, 0));
        double us = Math.min((srtt > 0 ? rto : 500000) * finTries, PROBE_CAP_US);
        wheel.after((long) Math.ceil(us / 1000), finTask, idFin);
    }

    public void onFinAck(Segment h) {
        boolean ok = h.payloadLen > 0 && h.buf[Segment.HDR] == Segment.STATUS_OK;
        finish(ok, ok ? "ok" : "hash mismatch");
    }

    /** Ends the transfer once: cancels the timers and reports to the owner. */
    void finish(boolean ok, String reason) {
        if (done) return;
        done = true;
        wheel.cancel(idProbe);
        wheel.cancel(idPace);
        wheel.cancel(idFin);
        cb.onDone(ok, reason, stats);
    }
}
