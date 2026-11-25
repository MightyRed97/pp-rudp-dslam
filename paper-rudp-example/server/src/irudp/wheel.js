'use strict';
// Two-level hashed timing wheel shared by all sessions (paper, Sec. 4.5 and Listing 3).
// Level 0: 256 slots of 1 ms; level 1: 256 slots of 256 ms (range 65.5 s).
// start/cancel are O(1); a tick visits one slot; one level-1 slot cascades every 256 ms.

class TimingWheel {
  constructor(n0 = 256, n1 = 256) {
    this.n0 = n0; this.n1 = n1;
    this.l0 = Array.from({ length: n0 }, () => new Map());   // slot: timerId -> [expiry, callback]
    this.l1 = Array.from({ length: n1 }, () => new Map());
    this.now = 0;                                             // current tick (ms)
    this.where = new Map();                                   // timerId -> slot, for O(1) cancel
    this.nextId = 1;
    this.onError = (e) => console.error('timer callback failed:', e);   // one failing callback must not stop the others
  }

  start(expiry, cb, id) {
    if (id === undefined) id = this.nextId++;
    this.cancel(id);
    const exp = Math.max(Math.ceil(expiry), this.now + 1);    // a timer that is already due fires at the next tick
    const slot = exp - this.now < this.n0 ? this.l0[exp % this.n0]
      : this.l1[Math.floor(exp / this.n0) % this.n1];
    slot.set(id, [exp, cb]); this.where.set(id, slot);
    return id;
  }

  after(ms, cb, id) { return this.start(this.now + Math.max(1, ms), cb, id); }

  cancel(id) {
    const slot = this.where.get(id);
    if (slot) { slot.delete(id); this.where.delete(id); }
  }

  tick() {
    this.now += 1;
    if (this.now % this.n0 === 0) {                           // move one level-1 slot down to level 0
      const moved = this.l1[Math.floor(this.now / this.n0) % this.n1];
      for (const [id, entry] of [...moved]) {
        moved.delete(id);
        const slot = this.l0[entry[0] % this.n0];              // expiry lies in [now, now + 256)
        slot.set(id, entry); this.where.set(id, slot);
      }
    }
    const due = this.l0[this.now % this.n0];
    for (const id of [...due.keys()]) {
      const entry = due.get(id);                               // may have been cancelled by an earlier callback
      if (!entry || entry[0] > this.now) continue;
      due.delete(id); this.where.delete(id);
      try { entry[1](id); } catch (e) { this.onError(e); }
    }
  }

  advanceTo(nowMs) {                                           // run every tick up to the current time
    let n = 0;
    while (this.now < nowMs && n < 1000) { this.tick(); n++; }
    if (this.now < nowMs) {
      // After a long stall (a sleeping laptop or phone) skip ahead. The skipped level-1 slots would not
      // cascade until the wheel came round again, 65.5 s later, so every pending timer is filed again;
      // the overdue ones fire at the next tick.
      const pending = [];
      for (const [id, slot] of this.where) pending.push([id, slot.get(id)]);
      for (const [id, slot] of this.where) slot.delete(id);
      this.where.clear();
      this.now = Math.floor(nowMs);
      for (const [id, [exp, cb]] of pending) this.start(exp, cb, id);
    }
  }
}

module.exports = { TimingWheel };
