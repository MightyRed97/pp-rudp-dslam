'use strict';
// IRUDP sender (paper, Sec. 4.3 and 4.7, Listing 1 and Algorithm 1).
// Ring of 1,024 slots indexed by sequence number, a scoreboard bitmap aligned with the ring,
// time-based loss marking (Eq. 5), one probe timer per session in the shared wheel (Eq. 6),
// CUBIC (Eq. 8) with pacing, and the GBDT classifier gating each window reduction (Eqs. 9-11).
const S = require('./segment');
const { lossFeatures } = require('./classifier');

const MASK = S.RING - 1;
const FREE = 0, INFLIGHT = 1, SACKED = 2, LOST = 3;
const BETA = 0.7, C = 0.4;
const RTO_MIN_US = 200000, ACK_DELAY_US = 10000, PROBE_CAP_US = 2000000, MAX_PROBES = 15;
const U32 = 4294967296;

class MinHeap {
  constructor() { this.a = []; }
  get size() { return this.a.length; }
  peek() { return this.a[0]; }
  push(v) {
    const a = this.a; a.push(v);
    for (let i = a.length - 1; i > 0;) { const p = (i - 1) >> 1; if (a[p] <= a[i]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; }
  }
  pop() {
    const a = this.a; const top = a[0]; const last = a.pop();
    if (a.length) {
      a[0] = last;
      for (let i = 0; ;) {
        const l = 2 * i + 1, r = l + 1; let m = i;
        if (l < a.length && a[l] < a[m]) m = l;
        if (r < a.length && a[r] < a[m]) m = r;
        if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m;
      }
    }
    return top;
  }
}

class IrudpSender {
  constructor({ connId, size, readSegment, held, send, wheel, now, classifier, theta = 0.4, onProgress, onDone }) {
    this.connId = connId; this.size = size; this.N = S.segmentsFor(size);
    this.readSegment = readSegment; this.send = send; this.wheel = wheel; this.now = now;
    this.clf = classifier; this.theta = theta;
    this.onProgress = onProgress || (() => {}); this.onDone = onDone || (() => {});
    this.held = held || null;                         // block bitmap of the receiver (resume)
    // ring and scoreboard (Listing 1)
    this.state = new Uint8Array(S.RING); this.sentAt = new Float64Array(S.RING);
    this.tx = new Uint16Array(S.RING); this.seqAt = new Float64Array(S.RING).fill(-1);
    this.board = new Uint32Array(S.RING / 32);        // bit (seq & 1023) set = delivered
    this.una = 0; this.nxt = 0; this.rackTs = -1; this.pipe = 0;
    this.lostQ = new MinHeap(); this.order = []; this.orderHead = 0;   // order: [seq, tx, sentAt] by send time
    // round-trip estimator (Eq. 6)
    this.srtt = 0; this.rttvar = 0; this.minRtt = Infinity; this.latest = 0; this.rtts = []; this.rto = 1000000;
    // CUBIC (Eq. 8)
    this.cwnd = 10; this.ssthresh = Infinity; this.wmax = 0; this.k = 0; this.origin = 0; this.epoch = null; this.west = 0;
    this.recEnd = -1; this.decEnd = -1; this.hystart = true;
    // classifier inputs (Eq. 9)
    this.lossEwma = 0; this.lastEventUs = now(); this.rateHist = []; this.bwHist = []; this.delivered = 0;
    // pacing, probe timer and FIN
    this.nextSendUs = 0; this.backoff = 1; this.probes = 0; this.finSent = false; this.finTries = 0; this.done = false;
    this.ids = { probe: `probe:${connId}`, pace: `pace:${connId}`, fin: `fin:${connId}` };
    this.stats = { sent: 0, retx: 0, events: 0, reductions: 0, keptWindow: 0, skipped: 0 };
    // segments of blocks the receiver already holds count as done; a held prefix is skipped outright
    this.doneSegs = 0;
    if (this.held) for (let s = 0; s < this.N; s++) if (this.isHeld(s)) this.doneSegs++;
    while (this.una < this.N && this.isHeld(this.una)) { this.una++; this.stats.skipped++; }
    this.nxt = this.una;
  }

  isHeld(seq) { return this.held !== null && S.bitGet(this.held, Math.floor(seq / S.BLOCK_SEGS)) === 1; }

