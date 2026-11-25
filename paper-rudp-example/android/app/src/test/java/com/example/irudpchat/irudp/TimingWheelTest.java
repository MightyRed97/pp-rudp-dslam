package com.example.irudpchat.irudp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The same cases as the wheel test in server/test/unit-test.js. */
public class TimingWheelTest {
    @Test
    public void dueReachedCascadedAndCancelledTimersFireOnceAtTheirTick() {
        final TimingWheel w = new TimingWheel();
        final List<String> fired = new ArrayList<>();
        for (int i = 0; i < 1000; i++) w.tick();
        w.start(w.now(), () -> fired.add("now@" + (w.now() - 1000)));
        w.start(w.now() - 50, () -> fired.add("past@" + (w.now() - 1000)));
        w.start(1048, () -> fired.add("exact@" + (w.now() - 1000)));
        w.start(1700, () -> fired.add("long@" + (w.now() - 1000)));
        final String[] b = new String[1];
        String a = w.start(1010, () -> {
            fired.add("a@" + (w.now() - 1000));
            w.cancel(b[0]);
        });
        b[0] = w.start(1010, () -> fired.add("b@" + (w.now() - 1000)));
        for (int i = 0; i < 2000; i++) w.tick();
        assertEquals(Arrays.asList("now@1", "past@1", "a@10", "exact@48", "long@700"), fired);
        assertNotEquals(a, b[0]);
    }

    @Test
    public void fiveThousandRandomTimersAreNeverLate() {
        final TimingWheel w = new TimingWheel();
        final int[] count = {0, 0};                    // fired, late
        long seed = 7;
        for (int i = 0; i < 5000; i++) {
            seed = (seed * 1103515245L + 12345L) % 2147483648L;
            final long e = 1 + (long) Math.floor(seed / 2147483648.0 * 60000);
            w.start(e, () -> {
                count[0]++;
                if (w.now() != e) count[1]++;
            });
        }
        for (int i = 0; i < 60001; i++) w.tick();
        assertEquals(5000, count[0]);
        assertEquals(0, count[1]);
    }

    @Test
    public void aLongStallDoesNotStrandLevelOneTimers() {
        final TimingWheel w = new TimingWheel();
        final List<String> got = new ArrayList<>();
        w.after(500, () -> got.add("a@" + w.now()), "a");
        w.after(1500, () -> got.add("b@" + w.now()), "b");    // level 1; its cascade at 1,280 ms is skipped
        w.after(9000, () -> got.add("c@" + w.now()), "c");
        w.advanceTo(5000);                                     // a stall of 5 s
        assertEquals(Arrays.asList("a@500"), got);
        w.advanceTo(5001);
        assertEquals(Arrays.asList("a@500", "b@5001"), got);
        w.advanceTo(8999);
        assertEquals(2, got.size());
        w.advanceTo(9000);
        assertEquals(Arrays.asList("a@500", "b@5001", "c@9000"), got);
    }

    @Test
    public void aFailingCallbackDoesNotStopTheOthers() {
        final TimingWheel w = new TimingWheel();
        final List<String> got = new ArrayList<>();
        w.setErrorHandler(e -> got.add("error"));
        w.after(5, () -> {
            throw new IllegalStateException("boom");
        }, "x");
        w.after(5, () -> got.add("y"), "y");
        w.advanceTo(10);
        assertEquals(Arrays.asList("error", "y"), got);
    }
}
