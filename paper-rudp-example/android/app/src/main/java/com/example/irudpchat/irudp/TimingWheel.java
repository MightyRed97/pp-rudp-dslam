package com.example.irudpchat.irudp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Two-level hashed timing wheel (paper, Sec. 4.5 and Listing 3), the same as server/src/irudp/wheel.js.
 * Level 0: 256 slots of 1 ms; level 1: 256 slots of 256 ms (range 65.5 s). start and cancel are O(1);
 * a tick visits one slot; one level-1 slot cascades every 256 ms. Not thread-safe: an endpoint uses
 * its wheel from its own thread only.
 */
public final class TimingWheel {
    public interface ErrorHandler {
        void onError(RuntimeException e);
    }

    private static final class Timer {
        final long exp;
        final Runnable cb;

        Timer(long exp, Runnable cb) {
            this.exp = exp;
            this.cb = cb;
        }
    }

    private final int n0, n1;
    private final List<LinkedHashMap<String, Timer>> l0 = new ArrayList<>();
    private final List<LinkedHashMap<String, Timer>> l1 = new ArrayList<>();
    private final LinkedHashMap<String, LinkedHashMap<String, Timer>> where = new LinkedHashMap<>();   // id -> slot
    private long now;                                         // current tick (ms)
    private long nextId = 1;
    private ErrorHandler onError = e -> e.printStackTrace();  // one failing callback must not stop the others

    public TimingWheel() {
        this(256, 256);
    }

    public TimingWheel(int n0, int n1) {
        this.n0 = n0;
        this.n1 = n1;
        for (int i = 0; i < n0; i++) l0.add(new LinkedHashMap<String, Timer>());
        for (int i = 0; i < n1; i++) l1.add(new LinkedHashMap<String, Timer>());
    }

    public long now() {
        return now;
    }

    public void setErrorHandler(ErrorHandler handler) {
        onError = handler;
    }

    public boolean has(String id) {
        return where.containsKey(id);
    }

    public String start(long expiry, Runnable cb) {
        return start(expiry, cb, "t" + (nextId++));
    }

    /** Runs cb at tick expiry (at the next tick if that is already past); replaces a timer with the same id. */
    public String start(long expiry, Runnable cb, String id) {
        cancel(id);
        long exp = Math.max(expiry, now + 1);
        LinkedHashMap<String, Timer> slot = exp - now < n0 ? l0.get((int) (exp % n0)) : l1.get((int) ((exp / n0) % n1));
        slot.put(id, new Timer(exp, cb));
        where.put(id, slot);
        return id;
    }

    public String after(long ms, Runnable cb, String id) {
        return start(now + Math.max(1, ms), cb, id);
    }

    public void cancel(String id) {
        LinkedHashMap<String, Timer> slot = where.remove(id);
        if (slot != null) slot.remove(id);
    }

    public void tick() {
        now += 1;
        if (now % n0 == 0) {                                  // move one level-1 slot down to level 0
            LinkedHashMap<String, Timer> moved = l1.get((int) ((now / n0) % n1));
            if (!moved.isEmpty()) {
                List<String> ids = new ArrayList<>(moved.keySet());
                List<Timer> timers = new ArrayList<>(moved.values());
                moved.clear();
                for (int i = 0; i < ids.size(); i++) {
                    LinkedHashMap<String, Timer> slot = l0.get((int) (timers.get(i).exp % n0));   // expiry lies in [now, now + 256)
                    slot.put(ids.get(i), timers.get(i));
                    where.put(ids.get(i), slot);
                }
            }
        }
        LinkedHashMap<String, Timer> due = l0.get((int) (now % n0));
        if (due.isEmpty()) return;
        for (String id : new ArrayList<>(due.keySet())) {
            Timer t = due.get(id);                            // may have been cancelled by an earlier callback
            if (t == null || t.exp > now) continue;
            due.remove(id);
            where.remove(id);
            try {
                t.cb.run();
            } catch (RuntimeException e) {
                onError.onError(e);
            }
        }
    }

    /** Runs every tick up to nowMs. */
    public void advanceTo(long nowMs) {
        int n = 0;
        while (now < nowMs && n < 1000) {
            tick();
            n++;
        }
        if (now < nowMs) {
            // After a long stall (a sleeping phone) skip ahead. The skipped level-1 slots would not cascade
            // until the wheel came round again, 65.5 s later, so every pending timer is filed again; the
            // overdue ones fire at the next tick.
            List<String> ids = new ArrayList<>(where.keySet());
            List<Timer> timers = new ArrayList<>();
            for (String id : ids) timers.add(where.get(id).get(id));
            for (String id : ids) where.get(id).remove(id);
            where.clear();
            now = nowMs;
            for (int i = 0; i < ids.size(); i++) start(timers.get(i).exp, timers.get(i).cb, ids.get(i));
        }
    }
}
