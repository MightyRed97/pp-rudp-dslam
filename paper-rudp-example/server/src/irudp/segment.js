'use strict';
// IRUDP wire format (paper, Sec. 4.2 and Fig. 3a). All header fields are big-endian.
//
//  byte 0     : version (2 bits) | type (3 bits) | flags (3 bits)
//  byte 1     : DATA: ACK frequency requested from the receiver; ACK: number of bitmap words k
//  bytes 2-3  : receive window (segments)
//  bytes 4-7  : connection ID (chosen by the client, carried in every segment)
//  bytes 8-11 : DATA: sequence number (file offset / 1384); ACK: cumulative ACK (next expected)
//  bytes 12-15: DATA: send time (microseconds, wraps at 2^32); ACK: echoed time + ACK hold time
//
// ACK payload: k x 8 bytes of bitmap. Bit i (byte i >> 3, bit i & 7, least significant first)
// reports segment (cumulative ACK + 1 + i), i.e. k little-endian 64-bit words.

const VERSION = 1;
const HDR = 16;
const MSS = 1384;                 // data bytes per segment (1,400-byte UDP payload)
const RING = 1024;                // ring capacity = maximum span of a window
const BLOCK_SEGS = 256;           // segments per block of the resume map
const KMAX = 16;                  // at most 16 bitmap words (1,024 segments) per ACK
const T = { DATA: 0, ACK: 1, SYN: 2, SYNACK: 3, FIN: 4, FINACK: 5, RST: 6, PING: 7 };
const F = { RETX: 1, PROBE: 2 };
const ROLE = { CLIENT_SENDS: 0, CLIENT_RECEIVES: 1 };
const STATUS = { OK: 0, UNKNOWN_TRANSFER: 1, BAD_HASH: 2, BUSY: 3 };

function header(type, flags, aux, rwnd, connId, num, ts, payloadLen) {
  const b = Buffer.alloc(HDR + payloadLen);
  b[0] = (VERSION << 6) | ((type & 7) << 3) | (flags & 7);
  b[1] = aux & 0xff;
  b.writeUInt16BE(rwnd & 0xffff, 2);
  b.writeUInt32BE(connId >>> 0, 4);
  b.writeUInt32BE(num >>> 0, 8);
  b.writeUInt32BE(ts >>> 0, 12);
  return b;
}

function parse(b) {
  if (b.length < HDR || (b[0] >> 6) !== VERSION) return null;
  return {
    type: (b[0] >> 3) & 7, flags: b[0] & 7, aux: b[1], rwnd: b.readUInt16BE(2),
    connId: b.readUInt32BE(4), num: b.readUInt32BE(8), ts: b.readUInt32BE(12),
    payload: b.subarray(HDR),
  };
}

const segmentsFor = (size) => Math.ceil(size / MSS);
const blocksFor = (size) => Math.ceil(segmentsFor(size) / BLOCK_SEGS);
const bitmapBytes = (n) => Math.ceil(n / 8);

function writeU64(b, off, v) { b.writeUInt32BE(Math.floor(v / 4294967296), off); b.writeUInt32BE(v % 4294967296, off + 4); }
function readU64(b, off) { return b.readUInt32BE(off) * 4294967296 + b.readUInt32BE(off + 4); }

// SYN payload: role(1) reserved(1) blockSegs(2) fileSize(8) sha1(20) transferId(16) [blockBitmap]
function synPayload({ role, size, sha1, transferId, held }) {
  const p = Buffer.alloc(48 + (held ? held.length : 0));
  p[0] = role; p.writeUInt16BE(BLOCK_SEGS, 2); writeU64(p, 4, size);
  if (sha1) Buffer.from(sha1, 'hex').copy(p, 12);
  Buffer.from(String(transferId).slice(0, 16), 'ascii').copy(p, 32);
  if (held) Buffer.from(held).copy(p, 48);
  return p;
}
function parseSyn(p) {
  if (p.length < 48) return null;
  const tid = p.subarray(32, 48); const end = tid.indexOf(0);
  return {
    role: p[0], blockSegs: p.readUInt16BE(2), size: readU64(p, 4),
    sha1: p.subarray(12, 32).toString('hex'),
    transferId: (end < 0 ? tid : tid.subarray(0, end)).toString('ascii'),
    held: p.length > 48 ? new Uint8Array(p.subarray(48)) : null,
  };
}
// SYNACK payload: status(1) reserved(1) blockSegs(2) fileSize(8) sha1(20) [blockBitmap]
function synAckPayload({ status, size, sha1, held }) {
  const p = Buffer.alloc(32 + (held ? held.length : 0));
  p[0] = status; p.writeUInt16BE(BLOCK_SEGS, 2); writeU64(p, 4, size || 0);
  if (sha1) Buffer.from(sha1, 'hex').copy(p, 12);
  if (held) Buffer.from(held).copy(p, 32);
  return p;
}
function parseSynAck(p) {
  if (p.length < 32) return null;
  return { status: p[0], blockSegs: p.readUInt16BE(2), size: readU64(p, 4), sha1: p.subarray(12, 32).toString('hex'),
           held: p.length > 32 ? new Uint8Array(p.subarray(32)) : null };
}

const bitGet = (bm, i) => (bm[i >> 3] >> (i & 7)) & 1;
const bitSet = (bm, i) => { bm[i >> 3] |= 1 << (i & 7); };

module.exports = { VERSION, HDR, MSS, RING, BLOCK_SEGS, KMAX, T, F, ROLE, STATUS, header, parse, segmentsFor,
  blocksFor, bitmapBytes, synPayload, parseSyn, synAckPayload, parseSynAck, bitGet, bitSet };