  boardGet32(seq) {                                    // 32 scoreboard bits for seq .. seq + 31
    const i = seq & MASK, w = i >>> 5, off = i & 31;
    let v = this.board[w] >>> off;
    if (off) v |= this.board[(w + 1) & 31] << (32 - off);
    return v >>> 0;
  }
  boardSet(seq) { const i = seq & MASK; this.board[i >>> 5] |= 1 << (i & 31); }
  boardClear(seq) { const i = seq & MASK; this.board[i >>> 5] &= ~(1 << (i & 31)); }

  start() { this.onProgress(this.doneSegs, this.N); this.pump(); }

  ackFreq() { return Math.min(Math.max(Math.floor(this.cwnd / 8), 2), 8); }          // Eq. (7)

  pump() {
    if (this.done) return;
    for (let budget = 256; budget > 0; budget--) {
      if (this.una >= this.N) { this.sendFin(); return; }
      while (this.lostQ.size) {                        // drop queue entries that were delivered meanwhile
        const s = this.lostQ.peek(), i = s & MASK;
        if (s >= this.una && this.seqAt[i] === s && this.state[i] === LOST) break;
        this.lostQ.pop();
      }
      const retx = this.lostQ.size > 0;
      if (!retx) {
        while (this.nxt < this.N && this.nxt < this.una + S.RING && this.isHeld(this.nxt)) {
          const i = this.nxt & MASK;                     // a held block inside the window: pre-acknowledged
          this.state[i] = SACKED; this.seqAt[i] = this.nxt; this.tx[i] = 0; this.boardSet(this.nxt);
          this.nxt++; this.stats.skipped++;
        }
        if (this.nxt >= this.N || this.nxt >= this.una + S.RING) return;
      }
      if (this.pipe >= this.cwnd) return;              // window full: the next ACK pumps again
      const nowUs = this.now();
      if (this.srtt > 0 && nowUs < this.nextSendUs) { // paced: wait for the release time in the wheel
        this.wheel.after(Math.ceil((this.nextSendUs - nowUs) / 1000), () => this.pump(), this.ids.pace);
        return;
      }
      let seq;
      if (retx) { seq = this.lostQ.pop(); this.stats.retx++; } else { seq = this.nxt++; }
      this.transmit(seq, retx);
      if (this.srtt > 0) {
        const g = this.cwnd < this.ssthresh ? 2 : 1.25;
        this.nextSendUs = Math.max(this.nextSendUs, nowUs - 20000) + this.srtt / (g * this.cwnd);
      }
    }
    this.wheel.after(1, () => this.pump(), this.ids.pace);
  }

  transmit(seq, retx) {
    const i = seq & MASK, nowUs = this.now();
    const payload = this.readSegment(seq);
    const b = S.header(S.T.DATA, retx ? S.F.RETX : 0, this.ackFreq(), S.RING, this.connId, seq, nowUs % U32, payload.length);
    payload.copy(b, S.HDR);
    if (this.seqAt[i] !== seq) this.tx[i] = 0;
    this.state[i] = INFLIGHT; this.seqAt[i] = seq; this.sentAt[i] = nowUs; this.tx[i]++;
    this.order.push([seq, this.tx[i], nowUs]);
    this.pipe++; this.stats.sent++;
    this.send(b);
    if (!this.wheel.where.has(this.ids.probe)) this.armProbe();
  }

  deliver(s, nowUs, echo) {
    const i = s & MASK;
    if (s < this.una || this.seqAt[i] !== s) return 0;
    const st = this.state[i];
    if (st !== INFLIGHT && st !== LOST) return 0;
    if (st === INFLIGHT) this.pipe--;
    this.state[i] = SACKED;
    // RFC 8985, 6.2: a resent segment may have been delivered by its first copy (after a delay spike, the
    // probe resends a segment that was only late). Then its last send time says nothing about the
    // segments sent after the first copy and must not advance the RACK time. That is so when the ACK
    // echoes a time before the resend, or comes sooner than min RTT after it.
    if (this.tx[i] > 1) {
      const echoBefore = ((this.sentAt[i] % U32) - echo + U32) % U32;
      if ((echoBefore > 0 && echoBefore < U32 / 2) || nowUs - this.sentAt[i] < this.minRtt) { this.doneSegs++; return 1; }
    }
    if (this.sentAt[i] > this.rackTs) this.rackTs = this.sentAt[i];
    this.doneSegs++;
    return 1;
  }

