'use strict';
// Unit tests of the IRUDP data structures and of the classifier, and generation of wire-format
// vectors that the Java tests of the Android app compare against.
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const S = require('../src/irudp/segment');
const { TimingWheel } = require('../src/irudp/wheel');
const { IrudpSender } = require('../src/irudp/sender');
const { IrudpReceiver } = require('../src/irudp/receiver');
const { LossClassifier } = require('../src/irudp/classifier');

let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log(`ok   ${name}`); };

test('classifier reproduces the Python reference model on 40 held-out vectors', () => {
  const clf = LossClassifier.load(path.join(__dirname, '..', 'model', 'gbdt.txt'));
  const rows = fs.readFileSync(path.join(__dirname, 'gbdt-vectors.txt'), 'utf8').split('\n').filter((l) => l && !l.startsWith('#'));
  assert.strictEqual(rows.length, 40);
  for (const r of rows) {
    const v = r.trim().split(/\s+/).map(Number);
    assert.ok(Math.abs(clf.prob(v.slice(0, 6)) - v[6]) < 1e-12, `p differs for ${r}`);
  }
});

test('timing wheel: due, past, cascaded and cancelled timers fire exactly once at the right tick', () => {
  const w = new TimingWheel(); const fired = [];
  for (let i = 0; i < 1000; i++) w.tick();
  w.start(w.now, () => fired.push(['now', w.now - 1000]));
  w.start(w.now - 50, () => fired.push(['past', w.now - 1000]));
  w.start(1048, () => fired.push(['exact', w.now - 1000]));
  w.start(1700, () => fired.push(['long', w.now - 1000]));
  const a = w.start(1010, () => { fired.push(['a', w.now - 1000]); w.cancel(b); });
  const b = w.start(1010, () => fired.push(['b', w.now - 1000]));
  for (let i = 0; i < 2000; i++) w.tick();
  assert.deepStrictEqual(fired, [['now', 1], ['past', 1], ['a', 10], ['exact', 48], ['long', 700]]);
  assert.ok(a !== b);
  const w2 = new TimingWheel(); let late = 0, n = 0; let seed = 7;
  const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let i = 0; i < 5000; i++) { const e = 1 + Math.floor(rnd() * 60000); w2.start(e, () => { n++; if (w2.now !== e) late++; }); }
  for (let i = 0; i < 60001; i++) w2.tick();
  assert.strictEqual(n, 5000); assert.strictEqual(late, 0);
  // a stall of 5 s: a level-1 timer whose cascade (at 1,280 ms) was skipped fires at the next tick,
  // not 65.5 s later, and a timer due after the stall still fires on time
  const w3 = new TimingWheel(); const got = [];
  w3.after(500, () => got.push(`a@${w3.now}`)); w3.after(1500, () => got.push(`b@${w3.now}`)); w3.after(9000, () => got.push(`c@${w3.now}`));
  w3.advanceTo(5000); assert.deepStrictEqual(got, ['a@500']);
  w3.advanceTo(5001); assert.deepStrictEqual(got, ['a@500', 'b@5001']);
  w3.advanceTo(8999); assert.strictEqual(got.length, 2);
  w3.advanceTo(9000); assert.deepStrictEqual(got, ['a@500', 'b@5001', 'c@9000']);
});

