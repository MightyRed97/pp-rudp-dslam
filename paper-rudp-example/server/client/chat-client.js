'use strict';
// A desktop chat client that speaks the same protocol as the Android app: SIP-style REGISTER, MESSAGE
// and INVITE over UDP, and IRUDP for files. It always opens the IRUDP session itself (SYN), so it
// works behind NAT. Used by the command-line client and by the tests.
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const EventEmitter = require('events');
const sip = require('../src/sip');
const S = require('../src/irudp/segment');
const { IrudpEndpoint } = require('../src/irudp/endpoint');
const { sha1OfFile } = require('../src/storage');

const TYPES = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.mp4': 'video/mp4', '.pdf': 'application/pdf', '.txt': 'text/plain' };
const MAX_FILE = 16 * 1024 ** 3;     // 16 GiB

// The file name comes from the other user: keep only its last path element and no control characters,
// so that a name such as "../../x" cannot write outside the download folder.
function safeName(name) {
  const base = path.basename(String(name || '').replace(/\\/g, '/')).replace(/[\x00-\x1f<>:"|?*]/g, '_').trim();
  return base && base !== '.' && base !== '..' ? base : 'file';
}

class ChatClient extends EventEmitter {
  constructor({ server, sipPort = 5070, user, downloadDir, classifier = null, log = () => {} }) {
    super();
    Object.assign(this, { server, sipPort, user, downloadDir, clf: classifier, log });
    this.pending = new Map();      // Call-ID -> { resolve, timer }
    this.cseq = 1;
    this.active = new Map();       // transfer id -> session (for tests that abort a transfer)
    this.seenMessages = new Set(); // Call-IDs of recent MESSAGEs: a retransmitted one is answered but shown once
    this.downloading = new Set();  // SHA-1s of the files being received
  }

  async start() {
    fs.mkdirSync(this.downloadDir, { recursive: true });
    this.irudp = new IrudpEndpoint({ classifier: this.clf, log: this.log });
    await this.irudp.start();
    this.sock = dgram.createSocket('udp4');
    this.sock.on('message', (b, r) => { try { this.onSip(b, r); } catch (e) { this.log('sip error', e); } });
    await new Promise((res) => this.sock.bind(0, '0.0.0.0', res));
    const ok = await this.register(120);
    if (!ok) throw new Error(`no answer from ${this.server}:${this.sipPort}`);
    this.regTimer = setInterval(() => this.register(120), 30000);
  }

  stop() {
    clearInterval(this.regTimer);
    for (const p of this.pending.values()) clearTimeout(p.timer);
    this.irudp.stop(); this.sock.close();
  }

  request(method, toUser, { contentType, body = '', expires } = {}) {
    const callId = `${sip.rand(8)}@${this.user}`; const tag = sip.rand(4);
    const local = this.sock.address();
    const buf = sip.request(method, method === 'REGISTER' ? `sip:${sip.DOMAIN}` : `sip:${toUser}@${sip.DOMAIN}`, {
      via: `SIP/2.0/UDP ${local.address}:${local.port};branch=z9hG4bK${sip.rand(6)};rport`,
      from: `<sip:${this.user}@${sip.DOMAIN}>;tag=${tag}`, to: `<sip:${toUser}@${sip.DOMAIN}>`, callId, cseq: this.cseq++,
      contact: `<sip:${this.user}@${local.address}:${local.port}>`, contentType, expires }, body);
    return new Promise((resolve) => {
      let tries = 0;
      const again = () => {
        if (tries++ >= 7) { this.pending.delete(callId); resolve(null); return; }
        this.sock.send(buf, this.sipPort, this.server);
        this.pending.get(callId).timer = setTimeout(again, 500 * Math.min(2 ** (tries - 1), 4));
      };
      this.pending.set(callId, { resolve, timer: null, tag, toUser, method, cseq: this.cseq - 1 });
      again();
    });
  }

  async register(expires) { const r = await this.request('REGISTER', this.user, { expires }); return !!(r && r.status === 200); }

  async sendMessage(to, text) {
    const r = await this.request('MESSAGE', to, { contentType: 'text/plain;charset=UTF-8', body: text });
    return !!(r && r.status < 300);
  }

  sendAck(resp) {
    const local = this.sock.address();
    const ack = sip.request('ACK', `sip:relay@${this.server}:${this.sipPort}`, {
      via: `SIP/2.0/UDP ${local.address}:${local.port};branch=z9hG4bK${sip.rand(6)};rport`,
      from: sip.header(resp, 'from'), to: sip.header(resp, 'to'), callId: sip.header(resp, 'call-id'), cseq: sip.cseqOf(resp).n });
    this.sock.send(ack, this.sipPort, this.server);
  }

  // Sends a file to another user through the relay. A failed attempt is retried with a new offer; the
  // relay answers the new SYN with the blocks it already holds, so the transfer resumes.
  async sendFile(to, file, { onProgress = () => {}, attempts = 3, onSession = () => {} } = {}) {
    const size = fs.statSync(file).size; const sha1 = sha1OfFile(file); const name = path.basename(file);
    const type = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
    let last = null;
    for (let attempt = 1; attempt <= attempts; attempt++) {
      const tid = sip.rand(8);
      const sdp = sip.buildSdp({ user: this.user, ip: '0.0.0.0', port: 9, setup: 'active', direction: 'sendonly', name, type, size, sha1, transferId: tid });
      const resp = await this.request('INVITE', to, { contentType: 'application/sdp', body: sdp });
      if (!resp || resp.status >= 300) { last = { ok: false, reason: resp ? `${resp.status} ${resp.reason}` : 'no answer' }; continue; }
      this.sendAck(resp);
      const ans = sip.parseSdp(resp.body);
      const fd = fs.openSync(file, 'r');
      last = await new Promise((resolve) => {
        const sess = this.irudp.connect({ host: this.server, port: ans.port, role: S.ROLE.CLIENT_SENDS, size, sha1, transferId: tid,
          readSegment: (seq) => { const len = Math.min(S.MSS, size - seq * S.MSS); const b = Buffer.alloc(len); fs.readSync(fd, b, 0, len, seq * S.MSS); return b; },
          onProgress, onDone: (ok, reason, stats) => resolve({ ok, reason, stats, attempt }) });
        this.active.set(tid, sess); onSession(sess, attempt);
      });
      fs.closeSync(fd); this.active.delete(tid);
      if (last.ok) { this.request('BYE', to); return last; }
      this.log(`${this.user}: transfer of ${name} interrupted (${last.reason}), attempt ${attempt} of ${attempts}`);
    }
    return last;
  }

  onSip(buf, rinfo) {
    const msg = sip.parse(buf);
    if (!msg) return;
    if (msg.status) {
      const p = this.pending.get(sip.header(msg, 'call-id'));
      if (p && msg.status >= 200) { clearTimeout(p.timer); this.pending.delete(sip.header(msg, 'call-id')); p.resolve(msg); }
      return;
    }
    const reply = (status, reason, extra = [], body = '') => this.sock.send(sip.response(msg, status, reason, extra, body), rinfo.port, rinfo.address);
    if (msg.method === 'MESSAGE') {
      reply(200, 'OK');
      const callId = sip.header(msg, 'call-id');
      if (this.seenMessages.has(callId)) return;
      this.seenMessages.add(callId);
      if (this.seenMessages.size > 256) this.seenMessages.delete(this.seenMessages.values().next().value);
      this.emit('message', { from: sip.userOf(sip.header(msg, 'from')), text: msg.body });
    } else if (msg.method === 'INVITE') {
      const o = sip.parseSdp(msg.body); const from = sip.userOf(sip.header(msg, 'from'));
      if (this.offers && this.offers.has(o.transferId)) { reply(200, 'OK', [['Content-Type', 'application/sdp']], this.offers.get(o.transferId)); return; }
      if (o.proto !== 'UDP/IRUDP' || !o.transferId || !/^[0-9a-f]{40}$/.test(o.sha1 || '') || !Number.isSafeInteger(o.size)
        || o.size < 0 || o.size > MAX_FILE || !(o.port > 0 && o.port < 65536)) { reply(488, 'Not Acceptable Here'); return; }
      if (this.downloading.has(o.sha1)) { reply(486, 'Busy Here'); return; }   // the same file is already on its way
      const answer = sip.buildSdp({ user: this.user, ip: '0.0.0.0', port: 9, setup: 'active', direction: 'recvonly', name: o.name,
        type: o.type, size: o.size, sha1: o.sha1, transferId: o.transferId });
      (this.offers = this.offers || new Map()).set(o.transferId, answer);
      reply(200, 'OK', [['Content-Type', 'application/sdp']], answer);
      this.download({ ...o, from });
    } else if (msg.method !== 'ACK') reply(200, 'OK');
  }

  download(o) {
    const part = path.join(this.downloadDir, `${o.sha1}.part`); const mapFile = path.join(this.downloadDir, `${o.sha1}.map`);
    // the block map is trusted only next to the partial file it describes
    const resumable = fs.existsSync(part) && fs.statSync(part).size === o.size;
    if (!resumable) {
      const f = fs.openSync(part, 'w'); fs.ftruncateSync(f, o.size); fs.closeSync(f);
      try { fs.unlinkSync(mapFile); } catch (e) { /* none */ }
    }
    let held = new Uint8Array(S.bitmapBytes(S.blocksFor(o.size)));
    if (resumable) {
      try { const m = fs.readFileSync(mapFile); if (m.length === held.length) held = new Uint8Array(m); } catch (e) { /* no map yet */ }
    }
    const fd = fs.openSync(part, 'r+');
    this.downloading.add(o.sha1);
    this.emit('offer', o);
    this.irudp.connect({ host: this.server, port: o.port, role: S.ROLE.CLIENT_RECEIVES, size: o.size, sha1: o.sha1, transferId: o.transferId, held,
      writeSegment: (seq, b) => fs.writeSync(fd, b, 0, b.length, seq * S.MSS),
      onBlock: (b, bitmap) => fs.writeFileSync(mapFile, Buffer.from(bitmap)),
      verify: () => sha1OfFile(part) === o.sha1,
      onProgress: (d, n) => this.emit('progress', { name: o.name, done: d, total: n }),
      onDone: (ok, reason) => {
        fs.closeSync(fd);
        this.downloading.delete(o.sha1);
        if (!ok) {
          if (reason === 'hash mismatch') {                // not resumable: its block map says every block is held
            for (const f of [part, mapFile]) { try { fs.unlinkSync(f); } catch (e) { /* already gone */ } }
          }
          this.emit('failed', { name: o.name, reason });
          return;
        }
        const name = safeName(o.name);
        let dest = path.join(this.downloadDir, name);
        for (let i = 1; fs.existsSync(dest); i++) dest = path.join(this.downloadDir, `${path.parse(name).name} (${i})${path.extname(name)}`);
        fs.renameSync(part, dest); try { fs.unlinkSync(mapFile); } catch (e) { /* none */ }
        this.emit('file', { from: o.from, name: o.name, path: dest, size: o.size });
      } });
  }
}

module.exports = { ChatClient, safeName };
