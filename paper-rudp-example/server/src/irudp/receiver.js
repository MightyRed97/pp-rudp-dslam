'use strict';
// IRUDP receiver (paper, Sec. 4.4 and Listing 2). Each payload is written at its file offset, a bit
// is set in the bitmap, and the ACK carries the bitmap words above the cumulative ACK. ACKs are sent
// every n_a segments (requested by the sender, Eq. 7), at once on a new gap, a filled gap or a
// duplicate, and otherwise after at most 10 ms. The echoed time is advanced by the hold time.
const S = require('./segment');

const U32 = 4294967296;

class IrudpReceiver {
  constructor({ connId, size, held, writeSegment, onBlock, verify, send, wheel, now, onProgress, onDone }) {
    this.connId = connId; this.size = size; this.N = S.segmentsFor(size);
    this.writeSegment = writeSegment; this.onBlock = onBlock || (() => {}); this.verify = verify;
    this.send = send; this.wheel = wheel; this.now = now;
    this.onProgress = onProgress || (() => {}); this.onDone = onDone || (() => {});
    this.got = new Uint8Array(S.bitmapBytes(this.N));
    this.nBlocks = S.blocksFor(size);
    this.held = new Uint8Array(S.bitmapBytes(this.nBlocks));
    this.blockCount = new Uint16Array(this.nBlocks);
    this.doneSegs = 0; this.highest = -1;
    for (let b = 0; b < this.nBlocks; b++) {
      if (!held || !S.bitGet(held, b)) continue;      // blocks already on disk count as received
      S.bitSet(this.held, b);
      for (let s = b * S.BLOCK_SEGS; s < Math.min(this.N, (b + 1) * S.BLOCK_SEGS); s++) { S.bitSet(this.got, s); this.doneSegs++; this.highest = s; }
      this.blockCount[b] = this.segsIn(b);
    }
    this.rcvNxt = 0;
    while (this.rcvNxt < this.N && S.bitGet(this.got, this.rcvNxt)) this.rcvNxt++;
    this.pending = 0; this.ackFreq = 2; this.lastTs = 0; this.lastArrUs = 0;
    this.finStatus = null; this.ackTimer = `ack:${connId}`;
  }

  segsIn(b) { return Math.min(S.BLOCK_SEGS, this.N - b * S.BLOCK_SEGS); }

  onData(h) {
    const s = h.num;
    if (s >= this.N) return;
    let immediate = false;
    if (S.bitGet(this.got, s)) immediate = true;      // duplicate: acknowledge at once
    else {
      if (h.payload.length !== Math.min(S.MSS, this.size - s * S.MSS)) return;
      this.writeSegment(s, h.payload);                  // no reordering buffer: straight to the file offset
      S.bitSet(this.got, s); this.doneSegs++;
      const b = Math.floor(s / S.BLOCK_SEGS);
      if (++this.blockCount[b] === this.segsIn(b)) { S.bitSet(this.held, b); this.onBlock(b, this.held); }
      if (s === this.rcvNxt) {
        if (this.highest > s) immediate = true;         // filled the first gap
        while (this.rcvNxt < this.N && S.bitGet(this.got, this.rcvNxt)) this.rcvNxt++;   // count trailing ones
      } else if (s > this.highest + 1 || s < this.highest) immediate = true;              // new gap, or a gap filled
      if (s > this.highest) this.highest = s;
    }
    this.lastTs = h.ts; this.lastArrUs = this.now(); this.ackFreq = h.aux || 2; this.pending++;
    if (this.rcvNxt >= this.N) immediate = true;
    if (immediate || this.pending >= this.ackFreq) this.sendAck();
    else if (!this.wheel.where.has(this.ackTimer)) this.wheel.after(10, () => this.sendAck(), this.ackTimer);
    this.onProgress(this.doneSegs, this.N);
  }

  sendAck() {
    this.wheel.cancel(this.ackTimer); this.pending = 0;
    const cum = this.rcvNxt;
    const top = Math.min(this.highest, cum + 64 * S.KMAX);
    const k = top > cum ? Math.ceil((top - cum) / 64) : 0;
    const words = Buffer.alloc(8 * k);
    for (let i = 0; i < 64 * k; i++) {
      const s = cum + 1 + i;
      if (s < this.N && S.bitGet(this.got, s)) words[i >> 3] |= 1 << (i & 7);
    }
    const echo = (this.lastTs + (this.now() - this.lastArrUs)) % U32;
    const b = S.header(S.T.ACK, 0, k, S.RING, this.connId, cum, Math.floor(echo), words.length);
    words.copy(b, S.HDR);
    this.send(b);
  }

  onFin() {
    if (this.rcvNxt < this.N) { this.sendAck(); return; }      // not complete yet: tell the sender what is missing
    if (this.finStatus === null) {
      const ok = this.verify();
      this.finStatus = ok ? S.STATUS.OK : S.STATUS.BAD_HASH;
      this.wheel.cancel(this.ackTimer);
      this.onDone(ok);
    }
    const b = S.header(S.T.FINACK, 0, 0, 0, this.connId, this.N, Math.floor(this.now() % U32), 1);
    b[S.HDR] = this.finStatus;
    this.send(b);
  }
}

module.exports = { IrudpReceiver };
