'use strict';
// Interactive desktop chat client.
//   node client/cli.js --user bob [--server 127.0.0.1] [--port 5070] [--downloads ./downloads]
// Commands: /to <user>   /file <path>   /quit   (any other line is sent as a message)
const path = require('path');
const readline = require('readline');
const { ChatClient } = require('./chat-client');
const { LossClassifier } = require('../src/irudp/classifier');

const arg = (n, d) => { const i = process.argv.indexOf(`--${n}`); return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : d; };

async function main() {
  const user = arg('user');
  if (!user) { console.log('usage: node client/cli.js --user <name> [--server 127.0.0.1] [--port 5070]'); process.exit(1); }
  const client = new ChatClient({ server: arg('server', '127.0.0.1'), sipPort: +arg('port', 5070), user,
    downloadDir: path.resolve(arg('downloads', `downloads-${user}`)),
    classifier: LossClassifier.load(path.join(__dirname, '..', 'model', 'gbdt.txt')) });
  let to = null; let lastPct = -1;
  client.on('message', (m) => console.log(`\n[${m.from}] ${m.text}`));
  client.on('offer', (o) => console.log(`\n[${o.from}] sends ${o.name} (${o.size} bytes)`));
  client.on('progress', (p) => { const pct = Math.floor(100 * p.done / p.total); if (pct >= lastPct + 10) { lastPct = pct; process.stdout.write(` ${pct}%`); } });
  client.on('file', (f) => { lastPct = -1; console.log(`\nreceived ${f.name} from ${f.from}: ${f.path}`); });
  client.on('failed', (f) => console.log(`\ndownload of ${f.name} interrupted (${f.reason}); it resumes on the next offer`));
  await client.start();
  console.log(`registered as ${user}. /to <user> chooses the recipient, /file <path> sends a file, /quit exits.`);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  rl.on('line', async (line) => {
    line = line.trim();
    if (!line) return;
    if (line === '/quit') { client.stop(); process.exit(0); }
    if (line.startsWith('/to ')) { to = line.slice(4).trim(); console.log(`messages now go to ${to}`); return; }
    if (!to) { console.log('choose a recipient first: /to <user>'); return; }
    if (line.startsWith('/file ')) {
      const file = line.slice(6).trim(); let shown = -1;
      const r = await client.sendFile(to, file, { onProgress: (d, n) => { const pct = Math.floor(100 * d / n); if (pct >= shown + 10) { shown = pct; process.stdout.write(` ${pct}%`); } } });
      console.log(r && r.ok ? `\nsent ${path.basename(file)} (${r.stats.sent} segments, ${r.stats.retx} resent)` : `\nsending failed: ${r ? r.reason : '?'}`);
      return;
    }
    if (!(await client.sendMessage(to, line))) console.log('(message not accepted by the server)');
  });
}

main().catch((e) => { console.error(e.message); process.exit(1); });
