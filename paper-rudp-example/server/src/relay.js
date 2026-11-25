'use strict';
// The relay server of the paper (Fig. 1 and Fig. 2): SIP registrar and message relay, and the
// receiving UA for file transfers. An uploaded file is stored by its SHA-1 and then offered to the
// recipient with an INVITE; messages and files for offline users are queued until they register.
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const sip = require('./sip');
const S = require('./irudp/segment');
const { IrudpEndpoint } = require('./irudp/endpoint');
const { FileStore } = require('./storage');

const MAX_FILE = 16 * 1024 ** 3;     // 16 GiB

class Relay {
  constructor({ sipPort = 5070, irudpPort = 5080, advertisePort = 0, publicIp = '127.0.0.1', dataDir, classifier = null, log = console.log }) {
    Object.assign(this, { sipPort, irudpPort, advertisePort, publicIp, dataDir, clf: classifier, log });
    this.users = new Map();          // name -> { address, port, expiresAt }
    this.uploads = new Map();        // transfer id -> { from, to, name, type, size, sha1 }
    this.deliveries = new Map();     // transfer id -> { to, from, name, type, size, sha1, since }
    this.receiving = new Map();      // sender|sha1 -> IRUDP session of an upload in progress
    this.tx = new Map();             // Call-ID -> outgoing request in progress
    this.seen = new Map();           // Call-ID|CSeq -> cached response (retransmitted requests)
    this.queue = { messages: [], files: [] };
  }

  async start() {
    fs.mkdirSync(this.dataDir, { recursive: true });
    this.store = new FileStore(path.join(this.dataDir, 'files'));
    try { this.queue = JSON.parse(fs.readFileSync(path.join(this.dataDir, 'queue.json'), 'utf8')); } catch (e) { /* fresh start */ }
    this.irudp = new IrudpEndpoint({ port: this.irudpPort, classifier: this.clf, onSyn: (syn, r) => this.onSyn(syn, r), log: this.log });
    this.irudpPort = await this.irudp.start();
    if (!this.advertisePort) this.advertisePort = this.irudpPort;
    this.sip = dgram.createSocket('udp4');
    this.sip.on('message', (b, r) => { try { this.onSip(b, r); } catch (e) { this.log('sip error', e); } });
    await new Promise((res) => this.sip.bind(this.sipPort, '0.0.0.0', res));
    this.sipPort = this.sip.address().port;
    this.retry = setInterval(() => this.retryQueued(), 10000);
    this.log(`relay: SIP on udp/${this.sipPort}, IRUDP on udp/${this.irudpPort} (advertised ${this.advertisePort}), data in ${this.dataDir}`);
  }

  stop() { clearInterval(this.retry); for (const t of this.tx.values()) clearTimeout(t.timer); this.irudp.stop(); this.sip.close(); }

  saveQueue() { fs.writeFileSync(path.join(this.dataDir, 'queue.json'), JSON.stringify(this.queue, null, 1)); }

  reply(msg, rinfo, status, reason, extra = [], body = '') {
    const b = sip.response(msg, status, reason, extra, body);
    this.sip.send(b, rinfo.port, rinfo.address);
    return b;
  }

  onSip(buf, rinfo) {
    const msg = sip.parse(buf);
    if (!msg) return;
    if (msg.status) { this.onResponse(msg); return; }
    const key = `${sip.header(msg, 'call-id')}|${sip.header(msg, 'cseq')}`;
    if (msg.method !== 'ACK' && this.seen.has(key)) { this.sip.send(this.seen.get(key), rinfo.port, rinfo.address); return; }
    let resp;
    switch (msg.method) {
      case 'REGISTER': resp = this.onRegister(msg, rinfo); break;
      case 'MESSAGE': resp = this.onMessage(msg, rinfo); break;
      case 'INVITE': resp = this.onInvite(msg, rinfo); break;
      case 'BYE': case 'OPTIONS': resp = this.reply(msg, rinfo, 200, 'OK'); break;
      case 'ACK': return;
      default: resp = this.reply(msg, rinfo, 501, 'Not Implemented');
    }
    this.seen.set(key, resp);
    if (this.seen.size > 2000) this.seen.delete(this.seen.keys().next().value);
  }

  // ---- registrar ---------------------------------------------------------------------------------
  onRegister(msg, rinfo) {
    const user = sip.userOf(sip.header(msg, 'to'));
    if (!user) return this.reply(msg, rinfo, 400, 'Bad Request');
    const expires = parseInt(sip.header(msg, 'expires') || '300', 10);
    if (expires === 0) { this.users.delete(user); return this.reply(msg, rinfo, 200, 'OK', [['Expires', 0]]); }
    const fresh = !this.users.has(user);
    // the source address of the packet is used, not the Contact header, so that NAT bindings work (RFC 3581)
    this.users.set(user, { address: rinfo.address, port: rinfo.port, expiresAt: Date.now() + expires * 1000 });
    const resp = this.reply(msg, rinfo, 200, 'OK', [['Contact', `<sip:${user}@${rinfo.address}:${rinfo.port}>`], ['Expires', expires]]);
    if (fresh) this.log(`registered ${user} at ${rinfo.address}:${rinfo.port}`);
    setTimeout(() => this.flush(user), 50);
    return resp;
  }

