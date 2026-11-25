'use strict';
// The SIP subset used by the example (RFC 3261 syntax): REGISTER, MESSAGE, INVITE, ACK, BYE and
// responses, over UDP. File offers use the SDP attributes of RFC 5547 plus a private irudp attribute.
const crypto = require('crypto');

const DOMAIN = 'irudp.example';
const rand = (n = 8) => crypto.randomBytes(n).toString('hex');

function parse(buf) {
  const text = buf.toString('utf8');
  const cut = text.indexOf('\r\n\r\n');
  const head = cut < 0 ? text : text.slice(0, cut);
  let body = cut < 0 ? '' : text.slice(cut + 4);
  const lines = head.split('\r\n');
  const first = lines.shift();
  const msg = { headers: {} };
  let m;
  if ((m = /^SIP\/2\.0 (\d{3}) (.*)$/.exec(first))) { msg.status = +m[1]; msg.reason = m[2]; }
  else if ((m = /^([A-Z]+) (\S+) SIP\/2\.0$/.exec(first))) { msg.method = m[1]; msg.uri = m[2]; }
  else return null;
  for (const line of lines) {
    const i = line.indexOf(':');
    if (i < 0) continue;
    const name = line.slice(0, i).trim().toLowerCase();
    (msg.headers[name] = msg.headers[name] || []).push(line.slice(i + 1).trim());
  }
  const len = parseInt(header(msg, 'content-length') || '0', 10);
  msg.body = Buffer.from(body, 'utf8').subarray(0, len).toString('utf8');
  return msg;
}

const header = (msg, name) => (msg.headers[name] || [])[0];

function build(firstLine, headers, body = '') {
  const b = Buffer.from(body, 'utf8');
  const lines = [firstLine];
  for (const [k, v] of headers) if (v !== undefined && v !== null) lines.push(`${k}: ${v}`);
  lines.push(`Content-Length: ${b.length}`);
  return Buffer.concat([Buffer.from(lines.join('\r\n') + '\r\n\r\n', 'utf8'), b]);
}

function request(method, uri, { via, from, to, callId, cseq, contact, contentType, expires }, body = '') {
  return build(`${method} ${uri} SIP/2.0`, [
    ['Via', via], ['Max-Forwards', 70], ['From', from], ['To', to], ['Call-ID', callId],
    ['CSeq', `${cseq} ${method}`], ['Contact', contact], ['Expires', expires], ['Content-Type', contentType],
  ], body);
}

function response(req, status, reason, extra = [], body = '') {
  let to = header(req, 'to');
  if (to && !/;tag=/.test(to) && status >= 200) to += `;tag=${rand(4)}`;
  return build(`SIP/2.0 ${status} ${reason}`, [
    ...(req.headers.via || []).map((v) => ['Via', v]), ['From', header(req, 'from')], ['To', to],
    ['Call-ID', header(req, 'call-id')], ['CSeq', header(req, 'cseq')], ...extra,
  ], body);
}

const userOf = (h) => { const m = /sip:([^@>;]+)@/.exec(h || ''); return m ? m[1] : null; };
const cseqOf = (msg) => { const m = /^(\d+)\s+([A-Z]+)/.exec(header(msg, 'cseq') || ''); return m ? { n: +m[1], method: m[2] } : null; };

// --- SDP (RFC 4566) with the file transfer attributes of RFC 5547 -----------------------------
function hashColons(hex) { return hex.toUpperCase().match(/../g).join(':'); }

function buildSdp({ user, ip, port, setup, direction, name, type, size, sha1, transferId }) {
  return [
    'v=0', `o=${user} ${Date.now()} 1 IN IP4 ${ip}`, 's=-', `c=IN IP4 ${ip}`, 't=0 0',
    `m=application ${port} UDP/IRUDP *`, `a=setup:${setup}`, `a=${direction}`,
    `a=file-selector:name:"${String(name).replace(/"/g, '')}" type:${type} size:${size} hash:sha-1:${hashColons(sha1)}`,
    `a=file-transfer-id:${transferId}`, 'a=irudp:mss=1384;ring=1024;block=256', '',
  ].join('\r\n');
}

function parseSdp(text) {
  const out = {};
  for (const line of String(text).split(/\r?\n/)) {
    let m;
    if ((m = /^m=application (\d+) (\S+)/.exec(line))) { out.port = +m[1]; out.proto = m[2]; }
    else if ((m = /^a=setup:(\w+)/.exec(line))) out.setup = m[1];
    else if (/^a=(sendonly|recvonly|sendrecv)$/.test(line)) out.direction = line.slice(2);
    else if ((m = /^a=file-transfer-id:(\S+)/.exec(line))) out.transferId = m[1];
    else if (line.startsWith('a=file-selector:')) {
      const s = line.slice(16);
      const name = /name:"([^"]*)"/.exec(s); const type = /type:(\S+)/.exec(s);
      const size = /size:(\d+)/.exec(s); const hash = /hash:sha-1:([0-9A-Fa-f:]+)/.exec(s);
      out.name = name ? name[1] : 'file'; out.type = type ? type[1] : 'application/octet-stream';
      out.size = size ? +size[1] : 0; out.sha1 = hash ? hash[1].replace(/:/g, '').toLowerCase() : '';
    }
  }
  return out;
}

module.exports = { DOMAIN, rand, parse, header, request, response, userOf, cseqOf, buildSdp, parseSdp };
