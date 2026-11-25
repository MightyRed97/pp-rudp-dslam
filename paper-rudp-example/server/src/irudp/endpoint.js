'use strict';
// One UDP socket for all IRUDP sessions. Sessions are found by connection ID, so a client that changes
// its address (Wi-Fi to 4G) keeps its session. The passive side (the relay) answers SYN segments; the
// active side (a client) opens sessions with connect(). All timers live in one shared timing wheel.
const dgram = require('dgram');
const crypto = require('crypto');
const { performance } = require('perf_hooks');
const S = require('./segment');
const { TimingWheel } = require('./wheel');
const { IrudpSender } = require('./sender');
const { IrudpReceiver } = require('./receiver');

const IDLE_MS = 60000;

class IrudpEndpoint {
  constructor({ port = 0, host = '0.0.0.0', classifier = null, theta = 0.4, onSyn = null, log = () => {} }) {
    this.port = port; this.host = host; this.clf = classifier; this.theta = theta; this.onSyn = onSyn; this.log = log;
    this.sessions = new Map();
    this.now = () => Math.floor(performance.now() * 1000);       // microseconds
    this.wheel = new TimingWheel();
    this.wheel.now = Math.floor(performance.now());
    this.wheel.onError = (e) => this.log('irudp timer error', e);
  }

  start() {
    return new Promise((resolve) => {
      this.sock = dgram.createSocket('udp4');
      this.sock.on('message', (buf, rinfo) => { try { this.onPacket(buf, rinfo); } catch (e) { this.log('irudp error', e); } });
      this.sock.on('error', (e) => this.log('irudp socket error', e.message));
      this.sock.bind(this.port, this.host, () => {
        try { this.sock.setRecvBufferSize(4 << 20); this.sock.setSendBufferSize(4 << 20); } catch (e) { /* OS limit */ }
        this.port = this.sock.address().port;
        this.ticker = setInterval(() => this.wheel.advanceTo(Math.floor(performance.now())), 1);
        resolve(this.port);
      });
    });
  }

  stop() { clearInterval(this.ticker); for (const s of this.sessions.values()) this.close(s, 'stopped'); try { this.sock.close(); } catch (e) { /* closed */ } }

  sendTo(sess, buf) { this.sock.send(buf, sess.peer.port, sess.peer.address); }

  touch(sess) { this.wheel.after(IDLE_MS, () => this.close(sess, 'idle'), `idle:${sess.connId}`); }

  close(sess, reason) {                               // ends a session; its owner hears about it exactly once
    if (!this.sessions.has(sess.connId)) return;
    this.sessions.delete(sess.connId);
    for (const id of [`idle:${sess.connId}`, `syn:${sess.connId}`, `ack:${sess.connId}`]) this.wheel.cancel(id);
    if (sess.sender) { if (!sess.sender.done) sess.sender.finish(false, reason); }
    else if (sess.receiver) { if (sess.receiver.finStatus === null && sess.onRecvDone) sess.onRecvDone(false, reason); }
    else if (sess.onFail) sess.onFail(reason);         // a sending session closed before its SYNACK
  }

  makeSender(sess, opts) {
    sess.sender = new IrudpSender({ ...opts, connId: sess.connId, send: (b) => this.sendTo(sess, b), wheel: this.wheel,
      now: this.now, classifier: this.clf, theta: this.theta,
      onDone: (ok, reason, stats) => { this.sessions.delete(sess.connId); this.wheel.cancel(`idle:${sess.connId}`); opts.onDone(ok, reason, stats); } });
    return sess.sender;
  }

  makeReceiver(sess, opts) {
    sess.onRecvDone = opts.onDone;
    sess.receiver = new IrudpReceiver({ ...opts, connId: sess.connId, send: (b) => this.sendTo(sess, b), wheel: this.wheel, now: this.now,
      onDone: (ok) => { opts.onDone(ok, ok ? 'ok' : 'hash mismatch'); } });
    return sess.receiver;
  }