  online(user) {
    const u = this.users.get(user);
    if (u && u.expiresAt < Date.now()) { this.users.delete(user); return null; }
    return u || null;
  }

  // ---- outgoing requests with retransmission -------------------------------------------------------
  send(user, method, { from, contentType, body = '' }, onFinal) {
    const u = this.online(user);
    if (!u) { if (onFinal) onFinal(null); return; }
    const callId = `${sip.rand(8)}@relay`; const fromTag = sip.rand(4);
    const buf = sip.request(method, `sip:${user}@${u.address}:${u.port}`, {
      via: `SIP/2.0/UDP ${this.publicIp}:${this.sipPort};branch=z9hG4bK${sip.rand(6)}`,
      from: `<sip:${from}@${sip.DOMAIN}>;tag=${fromTag}`, to: `<sip:${user}@${sip.DOMAIN}>`, callId, cseq: 1,
      contact: `<sip:relay@${this.publicIp}:${this.sipPort}>`, contentType }, body);
    const t = { tries: 0, method, user, dest: u, onFinal, from, fromTag };
    this.tx.set(callId, t);
    const again = () => {
      if (!this.tx.has(callId)) return;
      if (t.tries++ >= 7) { this.tx.delete(callId); if (onFinal) onFinal(null); return; }
      this.sip.send(buf, u.port, u.address);
      t.timer = setTimeout(again, 500 * Math.min(2 ** (t.tries - 1), 4));
    };
    again();
  }

  onResponse(msg) {
    const callId = sip.header(msg, 'call-id'); const t = this.tx.get(callId);
    if (!t || msg.status < 200) return;
    clearTimeout(t.timer); this.tx.delete(callId);
    if (t.method === 'INVITE' && msg.status < 300) {   // confirm the dialog with an ACK
      const ack = sip.request('ACK', `sip:${t.user}@${t.dest.address}:${t.dest.port}`, {
        via: `SIP/2.0/UDP ${this.publicIp}:${this.sipPort};branch=z9hG4bK${sip.rand(6)}`,
        from: `<sip:${t.from}@${sip.DOMAIN}>;tag=${t.fromTag}`, to: sip.header(msg, 'to'), callId, cseq: 1 });
      this.sip.send(ack, t.dest.port, t.dest.address);
    }
    if (t.onFinal) t.onFinal(msg);
  }

  // ---- chat messages -----------------------------------------------------------------------------
  onMessage(msg, rinfo) {
    const from = sip.userOf(sip.header(msg, 'from')); const to = sip.userOf(sip.header(msg, 'to'));
    if (!from || !to) return this.reply(msg, rinfo, 400, 'Bad Request');
    const resp = this.reply(msg, rinfo, 202, 'Accepted');
    this.deliverMessage({ from, to, text: msg.body, time: Date.now() });
    return resp;
  }

  deliverMessage(m, fromQueue = false) {
    if (!this.online(m.to)) { if (!fromQueue) { this.queue.messages.push(m); this.saveQueue(); this.log(`queued message ${m.from} -> ${m.to}`); } return; }
    this.send(m.to, 'MESSAGE', { from: m.from, contentType: 'text/plain;charset=UTF-8', body: m.text }, (resp) => {
      const ok = resp && resp.status < 300;
      if (ok && fromQueue) { this.queue.messages = this.queue.messages.filter((q) => q !== m); this.saveQueue(); }
      if (!ok && !fromQueue) { this.queue.messages.push(m); this.saveQueue(); }
    });
  }

  // ---- file transfers ----------------------------------------------------------------------------
  onInvite(msg, rinfo) {
    const from = sip.userOf(sip.header(msg, 'from')); const to = sip.userOf(sip.header(msg, 'to'));
    const o = sip.parseSdp(msg.body);
    if (!from || !to || o.proto !== 'UDP/IRUDP' || o.direction !== 'sendonly' || !o.transferId || !/^[0-9a-f]{40}$/.test(o.sha1 || '')
      || !Number.isSafeInteger(o.size) || o.size < 0 || o.size > MAX_FILE) {
      return this.reply(msg, rinfo, 488, 'Not Acceptable Here');
    }
    this.uploads.set(o.transferId, { from, to, name: o.name, type: o.type, size: o.size, sha1: o.sha1 });
    const answer = sip.buildSdp({ user: 'relay', ip: this.publicIp, port: this.advertisePort, setup: 'passive',
      direction: 'recvonly', name: o.name, type: o.type, size: o.size, sha1: o.sha1, transferId: o.transferId });
    this.log(`offer ${from} -> ${to}: ${o.name} (${o.size} bytes)`);
    return this.reply(msg, rinfo, 200, 'OK', [['Contact', `<sip:relay@${this.publicIp}:${this.sipPort}>`], ['Content-Type', 'application/sdp']], answer);
  }