  onAck(h) {
    if (this.done || h.num < this.una) return;         // stale or reordered acknowledgement
    const nowUs = this.now();
    const rtt = ((nowUs % U32) - h.ts + U32) % U32;
    if (rtt > 0 && rtt < 60e6) this.updateRtt(rtt);
    let newly = 0;
    const cum = Math.min(h.num, this.nxt);
    while (this.una < cum) {                            // cumulative part frees slots in order
      newly += this.deliver(this.una, nowUs, h.ts);
      const i = this.una & MASK;
      this.state[i] = FREE; this.tx[i] = 0; this.seqAt[i] = -1; this.boardClear(this.una);
      this.una++;
    }
    const k = h.aux, p = h.payload;                    // selective part: 64-bit words as two 32-bit halves
    outer: for (let w = 0; w < k && 8 * w + 8 <= p.length; w++) {
      for (let half = 0; half < 2; half++) {
        const start = h.num + 1 + 64 * w + 32 * half;
        const room = this.nxt - start;                 // bits at or beyond nxt are outside the window
        if (room <= 0) break outer;
        let bits = p.readUInt32LE(8 * w + 4 * half);
        if (!bits) continue;
        const inWindow = room >= 32 ? 0xffffffff : 2 ** room - 1;
        bits = (bits & ~this.boardGet32(start) & inWindow) >>> 0;   // new bits in the window only
        while (bits) {
          const low = (bits & -bits) >>> 0, b = 31 - Math.clz32(low);
          this.boardSet(start + b); newly += this.deliver(start + b, nowUs, h.ts);
          bits = (bits ^ low) >>> 0;
        }
      }
    }
    if (newly) {
      this.probes = 0; this.backoff = 1; this.delivered += newly;
      this.bwSample(nowUs); this.lossEwma *= Math.pow(0.99, newly);
      this.armProbe();
    }
    const reo = this.minRtt < Infinity ? this.minRtt / 8 : 0;    // Eq. (5)
    const lost = [];
    while (this.orderHead < this.order.length) {
      const [s, txn, at] = this.order[this.orderHead], i = s & MASK;
      if (s < this.una || this.seqAt[i] !== s || this.state[i] !== INFLIGHT || this.tx[i] !== txn) { this.orderHead++; continue; }
      if (at + reo >= this.rackTs) break;
      this.state[i] = LOST; this.pipe--; this.lostQ.push(s); lost.push(s); this.orderHead++;
    }
    if (this.orderHead > 8192) { this.order = this.order.slice(this.orderHead); this.orderHead = 0; }
    if (lost.length) {
      for (let j = 0; j < lost.length; j++) this.lossEwma = 0.99 * this.lossEwma + 0.01;
      this.onLoss(lost, nowUs);
    }
    if (newly) this.grow(newly, nowUs);
    this.onProgress(this.doneSegs, this.N);
    this.pump();
  }

  updateRtt(r) {
    this.latest = r; this.rtts.push(r); if (this.rtts.length > 16) this.rtts.shift();
    if (r < this.minRtt) this.minRtt = r;
    if (!this.srtt) { this.srtt = r; this.rttvar = r / 2; }
    else { this.rttvar = 0.75 * this.rttvar + 0.25 * Math.abs(this.srtt - r); this.srtt = 0.875 * this.srtt + 0.125 * r; }
    this.rto = Math.max(this.srtt + Math.max(1000, 4 * this.rttvar) + ACK_DELAY_US, RTO_MIN_US);
    if (this.hystart && this.cwnd < this.ssthresh && this.cwnd >= 16 && this.rtts.length >= 8) {
      const thr = Math.min(Math.max(this.minRtt / 8, 4000), 16000);
      if (Math.min(...this.rtts.slice(-8)) >= this.minRtt + thr) { this.ssthresh = this.cwnd; this.hystart = false; }
    }
  }

  bwSample(nowUs) {                                     // delivery rate over one round trip, max over ten
    const h = this.rateHist; h.push([nowUs, this.delivered]);
    const win = this.srtt || 100000;
    while (h.length > 2 && h[1][0] <= nowUs - win) h.shift();
    const [t0, d0] = h[0];
    if (nowUs > t0) {
      const r = (this.delivered - d0) / (nowUs - t0), b = this.bwHist;
      while (b.length && b[b.length - 1][1] <= r) b.pop();
      b.push([nowUs, r]);
      while (b[0][0] < nowUs - 10 * win) b.shift();
    }
  }
  btlbw() { return this.bwHist.length ? this.bwHist[0][1] : 0; }