// A sender and a receiver connected back to back in memory (one step = 200 us), with selected DATA
// segments dropped, and optionally a delay spike: once holdWhen(sender) is true, DATA is held, not
// lost, for holdSteps steps.
function loopback(size, drop, { holdWhen = null, holdSteps = 0 } = {}) {
  const wheel = new TimingWheel(); let t = 0; const now = () => t;
  const data = Buffer.alloc(size); for (let i = 0; i < size; i++) data[i] = (i * 31 + 7) & 0xff;
  const out = Buffer.alloc(size); const toRx = []; const toTx = [];
  let rxDone = false; let txResult = null;
  const rx = new IrudpReceiver({ connId: 9, size, held: null, writeSegment: (s, b) => b.copy(out, s * S.MSS),
    verify: () => out.equals(data), send: (b) => toTx.push(b), wheel, now, onDone: () => { rxDone = true; } });
  const tx = new IrudpSender({ connId: 9, size, readSegment: (s) => data.subarray(s * S.MSS, Math.min(size, (s + 1) * S.MSS)), held: null,
    send: (b) => toRx.push(b), wheel, now, classifier: null, onDone: (ok, reason, stats) => { txResult = { ok, reason, stats }; } });
  tx.start();
  let sentCount = 0, dups = 0, holdUntil = -1;
  for (let step = 0; step < 200000 && !txResult; step++) {
    t += 200; wheel.advanceTo(Math.floor(t / 1000));
    if (holdWhen && holdUntil < 0 && holdWhen(tx)) holdUntil = step + holdSteps;
    const holding = step < holdUntil;
    while (!holding && toRx.length) {
      const h = S.parse(toRx.shift());
      if (h.type === S.T.DATA) {
        sentCount++; if (drop(h.num, sentCount)) continue;
        if (S.bitGet(rx.got, h.num)) dups++;
        rx.onData(h);
      } else if (h.type === S.T.FIN) rx.onFin(h);
    }
    while (toTx.length) { const h = S.parse(toTx.shift()); if (h.type === S.T.ACK) tx.onAck(h); else if (h.type === S.T.FINACK) tx.onFinAck(h); }
  }
  return { txResult, rxDone, equal: out.equals(data), dups };
}

test('loopback transfer without loss delivers the file and verifies it', () => {
  const r = loopback(500000, () => false);
  assert.ok(r.txResult && r.txResult.ok && r.rxDone && r.equal);
  assert.strictEqual(r.txResult.stats.retx, 0);
});

test('loopback transfer with 5% loss marks, resends and completes', () => {
  let seed = 3; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  const r = loopback(2000000, () => rnd() < 0.05);
  assert.ok(r.txResult && r.txResult.ok && r.equal, JSON.stringify(r.txResult));
  assert.ok(r.txResult.stats.retx > 0);
});

test('a delay spike longer than the RTO resends only the probes, not the whole flight', () => {
  // with 100 segments in flight, nothing is delivered for 400 ms (twice the RTO), and nothing is lost
  const r = loopback(2000000, () => false, { holdWhen: (tx) => tx.pipe >= 100, holdSteps: 2000 });
  assert.ok(r.txResult && r.txResult.ok && r.equal, JSON.stringify(r.txResult));
  assert.ok(r.txResult.stats.retx >= 1 && r.txResult.stats.retx <= 3, `resent ${r.txResult.stats.retx}`);
  assert.strictEqual(r.dups, r.txResult.stats.retx);
});

test('listing example: holes at 10 and 11 give cumulative ACK 10 and the word 0x1ffffffe', () => {
  const wheel = new TimingWheel(); const acks = [];
  const rx = new IrudpReceiver({ connId: 1, size: 40 * S.MSS, held: null, writeSegment() {}, verify: () => true,
    send: (b) => acks.push(S.parse(b)), wheel, now: () => 0 });
  for (const s of [...Array(10).keys(), ...Array.from({ length: 28 }, (_, i) => 12 + i)]) rx.onData({ num: s, aux: 64, ts: 0, payload: Buffer.alloc(S.MSS) });
  rx.sendAck();
  const a = acks[acks.length - 1];
  assert.strictEqual(a.num, 10); assert.strictEqual(a.aux, 1);
  assert.strictEqual(a.payload.readBigUInt64LE(0), 0x1ffffffen);
});

