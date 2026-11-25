package com.example.irudpchat.irudp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * A sender and a receiver connected back to back in memory (one step = 200 us), as in the loopback
 * tests of server/test/unit-test.js: selected DATA segments are dropped, or held for a while (a delay
 * spike without loss).
 */
public class LoopbackTest {
    interface Drop {
        boolean drop(long seq, int sentCount);
    }

    static final class Result {
        boolean finished, ok, rxDone, equal;
        String reason;
        IrudpSender.Stats stats;
        int dups;
    }

    private static Link link(final TimingWheel wheel, final long[] clock, final ArrayDeque<byte[]> queue) {
        return new Link() {
            @Override
            public void send(byte[] packet) {
                queue.add(packet);
            }

            @Override
            public long nowUs() {
                return clock[0];
            }

            @Override
            public TimingWheel wheel() {
                return wheel;
            }
        };
    }

    private static Result run(int size, Drop drop, Predicate<IrudpSender> holdWhen, int holdSteps) {
        final TimingWheel wheel = new TimingWheel();
        final long[] clock = {0};
        final byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) ((i * 31 + 7) & 0xff);
        final byte[] out = new byte[size];
        final ArrayDeque<byte[]> toRx = new ArrayDeque<>(), toTx = new ArrayDeque<>();
        final Result res = new Result();
        IrudpReceiver rx = new IrudpReceiver(9, size, null, link(wheel, clock, toTx), new ReceiverCallbacks() {
            @Override
            public void write(int seq, byte[] src, int off, int len) {
                System.arraycopy(src, off, out, seq * Segment.MSS, len);
            }

            @Override
            public void onBlock(int b, byte[] heldBitmap) {
            }

            @Override
            public boolean verify() {
                return Arrays.equals(out, data);
            }

            @Override
            public void onProgress(long done, long total) {
            }

            @Override
            public void onDone(boolean ok, String reason) {
                res.rxDone = true;
            }
        });
        IrudpSender tx = new IrudpSender(9, size, null, link(wheel, clock, toRx), null, 0.4, new SenderCallbacks() {
            @Override
            public void read(int seq, byte[] dst, int off, int len) {
                System.arraycopy(data, seq * Segment.MSS, dst, off, len);
            }

            @Override
            public void onProgress(long done, long total) {
            }

            @Override
            public void onDone(boolean ok, String reason, IrudpSender.Stats stats) {
                res.finished = true;
                res.ok = ok;
                res.reason = reason;
                res.stats = stats;
            }
        });
        tx.start();
        int sentCount = 0, holdUntil = -1;
        for (int step = 0; step < 200000 && !res.finished; step++) {
            clock[0] += 200;
            wheel.advanceTo(clock[0] / 1000);
            if (holdWhen != null && holdUntil < 0 && holdWhen.test(tx)) holdUntil = step + holdSteps;
            boolean holding = step < holdUntil;
            while (!holding && !toRx.isEmpty()) {
                Segment h = Segment.parse(toRx.poll());
                if (h.type == Segment.DATA) {
                    sentCount++;
                    if (drop.drop(h.num, sentCount)) continue;
                    if (rx.has((int) h.num)) res.dups++;
                    rx.onData(h);
                } else if (h.type == Segment.FIN) {
                    rx.onFin(h);
                }
            }
            while (!toTx.isEmpty()) {
                Segment h = Segment.parse(toTx.poll());
                if (h.type == Segment.ACK) tx.onAck(h);
                else if (h.type == Segment.FINACK) tx.onFinAck(h);
            }
        }
        res.equal = Arrays.equals(out, data);
        return res;
    }

    @Test
    public void withoutLossTheFileArrivesAndNothingIsResent() {
        Result r = run(500000, (seq, n) -> false, null, 0);
        assertTrue(r.finished && r.ok && r.rxDone && r.equal);
        assertEquals(0, r.stats.retx);
    }

    @Test
    public void withFivePercentLossTheLostSegmentsAreResent() {
        final long[] seed = {3};
        Result r = run(2000000, (seq, n) -> {
            seed[0] = (seed[0] * 1103515245L + 12345L) % 2147483648L;
            return seed[0] / 2147483648.0 < 0.05;
        }, null, 0);
        assertTrue(String.valueOf(r.reason), r.finished && r.ok && r.equal);
        assertTrue(r.stats.retx > 0);
    }

    @Test
    public void aDelaySpikeLongerThanTheRtoResendsOnlyTheProbes() {
        // with 100 segments in flight, nothing is delivered for 400 ms (twice the RTO), and nothing is lost
        Result r = run(2000000, (seq, n) -> false, tx -> tx.pipe() >= 100, 2000);
        assertTrue(String.valueOf(r.reason), r.finished && r.ok && r.equal);
        assertTrue("resent " + r.stats.retx, r.stats.retx >= 1 && r.stats.retx <= 3);
        assertEquals(r.stats.retx, r.dups);
    }

    @Test
    public void listingExampleHolesAt10And11GiveCumulativeAck10AndWord0x1ffffffe() {
        TimingWheel wheel = new TimingWheel();
        ArrayDeque<byte[]> acks = new ArrayDeque<>();
        IrudpReceiver rx = new IrudpReceiver(1, 40L * Segment.MSS, null, link(wheel, new long[] {0}, acks), new ReceiverCallbacks() {
            @Override
            public void write(int seq, byte[] src, int off, int len) {
            }

            @Override
            public void onBlock(int b, byte[] heldBitmap) {
            }

            @Override
            public boolean verify() {
                return true;
            }

            @Override
            public void onProgress(long done, long total) {
            }

            @Override
            public void onDone(boolean ok, String reason) {
            }
        });
        for (int s = 0; s < 40; s++) {
            if (s == 10 || s == 11) continue;
            rx.onData(Segment.parse(Segment.header(Segment.DATA, 0, 64, 1024, 1, s, 0, Segment.MSS)));
        }
        rx.sendAck();
        Segment a = Segment.parse(acks.peekLast());
        assertEquals(10, a.num);
        assertEquals(1, a.aux);
        long word = 0;
        for (int i = 0; i < 8; i++) word |= (long) (a.buf[Segment.HDR + i] & 0xff) << (8 * i);
        assertEquals(0x1ffffffeL, word);
    }
}
