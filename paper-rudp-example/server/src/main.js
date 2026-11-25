'use strict';
// Starts the relay server.
//   node src/main.js [--sip-port 5070] [--irudp-port 5080] [--public-ip <ip>] [--data ./data]
const os = require('os');
const path = require('path');
const { Relay } = require('./relay');
const { LossClassifier } = require('./irudp/classifier');

function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
}

function firstIPv4() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) return a.address;
  }
  return '127.0.0.1';
}

async function main() {
  const relay = new Relay({
    sipPort: +arg('sip-port', 5070),
    irudpPort: +arg('irudp-port', 5080),
    advertisePort: +arg('advertise-port', 0),
    publicIp: arg('public-ip', firstIPv4()),
    dataDir: path.resolve(arg('data', path.join(__dirname, '..', 'data'))),
    classifier: LossClassifier.load(path.join(__dirname, '..', 'model', 'gbdt.txt')),
  });
  await relay.start();
  console.log(`Clients connect to ${relay.publicIp}:${relay.sipPort} (the Android emulator reaches this computer at 10.0.2.2).`);
  const stop = () => { relay.stop(); process.exit(0); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}

main().catch((e) => { console.error(e); process.exit(1); });
