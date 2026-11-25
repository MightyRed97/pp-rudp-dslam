'use strict';
// A UDP proxy that emulates a weak mobile link: a bottleneck rate with a drop-tail queue, Gilbert
// burst loss, one-way delay and outages. Each client source address gets its own upstream socket,
// as a NAT would give it.
//
// Each direction is a FIFO line: packets leave in the order they arrived. (One setTimeout per packet
// would not keep that order: Node runs timers grouped by their delay, so when the event loop wakes
// late, as it does on Windows with its ~15 ms timer, a later packet can overtake an earlier one by
// more than the reordering window of Eq. 5 and every overtaken segment would be resent for nothing.)
const dgram = require('dgram');
const { performance } = require('perf_hooks');

class LossyProxy {
  constructor({ listenPort = 0, targetHost = '127.0.0.1', targetPort, loss = 0, burst = 1, delayMs = 0, rateMbps = 0, queue = 200, seed = 1 }) {
    Object.assign(this, { listenPort, targetHost, targetPort, delayMs, rateMbps, queue });
    this.r = burst > 0 && loss > 0 ? 1 / burst : 1; this.q = loss > 0 ? loss * this.r / (1 - loss) : 0;
    this.bad = { up: false, down: false }; this.seed = seed; this.outageUntil = 0;
    this.busyUntil = { up: 0, down: 0 };
    this.line = { up: [], down: [] }; this.head = { up: 0, down: 0 }; this.timer = { up: null, down: null };
    this.flows = new Map(); this.stats = { forwarded: 0, dropped: 0, queueDrops: 0 };
  }

  rand() { this.seed = (this.seed * 1103515245 + 12345) % 2147483648; return this.seed / 2147483648; }

  lost(dir) {                                         // two-state Gilbert model per direction
    if (performance.now() < this.outageUntil) return true;
    const u = this.rand();
    if (this.bad[dir]) { if (u < this.r) this.bad[dir] = false; } else if (u < this.q) this.bad[dir] = true;
    return this.bad[dir];
  }

  forward(sock, buf, port, host, dir) {
    const now = performance.now();
    let departure = now;
    if (this.rateMbps > 0) {                            // serialise at the bottleneck rate; drop when the queue is full
      const txMs = (buf.length + 28) * 8 / (this.rateMbps * 1000);
      const start = Math.max(now, this.busyUntil[dir]);
      if ((start - now) / txMs >= this.queue) { this.stats.queueDrops++; return; }
      departure = start + txMs; this.busyUntil[dir] = departure;
    }
    if (this.lost(dir)) { this.stats.dropped++; return; }
    this.stats.forwarded++;
    this.line[dir].push({ at: departure + this.delayMs, sock, buf, port, host });   // release times never decrease
    if (!this.timer[dir]) this.drain(dir);
  }

  drain(dir) {                                        // send, in arrival order, every packet whose time has come
    this.timer[dir] = null;
    const line = this.line[dir], now = performance.now();
    while (this.head[dir] < line.length && line[this.head[dir]].at <= now) {
      const p = line[this.head[dir]++];
      try { p.sock.send(p.buf, p.port, p.host); } catch (e) { /* socket closed */ }
    }
    if (this.head[dir] > 4096) { line.splice(0, this.head[dir]); this.head[dir] = 0; }
    if (this.head[dir] < line.length) this.timer[dir] = setTimeout(() => this.drain(dir), Math.max(1, line[this.head[dir]].at - now));
  }

  outage(ms) { this.outageUntil = performance.now() + ms; }

  start() {
    return new Promise((resolve) => {
      this.sock = dgram.createSocket('udp4');
      this.sock.on('message', (buf, rinfo) => {
        const key = `${rinfo.address}:${rinfo.port}`;
        let up = this.flows.get(key);
        if (!up) {
          up = dgram.createSocket('udp4');
          up.on('message', (b) => this.forward(this.sock, b, rinfo.port, rinfo.address, 'down'));
          up.bind(0, () => { try { up.setRecvBufferSize(4 << 20); up.setSendBufferSize(4 << 20); } catch (e) { /* OS limit */ } });
          this.flows.set(key, up);
        }
        this.forward(up, buf, this.targetPort, this.targetHost, 'up');
      });
      this.sock.bind(this.listenPort, '127.0.0.1', () => {
        try { this.sock.setRecvBufferSize(4 << 20); this.sock.setSendBufferSize(4 << 20); } catch (e) { /* OS limit */ }
        this.listenPort = this.sock.address().port; resolve(this.listenPort);
      });
    });
  }

  stop() {
    for (const t of Object.values(this.timer)) if (t) clearTimeout(t);
    this.sock.close(); for (const s of this.flows.values()) s.close();
  }
}

module.exports = { LossyProxy };