  onSyn(syn) {
    if (syn.role === S.ROLE.CLIENT_SENDS) {
      const up = this.uploads.get(syn.transferId);
      if (!up || up.size !== syn.size || up.sha1 !== syn.sha1) return { status: S.STATUS.UNKNOWN_TRANSFER };
      // the same user sending the same file again means the earlier session is dead (app closed, network
      // changed): close it now rather than after the idle timeout, so that it cannot rewrite the block map
      const key = `${up.from}|${up.sha1}`;
      if (this.receiving.has(key)) this.irudp.close(this.receiving.get(key), 'replaced by a new attempt');
      const rx = this.store.openReceive(up);
      const heldBlocks = rx.held.reduce((n, b) => n + popcount(b), 0);
      this.log(`upload ${up.name}: receiving${heldBlocks ? `, resuming with ${heldBlocks} blocks already held` : ''}`);
      return { status: S.STATUS.OK, size: up.size, sha1: up.sha1, held: rx.held, start: (sess, ep) => {
        this.receiving.set(key, sess);
        ep.makeReceiver(sess, { size: up.size, held: rx.held, writeSegment: rx.writeSegment,
          onBlock: (b, bitmap) => rx.markBlock(bitmap), verify: rx.verify,
          onDone: (ok, reason) => {
            if (this.receiving.get(key) === sess) this.receiving.delete(key);
            if (!ok) {
              if (reason === 'hash mismatch') rx.discard(); else rx.close();   // a bad file is received again in full
              this.log(`upload ${up.name}: ${reason}`);
              return;
            }
            rx.finalize(); this.uploads.delete(syn.transferId);
            this.log(`upload ${up.name}: complete, SHA-1 verified`);
            this.offerFile(up.to, up);
          } });
      } };
    }
    const d = this.deliveries.get(syn.transferId);      // the client receives a file from the relay
    if (!d || !this.store.hasComplete(d.sha1)) return { status: S.STATUS.UNKNOWN_TRANSFER };
    const src = this.store.openSend(d.sha1);
    return { status: S.STATUS.OK, size: src.size, sha1: d.sha1, held: syn.held, start: (sess, ep) => {
      d.active = true;                                   // a long delivery is not given up while it runs
      ep.makeSender(sess, { size: src.size, readSegment: src.readSegment, held: syn.held,
        onDone: (ok, reason, stats) => {
          src.close();
          this.deliveries.delete(syn.transferId);         // on failure the next retry offers it again, and it resumes
          if (!ok) { this.log(`delivery ${d.name} -> ${d.to}: ${reason}`); return; }
          this.queue.files = this.queue.files.filter((f) => !(f.to === d.to && f.sha1 === d.sha1)); this.saveQueue();
          this.log(`delivery ${d.name} -> ${d.to}: done (${stats.sent} segments sent, ${stats.retx} resent, ${stats.skipped} skipped, `
            + `${stats.reductions} window reductions, ${stats.keptWindow} random losses kept the window)`);
        } }).start();
    } };
  }

  offerFile(to, info) {
    for (const d of this.deliveries.values()) if (d.to === to && d.sha1 === info.sha1) return;   // already offered
    if (!this.queue.files.some((f) => f.to === to && f.sha1 === info.sha1)) {
      this.queue.files.push({ to, from: info.from, name: info.name, type: info.type, size: info.size, sha1: info.sha1 });
      this.saveQueue();
    }
    if (!this.online(to)) { this.log(`queued file ${info.name} for ${to}`); return; }
    const tid = sip.rand(8);
    this.deliveries.set(tid, { to, from: info.from, name: info.name, type: info.type, size: info.size, sha1: info.sha1, since: Date.now() });
    const sdp = sip.buildSdp({ user: 'relay', ip: this.publicIp, port: this.advertisePort, setup: 'passive', direction: 'sendonly',
      name: info.name, type: info.type, size: info.size, sha1: info.sha1, transferId: tid });
    this.send(to, 'INVITE', { from: info.from, contentType: 'application/sdp', body: sdp }, (resp) => {
      if (!resp || resp.status >= 300) this.deliveries.delete(tid);     // stays queued for the next attempt
    });
  }

  flush(user) {
    for (const m of [...this.queue.messages]) if (m.to === user) this.deliverMessage(m, true);
    for (const f of [...this.queue.files]) if (f.to === user && this.store.hasComplete(f.sha1)) this.offerFile(user, f);
  }

  retryQueued() {
    for (const [tid, d] of this.deliveries) if (!d.active && Date.now() - d.since > 120000) this.deliveries.delete(tid);   // offer not taken up
    const users = new Set([...this.queue.messages.map((m) => m.to), ...this.queue.files.map((f) => f.to)]);
    for (const u of users) if (this.online(u)) this.flush(u);
  }
}

function popcount(b) { let n = 0; while (b) { n += b & 1; b >>= 1; } return n; }

module.exports = { Relay };
