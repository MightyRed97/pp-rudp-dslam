'use strict';
// GBDT loss classifier of the paper (Sec. 4.7, Eqs. 9-11): 60 trees of depth 3, theta = 0.4.
const fs = require('fs');

class LossClassifier {
  constructor(text) {
    this.trees = [];
    let cur = null;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('#')) continue;
      const t = line.split(/\s+/);
      if (t[0] === 'gbdt') { this.nFeatures = +t[1]; this.f0 = +t[3]; this.lr = +t[4]; }
      else if (t[0] === 'tree') { cur = []; this.trees.push(cur); }
      else if (t[0] === 'split') cur.push({ f: +t[1], thr: +t[2], l: +t[3], r: +t[4] });
      else if (t[0] === 'leaf') cur.push({ v: +t[1] });
    }
  }

  static load(path) { return new LossClassifier(fs.readFileSync(path, 'utf8')); }

  prob(x) {
    let s = 0;
    for (const tree of this.trees) {
      let n = tree[0];
      while (n.v === undefined) n = tree[x[n.f] <= n.thr ? n.l : n.r];
      s += n.v;
    }
    return 1 / (1 + Math.exp(-(this.f0 + this.lr * s)));
  }
}

// Eq. (9): features from the sender's structures, all dimensionless.
function lossFeatures({ latestRtt, minRtt, rtts, lostRun, lossEwma, inflight, btlRate, gapUs, srtt }) {
  const q = (latestRtt - minRtt) / minRtt;
  let trend = 0;
  if (rtts.length >= 8) {
    const last = rtts.slice(-4); const prev = rtts.slice(0, -4);
    trend = (last.reduce((a, b) => a + b, 0) / 4 - prev.reduce((a, b) => a + b, 0) / prev.length) / minRtt;
  }
  const bdp = btlRate * minRtt;                       // segments per microsecond x microseconds
  const sat = bdp > 0 ? inflight / bdp : 1;
  return [q, trend, lostRun, 100 * lossEwma, sat, Math.min(gapUs / srtt, 50)];
}

module.exports = { LossClassifier, lossFeatures };