// Wire-format vectors for the Java implementation (android/app/src/test/resources/wire-vectors.txt)
const vec = [];
const add = (name, buf) => vec.push(`${name} ${buf.toString('hex')}`);
{
  const d = S.header(S.T.DATA, S.F.RETX, 8, 1024, 0xdeadbeef, 123456, 4000000000, 3); Buffer.from([1, 2, 3]).copy(d, S.HDR); add('data', d);
  const words = Buffer.alloc(16); words.writeBigUInt64LE(0x1ffffffen, 0); words.writeBigUInt64LE(0x8000000000000001n, 8);
  const a = S.header(S.T.ACK, 0, 2, 1024, 0x01020304, 10, 77, 16); words.copy(a, S.HDR); add('ack', a);
  const sp = S.synPayload({ role: S.ROLE.CLIENT_RECEIVES, size: 5000000000, sha1: '00112233445566778899aabbccddeeff00112233', transferId: 'k3T9qwab', held: Uint8Array.from([0x05, 0x80]) });
  const syn = S.header(S.T.SYN, 0, 0, 1024, 42, 0, 1000, sp.length); sp.copy(syn, S.HDR); add('syn', syn);
  const ap = S.synAckPayload({ status: S.STATUS.OK, size: 5000000000, sha1: '00112233445566778899aabbccddeeff00112233', held: Uint8Array.from([0xff, 0x01]) });
  const sa = S.header(S.T.SYNACK, 0, 0, 1024, 42, 0, 1000, ap.length); ap.copy(sa, S.HDR); add('synack', sa);
  const fa = S.header(S.T.FINACK, 0, 0, 0, 42, 3613, 5, 1); fa[S.HDR] = S.STATUS.BAD_HASH; add('finack', fa);
}
test('wire vectors parse back to the same fields', () => {
  for (const line of vec) {
    const [name, hex] = line.split(' '); const h = S.parse(Buffer.from(hex, 'hex'));
    assert.ok(h, name);
    if (name === 'syn') { const s = S.parseSyn(h.payload); assert.strictEqual(s.size, 5000000000); assert.strictEqual(s.transferId, 'k3T9qwab'); }
  }
});
test('an upload that fails its hash check is discarded, so the next attempt does not resume it', () => {
  const { FileStore } = require('../src/storage');
  const dir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'irudp-store-'));
  const store = new FileStore(dir);
  const info = { name: 'x.bin', type: 'application/octet-stream', size: 300 * S.MSS, sha1: 'ab'.repeat(20) };
  const rx = store.openReceive(info);
  for (let s = 0; s < 300; s++) rx.writeSegment(s, Buffer.alloc(S.MSS, 1));
  rx.markBlock(Uint8Array.from([0x03]));
  assert.strictEqual(rx.verify(), false);
  rx.discard();
  const again = store.openReceive(info);
  assert.strictEqual(again.held[0], 0);
  again.close();
  // a block map left without its partial file is not trusted either
  fs.unlinkSync(path.join(dir, `${info.sha1}.part`));
  fs.writeFileSync(path.join(dir, `${info.sha1}.map`), Buffer.from([0x03, 0x00]));
  const fresh = store.openReceive(info);
  assert.strictEqual(fresh.held[0], 0);
  fresh.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test('a file name chosen by the other user cannot leave the download folder', () => {
  const { safeName } = require('../client/chat-client');
  assert.strictEqual(safeName('../../etc/passwd'), 'passwd');
  assert.strictEqual(safeName('..\\..\\Windows\\win.ini'), 'win.ini');
  assert.strictEqual(safeName('..'), 'file');
  assert.strictEqual(safeName('a\r\nb.txt'), 'a__b.txt');
  assert.strictEqual(safeName('photo 1.jpg'), 'photo 1.jpg');
});

async function asyncTests() {
  const { IrudpEndpoint } = require('../src/irudp/endpoint');
  // sessions closed before any SYNACK (nobody listens on port 9): each owner hears about it exactly once
  const ep = new IrudpEndpoint({ host: '127.0.0.1' }); await ep.start();
  const calls = [];
  const common = { host: '127.0.0.1', port: 9, size: 10, sha1: '00'.repeat(20) };
  const s1 = ep.connect({ ...common, role: S.ROLE.CLIENT_SENDS, transferId: 'a', readSegment: () => Buffer.alloc(10),
    onDone: (ok, r) => calls.push(['send', ok, r]) });
  const s2 = ep.connect({ ...common, role: S.ROLE.CLIENT_RECEIVES, transferId: 'b', held: null, writeSegment() {}, verify: () => true,
    onDone: (ok, r) => calls.push(['receive', ok, r]) });
  ep.abort(s1); ep.abort(s2); ep.abort(s1);
  await new Promise((r) => setTimeout(r, 50));
  ep.stop();
  assert.deepStrictEqual(calls, [['send', false, 'aborted'], ['receive', false, 'aborted']]);
  passed++; console.log('ok   a session closed before its SYNACK reports its end exactly once');
}

asyncTests().then(() => {
  const out = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'test', 'resources', 'wire-vectors.txt');
  fs.writeFileSync(out, '# name hex (generated by server/test/unit-test.js)\n' + vec.join('\n') + '\n');
  console.log(`\n${passed} unit tests passed; wire vectors written to ${path.relative(process.cwd(), out)}`);
}).catch((e) => { console.error('FAILED:', e); process.exit(1); });
