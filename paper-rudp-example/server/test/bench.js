'use strict';
// Benchmark of the paper's main idea (Sec. 4.7): the same IRUDP sender with and without the GBDT loss
// classifier, on the same emulated link: 20 Mbit/s bottleneck with a 200-packet queue, 25 ms each way,
// and random loss in bursts of 2 that is not caused by congestion. Without the classifier every loss
// event reduces the window, as plain CUBIC does.
//
//   node test/bench.js [sizeMB]
const crypto = require('crypto');
const path = require('path');
const { IrudpEndpoint } = require('../src/irudp/endpoint');
const { LossClassifier } = require('../src/irudp/classifier');
const { LossyProxy } = require('./lossy-proxy');
const S = require('../src/irudp/segment');

async function transfer({ clf, loss, size, data, sha1 }) {
  const out = Buffer.alloc(size);
  const rx = new IrudpEndpoint({ host: '127.0.0.1', onSyn: () => ({ status: S.STATUS.OK, size, sha1, held: null,
    start: (sess, ep) => ep.makeReceiver(sess, { size, held: null, writeSegment: (s, b) => b.copy(out, s * S.MSS),
      verify: () => out.equals(data), onDone: () => {} }) }) });
  const proxy = new LossyProxy({ targetPort: await rx.start(), rateMbps: 20, queue: 200, loss, burst: 2, delayMs: 25, seed: 5 });
  const port = await proxy.start();
  const tx = new IrudpEndpoint({ host: '127.0.0.1', classifier: clf });
  await tx.start();
  const t0 = Date.now();
  const r = await new Promise((resolve) => tx.connect({ host: '127.0.0.1', port, role: S.ROLE.CLIENT_SENDS, size, sha1,
    transferId: 'bench', readSegment: (s) => data.subarray(s * S.MSS, Math.min(size, (s + 1) * S.MSS)),
    onDone: (ok, reason, stats) => resolve({ ok, reason, stats }) }));
  const secs = (Date.now() - t0) / 1000;
  tx.stop(); proxy.stop(); rx.stop();
  if (!r.ok) throw new Error(`transfer failed: ${r.reason}`);
  return { mbps: size * 8 / secs / 1e6, secs, ...r.stats };
}

async function main() {
  const mb = Number(process.argv[2] || 2);
  const clf = LossClassifier.load(path.join(__dirname, '..', 'model', 'gbdt.txt'));
  const size = mb * 1024 * 1024; const data = crypto.randomBytes(size);
  const sha1 = crypto.createHash('sha1').update(data).digest('hex');
  console.log(`${mb} MB upload, 20 Mbit/s bottleneck, 50 ms RTT, random loss in bursts of 2\n`);
  console.log('random loss | with classifier (IRUDP)          | without (plain CUBIC)');
  console.log('            | Mbit/s  reductions  kept  resent | Mbit/s  reductions  resent');
  for (const loss of [0, 0.01, 0.03, 0.05]) {
    const a = await transfer({ clf, loss, size, data, sha1 });
    const b = await transfer({ clf: null, loss, size, data, sha1 });
    const f = (v, w) => String(v).padStart(w);
    console.log(`${f((loss * 100).toFixed(0) + ' %', 11)} | ${f(a.mbps.toFixed(1), 6)}  ${f(a.reductions, 10)}  ${f(a.keptWindow, 4)}  ${f(a.retx, 6)} | `
      + `${f(b.mbps.toFixed(1), 6)}  ${f(b.reductions, 10)}  ${f(b.retx, 6)}`);
  }
  console.log('\nThe numbers come from this run on this computer; they vary from run to run and are not the paper\'s results.');
  process.exit(0);
}

main().catch((e) => { console.error('FAILED:', e); process.exit(1); });