  onPacket(buf, rinfo) {
    const h = S.parse(buf);
    if (!h) return;
    let sess = this.sessions.get(h.connId);
    if (h.type === S.T.SYN) { this.onSynPacket(h, rinfo, sess); return; }
    if (!sess) {
      if (h.type === S.T.FIN) {                       // a FIN for a finished receiving session: confirm it again
        const done = this.finished && this.finished.get(h.connId);
        if (done !== undefined) { const b = S.header(S.T.FINACK, 0, 0, 0, h.connId, h.num, 0, 1); b[S.HDR] = done; this.sock.send(b, rinfo.port, rinfo.address); }
      }
      return;
    }
    if (sess.peer.address !== rinfo.address || sess.peer.port !== rinfo.port) {
      this.log(`session ${h.connId} moved to ${rinfo.address}:${rinfo.port}`);
      sess.peer = { address: rinfo.address, port: rinfo.port };
    }
    this.touch(sess);
    switch (h.type) {
      case S.T.DATA: if (sess.receiver) sess.receiver.onData(h); break;
      case S.T.ACK: if (sess.sender) sess.sender.onAck(h); break;
      case S.T.FIN:
        if (sess.receiver) {
          sess.receiver.onFin(h);
          if (sess.receiver.finStatus !== null) {         // remember the verdict for repeated FINs, then end the session
            (this.finished = this.finished || new Map()).set(h.connId, sess.receiver.finStatus);
            this.wheel.after(30000, () => this.finished.delete(h.connId), `fin-memo:${h.connId}`);
            this.sessions.delete(h.connId); this.wheel.cancel(`idle:${h.connId}`);
          }
        }
        break;
      case S.T.FINACK: if (sess.sender) sess.sender.onFinAck(h); break;
      case S.T.SYNACK: if (sess.onSynAck) sess.onSynAck(h); break;
      case S.T.RST: this.close(sess, 'reset by peer'); break;
      default: break;
    }
  }

  // ---- passive side ------------------------------------------------------------------------------
  onSynPacket(h, rinfo, sess) {
    if (sess && sess.synAck) { this.sock.send(sess.synAck, rinfo.port, rinfo.address); return; }   // repeated SYN
    if (!this.onSyn) return;
    const syn = S.parseSyn(h.payload);
    if (!syn) return;
    sess = { connId: h.connId, peer: { address: rinfo.address, port: rinfo.port } };
    const answer = this.onSyn(syn, rinfo);           // { status, size, sha1, held, start(sess) }
    const p = S.synAckPayload({ status: answer.status, size: answer.size, sha1: answer.sha1, held: answer.held });
    sess.synAck = S.header(S.T.SYNACK, 0, 0, S.RING, h.connId, 0, h.ts, p.length);
    p.copy(sess.synAck, S.HDR);
    this.sock.send(sess.synAck, rinfo.port, rinfo.address);
    if (answer.status !== S.STATUS.OK) return;
    this.sessions.set(h.connId, sess);
    this.touch(sess);
    answer.start(sess, this);
  }

  // ---- active side -------------------------------------------------------------------------------
  connect({ host, port, role, size, sha1, transferId, held, readSegment, writeSegment, onBlock, verify, onProgress, onDone }) {
    const connId = crypto.randomBytes(4).readUInt32BE(0) >>> 0 || 1;
    const sess = { connId, peer: { address: host, port }, onFail: (reason) => onDone(false, reason) };
    this.sessions.set(connId, sess);
    const p = S.synPayload({ role, size, sha1, transferId, held: role === S.ROLE.CLIENT_RECEIVES ? held : null });
    const syn = S.header(S.T.SYN, 0, 0, S.RING, connId, 0, this.now() % 4294967296, p.length);
    p.copy(syn, S.HDR);
    let tries = 0;
    const sendSyn = () => {
      if (sess.established || !this.sessions.has(connId)) return;
      if (++tries > 10) { this.close(sess, 'no SYNACK'); return; }
      this.sendTo(sess, syn);
      this.wheel.after(500, sendSyn, `syn:${connId}`);
    };
    if (role === S.ROLE.CLIENT_RECEIVES) {             // the receiver exists from the start: DATA may beat the SYNACK
      this.makeReceiver(sess, { size, held, writeSegment, onBlock, verify, onProgress, onDone });
    }
    sess.onSynAck = (h) => {
      if (sess.established) return;
      const a = S.parseSynAck(h.payload);
      sess.established = true; this.wheel.cancel(`syn:${connId}`);
      if (!a || a.status !== S.STATUS.OK) { this.close(sess, `refused (${a ? a.status : '?'})`); return; }
      if (role === S.ROLE.CLIENT_SENDS) {
        this.makeSender(sess, { size, readSegment, held: a.held, onProgress, onDone }).start();
      }
    };
    this.touch(sess);
    sendSyn();
    return sess;
  }

  abort(sess) { this.close(sess, 'aborted'); }
}

module.exports = { IrudpEndpoint };
