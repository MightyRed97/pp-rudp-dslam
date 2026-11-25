'use strict';
// End-to-end test: a relay and two chat clients (alice, bob) on this computer. IRUDP traffic passes
// through a lossy proxy (20 Mbit/s with a 200-packet queue, 3% Gilbert loss in bursts of 2, 25 ms each
// way). The test checks chat
// messages, a file transfer with a 3 s outage, an upload killed half-way and resumed from the block
// map, and offline delivery of a message and a file.
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert');
const { Relay } = require('../src/relay');
const { LossClassifier } = require('../src/irudp/classifier');
const { ChatClient } = require('../client/chat-client');
const { LossyProxy } = require('./lossy-proxy');
const { sha1OfFile } = require('../src/storage');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (cond, ms, what) => { const t0 = Date.now(); while (!cond()) { if (Date.now() - t0 > ms) throw new Error(`timeout: ${what}`); await sleep(50); } };

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'irudp-e2e-'));
  const clf = LossClassifier.load(path.join(__dirname, '..', 'model', 'gbdt.txt'));
  const log = (...a) => console.log('   ', ...a);
  const relay = new Relay({ sipPort: 0, irudpPort: 0, publicIp: '127.0.0.1', dataDir: path.join(root, 'relay'), classifier: clf, log });
  await relay.start();
  const proxy = new LossyProxy({ targetPort: relay.irudpPort, rateMbps: 20, queue: 200, loss: 0.03, burst: 2, delayMs: 25, seed: 11 });
  relay.advertisePort = await proxy.start();         // clients reach the relay's IRUDP port through the proxy

  const mk = (user) => new ChatClient({ server: '127.0.0.1', sipPort: relay.sipPort, user, downloadDir: path.join(root, user), classifier: clf, log });
  const alice = mk('alice'); let bob = mk('bob');
  await alice.start(); await bob.start();
  const inbox = []; const files = [];
  const watch = (c) => { c.on('message', (m) => inbox.push(m)); c.on('file', (f) => files.push(f)); };
  watch(bob);

  // 1. chat
  assert.ok(await alice.sendMessage('bob', 'hello from alice'));
  await until(() => inbox.length === 1, 5000, 'message');
  assert.strictEqual(inbox[0].text, 'hello from alice'); assert.strictEqual(inbox[0].from, 'alice');
  console.log('ok   chat message delivered');

  // 2. file with an outage of 3 s in the middle of the upload
  const f1 = path.join(root, 'inspection.mp4'); fs.writeFileSync(f1, crypto.randomBytes(6 * 1024 * 1024));
  let outageDone = false; const t1 = Date.now();
  const r1 = await alice.sendFile('bob', f1, { onProgress: (d, n) => { if (!outageDone && d > 0.4 * n) { outageDone = true; proxy.outage(3000); log('outage of 3 s'); } } });
  assert.ok(r1.ok, r1.reason);
  log(`upload: ${((Date.now() - t1) / 1000).toFixed(1)} s, ${r1.stats.sent} segments sent, ${r1.stats.retx} resent, `
    + `${r1.stats.reductions} reductions, ${r1.stats.keptWindow} random losses kept the window`);
  await until(() => files.length === 1, 60000, 'download of file 1');
  assert.strictEqual(sha1OfFile(files[0].path), sha1OfFile(f1));
  console.log('ok   6 MB file relayed through 3% loss and a 3 s outage, SHA-1 matches');

  // 3. upload killed half-way (as if the app were closed), then sent again: it resumes from the block map
  const f2 = path.join(root, 'tower.jpg'); fs.writeFileSync(f2, crypto.randomBytes(4 * 1024 * 1024));
  const segs = Math.ceil(fs.statSync(f2).size / 1384);
  let killed = false;
  const r2 = await alice.sendFile('bob', f2, { attempts: 2, onProgress: (d, n) => {
    if (!killed && d > 0.5 * n) { killed = true; for (const s of alice.active.values()) alice.irudp.abort(s); }
  } });
  assert.ok(r2.ok && r2.attempt === 2, JSON.stringify(r2));
  // each segment that the relay did not already hold is sent once, plus the retransmissions
  assert.ok(r2.stats.skipped > 0 && r2.stats.sent - r2.stats.retx === segs - r2.stats.skipped, JSON.stringify(r2.stats));
  log(`resumed upload: second attempt skipped ${r2.stats.skipped} of ${segs} segments, sent ${r2.stats.sent - r2.stats.retx} once and resent ${r2.stats.retx}`);
  await until(() => files.length === 2, 60000, 'download of file 2');
  assert.strictEqual(sha1OfFile(files[1].path), sha1OfFile(f2));
  console.log('ok   interrupted upload resumed from the block map, SHA-1 matches');

  // 4. offline delivery: bob leaves, alice sends a message and a file, bob comes back
  bob.stop(); relay.users.delete('bob');
  assert.ok(await alice.sendMessage('bob', 'are you there?'));
  const f3 = path.join(root, 'report.pdf'); fs.writeFileSync(f3, crypto.randomBytes(300 * 1024));
  const r3 = await alice.sendFile('bob', f3);
  assert.ok(r3.ok);
  bob = mk('bob'); watch(bob); await bob.start();
  await until(() => inbox.some((m) => m.text === 'are you there?') && files.length === 3, 30000, 'offline delivery');
  assert.strictEqual(sha1OfFile(files[2].path), sha1OfFile(f3));
  console.log('ok   offline message and file delivered when bob registered again');

  // every relay session has ended: deliveries got their FINACK and the killed upload was replaced
  await until(() => relay.irudp.sessions.size === 0, 5000, 'relay sessions to end');
  log(`proxy: ${proxy.stats.forwarded} packets forwarded, ${proxy.stats.dropped} lost at random, ${proxy.stats.queueDrops} dropped by the full queue`);
  alice.stop(); bob.stop(); proxy.stop(); relay.stop();
  fs.rmSync(root, { recursive: true, force: true });
  console.log('\nend-to-end test passed');
  process.exit(0);
}

main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