  onLoss(lost, nowUs) {
    const first = Math.min(...lost);
    if (first < this.recEnd || first < this.decEnd) return;      // one decision per window of data
    this.stats.events++;
    let cong = true;
    if (this.clf && this.srtt) {
      const set = new Set(lost); let run = 1;
      while (set.has(first + run)) run++;
      const x = lossFeatures({ latestRtt: this.latest, minRtt: this.minRtt, rtts: this.rtts, lostRun: run,
        lossEwma: this.lossEwma, inflight: this.pipe + lost.length, btlRate: this.btlbw(),
        gapUs: nowUs - this.lastEventUs, srtt: this.srtt });
      cong = this.clf.prob(x) >= this.theta;                     // Eq. (11)
    }
    this.lastEventUs = nowUs; this.decEnd = this.nxt;
    if (cong) { this.recEnd = this.nxt; this.reduce(); } else this.stats.keptWindow++;
  }

  reduce() {
    this.stats.reductions++;
    this.wmax = this.cwnd < this.wmax ? this.cwnd * (1 + BETA) / 2 : this.cwnd;   // fast convergence
    this.cwnd = Math.max(this.cwnd * BETA, 2); this.ssthresh = this.cwnd; this.epoch = null; this.hystart = false;
  }

  grow(n, nowUs) {
    if (this.una < this.recEnd) return;
    if (this.cwnd < this.ssthresh) { this.cwnd = Math.min(this.cwnd + n, S.RING); return; }
    const t = nowUs / 1e6;
    if (this.epoch === null) {
      this.epoch = t;
      if (this.cwnd < this.wmax) { this.k = Math.cbrt((this.wmax - this.cwnd) / C); this.origin = this.wmax; }
      else { this.k = 0; this.origin = this.cwnd; }
      this.west = this.cwnd;
    }
    const tt = t - this.epoch + (this.minRtt < Infinity ? this.minRtt / 1e6 : 0.1);
    const target = this.origin + C * Math.pow(tt - this.k, 3);
    const inc = target > this.cwnd ? (target - this.cwnd) / this.cwnd * n : 0.01 / this.cwnd * n;
    this.west += 3 * (1 - BETA) / (1 + BETA) * n / this.cwnd;     // Reno-friendly estimate
    this.cwnd = Math.min(Math.max(this.cwnd + inc, this.west), S.RING);
  }

  armProbe() {
    if (this.done) return;
    if (this.una >= this.nxt && !this.lostQ.size) { this.wheel.cancel(this.ids.probe); return; }
    const us = Math.min(this.rto * this.backoff, PROBE_CAP_US);
    this.wheel.after(Math.ceil(us / 1000), () => this.onProbe(), this.ids.probe);
  }

  onProbe() {                                           // RTO: the oldest segment in flight becomes a probe
    if (this.done) return;
    if (++this.probes > MAX_PROBES) { this.finish(false, 'peer not responding'); return; }
    for (let s = this.una; s < this.nxt; s++) {
      const i = s & MASK;
      if (this.seqAt[i] === s && this.state[i] === INFLIGHT) { this.state[i] = LOST; this.pipe--; this.lostQ.push(s); break; }
    }
    if (this.probes >= 3) {                            // persistent congestion
      this.ssthresh = Math.max(this.cwnd * BETA, 2); this.cwnd = 2; this.epoch = null; this.recEnd = this.nxt;
    }
    this.backoff = Math.min(this.backoff * 1.5, 64);
    while (this.lostQ.size) {                          // send one segment even if the window is full
      const s = this.lostQ.pop(), i = s & MASK;
      if (s >= this.una && this.seqAt[i] === s && this.state[i] === LOST) { this.stats.retx++; this.transmit(s, true); break; }
    }
    this.armProbe();
    this.pump();
  }

  sendFin() {
    if (this.done || (this.finSent && this.wheel.where.has(this.ids.fin))) return;
    this.wheel.cancel(this.ids.probe);
    if (++this.finTries > 12) { this.finish(false, 'no FINACK'); return; }
    this.finSent = true;
    this.send(S.header(S.T.FIN, 0, 0, 0, this.connId, this.N, this.now() % U32, 0));
    const us = Math.min((this.srtt ? this.rto : 500000) * this.finTries, PROBE_CAP_US);
    this.wheel.after(Math.ceil(us / 1000), () => this.sendFin(), this.ids.fin);
  }

  onFinAck(h) { const ok = h.payload.length > 0 && h.payload[0] === S.STATUS.OK; this.finish(ok, ok ? 'ok' : 'hash mismatch'); }

  finish(ok, reason) {
    if (this.done) return;
    this.done = true;
    for (const id of Object.values(this.ids)) this.wheel.cancel(id);
    this.onDone(ok, reason, this.stats);
  }
}

module.exports = { IrudpSender };
